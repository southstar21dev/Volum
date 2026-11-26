package dev.southstar21.volum.data

import dev.southstar21.volum.model.AuthUser
import dev.southstar21.volum.model.VolumJson
import dev.southstar21.volum.model.WeightUnit
import dev.southstar21.volum.platform.SecureStore
import dev.southstar21.volum.platform.appDataDirectory
import dev.southstar21.volum.platform.cacheDirectory
import dev.southstar21.volum.platform.logWarn
import dev.southstar21.volum.platform.appVersion
import dev.southstar21.volum.platform.isIos
import dev.southstar21.volum.platform.createHttpClient
import dev.southstar21.volum.platform.pocketBaseUrl
import dev.southstar21.volum.util.nowMillis
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
internal val backstop = CoroutineExceptionHandler { _, e -> logWarn("Volum", "A background task failed", e) }

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

/** The server no longer accepts this build; [minVersion] is the oldest one it does, when it said. */
data class UpdateRequired(val minVersion: String?)

/** App-wide services. One per process; a [UserSession] is created per signed-in user. */
class AppContainer internal constructor(
    private val root: Path,
    val pb: PocketBase,
    private val secrets: Secrets,
    internal val mainDispatcher: CoroutineDispatcher,
    internal val ioDispatcher: CoroutineDispatcher,
    /** Where downloaded photos are kept: apart from [root], so they stay out of backups. */
    private val cacheRoot: Path = Path(root, IMAGE_CACHE_DIR),
) {
    constructor() : this(
        Path(appDataDirectory()),
        PocketBase(pocketBaseUrl, createHttpClient(), appVersion, if (isIos) "ios" else "android"),
        PlatformSecrets,
        Dispatchers.Main,
        Dispatchers.IO,
        Path(cacheDirectory(), IMAGE_CACHE_DIR),
    )

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

    /** Set once the server has refused this build as too old; the app then shows only the update screen. */
    private val _updateRequired = MutableStateFlow<UpdateRequired?>(null)
    val updateRequired: StateFlow<UpdateRequired?> = _updateRequired.asStateFlow()

    /** Profile photos, cached on disk so they still show offline. */
    val images = ImageCache(cacheRoot, scope, ioDispatcher) { url -> pb.download(url) }

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

    /** The coroutine applying [diskWrites]. */
    private val writer: Job

    init {
        pb.onUpdateRequired = { minVersion -> _updateRequired.value = UpdateRequired(minVersion) }
        writer = scope.launch(ioDispatcher) {
            for (write in diskWrites) {
                try {
                    write()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Disk full or similar: the value stays in memory and the next change writes it again.
                    logWarn("AppContainer", "Couldn't write prefs or the saved sign-in", e)
                }
            }
        }
        scope.launch {
            val (device, saved) = withContext(ioDispatcher) {
                // Folder work happens here, off the main thread that built the container.
                try {
                    ensureDirectory(root)
                    // Photos used to be cached under the app data folder, which is backed up.
                    JsonFiles.deleteTree(Path(root, IMAGE_CACHE_DIR))
                } catch (e: Exception) {
                    logWarn("AppContainer", "Couldn't prepare the app folder", e)
                }
                JsonFiles.read(Path(root, PREFS_FILE), DevicePrefs.serializer()) to readSavedAuth()
            }
            device?.let { devicePrefs = it }
            _prefs.value = Prefs(solidBackground = devicePrefs.solidBackground)
            if (saved != null) {
                try {
                    sessionLock.withLock {
                        pb.token = saved.token
                        _user.value = saved.user
                        openSessionOrResetLocked(saved.user)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The local data couldn't be opened; the sign-in screen is a way out, a stuck splash isn't.
                    logWarn("AppContainer", "Couldn't open the local data", e)
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
        avatar = record["avatar"]?.jsonPrimitive?.contentOrNull.orEmpty(),
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

    /**
     * [openSessionLocked], and when it fails (the local data can't be opened) the token and user set
     * for it are dropped again: nothing may keep refreshing a sign-in the UI doesn't show. The saved
     * sign-in stays on disk, so the next launch tries again. Call with [sessionLock] held.
     */
    private suspend fun openSessionOrResetLocked(user: AuthUser) {
        try {
            openSessionLocked(user)
        } catch (e: Throwable) {
            if (_session.value == null && _user.value?.id == user.id) {
                pb.token = null
                _user.value = null
            }
            throw e
        }
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
            openSessionOrResetLocked(user)
        }
    }

    suspend fun register(email: String, password: String) {
        pb.createUser(email.trim(), password, "")
        login(email, password)
    }

    suspend fun setDisplayName(name: String) {
        val user = _user.value ?: return
        applyUserRecord(user, pb.update("users", user.id, buildJsonObject { put("name", name) }))
    }

    /** Uploads [jpeg] as the signed-in user's profile photo, replacing any earlier one. */
    suspend fun setAvatar(jpeg: ByteArray) {
        val user = _user.value ?: return
        val record = pb.uploadFile("users", user.id, AVATAR_FIELD, jpeg, "avatar.jpg", "image/jpeg")
        // Shown at once from what was uploaded: the server's thumbnail is the same picture.
        record["avatar"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { file ->
            images.put(pb.fileUrl("users", user.id, file, AVATAR_THUMB), jpeg)
        }
        applyUserRecord(user, record)
    }

    suspend fun removeAvatar() {
        val user = _user.value ?: return
        applyUserRecord(user, pb.clearFile("users", user.id, AVATAR_FIELD))
    }

    /** Where [userId]'s profile photo [file] is served, or null when they have none. */
    fun avatarUrl(userId: String, file: String): String? =
        file.takeIf(String::isNotBlank)?.let { pb.fileUrl("users", userId, it, AVATAR_THUMB) }

    /** Takes the users record a profile change returned as the signed-in user, unless they changed meanwhile. */
    private fun applyUserRecord(user: AuthUser, record: JsonObject) {
        // Signed out, or in as someone else, while the update was in flight.
        if (_user.value?.id != user.id) return
        val updated = userOf(record)
        persistAuth(pb.token ?: return, updated)
        _session.value?.updateUser(updated)
    }

    /**
     * Asks the server whether this build is still supported. Sync finds out by itself; this covers a
     * signed-out app, and lifts the block again if the server's minimum was lowered. Offline is no answer.
     */
    fun checkUpdateRequired() {
        scope.launch {
            try {
                pb.send("/api/health")
                _updateRequired.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (_: PbException) {
                // A 426 has already set the flag (PocketBase.onUpdateRequired); anything else changes nothing.
            }
        }
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
    /** Whether the server still accepts the sign-in (see SyncEngine's authStillValid); no answer counts as yes. */
    internal suspend fun authStillValid(): Boolean = refreshAuth() != AuthCheck.Invalid

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
        } catch (e: Exception) {
            logWarn("AppContainer", "Token refresh failed", e)
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

    /**
     * Shuts everything down: the open session is written out, pending prefs and sign-in writes are
     * applied, then the scope is cancelled and the HTTP client closed. For tests and embedders; the
     * apps keep one container for the life of the process and never call it.
     */
    suspend fun close() {
        closeSession()
        diskWrites.close()
        writer.join()
        closing?.join()
        scope.cancel()
        pb.close()
    }

    private companion object {
        const val AUTH_KEY = "auth"
        const val PREFS_FILE = "prefs.json"
        /** Well inside PocketBase's token lifetime (days), so a token in use never runs out. */
        const val AUTH_REFRESH_MS = 12 * 60 * 60 * 1000L
        const val AUTH_CHECK_MS = 15 * 60 * 1000L
        const val EXPIRY_RETRY_MS = 60 * 1000L
        const val IMAGE_CACHE_DIR = "image-cache"
        const val AVATAR_FIELD = "avatar"
        /** One of the thumb sizes in backend/pb_migrations/1759600014_user_avatar.js. */
        const val AVATAR_THUMB = "256x256"
    }
}
