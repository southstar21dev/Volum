@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.southstar21.volum

import dev.southstar21.volum.data.LocalStore
import dev.southstar21.volum.data.PocketBase
import dev.southstar21.volum.data.Repository
import dev.southstar21.volum.data.SyncEngine
import dev.southstar21.volum.data.SyncProblem
import dev.southstar21.volum.model.SetEntry
import dev.southstar21.volum.model.VolumJson
import dev.southstar21.volum.model.Workout
import dev.southstar21.volum.model.WorkoutEntry
import dev.southstar21.volum.model.WorkoutStatus
import dev.southstar21.volum.util.isoOf
import dev.southstar21.volum.util.newId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val USER = "me"
private val JSON = headersOf(HttpHeaders.ContentType, "application/json")
private fun pbStamp(millis: Long) = isoOf(millis).replace('T', ' ')

/** A small scripted server: [handle] answers each request (method, path after /api/collections/, filter). */
private class Script(private val handle: suspend (HttpMethod, String, String?) -> Pair<Int, String>) {
    val log = mutableListOf<String>()
    val client = HttpClient(
        MockEngine { data ->
            val path = data.url.encodedPath.removePrefix("/api/collections/")
            log += "${data.method.value} $path"
            val (status, body) = handle(data.method, path, data.url.parameters["filter"])
            respond(body, HttpStatusCode.fromValue(status), JSON)
        },
    )
}

private const val EMPTY_LIST = """{"page":1,"perPage":500,"totalPages":1,"items":[]}"""
private const val REJECTED = """{"status":400,"message":"Failed to create record.","data":{}}"""

class SyncAuditTest {
    private class Rig(scope: TestScope, val script: Script, authStillValid: suspend () -> Boolean = { true }) {
        val store = LocalStore(Path(SystemTemporaryDirectory, "volum-audit-${newId()}"), scope.backgroundScope)
        val repo = Repository(store, USER)
        var authExpired = 0
        val sync = SyncEngine(store, PocketBase("https://pb.test", script.client), repo, USER, scope.backgroundScope, authStillValid = authStillValid) { authExpired++ }
    }

    // ---- VOL-6 ----

    @Test fun filterValuesAreNeverSubstitutedTwice() {
        val out = PocketBase.filter("a = {:a} && b = {:b}", mapOf("a" to "x{:b}", "b" to "it's"))
        assertEquals("a = 'x{:b}' && b = 'it\\'s'", out)
    }

    // ---- VOL-16 ----

    private fun pushRejectingScript() = Script { m, _, _ ->
        when (m) {
            HttpMethod.Get -> 200 to EMPTY_LIST
            HttpMethod.Patch -> 404 to """{"status":404,"message":"Not found.","data":{}}"""
            else -> 400 to REJECTED
        }
    }

    @Test fun rejectedPushWithARevokedTokenSignsOutInsteadOfFailingTheOutbox() = runTest {
        var probes = 0
        val rig = Rig(this, pushRejectingScript(), authStillValid = { probes++; false })
        rig.repo.saveExercise(name = "Bench")
        rig.repo.saveExercise(name = "Squat")

        rig.sync.syncNow()

        assertEquals(1, probes)
        assertEquals(1, rig.authExpired)
        assertEquals(SyncProblem.SessionExpired, rig.sync.status.value.problem)
        assertTrue(rig.store.outbox.value.none { it.failed }, "nothing is marked rejected")
        assertEquals(2, rig.store.outbox.value.size)
    }

    @Test fun rejectedPushWithAValidTokenIsStillMarkedFailed() = runTest {
        var probes = 0
        val rig = Rig(this, pushRejectingScript(), authStillValid = { probes++; true })
        rig.repo.saveExercise(name = "Bench")
        rig.repo.saveExercise(name = "Squat")

        rig.sync.syncNow()

        assertEquals(1, probes, "asked once per run, not once per item")
        assertEquals(0, rig.authExpired)
        assertTrue(rig.store.outbox.value.all { it.failed })
    }

    // ---- VOL-17 ----

    @Test fun anUpdateRequiredAnswerWhenRetryingUndecodableRecordsKeepsTheirIds() = runTest {
        val bad = buildJsonObject { put("id", "bad"); put("updated", "2026-01-01 09:00:00.000Z") }
        val script = Script { _, path, filter ->
            when {
                path.startsWith("exercises") && filter?.contains("id = 'bad'") == true ->
                    426 to """{"status":426,"message":"Out of date","data":{"minVersion":"9.9.9"}}"""
                path.startsWith("exercises") -> 200 to """{"page":1,"perPage":500,"totalPages":1,"items":[$bad]}"""
                else -> 200 to EMPTY_LIST
            }
        }
        val rig = Rig(this, script)

        rig.sync.syncNow()

        assertEquals("bad", rig.store.meta.value["undecodable.exercises"])
    }

    // ---- VOL-14 ----

    private fun bench(updated: Long, status: WorkoutStatus, vararg sets: SetEntry, finishedAt: String? = null) = Workout(
        id = "w1", owner = USER, routineName = "Push", startedAt = isoOf(updated), finishedAt = finishedAt, status = status,
        entries = listOf(WorkoutEntry(id = "e1", exerciseId = "x1", name = "Bench", sets = sets.toList())),
        updatedAt = isoOf(updated), updated = pbStamp(updated),
    )

    private fun workoutRecord(w: Workout): JsonObject = VolumJson.encodeToJsonElement(Workout.serializer(), w).jsonObject

    @Test fun aWorkoutFinishedElsewhereIsNotReopenedByAQueuedEdit() = runTest {
        val t = 1_767_225_600_000L
        val s1 = SetEntry(id = "s1", weight = 60.0, reps = 8, updatedAt = isoOf(t))
        var served: JsonObject = workoutRecord(bench(t, WorkoutStatus.Active, s1))
        var rigRef: Rig? = null
        val script = Script { m, path, _ ->
            when {
                m == HttpMethod.Get && path.startsWith("workouts") -> 200 to """{"page":1,"perPage":500,"totalPages":1,"items":[$served]}"""
                m == HttpMethod.Get -> 200 to EMPTY_LIST
                else -> {
                    // A second edit lands while the first is pushed, so one is still queued at the pull.
                    val r = rigRef!!
                    val current = r.store.workouts.value.getValue("w1")
                    r.repo.updateWorkout(current, entries = current.entries.map { it.copy(sets = it.sets + SetEntry(id = "s2", weight = 60.0, reps = 6)) })
                    200 to "{}"
                }
            }
        }
        val rig = Rig(this, script)
        rigRef = rig
        rig.sync.syncNow()
        val local = rig.store.workouts.value.getValue("w1")
        rig.repo.updateWorkout(local, entries = local.entries.map { e -> e.copy(sets = e.sets.map { it.copy(done = true) }) })
        // Another device finished it.
        served = workoutRecord(bench(t + 60_000, WorkoutStatus.Completed, s1, finishedAt = isoOf(t + 60_000)))

        rig.sync.syncNow()

        val merged = rig.store.workouts.value.getValue("w1")
        assertEquals(WorkoutStatus.Completed, merged.status)
        assertEquals(isoOf(t + 60_000), merged.finishedAt)
        val queued = VolumJson.decodeFromJsonElement(Workout.serializer(), rig.store.outbox.value.single().payload)
        assertEquals(WorkoutStatus.Completed, queued.status)
    }

    @Test fun aRejectedOutboxItemDoesNotBlockOrGetOverwrittenByTheServerCopy() = runTest {
        val t = 1_767_225_600_000L
        val s1 = SetEntry(id = "s1", weight = 60.0, reps = 8, updatedAt = isoOf(t))
        val script = Script { m, path, _ ->
            when {
                m == HttpMethod.Get && path.startsWith("workouts") ->
                    200 to """{"page":1,"perPage":500,"totalPages":1,"items":[${workoutRecord(bench(t + 60_000, WorkoutStatus.Active, s1))}]}"""
                m == HttpMethod.Get -> 200 to EMPTY_LIST
                else -> 400 to REJECTED
            }
        }
        val rig = Rig(this, script)
        rig.repo.saveWorkout(id = "w1", routineName = "Push", entries = listOf(WorkoutEntry(id = "e1", exerciseId = "x1", name = "Bench", sets = emptyList())))
        rig.sync.syncNow()
        val failed = rig.store.outbox.value.single()
        assertTrue(failed.failed)

        rig.sync.syncNow()

        assertEquals(failed.payload, rig.store.outbox.value.single().payload, "the rejected payload is left as it was")
        assertEquals(1, rig.store.workouts.value.getValue("w1").entries.single().sets.size, "the server copy is applied")
    }

    // ---- VOL-24 ----

    @Test fun aSyncRequestedWhileOneRunsRunsAgainAfterIt() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        var exercisePulls = 0
        val script = Script { _, path, _ ->
            if (path.startsWith("exercises")) {
                exercisePulls++
                if (exercisePulls == 1) {
                    entered.complete(Unit)
                    release.await()
                } else {
                    second.complete(Unit)
                }
            }
            200 to EMPTY_LIST
        }
        val rig = Rig(this, script)
        backgroundScope.launch { rig.sync.syncNow() }
        entered.await()
        rig.sync.syncNow()
        release.complete(Unit)
        second.await()
        assertEquals(2, exercisePulls)
    }

    @Test fun stopCancelsScheduledSyncs() = runTest {
        val script = Script { _, _, _ -> 200 to EMPTY_LIST }
        val rig = Rig(this, script)
        rig.sync.scheduleSync()
        rig.sync.stop()
        testScheduler.advanceUntilIdle()
        assertTrue(script.log.isEmpty(), "a scheduled sync doesn't run after stop()")
    }

    // ---- VOL-39 ----

    @Test fun listsSkipTheTotalCount() = runTest {
        var url: String? = null
        val pb = PocketBase("https://pb.test", HttpClient(MockEngine { url = it.url.toString(); respond(EMPTY_LIST, HttpStatusCode.OK, JSON) }))
        pb.getList("exercises", 1, 10)
        assertTrue(url!!.contains("skipTotal=1"))
    }

    // ---- VOL-27 ----

    @Test fun realtimeReportsAnUpdateRequiredAnswerAndBacksOff() = runTest {
        var connects = 0
        val reported = CompletableDeferred<Unit>()
        val pb = PocketBase(
            "https://pb.test",
            HttpClient(MockEngine {
                connects++
                respond("""{"status":426,"message":"Out of date","data":{"minVersion":"2.0.0"}}""", HttpStatusCode.UpgradeRequired, JSON)
            }),
        )
        val asked = mutableListOf<String?>()
        pb.onUpdateRequired = { asked += it; reported.complete(Unit) }
        val job = pb.realtime(backgroundScope, listOf("workouts/*")) { _, _ -> }
        reported.await()
        assertEquals(listOf<String?>("2.0.0"), asked)
        // Not retried sooner than the first backoff step.
        testScheduler.advanceTimeBy(4_900)
        runCurrent()
        assertEquals(1, connects)
        job.cancel()
    }
}
