@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.southstar21.volum

import dev.southstar21.volum.data.AppContainer
import dev.southstar21.volum.data.JsonFiles
import dev.southstar21.volum.data.PartnerRepository
import dev.southstar21.volum.data.PocketBase
import dev.southstar21.volum.data.Secrets
import dev.southstar21.volum.data.UserSession
import dev.southstar21.volum.data.WorkoutMutation
import dev.southstar21.volum.model.AuthUser
import dev.southstar21.volum.model.Exercise
import dev.southstar21.volum.model.OutboxItem
import dev.southstar21.volum.model.PartnerSnapshot
import dev.southstar21.volum.model.PartnerUser
import dev.southstar21.volum.model.Routine
import dev.southstar21.volum.model.SetEntry
import dev.southstar21.volum.model.SetTag
import dev.southstar21.volum.model.VolumJson
import dev.southstar21.volum.model.WeightUnit
import dev.southstar21.volum.model.Workout
import dev.southstar21.volum.util.newId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val ACCOUNT_EPOCH = "1970-01-01 00:00:00.000Z"

/**
 * Accounts, records and the partner routes of PocketBase behind Ktor's MockEngine, on the test's
 * virtual clock. Every request is logged; tokens name their user.
 */
private class AccountServer(scope: TestScope) {
    class Req(val method: HttpMethod, val path: String, val filter: String?, val auth: String?, val body: JsonElement?) {
        fun isList(collection: String) = method == HttpMethod.Get && path == "/api/collections/$collection/records"
        fun isPush() = path.startsWith("/api/collections/") && !path.startsWith("/api/collections/users/") &&
            (method == HttpMethod.Patch || method == HttpMethod.Post)
    }

    class Reply(val status: Int, val body: String)

    val log = MutableStateFlow<List<Req>>(emptyList())
    private val users = mutableMapOf<String, AuthUser>()
    private val tokenUser = mutableMapOf<String, String>()
    private var issued = 0

    /** Holds a password sign-in for [email] until it returns. */
    var authGate: suspend (email: String) -> Unit = {}

    /** Replaces the default token refresh (a new token for the token's user, 401 for an unknown one). */
    var refresh: suspend (token: String?) -> Reply? = { null }

    /** Replaces the default reply to a record request (lists from [lists], writes echoed). */
    var records: suspend (Req) -> Reply? = { null }

    /** Holds a request for the pairing state until it returns. */
    var partnerGate: suspend () -> Unit = {}
    var partner: PartnerSnapshot = PartnerSnapshot()
    val lists = mutableMapOf<String, List<JsonObject>>()

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    val client = HttpClient(
        MockEngine(
            MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(scope.testScheduler)
                reuseHandlers = true
                addHandler { data ->
                    val path = data.url.encodedPath
                    val body = (data.body as? TextContent)?.text?.let { runCatching { VolumJson.parseToJsonElement(it) }.getOrNull() }
                    val req = Req(data.method, path, data.url.parameters["filter"], data.headers[HttpHeaders.Authorization], body)
                    log.update { it + req }
                    val reply = reply(req)
                    respond(reply.body, HttpStatusCode.fromValue(reply.status), jsonHeaders)
                }
            },
        ),
    )

    val pb = PocketBase("https://pb.test", client)

    fun addUser(user: AuthUser) { users[user.email] = user }

    /** A token for [userId], as a sign-in would issue it. */
    fun issue(userId: String, token: String = "t-$userId-${++issued}"): String = token.also { tokenUser[it] = userId }

    fun userOfToken(token: String?): String? = tokenUser[token]

    private fun authJson(token: String, user: AuthUser) = buildJsonObject {
        put("token", token)
        putJsonObject("record") { put("id", user.id); put("email", user.email); put("name", user.name) }
    }.toString()

    private suspend fun reply(req: Req): Reply = when {
        req.path == "/api/collections/users/auth-with-password" -> {
            val email = req.body!!.jsonObject["identity"]!!.jsonPrimitive.content
            authGate(email)
            val user = users[email]
            if (user == null) Reply(400, """{"status":400,"message":"Failed to authenticate.","data":{}}""")
            else Reply(200, authJson(issue(user.id), user))
        }
        req.path == "/api/collections/users/auth-refresh" -> refresh(req.auth) ?: run {
            val user = users.values.firstOrNull { it.id == tokenUser[req.auth] }
            if (user == null) Reply(401, """{"status":401,"message":"The request requires valid record authorization token.","data":{}}""")
            else Reply(200, authJson(issue(user.id), user))
        }
        req.path == "/api/volum/partner" -> {
            partnerGate()
            Reply(200, VolumJson.encodeToString(PartnerSnapshot.serializer(), partner))
        }
        req.path.startsWith("/api/volum/partner/workout/") -> {
            val workout = Workout(id = "pw", owner = "partner", routineName = "Legs", startedAt = T0, updatedAt = T0, updated = PB_T0)
            Reply(200, VolumJson.encodeToString(Workout.serializer(), workout))
        }
        req.path.startsWith("/api/collections/") -> records(req) ?: defaultRecordReply(req)
        else -> Reply(404, """{"status":404,"message":"Not found.","data":{}}""")
    }

    private fun defaultRecordReply(req: Req): Reply {
        val collection = req.path.removePrefix("/api/collections/").substringBefore('/')
        return when {
            req.method == HttpMethod.Get && req.isList(collection) -> {
                val items = lists[collection].orEmpty()
                Reply(200, buildJsonObject {
                    put("page", 1); put("perPage", 500); put("totalPages", 1); put("totalItems", items.size)
                    put("items", JsonArray(items))
                }.toString())
            }
            req.method == HttpMethod.Patch || req.method == HttpMethod.Post ->
                Reply(200, JsonObject((req.body as? JsonObject).orEmpty() + ("updated" to kotlinx.serialization.json.JsonPrimitive(PB_T1))).toString())
            else -> Reply(404, """{"status":404,"message":"Not found.","data":{}}""")
        }
    }

    fun lists(collection: String) = log.value.filter { it.isList(collection) }
    fun pushes() = log.value.filter { it.isPush() }
    fun refreshes() = log.value.filter { it.path == "/api/collections/users/auth-refresh" }
}

private class MemorySecrets : Secrets {
    val values = mutableMapOf<String, String>()
    override fun read(key: String): String? = values[key]
    override fun write(key: String, value: String): Boolean { values[key] = value; return true }
    override fun delete(key: String) { values.remove(key) }
}

private const val T0 = "2026-01-01T10:00:00.000Z"
private const val PB_T0 = "2026-01-01 10:00:00.000Z"
private const val PB_T1 = "2026-02-01 10:00:00.000Z"

class AppContainerTest {
    private val alex = AuthUser(id = "ua", email = "alex@example.com", name = "Alex")
    private val blair = AuthUser(id = "ub", email = "blair@example.com", name = "Blair")
    private val pat = PartnerUser(id = "up", name = "Pat", email = "pat@example.com")

    /** One test's app: a temp folder, the fake server and in-memory secrets, all on the test's clock. */
    private inner class Rig(val scope: TestScope) {
        val root = Path(SystemTemporaryDirectory, "volum-app-${newId()}")
        val server = AccountServer(scope).apply { addUser(alex); addUser(blair) }
        val secrets = MemorySecrets()
        private var app: AppContainer? = null

        fun write(relative: String, text: String) {
            val path = Path(root, relative)
            path.parent?.let { SystemFileSystem.createDirectories(it) }
            SystemFileSystem.sink(path).buffered().use { it.writeString(text) }
        }

        fun exists(relative: String) = SystemFileSystem.exists(Path(root, relative))

        fun saveSignIn(user: AuthUser, token: String) {
            server.issue(user.id, token)
            secrets.values["auth"] = buildJsonObject {
                put("token", token)
                putJsonObject("user") { put("id", user.id); put("email", user.email); put("name", user.name) }
            }.toString()
        }

        suspend fun start(): AppContainer {
            val dispatcher = StandardTestDispatcher(scope.testScheduler)
            val container = AppContainer(root, server.pb, secrets, dispatcher, dispatcher)
            app = container
            container.ready.first { it }
            return container
        }

        fun stop() {
            app?.let { it.session.value?.scope?.cancel(); it.scope.cancel() }
            runCatching { deleteTree(root) }
        }

        private fun deleteTree(path: Path) {
            if (!SystemFileSystem.exists(path)) return
            if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) SystemFileSystem.list(path).forEach(::deleteTree)
            SystemFileSystem.delete(path, mustExist = false)
        }
    }

    private fun appTest(block: suspend TestScope.(Rig) -> Unit) = runTest {
        val rig = Rig(this)
        try {
            block(rig)
        } finally {
            rig.stop()
        }
    }

    private fun routineOf(owner: String, id: String, updated: String) =
        VolumJson.encodeToJsonElement(Routine.serializer(), Routine(id = id, owner = owner, name = "Routine $id", updatedAt = T0, updated = updated)).jsonObject

    private fun exerciseOf(owner: String, id: String, updated: String) =
        VolumJson.encodeToJsonElement(Exercise.serializer(), Exercise(id = id, owner = owner, name = "Lift $id", updatedAt = T0, updated = updated)).jsonObject

    private fun AccountServer.Req.since(): String? = filter?.let { Regex("updated >= '([^']*)'").find(it)?.groupValues?.get(1) }

    // ---- Prefs ------------------------------------------------------------------------------

    @Test fun eachUserKeepsTheirOwnUnitAndOnboarding() = appTest { rig ->
        val app = rig.start()
        app.login(alex.email, "pw")
        app.updatePrefs { it.copy(unit = WeightUnit.Lb, onboardingDone = true) }
        app.signOut()

        app.login(blair.email, "pw")
        assertEquals(WeightUnit.Kg, app.prefs.value.unit)
        assertFalse(app.prefs.value.onboardingDone)
        app.signOut()

        app.login(alex.email, "pw")
        assertEquals(WeightUnit.Lb, app.prefs.value.unit)
        assertTrue(app.prefs.value.onboardingDone)
    }

    @Test fun theDeviceWidePrefsOfAnEarlierBuildGoToTheFirstUserOnly() = appTest { rig ->
        rig.write("prefs.json", """{"solidBackground":true,"unit":"lb","onboardingDone":true}""")
        val app = rig.start()
        assertTrue(app.prefs.value.solidBackground)

        app.login(alex.email, "pw")
        assertEquals(WeightUnit.Lb, app.prefs.value.unit)
        assertTrue(app.prefs.value.onboardingDone)
        assertTrue(app.prefs.value.solidBackground)
        runCurrent()
        val root = assertNotNull(JsonFiles.read(Path(rig.root, "prefs.json"), JsonObject.serializer()))
        assertEquals(setOf("solidBackground"), root.keys)
        val mine = assertNotNull(JsonFiles.read(Path(rig.root, "users/ua/prefs.json"), JsonObject.serializer()))
        assertEquals("lb", mine["unit"]?.jsonPrimitive?.content)
        app.signOut()

        app.login(blair.email, "pw")
        assertEquals(WeightUnit.Kg, app.prefs.value.unit)
        assertFalse(app.prefs.value.onboardingDone)
        assertTrue(app.prefs.value.solidBackground, "the look stays the device's")
    }

    // ---- Sign-in ----------------------------------------------------------------------------

    @Test fun aRefreshThatReturnsAfterASignOutAndANewSignInDoesNotBringTheOldOneBack() = appTest { rig ->
        rig.saveSignIn(alex, "old")
        val release = CompletableDeferred<Unit>()
        rig.server.refresh = { token -> if (token == "old") { release.await(); null } else null }
        val app = rig.start()
        assertEquals(alex.id, app.user.value?.id)
        rig.server.log.first { log -> log.any { it.path.endsWith("auth-refresh") } }

        app.signOut()
        app.login(blair.email, "pw")
        val blairToken = app.pb.token
        release.complete(Unit)
        rig.server.log.first { log -> log.count { it.path.endsWith("auth-refresh") } >= 1 }
        runCurrent()

        assertEquals(blairToken, app.pb.token)
        assertEquals(blair.id, app.user.value?.id)
        assertEquals(blair.id, app.session.value?.user?.id)
        assertTrue(rig.secrets.values["auth"]!!.contains("\"ub\""), "the saved sign-in is still Blair's")
    }

    @Test fun twoSignInsAtOnceLeaveOneSessionThatMatchesTheToken() = appTest { rig ->
        val app = rig.start()
        val sessions = mutableListOf<UserSession>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { app.session.collect { s -> if (s != null) sessions += s } }
        val gates = mapOf(alex.email to CompletableDeferred<Unit>(), blair.email to CompletableDeferred<Unit>())
        rig.server.authGate = { email -> gates.getValue(email).await() }

        val first = launch { app.login(alex.email, "pw") }
        val second = launch { app.login(blair.email, "pw") }
        rig.server.log.first { log -> log.count { it.path.endsWith("auth-with-password") } == 2 }
        gates.getValue(blair.email).complete(Unit)
        runCurrent()
        gates.getValue(alex.email).complete(Unit)
        first.join()
        second.join()
        runCurrent()

        val live = assertNotNull(app.session.value)
        assertEquals(live.user.id, app.user.value?.id)
        assertEquals(live.user.id, rig.server.userOfToken(app.pb.token))
        assertTrue(sessions.size >= 2)
        for (s in sessions) if (s !== live) assertFalse(s.scope.isActiveScope(), "a replaced session is closed")
        assertTrue(live.scope.isActiveScope())
    }

    private fun kotlinx.coroutines.CoroutineScope.isActiveScope() = coroutineContext[kotlinx.coroutines.Job]?.isActive == true

    @Test fun aStaleTokenIsRefreshedBeforeAnythingIsPushedAndARefusalSignsOutWithoutPushing() = appTest { rig ->
        rig.saveSignIn(alex, "expired")
        // PocketBase answers a refresh with a dead token with 401; a push with it would be refused as invalid.
        rig.server.refresh = { token -> if (token == "expired") AccountServer.Reply(401, """{"status":401,"message":"Unauthorized.","data":{}}""") else null }
        val queued = OutboxItem(
            id = 1, collection = "exercises", recordId = "e1",
            payload = VolumJson.encodeToJsonElement(Exercise.serializer(), Exercise(id = "e1", owner = alex.id, name = "Bench", updatedAt = T0)).jsonObject,
            createdAt = T0,
        )
        rig.write("users/ua/outbox.json", VolumJson.encodeToString(ListSerializer(OutboxItem.serializer()), listOf(queued)))

        val app = rig.start()
        app.user.first { it == null }
        runCurrent()

        assertTrue(rig.server.pushes().none { it.auth == "expired" }, "nothing went up with the dead token")
        assertNull(app.session.value)

        app.login(alex.email, "pw")
        rig.server.log.first { log -> log.any { it.isPush() && it.path.endsWith("/e1") } }
        assertEquals(app.pb.token, rig.server.pushes().first { it.path.endsWith("/e1") }.auth)
    }

    @Test fun anUnansweredRefreshAfterA401KeepsTheUserSignedInAndTriesAgain() = appTest { rig ->
        val app = rig.start()
        app.login(alex.email, "pw")
        val session = assertNotNull(app.session.value)
        var listsRefused = true
        rig.server.records = { req ->
            if (listsRefused && req.method == HttpMethod.Get) AccountServer.Reply(401, """{"status":401,"message":"Unauthorized.","data":{}}""") else null
        }
        var refreshes = 0
        rig.server.refresh = { _ ->
            refreshes++
            if (refreshes == 1) AccountServer.Reply(503, """{"status":503,"message":"Unavailable.","data":{}}""") else { listsRefused = false; null }
        }

        session.sync.syncNow()
        rig.server.log.first { log -> log.count { it.path.endsWith("auth-refresh") } >= 1 }
        runCurrent()
        assertSame(session, app.session.value, "no answer is no reason to sign out")
        assertEquals(alex.id, app.user.value?.id)

        rig.server.log.first { log -> log.count { it.path.endsWith("auth-refresh") } >= 2 }
        session.sync.status.first { it.state == dev.southstar21.volum.data.SyncState.Synced }
        assertSame(session, app.session.value)
    }

    @Test fun aRefusedRefreshAfterA401SignsOut() = appTest { rig ->
        val app = rig.start()
        app.login(alex.email, "pw")
        val session = assertNotNull(app.session.value)
        rig.server.records = { req -> if (req.method == HttpMethod.Get) AccountServer.Reply(401, """{"status":401,"message":"Unauthorized.","data":{}}""") else null }
        rig.server.refresh = { AccountServer.Reply(401, """{"status":401,"message":"Unauthorized.","data":{}}""") }

        session.sync.syncNow()
        app.user.first { it == null }

        assertNull(app.session.value)
        assertNull(app.pb.token)
        runCurrent()
        assertNull(rig.secrets.values["auth"], "the saved sign-in is gone")
    }

    // ---- Sessions ---------------------------------------------------------------------------

    @Test fun changesNotYetOnDiskAtSignOutAreThereAfterSigningBackIn() = appTest { rig ->
        val app = rig.start()
        app.login(alex.email, "pw")
        val session = assertNotNull(app.session.value)
        // The server is down, so the change stays queued too.
        rig.server.records = { req -> if (req.isPush()) AccountServer.Reply(503, """{"status":503,"message":"Unavailable.","data":{}}""") else null }
        // A directory where the store's temp file goes: the exercises table can't be written.
        val blocker = Path(rig.root, "users/ua/exercises.json.tmp")
        SystemFileSystem.createDirectories(blocker)
        session.repository.saveExercise(name = "Bench")
        session.store.writeError.first { it != null }

        // The disk is fine again, but the store's writer is waiting to retry when the user signs out.
        SystemFileSystem.delete(blocker)
        app.signOut()
        app.login(alex.email, "pw")

        val reopened = assertNotNull(app.session.value)
        assertTrue(reopened !== session)
        assertEquals(listOf("Bench"), reopened.store.exercises.value.values.map { it.name })
        assertEquals(1, reopened.store.outbox.value.size)
    }

    @Test fun changesTheServerRefusedAreTriedAgainOnTheNextSignIn() = appTest { rig ->
        val refused = OutboxItem(
            id = 3, collection = "exercises", recordId = "e1",
            payload = VolumJson.encodeToJsonElement(Exercise.serializer(), Exercise(id = "e1", owner = alex.id, name = "Bench", updatedAt = T0)).jsonObject,
            createdAt = T0, attempts = 1, failed = true, lastError = "Failed to update record.",
        )
        rig.write("users/ua/outbox.json", VolumJson.encodeToString(ListSerializer(OutboxItem.serializer()), listOf(refused)))
        val app = rig.start()

        app.login(alex.email, "pw")
        rig.server.log.first { log -> log.any { it.isPush() && it.path.endsWith("/e1") } }
        val session = assertNotNull(app.session.value)
        session.store.outbox.first { it.isEmpty() }
    }

    // ---- Partner ----------------------------------------------------------------------------

    @Test fun aUserPairedBeforeThisBuildPullsTheirPartnersRoutinesAgainOnce() = appTest { rig ->
        rig.write("users/ua/meta.json", """{"lastPull.exercises":"$PB_T0","lastPull.routines":"$PB_T0","lastPull.workouts":"$PB_T0"}""")
        rig.write("users/ua/partner.json", VolumJson.encodeToString(PartnerSnapshot.serializer(), PartnerSnapshot(partner = pat)))
        rig.server.partner = PartnerSnapshot(partner = pat)
        rig.server.lists["routines"] = listOf(routineOf(pat.id, "r1", PB_T1))
        val app = rig.start()

        app.login(alex.email, "pw")
        rig.server.log.first { log -> log.any { it.isList("workouts") } }
        assertEquals(ACCOUNT_EPOCH, rig.server.lists("routines").first().since())
        assertEquals(PB_T0, rig.server.lists("exercises").first().since(), "exercises are visible to every account: no repull")
        assertEquals(PB_T0, rig.server.lists("workouts").first().since())
        val session = assertNotNull(app.session.value)
        session.store.routines.first { "r1" in it }
        assertEquals("1", session.store.meta.value["migration.partnerRepull"])

        app.signOut()
        val before = rig.server.lists("routines").size
        app.login(alex.email, "pw")
        rig.server.log.first { log -> log.count { it.isList("routines") } > before }
        assertEquals(PB_T1, rig.server.lists("routines")[before].since(), "only once")
    }

    @Test fun gainingAPartnerPullsRoutinesAgainAndLosingOneDropsTheirRoutinesButNotTheirLifts() = appTest { rig ->
        rig.write("users/ua/meta.json", """{"lastPull.exercises":"$PB_T0","lastPull.routines":"$PB_T0","lastPull.workouts":"$PB_T0"}""")
        rig.write("users/ua/partner.json", VolumJson.encodeToString(PartnerSnapshot.serializer(), PartnerSnapshot()))
        val app = rig.start()
        app.login(alex.email, "pw")
        val session = assertNotNull(app.session.value)
        rig.server.log.first { log -> log.any { it.isList("routines") } }

        rig.server.partner = PartnerSnapshot(partner = pat)
        rig.server.lists["routines"] = listOf(routineOf(pat.id, "r1", PB_T1))
        rig.server.lists["exercises"] = listOf(exerciseOf(pat.id, "e1", PB_T1))
        val before = rig.server.log.value.size
        session.partner.refresh()
        session.store.routines.first { "r1" in it }
        session.store.exercises.first { "e1" in it }
        val after = rig.server.log.value.drop(before)
        assertEquals(ACCOUNT_EPOCH, after.first { it.isList("routines") }.since())
        assertEquals(PB_T0, after.first { it.isList("exercises") }.since())

        rig.server.partner = PartnerSnapshot()
        rig.server.lists["routines"] = emptyList()
        session.partner.refresh()
        assertFalse("r1" in session.store.routines.value)
        assertTrue("e1" in session.store.exercises.value, "the library stays visible after unpairing")
    }

    @Test fun aNewInstallLearnsThePairingStateBeforeItsFirstPullAndPullsRoutinesOnce() = appTest { rig ->
        rig.server.partner = PartnerSnapshot(partner = pat)
        rig.server.lists["routines"] = listOf(routineOf(pat.id, "r1", PB_T1))
        val app = rig.start()

        app.login(alex.email, "pw")
        val session = assertNotNull(app.session.value)
        session.store.routines.first { "r1" in it }
        session.store.meta.first { it["lastPull.routines"] == PB_T1 }
        runCurrent()

        val log = rig.server.log.value
        val partnerFirst = log.indexOfFirst { it.path == "/api/volum/partner" }
        val pullFirst = log.indexOfFirst { it.isList("routines") }
        assertTrue(partnerFirst in 0 until pullFirst, "the pairing state comes first")
        assertEquals(1, rig.server.lists("routines").count { it.since() == ACCOUNT_EPOCH })
        assertTrue(rig.exists("users/ua/partner.json"))
    }

    @Test fun partnerRefreshesRunOneAtATimeSoAnOlderAnswerNeverLandsLast() = runTest {
        val server = AccountServer(this)
        val dir = Path(SystemTemporaryDirectory, "volum-app-${newId()}")
        val changes = mutableListOf<Pair<String?, String?>>()
        val partner = PartnerRepository(server.pb, dir, backgroundScope) { previous, current -> changes += previous?.id to current?.id }
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        server.partnerGate = { if (++calls == 1) gate.await() }
        server.partner = PartnerSnapshot()

        val older = launch { partner.refresh() }
        server.log.first { it.size == 1 }
        val newer = launch {
            server.partner = PartnerSnapshot(partner = pat)
            partner.refresh()
        }
        runCurrent()
        assertEquals(1, server.log.value.size, "the second waits for the first")

        gate.complete(Unit)
        older.join()
        newer.join()

        assertEquals(pat, partner.snapshot.value.partner)
        assertEquals(listOf<Pair<String?, String?>>(null to pat.id), changes)
    }

    @Test fun aClearedTagNoteOrHoldIsSentToTheServerAsNull() = runTest {
        val server = AccountServer(this)
        val partner = PartnerRepository(server.pb, Path(SystemTemporaryDirectory, "volum-app-${newId()}"), backgroundScope)

        partner.mutate("pw", WorkoutMutation.Set("e1", "s1", SetEntry(id = "s1", weight = 60.0, reps = 8, done = true)))
        partner.mutate("pw", WorkoutMutation.Set("e1", "s2", SetEntry(id = "s2", weight = 20.0, reps = 0, seconds = 30, tag = SetTag.Drop, note = "slow")))

        val (cleared, kept) = server.log.value.map { it.body!!.jsonObject.getValue("mutation").jsonObject.getValue("patch").jsonObject }
        assertEquals(JsonNull, cleared["tag"])
        assertEquals(JsonNull, cleared["note"])
        assertEquals(JsonNull, cleared["seconds"])
        assertEquals("drop", kept["tag"]?.jsonPrimitive?.contentOrNull)
        assertEquals("slow", kept["note"]?.jsonPrimitive?.contentOrNull)
        assertEquals("30", kept["seconds"]?.jsonPrimitive?.contentOrNull)
    }
}
