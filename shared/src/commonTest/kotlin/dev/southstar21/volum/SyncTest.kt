@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.southstar21.volum

import dev.southstar21.volum.data.LocalStore
import dev.southstar21.volum.data.PbException
import dev.southstar21.volum.data.PocketBase
import dev.southstar21.volum.data.Repository
import dev.southstar21.volum.data.SyncEngine
import dev.southstar21.volum.data.SyncProblem
import dev.southstar21.volum.data.SyncState
import dev.southstar21.volum.model.Exercise
import dev.southstar21.volum.model.Routine
import dev.southstar21.volum.model.SetEntry
import dev.southstar21.volum.model.VolumJson
import dev.southstar21.volum.model.Workout
import dev.southstar21.volum.model.WorkoutEntry
import dev.southstar21.volum.model.WorkoutStatus
import dev.southstar21.volum.util.isoOf
import dev.southstar21.volum.util.newId
import dev.southstar21.volum.util.nowMillis
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.decodeURLPart
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

private const val ME = "me"
private const val EPOCH = "1970-01-01 00:00:00.000Z"

/** PocketBase's timestamp format ("2026-01-02 03:04:05.678Z"). */
private fun pbTime(millis: Long) = isoOf(millis).replace('T', ' ')

private fun <T> record(serializer: KSerializer<T>, value: T): JsonObject = VolumJson.encodeToJsonElement(serializer, value).jsonObject

/**
 * An in-memory PocketBase behind Ktor's MockEngine. Lists honour the sync engine's `since`, cursor
 * and `id = ...` filters and its `updated,id` sort; writes echo the record back. [route] overrides a reply.
 */
private class FakePb(private val clock: () -> Long) {
    class Req(val method: HttpMethod, val collection: String, val id: String?, val filter: String?, val body: JsonObject?, val at: Long) {
        fun isList(c: String) = method == HttpMethod.Get && id == null && collection == c
        fun isPush() = method == HttpMethod.Patch || method == HttpMethod.Post
    }

    sealed interface Reply {
        class Json(val status: Int, val body: String) : Reply
        /** 200 headers, then the connection drops after [prefix]. */
        class Broken(val prefix: String) : Reply
    }

    val log = MutableStateFlow<List<Req>>(emptyList())
    val lists = mutableMapOf<String, List<JsonObject>>()
    var route: suspend (Req) -> Reply? = { null }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    val client = HttpClient(
        MockEngine { data ->
            val parts = data.url.encodedPath.removePrefix("/api/collections/").split("/")
            val body = (data.body as? TextContent)?.text?.let { VolumJson.parseToJsonElement(it).jsonObject }
            val req = Req(data.method, parts[0], parts.getOrNull(2)?.decodeURLPart(), data.url.parameters["filter"], body, clock())
            log.update { it + req }
            when (val reply = route(req) ?: defaultReply(req, data.url.parameters["perPage"]?.toInt() ?: 30)) {
                is Reply.Json -> respond(reply.body, HttpStatusCode.fromValue(reply.status), jsonHeaders)
                is Reply.Broken -> {
                    val channel = ByteChannel()
                    channel.writeStringUtf8(reply.prefix)
                    channel.flush()
                    channel.cancel(IOException("Connection reset by peer"))
                    respond(channel, HttpStatusCode.OK, jsonHeaders)
                }
            }
        },
    )

    private fun defaultReply(req: Req, perPage: Int): Reply = when {
        req.method == HttpMethod.Get && req.id == null -> listReply(req.collection, req.filter, perPage)
        req.isPush() -> Reply.Json(200, (req.body ?: JsonObject(emptyMap())).toString())
        else -> Reply.Json(404, """{"status":404,"message":"The requested resource wasn't found.","data":{}}""")
    }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull ?: ""

    private fun listReply(collection: String, filter: String?, perPage: Int): Reply {
        val since = filter?.let { Regex("updated >= '([^']*)'").find(it)?.groupValues?.get(1) }
        val cursor = filter?.let { Regex("updated > '([^']*)' \\|\\| \\(updated = '[^']*' && id > '([^']*)'\\)").find(it)?.groupValues }
        val ids = filter?.let { f -> Regex("\\bid = '([^']*)'").findAll(f).map { it.groupValues[1] }.toSet() }.orEmpty()
        val items = lists[collection].orEmpty()
            .filter { r ->
                when {
                    ids.isNotEmpty() -> r.str("id") in ids
                    since != null -> r.str("updated") >= since
                    cursor != null -> r.str("updated") > cursor[1] || (r.str("updated") == cursor[1] && r.str("id") > cursor[2])
                    else -> true
                }
            }
            .sortedWith(compareBy({ it.str("updated") }, { it.str("id") }))
            .take(perPage)
        val body = buildJsonObject {
            put("page", 1)
            put("perPage", perPage)
            put("totalPages", 1)
            put("items", JsonArray(items))
        }
        return Reply.Json(200, body.toString())
    }

    fun pushes() = log.value.filter { it.isPush() }
    fun lists(collection: String) = log.value.filter { it.isList(collection) }
}

private class Rig(scope: TestScope, val dir: Path = Path(SystemTemporaryDirectory, "volum-sync-${newId()}")) {
    val server = FakePb { scope.testScheduler.currentTime }
    val store = LocalStore(dir, scope.backgroundScope)
    val repo = Repository(store, ME)
    val pb = PocketBase("https://pb.test", server.client)
    var authExpired = 0
    val sync = SyncEngine(store, pb, repo, ME, scope.backgroundScope) { authExpired++ }
}

class SyncTest {
    private fun bench(updated: Long, vararg sets: SetEntry, deleted: Boolean = false) = Workout(
        id = "w1", owner = ME, routineName = "Push", startedAt = isoOf(updated), status = WorkoutStatus.Active,
        entries = listOf(WorkoutEntry(id = "e1", exerciseId = "x1", name = "Bench", sets = sets.toList())),
        updatedAt = isoOf(updated), deleted = deleted, updated = pbTime(updated),
    )

    private fun Workout.setIds() = entries.single().sets.map { it.id }

    // ---- Push -------------------------------------------------------------------------------

    @Test fun pushCreatesTheRecordWhenTheServerHasNoneYet() = runTest {
        val rig = Rig(this)
        val exercise = rig.repo.saveExercise(name = "Bench")
        rig.server.route = { r -> if (r.method == HttpMethod.Patch) FakePb.Reply.Json(404, """{"status":404,"message":"Not found.","data":{}}""") else null }

        rig.sync.syncNow()

        val pushes = rig.server.pushes()
        assertEquals(listOf(HttpMethod.Patch, HttpMethod.Post), pushes.map { it.method })
        assertEquals(exercise.id, pushes[0].id)
        val created = assertNotNull(pushes[1].body)
        assertEquals(exercise.id, created["id"]?.jsonPrimitive?.content)
        assertEquals("Bench", created["name"]?.jsonPrimitive?.content)
        assertTrue(rig.store.outbox.value.isEmpty())
        assertEquals(SyncState.Synced, rig.sync.status.value.state)
    }

    @Test fun rejectedChangeIsMarkedFailedAndNotPushedAgainUntilRetried() = runTest {
        val rig = Rig(this)
        rig.repo.saveExercise(name = "Bench")
        rig.server.route = { r ->
            if (r.method == HttpMethod.Patch) {
                FakePb.Reply.Json(400, """{"status":400,"message":"Failed to update record.","data":{"name":{"code":"validation_required","message":"Cannot be blank."}}}""")
            } else {
                null
            }
        }

        rig.sync.syncNow()

        val item = rig.store.outbox.value.single()
        assertTrue(item.failed)
        assertEquals(1, item.attempts)
        assertEquals("Failed to update record.", item.lastError)
        assertEquals(SyncState.Error, rig.sync.status.value.state)
        assertEquals(SyncProblem.Rejected, rig.sync.status.value.problem)
        // A rejection doesn't stop the pull.
        assertEquals(listOf(1, 1, 1), listOf("exercises", "routines", "workouts").map { rig.server.lists(it).size })

        rig.sync.syncNow()
        assertEquals(1, rig.server.pushes().size, "a failed item is not pushed again on its own")
        assertEquals(SyncProblem.Rejected, rig.sync.status.value.problem)

        rig.server.route = { null }
        rig.sync.retryNow()
        assertEquals(2, rig.server.pushes().size)
        assertTrue(rig.store.outbox.value.isEmpty())
        assertEquals(SyncState.Synced, rig.sync.status.value.state)
    }

    @Test fun rateLimitAndTimeoutAreRetriedWithBackoffNotRejected() = runTest {
        val rig = Rig(this)
        rig.repo.saveExercise(name = "Bench")
        val replies = ArrayDeque(listOf(429, 408))
        rig.server.route = { r ->
            if (r.method == HttpMethod.Patch) replies.removeFirstOrNull()?.let { FakePb.Reply.Json(it, """{"status":$it,"message":"Slow down.","data":{}}""") } else null
        }

        rig.sync.syncNow()

        val item = rig.store.outbox.value.single()
        assertFalse(item.failed)
        assertEquals(0, item.attempts)
        assertEquals(SyncState.Error, rig.sync.status.value.state)
        assertEquals(SyncProblem.Failed, rig.sync.status.value.problem)
        assertTrue(rig.server.lists("exercises").isEmpty(), "a transient push failure ends the run before the pull")

        // The backoff retries on its own: 429 at 0 s, 408 at 2 s, then success after 4 s more.
        rig.sync.status.first { it.state == SyncState.Synced }
        assertEquals(listOf(0L, 2_000L, 6_000L), rig.server.pushes().map { it.at })
        assertTrue(rig.store.outbox.value.isEmpty())
    }

    @Test fun expiredSessionPausesSyncAndKeepsTheOutbox() = runTest {
        val rig = Rig(this)
        rig.repo.saveExercise(name = "Bench")
        rig.server.route = { r -> if (r.method == HttpMethod.Patch) FakePb.Reply.Json(401, """{"status":401,"message":"Unauthorized.","data":{}}""") else null }

        rig.sync.syncNow()

        assertEquals(1, rig.authExpired)
        assertEquals(SyncProblem.SessionExpired, rig.sync.status.value.problem)
        assertFalse(rig.store.outbox.value.single().failed)
        val requests = rig.server.log.value.size
        rig.sync.syncNow()
        assertEquals(requests, rig.server.log.value.size, "paused until signed in again")
    }

    // ---- Pull and merge ---------------------------------------------------------------------

    /**
     * Finding 01 #5: the partner adds a set after our last pull, and our own edits (one pushed, one
     * landing during the push) are both later than it. The partner's set must survive the merge.
     */
    @Test fun partnerSetAddedAfterTheLastPullSurvivesAPendingLocalEdit() = runTest {
        val rig = Rig(this)
        val now = nowMillis()
        val pulledAt = now - 600_000
        val s1 = SetEntry(id = "s1", weight = 60.0, reps = 8, updatedAt = isoOf(pulledAt))
        rig.server.lists["workouts"] = listOf(record(Workout.serializer(), bench(pulledAt, s1)))
        rig.sync.syncNow()

        // Our first edit (queued), then the partner's set lands on the server before our sync.
        val local = rig.store.workouts.value.getValue("w1")
        rig.repo.updateWorkout(local, entries = local.entries.map { e -> e.copy(sets = e.sets.map { it.copy(done = true) }) })
        val partnerSet = SetEntry(id = "p1", weight = 40.0, reps = 10, updatedAt = isoOf(now - 300_000))
        rig.server.lists["workouts"] = listOf(record(Workout.serializer(), bench(now - 60_000, s1, partnerSet)))
        // Our second edit lands while the first is being pushed, so it is still queued at the pull.
        rig.server.route = { r ->
            if (r.method == HttpMethod.Patch && r.collection == "workouts") {
                val current = rig.store.workouts.value.getValue("w1")
                rig.repo.updateWorkout(current, entries = current.entries.map { it.copy(sets = it.sets + SetEntry(id = "s2", weight = 60.0, reps = 6, done = true)) })
            }
            null
        }

        rig.sync.syncNow()

        val merged = rig.store.workouts.value.getValue("w1")
        assertEquals(listOf("s1", "s2", "p1"), merged.setIds())
        assertTrue(merged.entries.single().sets.first { it.id == "s1" }.done, "our tick wins over the server's older copy")
        assertEquals(1, rig.store.outbox.value.size, "the second edit is still queued")
        assertEquals(SyncState.Pending, rig.sync.status.value.state)
    }

    @Test fun workoutDeletedElsewhereIsNotResurrectedByAPendingLocalEdit() = runTest {
        val rig = Rig(this)
        val pulledAt = nowMillis() - 600_000
        val s1 = SetEntry(id = "s1", weight = 60.0, reps = 8, updatedAt = isoOf(pulledAt))
        rig.server.lists["workouts"] = listOf(record(Workout.serializer(), bench(pulledAt, s1)))
        rig.sync.syncNow()

        val local = rig.store.workouts.value.getValue("w1")
        rig.repo.updateWorkout(local, entries = local.entries.map { e -> e.copy(sets = e.sets.map { it.copy(done = true) }) })
        rig.server.lists["workouts"] = listOf(record(Workout.serializer(), bench(pulledAt + 60_000, s1, deleted = true)))
        rig.server.route = { r ->
            if (r.method == HttpMethod.Patch && r.collection == "workouts") {
                val current = rig.store.workouts.value.getValue("w1")
                rig.repo.updateWorkout(current, entries = current.entries.map { it.copy(sets = it.sets + SetEntry(id = "s2", reps = 6)) })
            }
            null
        }

        rig.sync.syncNow()

        assertTrue(rig.store.workouts.value.getValue("w1").deleted)
        assertTrue(rig.store.outbox.value.isEmpty(), "queued edits of a deleted workout are dropped, not pushed")
        assertEquals(SyncState.Synced, rig.sync.status.value.state)
        val pushes = rig.server.pushes().size
        rig.sync.syncNow()
        assertEquals(pushes, rig.server.pushes().size)
    }

    /**
     * The owner adds a set, it reaches the server, and the owner removes it again before the pull, so
     * the removal is still queued when the server copy (which still has the set) is merged in.
     */
    @Test fun setTheOwnerRemovedIsNotBroughtBackByTheServerCopy() = runTest {
        val rig = Rig(this)
        val pulledAt = nowMillis() - 600_000
        val s1 = SetEntry(id = "s1", weight = 60.0, reps = 8, updatedAt = isoOf(pulledAt))
        rig.server.lists["workouts"] = listOf(record(Workout.serializer(), bench(pulledAt, s1)))
        rig.sync.syncNow()

        val local = rig.store.workouts.value.getValue("w1")
        rig.repo.updateWorkout(local, entries = local.entries.map { it.copy(sets = it.sets + SetEntry(id = "s2", weight = 60.0, reps = 6)) })
        var removed = false
        rig.server.route = { r ->
            if (r.method == HttpMethod.Patch && r.collection == "workouts" && !removed) {
                removed = true
                // The pushed copy, with s2, is now the server's; then the owner removes s2.
                rig.server.lists["workouts"] = listOf(JsonObject(r.body!! + ("updated" to JsonPrimitive(pbTime(nowMillis())))))
                val current = rig.store.workouts.value.getValue("w1")
                rig.repo.updateWorkout(current, entries = current.entries.map { e -> e.copy(sets = e.sets.filterNot { it.id == "s2" }) })
            }
            null
        }

        rig.sync.syncNow()

        val merged = rig.store.workouts.value.getValue("w1")
        assertEquals(listOf("s1"), merged.setIds())
        assertEquals(listOf("s2"), merged.entries.single().removedSetIds)
        // The queued push says so too, so the server's own merge drops it.
        val queued = rig.store.outbox.value.single().payload["entries"] as JsonArray
        assertEquals("[\"s2\"]", queued.single().jsonObject["removedSetIds"].toString())
    }

    /**
     * The server's merge keeps a partner's set only if it is newer than the copy a push was based on.
     * A workout push says which copy that was (`syncBase`, the pulled record's server time); the
     * stored record never gets the field, and other collections don't send it.
     */
    @Test fun aWorkoutPushNamesTheServerCopyItWasBasedOn() = runTest {
        val rig = Rig(this)
        val pulledAt = nowMillis() - 600_000
        rig.server.lists["workouts"] = listOf(record(Workout.serializer(), bench(pulledAt, SetEntry(id = "s1", reps = 8, updatedAt = isoOf(pulledAt)))))
        rig.sync.syncNow()

        val local = rig.store.workouts.value.getValue("w1")
        rig.repo.updateWorkout(local, entries = local.entries.map { e -> e.copy(sets = e.sets.map { it.copy(done = true) }) })
        rig.repo.saveExercise(name = "Bench")
        assertNull(rig.store.outbox.value.first { it.recordId == "w1" }.payload["syncBase"])
        rig.sync.syncNow()

        val pushed = rig.server.pushes().associateBy { it.collection }
        assertEquals(pbTime(pulledAt), pushed.getValue("workouts").body?.get("syncBase")?.jsonPrimitive?.content)
        assertNull(pushed.getValue("exercises").body?.get("syncBase"))
    }

    @Test fun aWorkoutNeverPulledIsPushedWithoutABase() = runTest {
        val rig = Rig(this)
        rig.repo.saveWorkout(routineName = "Push", entries = bench(nowMillis()).entries)
        rig.sync.syncNow()
        assertNull(rig.server.pushes().single().body?.get("syncBase"))
    }

    /**
     * Finding 01 #5 against today's server, which still bases its merge on the pushed record's own
     * time: with a partner, a queued workout is pulled before it is pushed, so the partner's new set
     * goes up inside the push instead of being dropped by it.
     */
    @Test fun withAPartnerAQueuedWorkoutIsPulledFirstSoThePushCarriesTheirNewSet() = runTest {
        val rig = Rig(this)
        val sync = SyncEngine(rig.store, rig.pb, rig.repo, ME, backgroundScope, hasPartner = { true }) {}
        val pulledAt = nowMillis() - 600_000
        val s1 = SetEntry(id = "s1", weight = 60.0, reps = 8, updatedAt = isoOf(pulledAt))
        rig.server.lists["workouts"] = listOf(record(Workout.serializer(), bench(pulledAt, s1)))
        sync.syncNow()

        val local = rig.store.workouts.value.getValue("w1")
        rig.repo.updateWorkout(local, entries = local.entries.map { e -> e.copy(sets = e.sets.map { it.copy(done = true) }) })
        val partnerAt = nowMillis() - 300_000
        val partnerSet = SetEntry(id = "p1", weight = 40.0, reps = 10, updatedAt = isoOf(partnerAt))
        rig.server.lists["workouts"] = listOf(record(Workout.serializer(), bench(partnerAt, s1, partnerSet)))
        val before = rig.server.log.value.size
        sync.syncNow()

        val requests = rig.server.log.value.drop(before)
        val push = requests.single { it.isPush() }
        assertTrue(requests.indexOfFirst { it.isList("workouts") } < requests.indexOf(push), "pulled before pushing")
        val pushedSets = (push.body!!["entries"] as JsonArray).single().jsonObject["sets"] as JsonArray
        assertEquals(listOf("s1", "p1"), pushedSets.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertTrue(pushedSets.first().jsonObject["done"]!!.jsonPrimitive.content.toBoolean(), "our tick goes up too")
        assertEquals(pbTime(partnerAt), push.body["syncBase"]?.jsonPrimitive?.content, "based on the copy just merged")
        assertEquals(listOf("s1", "p1"), rig.store.workouts.value.getValue("w1").setIds())
    }

    @Test fun withoutAPartnerNothingIsPulledBeforeThePush() = runTest {
        val rig = Rig(this)
        val pulledAt = nowMillis() - 600_000
        rig.server.lists["workouts"] = listOf(record(Workout.serializer(), bench(pulledAt, SetEntry(id = "s1", reps = 8, updatedAt = isoOf(pulledAt)))))
        rig.sync.syncNow()
        val local = rig.store.workouts.value.getValue("w1")
        rig.repo.updateWorkout(local, entries = local.entries.map { e -> e.copy(sets = e.sets.map { it.copy(done = true) }) })
        val before = rig.server.log.value.size

        rig.sync.syncNow()

        assertTrue(rig.server.log.value[before].isPush())
    }

    /** A merge during a pull updates the queued copy too, so the next push carries what came in. */
    @Test fun aPartnersSetMergedWhileAnEditIsQueuedGoesIntoTheQueuedCopy() = runTest {
        val rig = Rig(this)
        val now = nowMillis()
        val pulledAt = now - 600_000
        val s1 = SetEntry(id = "s1", weight = 60.0, reps = 8, updatedAt = isoOf(pulledAt))
        rig.server.lists["workouts"] = listOf(record(Workout.serializer(), bench(pulledAt, s1)))
        rig.sync.syncNow()
        val local = rig.store.workouts.value.getValue("w1")
        rig.repo.updateWorkout(local, entries = local.entries.map { e -> e.copy(sets = e.sets.map { it.copy(done = true) }) })
        val partnerSet = SetEntry(id = "p1", weight = 40.0, reps = 10, updatedAt = isoOf(now - 300_000))
        rig.server.lists["workouts"] = listOf(record(Workout.serializer(), bench(now - 60_000, s1, partnerSet)))
        rig.server.route = { r ->
            if (r.method == HttpMethod.Patch && r.collection == "workouts") {
                val current = rig.store.workouts.value.getValue("w1")
                rig.repo.updateWorkout(current, entries = current.entries.map { it.copy(sets = it.sets + SetEntry(id = "s2", weight = 60.0, reps = 6, done = true)) })
            }
            null
        }

        rig.sync.syncNow()

        val queued = rig.store.outbox.value.single().payload["entries"] as JsonArray
        val ids = (queued.single().jsonObject["sets"] as JsonArray).map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("s1", "s2", "p1"), ids)
        assertEquals(rig.store.workouts.value.getValue("w1").copy(updated = null), VolumJson.decodeFromJsonElement(Workout.serializer(), rig.store.outbox.value.single().payload))
    }

    @Test fun theAppCanCheckTheSignInBeforeEachPush() = runTest {
        val rig = Rig(this)
        val order = mutableListOf<String>()
        val sync = SyncEngine(rig.store, rig.pb, rig.repo, ME, backgroundScope, beforePush = { order += "check" }) {}
        rig.server.route = { r -> order += r.method.value; null }
        rig.repo.saveExercise(name = "Bench")

        sync.syncNow()

        assertEquals("check", order.first())
        assertEquals(1, order.count { it == "check" })
    }

    @Test fun exerciseTheOwnerRemovedIsRecordedWhileTheWorkoutIsActive() = runTest {
        val rig = Rig(this)
        val workout = rig.repo.saveWorkout(routineName = "Push", entries = bench(nowMillis(), SetEntry(id = "s1", reps = 8)).entries)
        assertEquals(listOf("e1"), rig.repo.updateWorkout(workout, entries = emptyList()).removedEntryIds)

        // A finished workout's edits aren't tracked: no partner can change it.
        val other = rig.repo.saveWorkout(routineName = "Pull", entries = bench(nowMillis(), SetEntry(id = "s1", reps = 8)).entries)
        val finished = rig.repo.finishWorkout(other)
        assertNull(rig.repo.updateWorkout(finished, entries = emptyList()).removedEntryIds)
    }

    // ---- Network failures -------------------------------------------------------------------

    @Test fun responseThatFailsMidBodyIsANetworkFailure() = runTest {
        val rig = Rig(this)
        rig.server.route = { r -> if (r.method == HttpMethod.Get) FakePb.Reply.Broken("""{"page":1,"items":[{"id":"""") else null }

        val error = assertFailsWith<PbException> { rig.pb.getList("workouts", 1, 500) }
        assertEquals(0, error.status)

        rig.sync.syncNow()
        assertEquals(SyncState.Offline, rig.sync.status.value.state)
        assertNull(rig.store.meta.value["lastPull.exercises"], "nothing was pulled, so the marker doesn't move")
    }

    @Test fun successWithANonJsonBodyIsANetworkFailure() = runTest {
        val rig = Rig(this)
        rig.server.route = { FakePb.Reply.Json(200, "<html>Sign in to the Wi-Fi</html>") }

        val error = assertFailsWith<PbException> { rig.pb.getList("workouts", 1, 500) }
        assertEquals(0, error.status)
        rig.sync.syncNow()
        assertEquals(SyncState.Offline, rig.sync.status.value.state)
    }

    // ---- Pull markers -----------------------------------------------------------------------

    private fun writeFile(dir: Path, name: String, text: String) {
        SystemFileSystem.createDirectories(dir)
        SystemFileSystem.sink(Path(dir, name)).buffered().use { it.writeString(text) }
    }

    @Test fun corruptTableIsQuarantinedAndRefetchedInFull() = runTest {
        val marker = "2026-01-01 10:00:00.000Z"
        val dir = Path(SystemTemporaryDirectory, "volum-sync-${newId()}")
        writeFile(dir, "workouts.json", """{"w1":{"id":"w1","owner":"me","rou""")
        writeFile(dir, "meta.json", """{"lastPull.workouts":"$marker","lastPull.exercises":"$marker","lastPull.routines":"$marker"}""")
        val rig = Rig(this, dir)
        rig.store.load()
        assertTrue(SystemFileSystem.list(dir).any { it.name.startsWith("workouts.json.corrupt-") })
        // Older than the marker: only a full pull returns it.
        val old = Workout(id = "w1", owner = ME, routineName = "Push", startedAt = "2026-01-01T09:00:00.000Z", status = WorkoutStatus.Completed, updatedAt = "2026-01-01T09:00:00.000Z", updated = "2026-01-01 09:00:00.000Z")
        rig.server.lists["workouts"] = listOf(record(Workout.serializer(), old))

        rig.sync.syncNow()

        assertTrue(rig.server.lists("workouts").single().filter!!.contains(EPOCH))
        assertTrue(rig.server.lists("exercises").single().filter!!.contains(marker))
        assertEquals(old, rig.store.workouts.value["w1"])
        assertEquals("2026-01-01 09:00:00.000Z", rig.store.meta.value["lastPull.workouts"])
        assertEquals(marker, rig.store.meta.value["lastPull.exercises"])
    }

    /** An exercise this app can't decode (no name), as an older client or a future schema might leave. */
    private fun badExercise(updated: String) = buildJsonObject {
        put("id", "bad")
        put("owner", "partner")
        put("updatedAt", updated.replace(' ', 'T'))
        put("updated", updated)
    }

    @Test fun undecodableRecordDoesNotHoldThePullMarkerBack() = runTest {
        val rig = Rig(this)
        val good = Exercise(id = "good", owner = ME, name = "Bench", updatedAt = "2026-01-01T10:00:00.000Z", updated = "2026-01-01 10:00:00.000Z")
        rig.server.lists["exercises"] = listOf(badExercise("2026-01-01 09:00:00.000Z"), record(Exercise.serializer(), good))

        rig.sync.syncNow()

        assertEquals(good, rig.store.exercises.value["good"])
        assertEquals("2026-01-01 10:00:00.000Z", rig.store.meta.value["lastPull.exercises"])
        assertEquals("bad", rig.store.meta.value["undecodable.exercises"])
        // Fetched again on its own once, after the pull.
        assertEquals(2, rig.server.lists("exercises").size)
        assertTrue(rig.server.lists("exercises").last().filter!!.contains("id = 'bad'"))

        rig.sync.syncNow()
        assertEquals(3, rig.server.lists("exercises").size, "no second retry in the same launch")
        assertTrue(rig.server.lists("exercises").last().filter!!.contains("2026-01-01 10:00:00.000Z"))

        // A later launch (a newer app version) decodes it: fetched by id, applied, and forgotten.
        rig.server.lists["exercises"] = listOf(
            JsonObject(badExercise("2026-01-01 09:00:00.000Z") + ("name" to JsonPrimitive("Plank"))),
            record(Exercise.serializer(), good),
        )
        val relaunched = SyncEngine(rig.store, rig.pb, rig.repo, ME, backgroundScope) {}
        relaunched.syncNow()
        assertEquals("Plank", rig.store.exercises.value["bad"]?.name)
        assertNull(rig.store.meta.value["undecodable.exercises"])
    }

    private fun partnerRoutine() = Routine(id = "r1", owner = "partner", name = "Legs", updatedAt = "2026-01-01T09:00:00.000Z", updated = "2026-01-01 09:00:00.000Z")

    @Test fun forgetPullMarkersFetchesRecordsOlderThanTheMarker() = runTest {
        val marker = "2026-01-01 10:00:00.000Z"
        val rig = Rig(this)
        rig.store.transaction { for (c in listOf("exercises", "routines", "workouts")) putMeta("lastPull.$c", marker) }
        rig.server.lists["routines"] = listOf(record(Routine.serializer(), partnerRoutine()))
        rig.sync.syncNow()
        assertTrue(rig.store.routines.value.isEmpty(), "older than the marker, so not returned")

        rig.sync.forgetPullMarkers(listOf("exercises", "routines"))
        rig.store.meta.first { it["lastPull.routines"] == "2026-01-01 09:00:00.000Z" }
        rig.sync.status.first { it.state == SyncState.Synced }

        assertEquals(partnerRoutine(), rig.store.routines.value["r1"])
        assertTrue(rig.server.lists("routines").last().filter!!.contains(EPOCH))
        assertTrue(rig.server.lists("workouts").last().filter!!.contains(marker), "other collections keep their marker")
        assertEquals(marker, rig.store.meta.value["lastPull.workouts"])
    }

    @Test fun forgetPullMarkersDuringAPullIsNotUndoneByThatPull() = runTest {
        val marker = "2026-01-01 10:00:00.000Z"
        val rig = Rig(this)
        rig.store.transaction { for (c in listOf("exercises", "routines", "workouts")) putMeta("lastPull.$c", marker) }
        rig.server.lists["routines"] = listOf(record(Routine.serializer(), partnerRoutine()))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        rig.server.route = { r ->
            if (r.isList("exercises") && !entered.isCompleted) {
                entered.complete(Unit)
                release.await()
            }
            null
        }

        backgroundScope.launch { rig.sync.syncNow() }
        entered.await()
        // Paired while the exercises page is in flight with the old marker.
        rig.sync.forgetPullMarkers(listOf("exercises", "routines"))
        runCurrent()
        rig.store.meta.first { "lastPull.exercises" !in it }
        release.complete(Unit)

        // The running pull must not write its old marker back; the queued rerun pulls in full.
        rig.server.log.first { log -> log.count { it.isList("exercises") } == 2 }
        rig.sync.status.first { it.state == SyncState.Synced }
        val exercisePulls = rig.server.lists("exercises")
        assertTrue(exercisePulls[0].filter!!.contains(marker))
        assertTrue(exercisePulls[1].filter!!.contains(EPOCH))
        assertEquals(partnerRoutine(), rig.store.routines.value["r1"])
        assertEquals(EPOCH, rig.store.meta.value["lastPull.exercises"])
    }
}
