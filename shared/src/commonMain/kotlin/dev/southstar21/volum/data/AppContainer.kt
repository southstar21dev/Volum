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
import dev.southstar21.volum.util.nowMillis
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** What the UI reads: the signed-in user's [unit] and [onboardingDone], and this device's look. */
@Serializable
data class Prefs(
    val unit: WeightUnit = WeightUnit.Kg,
    val onboardingDone: Boolean = false,
    /** Plain background instead of the soft glow field. */
    val solidBackground: Boolean = false,
)

/**
 * `prefs.json` at the app root. Earlier builds kept the unit and onboarding flag here for the whole
 * device; [unit] and [onboardingDone] are those legacy values, handed to the first user who signs in
 * and then cleared.
 */
@Serializable
private data class DevicePrefs(
    val solidBackground: Boolean = false,
    val unit: WeightUnit? = null,
    val onboardingDone: Boolean? = null,
)

/** `users/<id>/prefs.json`: the part of [Prefs] that belongs to one user. */
@Serializable
private data class UserPrefs(val unit: WeightUnit = WeightUnit.Kg, val onboardingDone: Boolean = false)

@Serializable
private data class SavedAuth(val token: String, val user: AuthUser)

/**
 * Last resort for a failure nothing closer caught: a background task that dies is better than the
 * app closing mid-workout.
 */
private val backstop = CoroutineExceptionHandler { _, _ -> }

/** Where the saved sign-in lives: [SecureStore] in the app, a fake in tests. */
internal interface Secrets {
    fun read(key: String): String?
    fun write(key: String, value: String): Boolean
    fun delete(key: String)
}

internal object PlatformSecrets : Secrets {
    override fun read(key: String) = SecureStore.read(key)
    override fun write(key: String, value: String) = SecureStore.write(key, value)
    override fun delete(key: String) = SecureStore.delete(key)
}

/** How a sign-in check ended: the token is good, it was refused (signed out), or no answer came. */
internal enum class AuthCheck { Valid, Invalid, Unknown }

/** App-wide services. One per process; a [UserSession] is created per signed-in user. */
class AppContainer internal constructor(
    private val root: Path,
    val pb: PocketBase,
    private val secrets: Secrets,
    internal val mainDispatcher: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher,
) {
    constructor() : this(Path(appDataDirectory()), PocketBase(pocketBaseUrl, createHttpClient()), PlatformSecrets, Dispatchers.Main, Dispatchers.IO)

    val scope = CoroutineScope(SupervisorJob() + mainDispatcher + backstop)

    // Read from disk in init, off the main thread; the splash stays up until [ready].
    private val _prefs = MutableStateFlow(Prefs())
    val prefs: StateFlow<Prefs> = _prefs.asStateFlow()
    private var devicePrefs = DevicePrefs()
    /** The folder whose prefs.json holds the signed-in user's part of [prefs]; null when signed out. */
    private var prefsDir: Path? = null

    private val _user = MutableStateFlow<AuthUser?>(null)
    val user: StateFlow<AuthUser?> = _user.asStateFlow()

    private val _session = MutableStateFlow<UserSession?>(null)
    val session: StateFlow<UserSession?> = _session.asStateFlow()

    /** False until the saved sign-in (if any) has been restored and its data loaded. */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** One session at a time: a second login waits for the first to finish opening, then replaces it. */
    private val sessionLock = Mutex()

    /**
     * Prefs and sign-in writes, applied one at a time in the order they were made, so a quick second
     * change can't be overwritten by the first one finishing later.
     */
    private val diskWrites = Channel<() -> Unit>(Channel.UNLIMITED)

    private var lastAuthRefresh = 0L
    private var authRefresh: Deferred<AuthCheck>? = null
    private var lastExpiryRetry = 0L
    private var expiryCheck: Job? = null

    /** The last session's final write to disk; the next session waits for it before reading. */
    private var closing: Job? = null

    init {
        scope.launch(ioDispatcher) {
            for (write in diskWrites) {
                try {
                    write()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Disk full or similar: the value stays in memory and the next change writes it again.
                }
            }
        }
        scope.launch {
            val (device, saved) = withContext(ioDispatcher) {
                JsonFiles.read(Path(root, PREFS_FILE), DevicePrefs.serializer()) to readSavedAuth()
            }
            device?.let { devicePrefs = it }
            _prefs.value = Prefs(solidBackground = devicePrefs.solidBackground)
            if (saved != null) {
                try {
                    sessionLock.withLock {
                        pb.token = saved.token
                        _user.value = saved.user
                        openSessionLocked(saved.user)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The local data couldn't be opened; the sign-in screen is a way out, a stuck splash isn't.
                }
            }
            _ready.value = true
            // Refresh quietly now, then again every few hours while the process lives: an expired token
            // signs out, an offline start keeps working. delay() may not advance while the device
            // sleeps, so the age is measured on the wall clock and [refreshAuthIfStale] covers resume.
            while (true) {
                refreshAuthIfStale()
                delay(AUTH_CHECK_MS)
            }
        }
    }

    fun updatePrefs(transform: (Prefs) -> Prefs) {
        val next = transform(_prefs.value)
        _prefs.value = next
        devicePrefs = devicePrefs.copy(solidBackground = next.solidBackground)
        val device = devicePrefs
        diskWrites.trySend { JsonFiles.write(Path(root, PREFS_FILE), DevicePrefs.serializer(), device) }
        val dir = prefsDir ?: return
        val mine = UserPrefs(next.unit, next.onboardingDone)
        diskWrites.trySend { JsonFiles.write(Path(dir, PREFS_FILE), UserPrefs.serializer(), mine) }
    }

    /** The user's prefs in [dir]; the first user to sign in after an upgrade inherits the device-wide ones. */
    private suspend fun loadUserPrefs(dir: Path): UserPrefs {
        withContext(ioDispatcher) { JsonFiles.read(Path(dir, PREFS_FILE), UserPrefs.serializer()) }?.let { return it }
        val legacy = devicePrefs
        if (legacy.unit == null && legacy.onboardingDone == null) return UserPrefs()
        val migrated = UserPrefs(legacy.unit ?: WeightUnit.Kg, legacy.onboardingDone ?: false)
        devicePrefs = legacy.copy(unit = null, onboardingDone = null)
        val device = devicePrefs
        // One write, user file first: if that fails, the root file keeps the legacy values.
        diskWrites.trySend {
            JsonFiles.write(Path(dir, PREFS_FILE), UserPrefs.serializer(), migrated)
            JsonFiles.write(Path(root, PREFS_FILE), DevicePrefs.serializer(), device)
        }
        return migrated
    }

    private fun userOf(record: JsonObject) = AuthUser(
        id = record.getValue("id").jsonPrimitive.content,
        email = record["email"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        name = record["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    )

    private fun persistAuth(token: String, user: AuthUser) {
        pb.token = token
        _user.value = user
        val saved = VolumJson.encodeToString(SavedAuth.serializer(), SavedAuth(token, user))
        diskWrites.trySend { secrets.write(AUTH_KEY, saved) }
    }

    /**
     * The saved sign-in. Earlier iOS builds kept it in a plain auth.json, which went into backups;
     * that is moved into the Keychain once and deleted. On Android, SecureStore.read migrates its own
     * legacy auth.json into the encrypted store, so this fallback only runs on iOS.
     */
    private fun readSavedAuth(): SavedAuth? {
        secrets.read(AUTH_KEY)?.let { stored ->
            return runCatching { VolumJson.decodeFromString(SavedAuth.serializer(), stored) }.getOrNull()
        }
        val legacy = Path(root, "auth.json")
        val saved = JsonFiles.read(legacy, SavedAuth.serializer()) ?: return null
        if (secrets.write(AUTH_KEY, VolumJson.encodeToString(SavedAuth.serializer(), saved))) JsonFiles.delete(legacy)
        return saved
    }

    /** Call with [sessionLock] held, after setting the user and token. */
    private suspend fun openSessionLocked(user: AuthUser) {
        closeSession()
        // The closed session's last changes reach disk before this one reads (it may be the same user).
        closing?.join()
        val dir = Path(root, "users/${user.id}")
        val userPrefs = loadUserPrefs(dir)
        val session = UserSession(this, user, dir)
        try {
            session.open()
        } catch (e: Throwable) {
            session.close()
            throw e
        }
        // Signed out while the store was loading.
        if (pb.token == null || _user.value?.id != user.id) {
            session.close()
            return
        }
        prefsDir = dir
        _prefs.value = Prefs(userPrefs.unit, userPrefs.onboardingDone, devicePrefs.solidBackground)
        _session.value = session
    }

    suspend fun login(email: String, password: String) {
        val (token, record) = pb.authWithPassword(email.trim(), password)
        val user = userOf(record)
        sessionLock.withLock {
            persistAuth(token, user)
            lastAuthRefresh = nowMillis()
            openSessionLocked(user)
        }
    }

    suspend fun register(email: String, password: String) {
        pb.createUser(email.trim(), password, "")
        login(email, password)
    }

    suspend fun setDisplayName(name: String) {
        val user = _user.value ?: return
        val record = pb.update("users", user.id, buildJsonObject { put("name", name) })
        // Signed out, or in as someone else, while the update was in flight.
        if (_user.value?.id != user.id) return
        val updated = userOf(record)
        persistAuth(pb.token ?: return, updated)
        _session.value?.user = updated
    }

    /**
     * Refreshes the sign-in token if the last refresh is old enough. Call when the app comes back to
     * the foreground: a suspended process may have slept past the periodic check.
     */
    fun refreshAuthIfStale() {
        if (pb.token == null || nowMillis() - lastAuthRefresh < AUTH_REFRESH_MS) return
        scope.launch { refreshAuth() }
    }

    /**
     * Before sync pushes: if the token hasn't been refreshed for a while (a launch, a long sleep), it
     * is refreshed first. PocketBase treats an expired token as no sign-in at all, so a push made with
     * one is refused like an invalid change (404, then 400) instead of a 401, and would be marked as
     * rejected. A refusal of the token signs out here; with no answer the push goes ahead and meets
     * the same network.
     */
    internal suspend fun ensureFreshAuth() {
        if (pb.token == null || nowMillis() - lastAuthRefresh < AUTH_REFRESH_MS) return
        refreshAuth()
    }

    /**
     * Swaps the token for a fresh one and says whether the current token is good afterwards. A 401
     * signs out, unless the sign-in changed (sign-out, another sign-in) while the request was in
     * flight. Concurrent callers share one request.
     */
    private suspend fun refreshAuth(): AuthCheck {
        val running = authRefresh?.takeIf { it.isActive } ?: scope.async { doRefreshAuth() }.also { authRefresh = it }
        return running.await()
    }

    private suspend fun doRefreshAuth(): AuthCheck {
        val token = pb.token ?: return AuthCheck.Invalid
        val user = _user.value ?: return AuthCheck.Invalid
        return try {
            val (fresh, record) = pb.authRefresh()
            if (pb.token != token || _user.value?.id != user.id) {
                // Stale: the sign-in changed meanwhile. Persisting would bring back an older one.
                if (pb.token != null && _user.value?.id == user.id) AuthCheck.Valid else AuthCheck.Invalid
            } else {
                lastAuthRefresh = nowMillis()
                persistAuth(fresh, userOf(record))
                AuthCheck.Valid
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PbException) {
            when {
                e.status != 401 -> AuthCheck.Unknown
                pb.token == token -> { signOut(); AuthCheck.Invalid }
                // Refused, but for a token already replaced: the current one decides.
                else -> if (pb.token != null) AuthCheck.Valid else AuthCheck.Invalid
            }
        } catch (_: Exception) {
            AuthCheck.Unknown
        }
    }

    /**
     * Sync got a 401. The request may have carried a token that a refresh has since replaced, so a
     * refresh decides: if it works, sync resumes with the new token; if the server refuses it, the
     * user is signed out. No answer (offline, a server error) is no verdict: sync stays paused and
     * the refresh is tried again. Refreshes are at least a minute apart, so a server that keeps
     * answering 401 to sync but not to the refresh can't loop us.
     */
    internal fun onAuthExpired(session: UserSession) {
        if (expiryCheck?.isActive == true) return
        expiryCheck = scope.launch {
            while (true) {
                val wait = lastExpiryRetry + EXPIRY_RETRY_MS - nowMillis()
                if (wait > 0) delay(wait)
                if (_session.value !== session) return@launch
                lastExpiryRetry = nowMillis()
                val check = refreshAuth()
                if (_session.value !== session) return@launch
                when (check) {
                    AuthCheck.Valid -> {
                        session.sync.stop()
                        session.sync.start()
                        return@launch
                    }
                    AuthCheck.Invalid -> {
                        signOut()
                        return@launch
                    }
                    AuthCheck.Unknown -> Unit
                }
            }
        }
    }

    /**
     * Signs out but keeps `users/<id>/` on disk, outbox included, so changes that hadn't synced when a
     * 401 forced the sign-out still go up after signing back in as the same user.
     */
    fun signOut() {
        closeSession()
        _user.value = null
        pb.token = null
        prefsDir = null
        _prefs.value = Prefs(solidBackground = devicePrefs.solidBackground)
        val legacy = Path(root, "auth.json")
        diskWrites.trySend {
            secrets.delete(AUTH_KEY)
            JsonFiles.delete(legacy)
        }
    }

    /** Closes the open session, if any; its unsaved changes are written out in the background ([closing]). */
    private fun closeSession() {
        val session = _session.value ?: return
        _session.value = null
        // Stopped now, so nothing of it runs on under the next sign-in; only the final write waits.
        session.stop()
        val previous = closing
        closing = scope.launch {
            previous?.join()
            session.close()
        }
    }

    private companion object {
        const val AUTH_KEY = "auth"
        const val PREFS_FILE = "prefs.json"
        /** Well inside PocketBase's token lifetime (days), so a token in use never runs out. */
        const val AUTH_REFRESH_MS = 12 * 60 * 60 * 1000L
        const val AUTH_CHECK_MS = 15 * 60 * 1000L
        const val EXPIRY_RETRY_MS = 60 * 1000L
    }
}

/** Everything that belongs to the signed-in user: their local database, sync and partner state. */
class UserSession(val app: AppContainer, var user: AuthUser, dir: Path) {
    val scope = CoroutineScope(SupervisorJob() + app.mainDispatcher + backstop)
    val store = LocalStore(dir, scope, user.id)
    val repository = Repository(store, user.id)
    val sync = SyncEngine(
        store, app.pb, repository, user.id, scope,
        hasPartner = { partner.snapshot.value.partner != null },
        beforePush = { app.ensureFreshAuth() },
    ) { app.onAuthExpired(this@UserSession) }
    val partner = PartnerRepository(app.pb, dir, scope) { previous, current -> onPartnerChanged(previous, current) }

    suspend fun open() {
        store.load()
        partner.load()
        repullForExistingPartner()
        retryRejected()
        if (partner.knownAtStart) {
            sync.start()
        } else {
            // No pairing state on this device yet (a new install): learn it before the first pull, so
            // finding a partner doesn't restart a pull already under way. Offline, sync starts anyway.
            scope.launch {
                partner.refresh()
                sync.start()
            }
        }
        partner.start()
    }

    /**
     * Stops everything and writes what hasn't reached disk yet. The final write runs on the caller's
     * coroutine, outside this session's (cancelled) scope, so changes made just before a sign-out,
     * outbox included, are still there when the user signs back in.
     */
    suspend fun close() {
        stop()
        store.flushNow()
    }

    /** Stops sync, the partner poll and everything else running in [scope], at once. */
    fun stop() {
        sync.stop()
        partner.stop()
        scope.cancel()
    }

    /**
     * Changes the server refused stay queued as failed. A refusal can be a passing state (a push
     * made with a token that had run out is refused like an invalid change), so each sign-in gives
     * them one more try; a change that really is invalid is refused again and shows as such.
     */
    private suspend fun retryRejected() {
        store.transaction { updateOutbox { list -> list.map { if (it.failed) it.copy(failed = false) else it } } }
    }

    /**
     * Once per device: a user paired before pairing reset the pull markers (see [onPartnerChanged])
     * may be missing the partner's older routines, so those are pulled again in full. Runs before
     * sync starts, so no pull is in flight to write an old marker back.
     */
    private suspend fun repullForExistingPartner() {
        store.transaction {
            if (meta[PARTNER_REPULL_DONE] != null) return@transaction
            if (partner.snapshot.value.partner != null) deleteMeta("lastPull.routines")
            putMeta(PARTNER_REPULL_DONE, "1")
        }
    }

    private companion object {
        const val PARTNER_REPULL_DONE = "migration.partnerRepull"
    }

    /**
     * Runs on every device of both users, whoever tapped pair or unpair. A former partner's routines
     * are no longer visible, so they go; a new partner's may be older than the last pull, so routines
     * are pulled again in full. Exercises are visible to every account whoever the partner is, so
     * they are left alone.
     */
    private suspend fun onPartnerChanged(previous: PartnerUser?, current: PartnerUser?) {
        if (previous != null && previous.id != current?.id) repository.deletePartnerLocalData()
        if (current != null && current.id != previous?.id) sync.forgetPullMarkers(listOf("routines"))
    }
}

/** The pairing state and live partner sessions (backend/pb_hooks/partner.pb.js). */
class PartnerRepository(
    private val pb: PocketBase,
    private val dir: Path,
    private val scope: CoroutineScope,
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
        withContext(Dispatchers.IO) { JsonFiles.read(Path(dir, "partner.json"), PartnerSnapshot.serializer()) }?.let {
            _snapshot.value = it
            knownAtStart = true
        }
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
        } catch (e: PbException) {
            _error.value = partnerErrorMessage(e)
        } catch (_: Exception) {
            // A 2xx body that isn't the expected JSON, or a local write in the cleanup that failed.
            _error.value = "Something went wrong"
        }
    }

    /** partner.json is only a cache for the next start; a failed write isn't worth reporting. */
    private suspend fun save(snap: PartnerSnapshot) {
        try {
            withContext(Dispatchers.IO) { JsonFiles.write(Path(dir, "partner.json"), PartnerSnapshot.serializer(), snap) }
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

    fun subscribe(scope: CoroutineScope, workoutId: String, onRecord: (Workout) -> Unit) =
        pb.realtime(scope, listOf("workouts/$workoutId")) { _, record ->
            record?.let { decodeRecord(Workout.serializer(), it) }?.let(onRecord)
        }

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

/** Set fields that can go from a value back to none. */
private val CLEARABLE_SET_FIELDS = listOf("tag", "note", "seconds")

fun partnerErrorMessage(e: PbException): String = when {
    e.status == 401 -> "Session expired — sign in again"
    e.status == 404 -> "Session ended"
    (e.status == 400 || e.status == 403) && e.message.isNotBlank() -> e.message
    e.status == 0 -> "You're offline"
    else -> "Something went wrong"
}
