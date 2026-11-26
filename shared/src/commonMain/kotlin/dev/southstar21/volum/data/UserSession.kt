package dev.southstar21.volum.data

import dev.southstar21.volum.model.AuthUser
import dev.southstar21.volum.model.PartnerUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.io.files.Path

/** Everything that belongs to the signed-in user: their local database, sync and partner state. */
class UserSession(val app: AppContainer, initialUser: AuthUser, dir: Path) {
    private val _user = MutableStateFlow(initialUser)

    /** The signed-in user, observable: a name or photo change reaches whoever collects it. */
    val userState: StateFlow<AuthUser> = _user.asStateFlow()

    /** The signed-in user as of now. Read [userState] where a change must recompose. */
    val user: AuthUser get() = _user.value

    internal fun updateUser(updated: AuthUser) {
        _user.value = updated
    }

    val scope = CoroutineScope(SupervisorJob() + app.mainDispatcher + backstop)
    val store = LocalStore(dir, scope, initialUser.id, WRITE_DELAY_MS, app.ioDispatcher)
    val repository = Repository(store, initialUser.id, stampClock = { app.pb.serverNowMillis() })
    val sync = SyncEngine(
        store, app.pb, repository, initialUser.id, scope,
        hasPartner = { partner.snapshot.value.partner != null },
        beforePush = { app.ensureFreshAuth() },
        authStillValid = { app.authStillValid() },
    ) { app.onAuthExpired(this@UserSession) }
    val partner = PartnerRepository(app.pb, dir, scope, app.ioDispatcher) { previous, current -> onPartnerChanged(previous, current) }

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
        /** See [LocalStore]'s writeDelayMs: long enough to merge a burst of edits, short enough to be invisible. */
        const val WRITE_DELAY_MS = 250L
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
