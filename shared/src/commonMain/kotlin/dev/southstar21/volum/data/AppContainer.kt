package dev.southstar21.volum.data

import dev.southstar21.volum.model.AuthUser
import dev.southstar21.volum.model.PartnerSnapshot
import dev.southstar21.volum.model.PartnerUser
import dev.southstar21.volum.model.SetEntry
import dev.southstar21.volum.model.VolumJson
import dev.southstar21.volum.model.WeightUnit
import dev.southstar21.volum.model.Workout
import dev.southstar21.volum.model.WorkoutEntry
import dev.southstar21.volum.platform.SecureStore
import dev.southstar21.volum.platform.appDataDirectory
import dev.southstar21.volum.platform.createHttpClient
import dev.southstar21.volum.platform.pocketBaseUrl
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
data class Prefs(
    val unit: WeightUnit = WeightUnit.Kg,
    val onboardingDone: Boolean = false,
    /** Plain background instead of the soft glow field. */
    val solidBackground: Boolean = false,
)

@Serializable
private data class SavedAuth(val token: String, val user: AuthUser)

/** App-wide services. One per process; a [UserSession] is created per signed-in user. */
class AppContainer {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val root = Path(appDataDirectory())
    val pb = PocketBase(pocketBaseUrl, createHttpClient())

    // Read from disk in init, off the main thread; the splash stays up until [ready].
    private val _prefs = MutableStateFlow(Prefs())
    val prefs: StateFlow<Prefs> = _prefs.asStateFlow()

    private val _user = MutableStateFlow<AuthUser?>(null)
    val user: StateFlow<AuthUser?> = _user.asStateFlow()

    private val _session = MutableStateFlow<UserSession?>(null)
    val session: StateFlow<UserSession?> = _session.asStateFlow()

    /** False until the saved sign-in (if any) has been restored and its data loaded. */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    init {
        scope.launch {
            val (prefs, saved) = withContext(Dispatchers.IO) {
                JsonFiles.read(Path(root, "prefs.json"), Prefs.serializer()) to readSavedAuth()
            }
            prefs?.let { _prefs.value = it }
            if (saved != null) {
                pb.token = saved.token
                openSession(saved.user)
                // Refresh quietly; an expired token signs out, an offline start keeps working.
                launch {
                    runCatching { pb.authRefresh() }
                        .onSuccess { (token, record) -> persistAuth(token, userOf(record)) }
                        .onFailure { if (it is PbException && it.status == 401) signOut() }
                }
            }
            _ready.value = true
        }
    }

    fun updatePrefs(transform: (Prefs) -> Prefs) {
        val next = transform(_prefs.value)
        _prefs.value = next
        scope.launch(Dispatchers.IO) { JsonFiles.write(Path(root, "prefs.json"), Prefs.serializer(), next) }
    }

    private fun userOf(record: JsonObject) = AuthUser(
        id = record.getValue("id").jsonPrimitive.content,
        email = record["email"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        name = record["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    )

    private suspend fun persistAuth(token: String, user: AuthUser) {
        pb.token = token
        _user.value = user
        withContext(Dispatchers.IO) { SecureStore.write(AUTH_KEY, VolumJson.encodeToString(SavedAuth.serializer(), SavedAuth(token, user))) }
    }

    /**
     * The saved sign-in. Earlier iOS builds kept it in a plain auth.json, which went into backups;
     * that is moved into the Keychain once and deleted. (On Android SecureStore uses that same file.)
     */
    private fun readSavedAuth(): SavedAuth? {
        SecureStore.read(AUTH_KEY)?.let { stored ->
            return runCatching { VolumJson.decodeFromString(SavedAuth.serializer(), stored) }.getOrNull()
        }
        val legacy = Path(root, "auth.json")
        val saved = JsonFiles.read(legacy, SavedAuth.serializer()) ?: return null
        if (SecureStore.write(AUTH_KEY, VolumJson.encodeToString(SavedAuth.serializer(), saved))) JsonFiles.delete(legacy)
        return saved
    }

    private suspend fun openSession(user: AuthUser) {
        _session.value?.close()
        _user.value = user
        val session = UserSession(this, user, Path(root, "users/${user.id}"))
        session.open()
        _session.value = session
    }

    suspend fun login(email: String, password: String) {
        val (token, record) = pb.authWithPassword(email.trim(), password)
        val user = userOf(record)
        persistAuth(token, user)
        openSession(user)
    }

    suspend fun register(email: String, password: String) {
        pb.createUser(email.trim(), password, "")
        login(email, password)
    }

    suspend fun setDisplayName(name: String) {
        val user = _user.value ?: return
        val record = pb.update("users", user.id, buildJsonObject { put("name", name) })
        val updated = userOf(record)
        persistAuth(pb.token ?: return, updated)
        _session.value?.user = updated
    }

    fun signOut() {
        _session.value?.close()
        _session.value = null
        _user.value = null
        pb.token = null
        SecureStore.delete(AUTH_KEY)
        JsonFiles.delete(Path(root, "auth.json"))
    }

    private companion object {
        const val AUTH_KEY = "auth"
    }
}

/** Everything that belongs to the signed-in user: their local database, sync and partner state. */
class UserSession(val app: AppContainer, var user: AuthUser, dir: Path) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val store = LocalStore(dir, scope)
    val repository = Repository(store, user.id)
    val sync = SyncEngine(store, app.pb, repository, user.id, scope) { app.signOut() }
    val partner = PartnerRepository(app.pb, dir, scope)

    suspend fun open() {
        store.load()
        partner.load()
        sync.start()
        partner.start()
    }

    fun close() {
        sync.stop()
        partner.stop()
        scope.cancel()
    }
}

/** The pairing state and live partner sessions (pb_hooks/partner.pb.js). */
class PartnerRepository(private val pb: PocketBase, private val dir: Path, private val scope: CoroutineScope) {
    private val _snapshot = MutableStateFlow(PartnerSnapshot())
    val snapshot: StateFlow<PartnerSnapshot> = _snapshot.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var realtime: kotlinx.coroutines.Job? = null
    private var poll: kotlinx.coroutines.Job? = null

    suspend fun load() {
        withContext(Dispatchers.IO) { JsonFiles.read(Path(dir, "partner.json"), PartnerSnapshot.serializer()) }?.let { _snapshot.value = it }
    }

    fun start() {
        poll = scope.launch {
            while (true) {
                refresh()
                kotlinx.coroutines.delay(30_000)
            }
        }
        // Every workout event lands here, our own syncs included: a burst becomes one refresh.
        realtime = pb.realtime(scope, listOf("workouts/*")) { _, _ ->
            pending?.cancel()
            pending = scope.launch {
                kotlinx.coroutines.delay(400)
                refresh()
            }
        }
    }

    private var pending: kotlinx.coroutines.Job? = null

    fun stop() {
        poll?.cancel()
        realtime?.cancel()
        pending?.cancel()
    }

    suspend fun refresh() {
        try {
            val snap = VolumJson.decodeFromJsonElement(PartnerSnapshot.serializer(), pb.send("/api/volum/partner"))
            _error.value = null
            if (snap == _snapshot.value) return
            _snapshot.value = snap
            withContext(Dispatchers.IO) { JsonFiles.write(Path(dir, "partner.json"), PartnerSnapshot.serializer(), snap) }
        } catch (e: PbException) {
            _error.value = partnerErrorMessage(e)
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
        val result = pb.send("/api/volum/partner/workout/${id.encodeURLPathPart()}/mutate", HttpMethod.Post, body).jsonObject
        return decodeRecord(Workout.serializer(), result)
    }

    fun subscribe(scope: CoroutineScope, workoutId: String, onRecord: (Workout) -> Unit) =
        pb.realtime(scope, listOf("workouts/$workoutId")) { _, record ->
            record?.let { decodeRecord(Workout.serializer(), it) }?.let(onRecord)
        }

    private fun mutationJson(m: WorkoutMutation): JsonObject = buildJsonObject {
        when (m) {
            is WorkoutMutation.Set -> {
                put("kind", "set"); put("entryId", m.entryId); put("setId", m.setId)
                put("patch", VolumJson.encodeToJsonElement(SetEntry.serializer(), m.patch))
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

fun partnerErrorMessage(e: PbException): String = when {
    e.status == 401 -> "Session expired — sign in again"
    e.status == 404 -> "Session ended"
    (e.status == 400 || e.status == 403) && e.message.isNotBlank() -> e.message
    e.status == 0 -> "You're offline"
    else -> "Something went wrong"
}

@Suppress("unused")
private val unusedPartner: PartnerUser? = null
