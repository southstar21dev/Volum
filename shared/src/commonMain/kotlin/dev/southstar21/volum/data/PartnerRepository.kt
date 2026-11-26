package dev.southstar21.volum.data

import dev.southstar21.volum.model.PartnerSnapshot
import dev.southstar21.volum.model.PartnerUser
import dev.southstar21.volum.model.SetEntry
import dev.southstar21.volum.model.VolumJson
import dev.southstar21.volum.model.Workout
import dev.southstar21.volum.model.WorkoutEntry
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** The pairing state and live partner sessions (backend/pb_hooks/partner.pb.js). */
class PartnerRepository(
    private val pb: PocketBase,
    private val dir: Path,
    private val scope: CoroutineScope,
    /** Where the cached snapshot is read and written; tests pass a test dispatcher. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** The signed-in user's id: events about their own workouts are not worth a refresh. */
    private val userId: String = "",
    /** Called when the partner is gained, lost or replaced, before the new snapshot is saved. */
    private val onPartnerChanged: suspend (previous: PartnerUser?, current: PartnerUser?) -> Unit = { _, _ -> },
) {
    private val _snapshot = MutableStateFlow(PartnerSnapshot())
    val snapshot: StateFlow<PartnerSnapshot> = _snapshot.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var realtime: kotlinx.coroutines.Job? = null
    private var poll: kotlinx.coroutines.Job? = null

    /** One refresh at a time, so an older answer can never land after a newer one. */
    private val refreshing = Mutex()

    /** Whether the pairing state was known when the session opened (partner.json was there). */
    var knownAtStart = false
        private set

    suspend fun load() {
        withContext(ioDispatcher) { JsonFiles.read(Path(dir, "partner.json"), PartnerSnapshot.serializer()) }?.let {
            _snapshot.value = it
            knownAtStart = true
        }
    }

    /** Whether [start] was called and [stop] hasn't been since. */
    private var wanted = false

    /** Whether the app is on screen: the poll and the realtime stream only run while it is. */
    private var foreground = true

    fun start() {
        wanted = true
        if (foreground) connect()
    }

    /**
     * Tells the repository the app went to the background or came back. Away, the poll and the live
     * stream stop (they would only keep the radio busy); back, they start again with a fresh fetch.
     */
    fun setForeground(on: Boolean) {
        if (foreground == on) return
        foreground = on
        if (!wanted) return
        if (on) connect() else disconnect()
    }

    private fun connect() {
        if (poll != null) return
        poll = scope.launch {
            while (true) {
                refresh()
                kotlinx.coroutines.delay(30_000)
            }
        }
        // The live stream only matters while there is a partner to follow: without one it would wake the
        // radio for nothing. (The poll above stays: it is how an incoming pair request is found.)
        realtime = scope.launch {
            _snapshot.map { it.partner != null }.distinctUntilChanged().collectLatest { paired ->
                if (paired) pb.realtime(this, listOf("workouts/*")) { _, record -> onWorkoutEvent(record) }
            }
        }
    }

    /** One workout changed. A burst of events becomes one refresh. */
    private fun onWorkoutEvent(record: JsonObject?) {
        // The user's own workouts (their syncs, a partner's edit of them) change nothing the pairing state or the partner screen shows.
        if (record != null && userId.isNotEmpty() && record["owner"]?.let { (it as? JsonPrimitive)?.contentOrNull } == userId) return
        // A screen following one workout (see [subscribe]) shares this stream rather than opening its own.
        if (record != null && _workoutEvents.subscriptionCount.value > 0) {
            decodeRecord(Workout.serializer(), record)?.let { _workoutEvents.tryEmit(it) }
        }
        pending?.cancel()
        pending = scope.launch {
            delay(400)
            refresh()
        }
    }

    private var pending: kotlinx.coroutines.Job? = null

    private fun disconnect() {
        poll?.cancel()
        realtime?.cancel()
        pending?.cancel()
        poll = null
        realtime = null
        pending = null
    }

    fun stop() {
        wanted = false
        disconnect()
    }

    private val _workoutEvents = MutableSharedFlow<Workout>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Fetches the pairing state. Only cancellation escapes; every other failure lands in [error]. */
    suspend fun refresh() {
        refreshing.withLock { refreshLocked() }
    }

    private suspend fun refreshLocked() {
        try {
            val snap = VolumJson.decodeFromJsonElement(PartnerSnapshot.serializer(), pb.send("/api/volum/partner"))
            _error.value = null
            val previous = _snapshot.value
            if (snap == previous) return
            _snapshot.value = snap
            // partner.json is written after the cleanup, so a cleanup that fails runs again on the next start.
            if (snap.partner?.id != previous.partner?.id) onPartnerChanged(previous.partner, snap.partner)
            save(snap)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A PbException, a 2xx body that isn't the expected JSON, or a local write in the cleanup that failed.
            _error.value = partnerErrorMessage(e)
        }
    }

    /** partner.json is only a cache for the next start; a failed write isn't worth reporting. */
    private suspend fun save(snap: PartnerSnapshot) {
        try {
            withContext(ioDispatcher) { JsonFiles.write(Path(dir, "partner.json"), PartnerSnapshot.serializer(), snap) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    suspend fun requestPair(email: String) { pb.send("/api/volum/partner/request", HttpMethod.Post, buildJsonObject { put("email", email.trim()) }); refresh() }
    suspend fun accept(id: String) { pb.send("/api/volum/partner/accept", HttpMethod.Post, buildJsonObject { put("id", id) }); refresh() }
    suspend fun decline(id: String) { pb.send("/api/volum/partner/decline", HttpMethod.Post, buildJsonObject { put("id", id) }); refresh() }
    suspend fun cancelRequest(id: String) { pb.send("/api/volum/partner/request", HttpMethod.Delete, buildJsonObject { put("id", id) }); refresh() }
    suspend fun unpair() { pb.send("/api/volum/partner", HttpMethod.Delete); refresh() }

    suspend fun fetchWorkout(id: String): Workout? = decodeRecord(Workout.serializer(), pb.getOne("workouts", id))

    /** Applies one change on the server, atomically, and returns the stored workout. */
    suspend fun mutate(id: String, mutation: WorkoutMutation): Workout? {
        val body = buildJsonObject { put("mutation", mutationJson(mutation)) }
        val reply = pb.send("/api/volum/partner/workout/${id.encodeURLPathPart()}/mutate", HttpMethod.Post, body)
        // A reply that isn't a record is a failed exchange, reported like any other PbException.
        val result = reply as? JsonObject ?: throw PbException(0, "Unexpected response from server")
        return decodeRecord(Workout.serializer(), result)
    }

    /**
     * Applies [mutations] in order as one request (one transaction, all or nothing) and returns the
     * stored workout. A server that doesn't know `mutations` yet answers 400, and then they are sent
     * one by one, once, so the edit isn't lost before the backend is updated.
     */
    suspend fun mutateAll(id: String, mutations: List<WorkoutMutation>): Workout? {
        if (mutations.size == 1) return mutate(id, mutations.single())
        val body = buildJsonObject { put("mutations", JsonArray(mutations.map(::mutationJson))) }
        val reply = try {
            pb.send("/api/volum/partner/workout/${id.encodeURLPathPart()}/mutate", HttpMethod.Post, body)
        } catch (e: PbException) {
            if (e.status != BAD_REQUEST) throw e
            var last: Workout? = null
            for (m in mutations) last = mutate(id, m)
            return last
        }
        val result = reply as? JsonObject ?: throw PbException(0, "Unexpected response from server")
        return decodeRecord(Workout.serializer(), result)
    }

    private val queues = mutableMapOf<String, MutationQueue<List<WorkoutMutation>, Workout?>>()

    /**
     * Queues an edit of [workoutId] behind the earlier ones of that workout, sent in order on the
     * session's scope: leaving the screen doesn't drop what is still waiting. [onDone] gets the outcome.
     */
    fun enqueueMutation(workoutId: String, mutation: WorkoutMutation, onDone: suspend (Result<Workout?>) -> Unit) =
        enqueueMutations(workoutId, listOf(mutation), onDone)

    /** Like [enqueueMutation] for edits that go out together as one queue item (one request), in order. */
    fun enqueueMutations(workoutId: String, mutations: List<WorkoutMutation>, onDone: suspend (Result<Workout?>) -> Unit) {
        if (mutations.isEmpty()) return
        queues.getOrPut(workoutId) { MutationQueue(scope) { mutateAll(workoutId, it) } }.submit(mutations, onDone)
    }

    /** Follows [workoutId]'s live changes on the stream [start] opened (nothing arrives while the app is in the background). */
    fun subscribe(scope: CoroutineScope, workoutId: String, onRecord: (Workout) -> Unit): Job =
        scope.launch { _workoutEvents.filter { it.id == workoutId }.collect(onRecord) }

    private fun mutationJson(m: WorkoutMutation): JsonObject = buildJsonObject {
        when (m) {
            is WorkoutMutation.Set -> {
                put("kind", "set"); put("entryId", m.entryId); put("setId", m.setId)
                // The server merges only the keys it is sent, so a cleared tag, note or hold is sent
                // as null; left out, the old value would stay.
                val patch = VolumJson.encodeToJsonElement(SetEntry.serializer(), m.patch).jsonObject
                put("patch", JsonObject(patch + CLEARABLE_SET_FIELDS.filter { it !in patch }.associateWith { JsonNull }))
            }
            is WorkoutMutation.AddSet -> {
                put("kind", "addSet"); put("entryId", m.entryId)
                put("set", VolumJson.encodeToJsonElement(SetEntry.serializer(), m.set))
            }
            is WorkoutMutation.RemoveSet -> { put("kind", "removeSet"); put("entryId", m.entryId); put("setId", m.setId) }
            is WorkoutMutation.AddEntry -> { put("kind", "addEntry"); put("entry", VolumJson.encodeToJsonElement(WorkoutEntry.serializer(), m.entry)) }
            is WorkoutMutation.UpdateEntry -> {
                put("kind", "updateEntry"); put("entryId", m.entryId)
                put("patch", buildJsonObject {
                    m.name?.let { put("name", it) }
                    m.exerciseId?.let { put("exerciseId", it) }
                    m.timed?.let { put("timed", it) }
                    m.skipped?.let { put("skipped", it) }
                })
            }
            is WorkoutMutation.RemoveEntry -> { put("kind", "removeEntry"); put("entryId", m.entryId) }
        }
    }
}

private const val BAD_REQUEST = 400

/** Set fields that can go from a value back to none. */
private val CLEARABLE_SET_FIELDS = listOf("tag", "note", "seconds")
