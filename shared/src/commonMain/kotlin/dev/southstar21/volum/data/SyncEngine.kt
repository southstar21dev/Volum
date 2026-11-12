package dev.southstar21.volum.data

import dev.southstar21.volum.model.Collection
import dev.southstar21.volum.model.Exercise
import dev.southstar21.volum.model.OutboxItem
import dev.southstar21.volum.model.Routine
import dev.southstar21.volum.model.VolumJson
import dev.southstar21.volum.model.Workout
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
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.io.IOException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class SyncState { Synced, Pending, Offline, Error }

/** Why sync is in [SyncState.Error], for the UI to put in words; [SyncStatus.lastError] keeps the raw detail. */
enum class SyncProblem {
    /** The server no longer accepts the sign-in. */
    SessionExpired,
    /** The server refused some changes (a validation or permission error); they stay queued as failed. */
    Rejected,
    /** Anything else: a server error or a reply the app couldn't read. Retried with backoff. */
    Failed,
}

data class SyncStatus(
    val state: SyncState = SyncState.Synced,
    val pendingCount: Int = 0,
    /** The raw exception or server message behind an error: for diagnostics, not for display. */
    val lastError: String? = null,
    val lastSyncedAt: String? = null,
    val problem: SyncProblem? = null,
)

private const val EPOCH = "1970-01-01 00:00:00.000Z"
private const val SYNC_INTERVAL_MS = 60_000L
private const val ACTIVE_SYNC_INTERVAL_MS = 10_000L
private const val DEBOUNCE_MS = 1_000L
private const val MAX_BACKOFF_MS = 300_000L
private const val PAGE_SIZE = 500
/** How many undecodable records a collection keeps to fetch again (their ids go in one filter). */
private const val MAX_UNDECODABLE = 50
/** Request-only field of a workout push: see SyncEngine.withSyncBase. */
private const val SYNC_BASE = "syncBase"

/** Pushes the outbox and pulls changes, like the web app's sync engine (web/src/lib/sync). */
class SyncEngine(
    private val store: LocalStore,
    private val pb: PocketBase,
    private val repository: Repository,
    private val owner: String,
    private val scope: CoroutineScope,
    /** Whether a partner can edit this user's workout right now (see [pullBeforePush]). */
    private val hasPartner: () -> Boolean = { false },
    /** Runs before each push; the app makes sure the sign-in token is fresh there. */
    private val beforePush: suspend () -> Unit = {},
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

    // Bumped (inside a store transaction) whenever pull markers are forgotten, so a pull that was
    // already running doesn't write its marker back over the reset.
    private var pullResets = 0

    /** Whether the app is on screen. The quick poll during a workout only runs while it is. */
    var foreground = true

    fun start() {
        if (jobs.isNotEmpty()) return
        paused = false
        // Conflated rather than collectLatest: a write during a sync queues one more sync instead of
        // cancelling the running one, so steady edits can't keep a long pull from finishing.
        jobs += scope.launch {
            repository.writes.conflate().collect {
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

    /**
     * Forgets where the last pull of [collections] (wire names, e.g. "routines") stopped and syncs,
     * so records that became visible without changing, like a new partner's routines, are fetched.
     */
    fun forgetPullMarkers(collections: List<String>) {
        scope.launch {
            store.transaction {
                pullResets++
                for (collection in collections) deleteMeta("lastPull.$collection")
            }
            syncNow()
        }
    }

    suspend fun retryNow() {
        paused = false
        store.transaction { updateOutbox { list -> list.map { it.copy(failed = false, attempts = 0, lastError = null) } } }
        syncNow()
    }

    private fun report(state: SyncState, problem: SyncProblem? = null, lastError: String? = null) {
        _status.value = SyncStatus(state, store.outbox.value.size, lastError, store.meta.value["lastSyncedAt"], problem)
    }

    suspend fun syncNow() {
        if (paused) return
        if (!running.tryLock()) { rerun = true; return }
        try {
            report(SyncState.Pending)
            try {
                beforePush()
                if (pullBeforePush()) pullCollection(Collection.Workouts)
                val failed = pushOutbox()
                pullAll()
                if (failed > 0) {
                    val message = store.outbox.value.firstOrNull { it.failed }?.lastError ?: "Some changes were rejected"
                    store.transaction { putMeta("lastError", message) }
                    report(SyncState.Error, SyncProblem.Rejected, message)
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
                        report(SyncState.Error, SyncProblem.SessionExpired, "Session expired")
                        onAuthExpired()
                    }
                    e.status == 0 -> report(SyncState.Offline)
                    else -> {
                        report(SyncState.Error, SyncProblem.Failed, e.message)
                        scheduleBackoff()
                    }
                }
            } catch (_: IOException) {
                // The connection dropped while a response was being read.
                report(SyncState.Offline)
            } catch (e: Throwable) {
                report(SyncState.Error, SyncProblem.Failed, e.message ?: "Sync failed")
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

    /**
     * Whether to pull workouts before pushing: a change to a workout a partner may be editing is
     * queued. The server merges the push with what it has, but keeps a partner's set only if it is
     * newer than the copy the push was based on; pulling first merges it in here (and into the
     * queued copy), so it goes up with the push rather than being dropped by it.
     */
    private fun pullBeforePush(): Boolean {
        if (!hasPartner()) return false
        val meta = store.meta.value
        return store.outbox.value.any { !it.failed && it.collection == Collection.Workouts.wire && pulledKey(it.recordId) in meta }
    }

    /** Returns how many items were rejected (4xx). Network errors, timeouts, rate limits and 401 propagate. */
    private suspend fun pushOutbox(): Int {
        var failed = 0
        for (item in store.outbox.value.sortedBy { it.id }) {
            if (item.failed) { failed++; continue }
            try {
                try {
                    pb.update(item.collection, item.recordId, withSyncBase(item))
                } catch (e: PbException) {
                    if (e.status != 404) throw e
                    pb.create(item.collection, JsonObject(mapOf("id" to JsonPrimitive(item.recordId)) + item.payload))
                }
                store.transaction { updateOutbox { list -> list.filterNot { it.id == item.id } } }
            } catch (e: PbException) {
                if (e.status == 401 || isTransient(e.status)) throw e
                store.transaction {
                    updateOutbox { list -> list.map { if (it.id == item.id) it.copy(failed = true, attempts = it.attempts + 1, lastError = e.message) else it } }
                }
                failed++
            }
        }
        return failed
    }

    /**
     * A workout's payload plus `syncBase`: the server time of the copy it was based on, for the
     * server's merge to tell a partner's newer sets from ones the owner removed (backend/pb_hooks/partner.pb.js).
     * It is not a record field; a server that doesn't use it ignores it.
     */
    private fun withSyncBase(item: OutboxItem): JsonObject {
        if (item.collection != Collection.Workouts.wire) return item.payload
        val base = store.meta.value[pulledKey(item.recordId)] ?: return item.payload
        return JsonObject(item.payload + (SYNC_BASE to JsonPrimitive(base)))
    }

    /** Worth retrying as is: no connection, a timeout, a rate limit or a server error. */
    private fun isTransient(status: Int) = status == 0 || status == 408 || status == 429 || status >= 500

    private suspend fun pullAll() {
        for (collection in Collection.entries) pullCollection(collection)
    }

    /**
     * Pulls records changed since the collection's marker. Pages follow a cursor on (updated, id)
     * rather than page numbers, so a record edited mid-pull (which moves to the end) can't shift
     * another one onto a page already fetched.
     *
     * The marker moves with each page, so a long first pull on a slow connection that drops part way
     * resumes where it stopped instead of starting over. Resuming with `updated >=` refetches the
     * records sharing the last timestamp, which is harmless: applying a record twice changes nothing.
     */
    private suspend fun pullCollection(collection: Collection) {
        val key = "lastPull.${collection.wire}"
        val undecodableKey = undecodableKey(collection)
        val resets = pullResets
        val since = store.meta.value[key] ?: EPOCH
        var cursorUpdated: String? = null
        var cursorId: String? = null
        while (true) {
            val filter = if (cursorUpdated == null || cursorId == null) {
                PocketBase.filter("${ownFilter(collection)}updated >= {:since}", mapOf("owner" to owner, "since" to since))
            } else {
                PocketBase.filter(
                    "${ownFilter(collection)}(updated > {:updated} || (updated = {:updated} && id > {:id}))",
                    mapOf("owner" to owner, "updated" to cursorUpdated, "id" to cursorId),
                )
            }
            val result = pb.getList(collection.wire, 1, PAGE_SIZE, filter, "updated,id")
            // Decoded off the main thread, then applied as a whole page in one transaction: one update
            // for the UI and one disk write, not one per record.
            val decoded = withContext(Dispatchers.Default) { result.items.map { it to incomingOf(collection, it) } }
            val incoming = decoded.mapNotNull { it.second }
            // A record that doesn't decode doesn't hold the marker back (every later sync would download
            // everything since it again); its id is kept to be fetched again on its own (see retryUndecodable).
            val failedIds = decoded.filter { it.second == null }.mapNotNull { it.first["id"]?.jsonPrimitive?.contentOrNull }
            val last = result.items.lastOrNull()
            val lastUpdated = last?.get("updated")?.jsonPrimitive?.contentOrNull
            val lastId = last?.get("id")?.jsonPrimitive?.contentOrNull
            store.transaction {
                for (record in incoming) applyIncoming(record)
                val decodedIds = incoming.map { it.id }.toSet()
                val held = meta[undecodableKey].toIdList()
                val ids = (held.filter { it !in decodedIds } + failedIds).distinct().takeLast(MAX_UNDECODABLE)
                if (ids != held) putIdList(undecodableKey, ids)
                if (pullResets == resets && lastUpdated != null && lastUpdated > (meta[key] ?: EPOCH)) putMeta(key, lastUpdated)
            }
            if (lastUpdated == null || lastId == null) break
            if (lastUpdated == cursorUpdated && lastId == cursorId) break
            cursorUpdated = lastUpdated
            cursorId = lastId
            if (result.items.size < PAGE_SIZE) break
        }
        // A pull that found nothing new still records where it started, so a reset marker counts as pulled.
        store.transaction { if (pullResets == resets && key !in meta) putMeta(key, since) }
        if (collection !in undecodableRetried) {
            retryUndecodable(collection)
            undecodableRetried += collection
        }
    }

    // Collections whose undecodable records were fetched again since this engine started: once per app
    // launch, since only a newer app version can decode them (a fix on the server changes `updated`,
    // so the normal pull brings that one back).
    private val undecodableRetried = mutableSetOf<Collection>()

    /** Fetches the records of [collection] that didn't decode before; applies those that now do. */
    private suspend fun retryUndecodable(collection: Collection) {
        val key = undecodableKey(collection)
        val ids = store.meta.value[key].toIdList()
        if (ids.isEmpty()) return
        val params = ids.withIndex().associate { (i, id) -> "id$i" to id } + ("owner" to owner)
        val filter = PocketBase.filter(ownFilter(collection) + "(" + ids.indices.joinToString(" || ") { "id = {:id$it}" } + ")", params)
        val result = try {
            pb.getList(collection.wire, 1, ids.size, filter, "updated,id")
        } catch (e: PbException) {
            if (e.status == 401 || isTransient(e.status)) throw e
            // Refused outright: give up on these rather than fail every sync over them.
            store.transaction { putIdList(key, meta[key].toIdList().filter { it !in ids }) }
            return
        }
        val decoded = withContext(Dispatchers.Default) { result.items.map { it to incomingOf(collection, it) } }
        // Still undecodable and still on the server; the rest were deleted, hidden or now decode.
        val stillFailing = decoded.filter { it.second == null }.mapNotNull { it.first["id"]?.jsonPrimitive?.contentOrNull }.toSet()
        store.transaction {
            for (record in decoded.mapNotNull { it.second }) applyIncoming(record)
            // Ids added by a pull since the read above are kept.
            putIdList(key, meta[key].toIdList().filter { it !in ids || it in stillFailing })
        }
    }

    private fun ownFilter(collection: Collection) = if (collection == Collection.Workouts) "owner = {:owner} && " else ""

    private fun undecodableKey(collection: Collection) = "undecodable.${collection.wire}"

    private fun String?.toIdList(): List<String> = this?.split(',')?.filter { it.isNotEmpty() }.orEmpty()

    private fun LocalStore.Tx.putIdList(key: String, ids: List<String>) {
        if (ids.isEmpty()) deleteMeta(key) else putMeta(key, ids.joinToString(","))
    }

    /** A pulled record, decoded; [hasEntries] when it carried an entries array (a workout). */
    private class Incoming(val collection: Collection, val id: String, val updatedAt: String?, val hasEntries: Boolean, val record: Any)

    private fun incomingOf(collection: Collection, record: JsonObject): Incoming? {
        val id = record["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val decoded: Any = when (collection) {
            Collection.Exercises -> decodeRecord(Exercise.serializer(), record)
            Collection.Routines -> decodeRecord(Routine.serializer(), record)
            Collection.Workouts -> decodeRecord(Workout.serializer(), record)
        } ?: return null
        return Incoming(collection, id, record["updatedAt"]?.jsonPrimitive?.contentOrNull, record["entries"] is JsonArray, decoded)
    }

    private fun LocalStore.Tx.applyIncoming(item: Incoming) {
        val id = item.id
        val collection = item.collection
        val pending = outbox.count { it.recordId == id }
        if (collection == Collection.Workouts) {
            val incoming = item.record as Workout
            val mine = workouts[id]
            if (incoming.deleted) {
                // Deleted on another device. That wins over edits still queued here, whose whole-record
                // push would otherwise bring the workout back.
                if (pending > 0) updateOutbox { list -> list.filterNot { it.recordId == id } }
                putWorkout(incoming)
                recordPulled(incoming)
                return
            }
            if (pending > 0 && mine != null && item.hasEntries) {
                // A partner edited the active workout while ours has edits queued: keep both. A set only
                // the server has is new if it changed after the copy ours was based on, not after our
                // last edit (which may be later than the partner's change).
                val removed = (mine.removedEntryIds.orEmpty() + incoming.removedEntryIds.orEmpty()).distinct()
                val basedOn = meta[pulledKey(id)] ?: mine.updated ?: mine.updatedAt
                val merged = mine.copy(
                    entries = mergeWorkoutEntries(incoming.entries, mine.entries, basedOn, removed),
                    removedEntryIds = removed.ifEmpty { null },
                )
                putWorkout(merged)
                // The queued copy is what gets pushed, with this pull as its base (see withSyncBase):
                // it must carry what was just merged in, or the server would take the partner's
                // sets as removed.
                val payload = VolumJson.encodeToJsonElement(Workout.serializer(), merged.copy(updated = null)).jsonObject
                updateOutbox { list -> list.map { if (it.collection == collection.wire && it.recordId == id) it.copy(payload = payload) else it } }
                recordPulled(incoming)
                return
            }
        }
        val localUpdatedAt: String? = when (collection) {
            Collection.Exercises -> exercises[id]?.updatedAt
            Collection.Routines -> routines[id]?.updatedAt
            Collection.Workouts -> workouts[id]?.updatedAt
        }
        if (pending > 0 && localUpdatedAt != null && parseTime(localUpdatedAt) > parseTime(item.updatedAt)) return
        if (pending > 0) updateOutbox { list -> list.filterNot { it.recordId == id } }
        when (collection) {
            Collection.Exercises -> putExercise(item.record as Exercise)
            Collection.Routines -> putRoutine(item.record as Routine)
            Collection.Workouts -> (item.record as Workout).let { putWorkout(it); recordPulled(it) }
        }
    }

    /**
     * Remembers the server time of the copy an active workout was last updated from, as the merge's
     * snapshot time. Local saves rebuild the record without `updated`, so it can't live there.
     * Only active workouts are kept, so meta doesn't grow with the history.
     */
    private fun LocalStore.Tx.recordPulled(workout: Workout) {
        val updated = workout.updated
        if (workout.status == WorkoutStatus.Active && !workout.deleted && updated != null) {
            putMeta(pulledKey(workout.id), updated)
        } else {
            deleteMeta(pulledKey(workout.id))
        }
    }

    private fun pulledKey(workoutId: String) = "pulledAt.workouts.$workoutId"
}
