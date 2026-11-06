package dev.southstar21.volum.data

import dev.southstar21.volum.model.Collection
import dev.southstar21.volum.model.Exercise
import dev.southstar21.volum.model.Routine
import dev.southstar21.volum.model.VolumJson
import dev.southstar21.volum.model.Workout
import dev.southstar21.volum.model.WorkoutEntry
import dev.southstar21.volum.model.WorkoutStatus
import dev.southstar21.volum.util.nowIso
import dev.southstar21.volum.util.parseTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

enum class SyncState { Synced, Pending, Offline, Error }

data class SyncStatus(val state: SyncState = SyncState.Synced, val pendingCount: Int = 0, val lastError: String? = null, val lastSyncedAt: String? = null)

private const val EPOCH = "1970-01-01 00:00:00.000Z"
private const val SYNC_INTERVAL_MS = 60_000L
private const val ACTIVE_SYNC_INTERVAL_MS = 10_000L
private const val DEBOUNCE_MS = 1_000L
private const val MAX_BACKOFF_MS = 300_000L

/** Pushes the outbox and pulls changes, like the web app's sync engine (web/src/lib/sync). */
class SyncEngine(
    private val store: LocalStore,
    private val pb: PocketBase,
    private val repository: Repository,
    private val owner: String,
    private val scope: CoroutineScope,
    private val onAuthExpired: () -> Unit,
) {
    private val _status = MutableStateFlow(SyncStatus(lastSyncedAt = store.meta.value["lastSyncedAt"]))
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val running = Mutex()
    private var rerun = false
    private var paused = false
    private var backoffMs = 2_000L
    private val jobs = mutableListOf<Job>()
    private var backoffJob: Job? = null

    /** Whether the app is on screen. The quick poll during a workout only runs while it is. */
    var foreground = true

    fun start() {
        if (jobs.isNotEmpty()) return
        paused = false
        jobs += scope.launch {
            repository.writes.collectLatest {
                delay(DEBOUNCE_MS)
                syncNow()
            }
        }
        jobs += scope.launch {
            syncNow()
            while (isActive) {
                val active = store.workouts.value.values.any { it.status == WorkoutStatus.Active && !it.deleted }
                delay(if (active && foreground) ACTIVE_SYNC_INTERVAL_MS else SYNC_INTERVAL_MS)
                syncNow()
            }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        backoffJob?.cancel()
    }

    fun scheduleSync() { scope.launch { delay(DEBOUNCE_MS); syncNow() } }

    suspend fun retryNow() {
        paused = false
        store.transaction { updateOutbox { list -> list.map { it.copy(failed = false, attempts = 0, lastError = null) } } }
        syncNow()
    }

    private fun report(state: SyncState, lastError: String? = null) {
        _status.value = SyncStatus(state, store.outbox.value.size, lastError, store.meta.value["lastSyncedAt"])
    }

    suspend fun syncNow() {
        if (paused) return
        if (!running.tryLock()) { rerun = true; return }
        try {
            report(SyncState.Pending)
            try {
                val failed = pushOutbox()
                pullAll()
                if (failed > 0) {
                    val message = store.outbox.value.firstOrNull { it.failed }?.lastError ?: "Some changes were rejected"
                    store.transaction { putMeta("lastError", message) }
                    report(SyncState.Error, message)
                    return
                }
                backoffMs = 2_000
                backoffJob?.cancel()
                store.transaction { putMeta("lastSyncedAt", nowIso()); deleteMeta("lastError") }
                report(if (store.outbox.value.isNotEmpty()) SyncState.Pending else SyncState.Synced)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PbException) {
                when {
                    e.status == 401 -> {
                        paused = true
                        report(SyncState.Error, "Session expired — sign in again")
                        onAuthExpired()
                    }
                    e.status == 0 -> report(SyncState.Offline)
                    else -> {
                        report(SyncState.Error, e.message)
                        scheduleBackoff()
                    }
                }
            } catch (e: Throwable) {
                report(SyncState.Error, e.message ?: "Sync failed")
                scheduleBackoff()
            }
        } finally {
            running.unlock()
            if (rerun) {
                rerun = false
                scope.launch { syncNow() }
            }
        }
    }

    private fun scheduleBackoff() {
        backoffJob?.cancel()
        val wait = backoffMs
        backoffJob = scope.launch { delay(wait); syncNow() }
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
    }

    /** Returns how many items were rejected (4xx). Network errors and 401 propagate. */
    private suspend fun pushOutbox(): Int {
        var failed = 0
        for (item in store.outbox.value.sortedBy { it.id }) {
            if (item.failed) { failed++; continue }
            try {
                try {
                    pb.update(item.collection, item.recordId, item.payload)
                } catch (e: PbException) {
                    if (e.status != 404) throw e
                    pb.create(item.collection, JsonObject(mapOf("id" to JsonPrimitive(item.recordId)) + item.payload))
                }
                store.transaction { updateOutbox { list -> list.filterNot { it.id == item.id } } }
            } catch (e: PbException) {
                if (e.status == 401 || e.status == 0 || e.status >= 500) throw e
                store.transaction {
                    updateOutbox { list -> list.map { if (it.id == item.id) it.copy(failed = true, attempts = it.attempts + 1, lastError = e.message) else it } }
                }
                failed++
            }
        }
        return failed
    }

    private suspend fun pullAll() {
        for (collection in Collection.entries) pullCollection(collection)
    }

    private suspend fun pullCollection(collection: Collection) {
        val lastPull = store.meta.value["lastPull.${collection.wire}"] ?: EPOCH
        var page = 1
        var maxUpdated = lastPull
        while (true) {
            val filter = if (collection == Collection.Workouts) {
                PocketBase.filter("owner = {:owner} && updated >= {:since}", mapOf("owner" to owner, "since" to lastPull))
            } else {
                PocketBase.filter("updated >= {:since}", mapOf("since" to lastPull))
            }
            val result = pb.getList(collection.wire, page, 500, filter, "updated")
            for (record in result.items) {
                val updated = record["updated"]?.jsonPrimitive?.contentOrNull
                if (updated != null && updated > maxUpdated) maxUpdated = updated
            }
            // Decoded off the main thread, then applied as a whole page in one transaction: one update
            // for the UI and one disk write, not one per record.
            val incoming = withContext(Dispatchers.Default) { result.items.mapNotNull { incomingOf(collection, it) } }
            if (incoming.isNotEmpty()) store.transaction { for (record in incoming) applyIncoming(record) }
            if (page >= result.totalPages) break
            page++
        }
        store.transaction { putMeta("lastPull.${collection.wire}", maxUpdated) }
    }

    /** A pulled record, decoded; [hasEntries] when it carried an entries array (a workout). */
    private class Incoming(val collection: Collection, val id: String, val updatedAt: String?, val hasEntries: Boolean, val record: Any)

    private fun incomingOf(collection: Collection, record: JsonObject): Incoming? {
        val id = record["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val decoded: Any = when (collection) {
            Collection.Exercises -> decode(Exercise.serializer(), record)
            Collection.Routines -> decode(Routine.serializer(), record)
            Collection.Workouts -> decode(Workout.serializer(), record)
        } ?: return null
        return Incoming(collection, id, record["updatedAt"]?.jsonPrimitive?.contentOrNull, record["entries"] is JsonArray, decoded)
    }

    private fun LocalStore.Tx.applyIncoming(item: Incoming) {
        val id = item.id
        val collection = item.collection
        val pending = outbox.count { it.recordId == id }
        val localUpdatedAt: String? = when (collection) {
            Collection.Exercises -> exercises[id]?.updatedAt
            Collection.Routines -> routines[id]?.updatedAt
            Collection.Workouts -> workouts[id]?.updatedAt
        }
        if (collection == Collection.Workouts && pending > 0 && workouts[id] != null && item.hasEntries) {
            // A partner edited the active workout while ours has edits queued: keep both.
            val mine = workouts.getValue(id)
            val incoming = item.record as Workout
            val removed = (mine.removedEntryIds.orEmpty() + incoming.removedEntryIds.orEmpty()).distinct()
            putWorkout(
                mine.copy(
                    entries = mergeWorkoutEntries(incoming.entries, mine.entries, mine.updatedAt, removed),
                    removedEntryIds = removed.ifEmpty { null },
                ),
            )
            return
        }
        if (pending > 0 && localUpdatedAt != null && parseTime(localUpdatedAt) > parseTime(item.updatedAt)) return
        if (pending > 0) updateOutbox { list -> list.filterNot { it.recordId == id } }
        when (collection) {
            Collection.Exercises -> putExercise(item.record as Exercise)
            Collection.Routines -> putRoutine(item.record as Routine)
            Collection.Workouts -> putWorkout(item.record as Workout)
        }
    }

    private fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, record: JsonObject): T? =
        decodeRecord(serializer, record)

    @Suppress("unused")
    private fun entriesOf(record: JsonObject): List<WorkoutEntry> = decode(Workout.serializer(), record)?.entries.orEmpty()
}
