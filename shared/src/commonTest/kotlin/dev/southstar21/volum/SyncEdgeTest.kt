@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.southstar21.volum

import dev.southstar21.volum.data.LocalStore
import dev.southstar21.volum.data.PbException
import dev.southstar21.volum.data.PocketBase
import dev.southstar21.volum.data.Repository
import dev.southstar21.volum.data.SyncEngine
import dev.southstar21.volum.data.SyncState
import dev.southstar21.volum.model.Exercise
import dev.southstar21.volum.model.Routine
import dev.southstar21.volum.model.VolumJson
import dev.southstar21.volum.util.isoOf
import dev.southstar21.volum.util.newId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.decodeURLPart
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val EDGE_OWNER = "me"
private const val EDGE_ZERO = "1970-01-01 00:00:00.000Z"
private const val COLLECTIONS_PREFIX = "/api/collections/"

/** 2026-01-01T00:00:00Z. */
private const val BASE_MS = 1_767_225_600_000L

/** PocketBase's timestamp format ("2026-01-02 03:04:05.678Z"). */
private fun stamp(millis: Long) = isoOf(millis).replace('T', ' ')

private fun <T> asRecord(serializer: KSerializer<T>, value: T): JsonObject = VolumJson.encodeToJsonElement(serializer, value).jsonObject

private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull ?: ""

private fun exId(i: Int) = "x" + i.toString().padStart(3, '0')

private fun exerciseRecord(i: Int, at: Long = BASE_MS + i * 1_000L, name: String = "E$i") =
    asRecord(Exercise.serializer(), Exercise(id = exId(i), owner = EDGE_OWNER, name = name, updatedAt = isoOf(at), updated = stamp(at)))

private fun routineRecord(id: String, at: Long, owner: String = EDGE_OWNER) =
    asRecord(Routine.serializer(), Routine(id = id, owner = owner, name = "Routine $id", updatedAt = isoOf(at), updated = stamp(at)))

/** An exercise this app can't decode (no name), as an older client or a future schema might leave. */
private fun undecodableExercise(id: String, at: Long) = buildJsonObject {
    put("id", id)
    put("owner", "partner")
    put("updatedAt", isoOf(at))
    put("updated", stamp(at))
}

/**
 * An in-memory PocketBase behind Ktor's MockEngine, running on the test's virtual clock. Lists honour
 * `page`/`perPage` (with `totalPages`, as PocketBase pages), the `since`, cursor and `id = ...`
 * filters, and sort by `updated,id`. Writes echo the record back. [route] overrides a reply.
 */
private class EdgeServer(private val scope: TestScope) {
    class Req(val method: HttpMethod, val collection: String?, val id: String?, val filter: String?, val body: JsonObject?, val at: Long) {
        fun isList(c: String) = method == HttpMethod.Get && id == null && collection == c
        fun isPush() = collection != null && (method == HttpMethod.Patch || method == HttpMethod.Post)
        fun filterHas(text: String) = filter?.contains(text) == true
    }

    sealed interface Reply {
        class Json(val status: Int, val body: String) : Reply

        /** 200 headers, then the body stream fails with [cause] after [prefix]. */
        class Broken(val prefix: String, val cause: Throwable) : Reply
    }

    val log = MutableStateFlow<List<Req>>(emptyList())
    val lists = mutableMapOf<String, List<JsonObject>>()
    var route: suspend (Req) -> Reply? = { null }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    val client = HttpClient(
        MockEngine(
            MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(scope.testScheduler)
                reuseHandlers = true
                addHandler { data ->
                    val path = data.url.encodedPath
                    val parts = if (path.startsWith(COLLECTIONS_PREFIX)) path.removePrefix(COLLECTIONS_PREFIX).split("/") else null
                    val body = (data.body as? TextContent)?.text?.let { VolumJson.parseToJsonElement(it).jsonObject }
                    val req = Req(
                        method = data.method,
                        collection = parts?.get(0),
                        id = parts?.getOrNull(2)?.decodeURLPart(),
                        filter = data.url.parameters["filter"],
                        body = body,
                        at = scope.testScheduler.currentTime,
                    )
                    log.update { it + req }
                    val page = data.url.parameters["page"]?.toInt() ?: 1
                    val perPage = data.url.parameters["perPage"]?.toInt() ?: 30
                    when (val reply = route(req) ?: defaultReply(req, page, perPage)) {
                        is Reply.Json -> respond(reply.body, HttpStatusCode.fromValue(reply.status), jsonHeaders)
                        is Reply.Broken -> {
                            val channel = ByteChannel()
                            channel.writeStringUtf8(reply.prefix)
                            channel.flush()
                            channel.cancel(reply.cause)
                            respond(channel, HttpStatusCode.OK, jsonHeaders)
                        }
                    }
                }
            },
        ),
    )

    private fun defaultReply(req: Req, page: Int, perPage: Int): Reply = when {
        req.collection != null && req.method == HttpMethod.Get && req.id == null -> listReply(req.collection, req.filter, page, perPage)
        req.isPush() -> Reply.Json(200, (req.body ?: JsonObject(emptyMap())).toString())
        else -> Reply.Json(404, """{"status":404,"message":"The requested resource wasn't found.","data":{}}""")
    }

    private fun listReply(collection: String, filter: String?, page: Int, perPage: Int): Reply {
        val since = filter?.let { Regex("updated >= '([^']*)'").find(it)?.groupValues?.get(1) }
        val cursor = filter?.let { Regex("updated > '([^']*)' \\|\\| \\(updated = '[^']*' && id > '([^']*)'\\)").find(it)?.groupValues }
        val ids = filter?.let { f -> Regex("\\bid = '([^']*)'").findAll(f).map { it.groupValues[1] }.toSet() }.orEmpty()
        val matching = lists[collection].orEmpty()
            .filter { r ->
                when {
                    ids.isNotEmpty() -> r.text("id") in ids
                    since != null -> r.text("updated") >= since
                    cursor != null -> r.text("updated") > cursor[1] || (r.text("updated") == cursor[1] && r.text("id") > cursor[2])
                    else -> true
                }
            }
            .sortedWith(compareBy({ it.text("updated") }, { it.text("id") }))
        val totalPages = maxOf(1, (matching.size + perPage - 1) / perPage)
        val body = buildJsonObject {
            put("page", page)
            put("perPage", perPage)
            put("totalItems", matching.size)
            put("totalPages", totalPages)
            put("items", JsonArray(matching.drop((page - 1) * perPage).take(perPage)))
        }
        return Reply.Json(200, body.toString())
    }

    fun lists(collection: String) = log.value.filter { it.isList(collection) }
}

private class EdgeRig(private val scope: TestScope, val dir: Path = Path(SystemTemporaryDirectory, "volum-edge-${newId()}")) {
    val server = EdgeServer(scope)
    val store = LocalStore(dir, scope.backgroundScope)
    val repo = Repository(store, EDGE_OWNER)
    val pb = PocketBase("https://pb.test", server.client)
    val sync = SyncEngine(store, pb, repo, EDGE_OWNER, scope.backgroundScope) {}

    /** A new engine on the same store, as after the app is started again. */
    fun relaunch() = SyncEngine(store, pb, repo, EDGE_OWNER, scope.backgroundScope) {}
}

class SyncEdgeTest {
    private fun connectionReset() = IOException("Connection reset by peer")

    // ---- Network failures -------------------------------------------------------------------

    @Test fun bodyCancelledAfterTheHeadersIsANetworkFailureNotACancellation() = runTest {
        val rig = EdgeRig(this)
        rig.server.route = { r ->
            if (r.method == HttpMethod.Get) EdgeServer.Reply.Broken("""{"page":1,"items":[""", CancellationException("Stream reset")) else null
        }

        val error = assertFailsWith<PbException> { rig.pb.getList("exercises", 1, 500) }
        assertEquals(0, error.status)

        rig.sync.syncNow()
        assertEquals(SyncState.Offline, rig.sync.status.value.state)
        assertNull(rig.store.meta.value["lastPull.exercises"])

        rig.server.route = { null }
        rig.sync.syncNow()
        assertEquals(SyncState.Synced, rig.sync.status.value.state)
        assertEquals(EDGE_ZERO, rig.store.meta.value["lastPull.exercises"])
    }

    @Test fun pushWhoseReplyDropsMidBodyStaysQueuedAndIsNotRejected() = runTest {
        val rig = EdgeRig(this)
        rig.repo.saveExercise(name = "Bench")
        rig.server.route = { r -> if (r.isPush()) EdgeServer.Reply.Broken("""{"id":""", connectionReset()) else null }

        rig.sync.syncNow()

        val item = rig.store.outbox.value.single()
        assertFalse(item.failed)
        assertEquals(0, item.attempts)
        assertEquals(SyncState.Offline, rig.sync.status.value.state)

        rig.server.route = { null }
        rig.sync.syncNow()
        assertTrue(rig.store.outbox.value.isEmpty())
        assertEquals(SyncState.Synced, rig.sync.status.value.state)
    }

    // ---- Paged pulls ------------------------------------------------------------------------

    @Test fun failureOnTheSecondPageKeepsTheFirstPagesMarkerAndTheNextPullResumesFromIt() = runTest {
        val rig = EdgeRig(this)
        rig.server.lists["exercises"] = (0 until 600).map { exerciseRecord(it) }
        var listed = 0
        rig.server.route = { r ->
            if (r.isList("exercises") && ++listed == 2) EdgeServer.Reply.Broken("""{"page":1,"items":[""", connectionReset()) else null
        }

        rig.sync.syncNow()

        assertEquals(SyncState.Offline, rig.sync.status.value.state)
        assertEquals(500, rig.store.exercises.value.size)
        val firstPage = stamp(BASE_MS + 499_000)
        assertEquals(firstPage, rig.store.meta.value["lastPull.exercises"])

        rig.sync.syncNow()

        val resumed = rig.server.lists("exercises")[2]
        assertTrue(resumed.filterHas("updated >= '$firstPage'"), "resumes at the first page's last record, not from the start")
        assertEquals(600, rig.store.exercises.value.size)
        assertEquals(stamp(BASE_MS + 599_000), rig.store.meta.value["lastPull.exercises"])
        assertEquals(SyncState.Synced, rig.sync.status.value.state)
    }

    @Test fun recordEditedBetweenPagesDoesNotPushAnotherRecordOffThePage() = runTest {
        val rig = EdgeRig(this)
        rig.server.lists["exercises"] = (0..500).map { exerciseRecord(it) }
        val editedAt = BASE_MS + 10_000_000
        var listed = 0
        rig.server.route = { r ->
            if (r.isList("exercises") && ++listed == 2) {
                // Edited on another device after page 1 was served: it moves to the end of the order.
                rig.server.lists["exercises"] = rig.server.lists.getValue("exercises").map {
                    if (it.text("id") == exId(10)) exerciseRecord(10, at = editedAt, name = "Edited") else it
                }
            }
            null
        }

        rig.sync.syncNow()

        assertEquals(501, rig.store.exercises.value.size)
        assertNotNull(rig.store.exercises.value[exId(500)], "the record that was last on page 1's boundary still arrives")
        assertEquals("Edited", rig.store.exercises.value[exId(10)]?.name)
        assertEquals(stamp(editedAt), rig.store.meta.value["lastPull.exercises"])
    }

    @Test fun forgetPullMarkersWhileALaterPageIsInFlightIsNotOverwrittenByThatPull() = runTest {
        val rig = EdgeRig(this)
        val marker = stamp(BASE_MS)
        rig.store.transaction { for (c in listOf("exercises", "routines", "workouts")) putMeta("lastPull.$c", marker) }
        // A partner's routine older than the marker, and 600 newer ones: two pages.
        rig.server.lists["routines"] = listOf(routineRecord("old", BASE_MS - 86_400_000, owner = "partner")) +
            (0 until 600).map { routineRecord("r" + it.toString().padStart(3, '0'), BASE_MS + (it + 1) * 1_000L) }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var listed = 0
        rig.server.route = { r ->
            if (r.isList("routines") && ++listed == 2) {
                entered.complete(Unit)
                release.await()
            }
            null
        }

        backgroundScope.launch { rig.sync.syncNow() }
        entered.await()
        assertEquals(stamp(BASE_MS + 500_000), rig.store.meta.value["lastPull.routines"], "page 1 moved the marker")

        // Paired while page 2 is in flight.
        rig.sync.forgetPullMarkers(listOf("routines"))
        runCurrent()
        rig.store.meta.first { "lastPull.routines" !in it }
        release.complete(Unit)

        rig.server.log.first { log -> log.count { it.isList("routines") } == 4 && log.count { it.isList("workouts") } == 2 }
        val pulls = rig.server.lists("routines")
        assertTrue(pulls[0].filterHas(marker))
        assertTrue(pulls[2].filterHas(EDGE_ZERO), "the rerun pulls in full; the interrupted pull didn't write its marker back")
        assertNotNull(rig.store.routines.value["old"])
        assertEquals(601, rig.store.routines.value.size)
        assertEquals(stamp(BASE_MS + 600_000), rig.store.meta.value["lastPull.routines"])
        assertEquals(marker, rig.store.meta.value["lastPull.exercises"])
    }

    // ---- Undecodable records ----------------------------------------------------------------

    @Test fun undecodableRecordIsRetriedOncePerLaunchAndDroppedWhenTheServerNoLongerReturnsIt() = runTest {
        val rig = EdgeRig(this)
        rig.server.lists["exercises"] = listOf(undecodableExercise("bad", BASE_MS), exerciseRecord(1))
        fun retries() = rig.server.lists("exercises").count { it.filterHas("id = 'bad'") }

        rig.sync.syncNow()
        assertEquals("bad", rig.store.meta.value["undecodable.exercises"])
        assertNull(rig.store.exercises.value["bad"])
        assertNotNull(rig.store.exercises.value[exId(1)])
        assertEquals(1, retries())

        rig.sync.syncNow()
        assertEquals(1, retries(), "once per launch")

        // Deleted on the server, or no longer visible.
        rig.server.lists["exercises"] = listOf(exerciseRecord(1))
        rig.sync.syncNow()
        assertEquals(1, retries())
        assertEquals("bad", rig.store.meta.value["undecodable.exercises"])

        val relaunched = rig.relaunch()
        var failRetry = true
        rig.server.route = { r ->
            if (r.isList("exercises") && r.filterHas("id = 'bad'") && failRetry) {
                failRetry = false
                EdgeServer.Reply.Broken("""{"items":[""", connectionReset())
            } else {
                null
            }
        }
        relaunched.syncNow()
        assertEquals(SyncState.Offline, relaunched.status.value.state)
        assertEquals("bad", rig.store.meta.value["undecodable.exercises"], "a retry that never reached the server keeps the id")

        relaunched.syncNow()
        assertEquals(3, retries(), "a failed retry doesn't use up the launch's one retry")
        assertNull(rig.store.meta.value["undecodable.exercises"])
        assertNull(rig.store.exercises.value["bad"])
        assertEquals(SyncState.Synced, relaunched.status.value.state)

        relaunched.syncNow()
        assertEquals(3, retries())
    }

    // ---- Writes during a sync ---------------------------------------------------------------

    @Test fun writesDuringAPullDoNotRestartIt() = runTest {
        val rig = EdgeRig(this)
        rig.sync.start()
        rig.store.meta.first { "lastSyncedAt" in it }

        val mark = rig.server.log.value.size
        var edits = 0
        rig.server.route = { r ->
            // Steady edits while the pull runs, as during a workout.
            if (r.isList("exercises") && edits < 5) {
                edits++
                rig.repo.saveExercise(name = "Edit $edits")
            }
            null
        }
        rig.repo.saveExercise(name = "First")

        val log = rig.server.log.first { l -> l.drop(mark).any { it.isList("workouts") } }
        val untilFirstFullPull = log.drop(mark).takeWhile { !it.isList("workouts") }
        assertEquals(1, untilFirstFullPull.count { it.isList("exercises") }, "the first pull ran to the end without being restarted")
        rig.sync.stop()
    }
}
