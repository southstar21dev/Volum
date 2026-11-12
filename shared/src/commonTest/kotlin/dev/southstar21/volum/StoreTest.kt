package dev.southstar21.volum

import dev.southstar21.volum.data.LocalStore
import dev.southstar21.volum.data.Repository
import dev.southstar21.volum.model.VolumJson
import dev.southstar21.volum.model.Workout
import dev.southstar21.volum.model.WorkoutEntry
import dev.southstar21.volum.model.WorkoutStatus
import dev.southstar21.volum.util.newId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreTest {
    private fun tempDir() = Path(SystemTemporaryDirectory, "volum-test-${newId()}")

    private fun completed(id: String, at: String, vararg entries: WorkoutEntry) = Workout(
        id = id, owner = "me", routineName = "R", startedAt = at, finishedAt = at,
        status = WorkoutStatus.Completed, entries = entries.toList(), updatedAt = at,
    )

    @Test fun repeatedSavesOfOneRecordQueueOnlyTheNewest() = runTest {
        val store = LocalStore(tempDir(), backgroundScope)
        val repo = Repository(store, "me")
        val w = repo.saveWorkout(routineName = "Push", entries = emptyList())
        repo.updateWorkout(w, routineName = "Push A")
        repo.updateWorkout(w, routineName = "Push B")
        val queued = store.outbox.value.single()
        assertEquals("Push B", queued.payload["routineName"]?.jsonPrimitive?.content)
        // Ids keep rising, so an item already being pushed never shares one with its replacement.
        assertEquals(3L, queued.id)
        repo.saveExercise(name = "Bench")
        assertEquals(2, store.outbox.value.size)
    }

    @Test fun lastEntryComesFromTheNewestCompletedSession() = runTest {
        val store = LocalStore(tempDir(), backgroundScope)
        val repo = Repository(store, "me")
        store.transaction {
            putWorkout(completed("w1", "2026-01-01T10:00:00.000Z", WorkoutEntry("old", "x", "Bench")))
            putWorkout(completed("w2", "2026-01-08T10:00:00.000Z", WorkoutEntry("new", "x", "Bench")))
        }
        assertEquals("new", repo.lastEntryFor("x")?.id)
        assertNull(repo.lastEntryFor("y"))
        // The cache follows the store.
        store.transaction { putWorkout(completed("w3", "2026-01-15T10:00:00.000Z", WorkoutEntry("newest", "x", "Bench"))) }
        assertEquals("newest", repo.lastEntryFor("x")?.id)
    }

    @Test fun writesReachDiskAndReload() = runTest {
        val dir = tempDir()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LocalStore(dir, scope)
        repeat(20) { i -> store.transaction { putWorkout(completed("w$i", "2026-01-01T10:00:00.000Z")) } }
        store.transaction { putMeta("k", "v") }
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (true) {
                    val reloaded = LocalStore(dir, scope).also { it.load() }
                    if (reloaded.workouts.value.size == 20 && reloaded.meta.value["k"] == "v") break
                    delay(20)
                }
            }
        }
        scope.cancel()
        SystemFileSystem.list(dir).forEach { SystemFileSystem.delete(it) }
        SystemFileSystem.delete(dir)
    }

    private fun writeFile(dir: Path, name: String, text: String) {
        SystemFileSystem.createDirectories(dir)
        SystemFileSystem.sink(Path(dir, name)).buffered().use { it.writeString(text) }
    }

    private fun workoutJson(w: Workout) = VolumJson.encodeToString(Workout.serializer(), w)

    /** Loads [dir] with a store whose background writer is stopped afterwards, then deletes [dir]. */
    private suspend fun withLoaded(dir: Path, check: (LocalStore) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = LocalStore(dir, scope)
            store.load()
            check(store)
        } finally {
            scope.cancel()
            // The writer may still be finishing a rewrite of the damaged tables; leftovers are harmless.
            runCatching {
                SystemFileSystem.list(dir).forEach { SystemFileSystem.delete(it, mustExist = false) }
                SystemFileSystem.delete(dir, mustExist = false)
            }
        }
    }

    @Test fun truncatedTableIsQuarantinedAndPulledAgain() = runTest {
        val dir = tempDir()
        writeFile(dir, "workouts.json", """{"w1":{"id":"w1","owner":"me","rou""")
        writeFile(dir, "meta.json", """{"lastPull.workouts":"2026-01-01 10:00:00.000Z","lastPull.exercises":"2026-01-01 10:00:00.000Z"}""")
        withLoaded(dir) { store ->
            assertTrue(store.workouts.value.isEmpty())
            assertNull(store.meta.value["lastPull.workouts"])
            // Only the damaged collection is refetched.
            assertEquals("2026-01-01 10:00:00.000Z", store.meta.value["lastPull.exercises"])
            assertTrue(SystemFileSystem.list(dir).any { it.name.startsWith("workouts.json.corrupt-") })
        }
    }

    @Test fun damagedTableKeepsTheRecordsThatStillDecode() = runTest {
        val dir = tempDir()
        val good = completed("w1", "2026-01-01T10:00:00.000Z")
        writeFile(dir, "workouts.json", """{"w1":${workoutJson(good)},"w2":{"id":"w2"}}""")
        withLoaded(dir) { store ->
            assertEquals(mapOf("w1" to good), store.workouts.value)
            assertTrue(SystemFileSystem.list(dir).any { it.name.startsWith("workouts.json.corrupt-") })
        }
    }

    @Test fun unreadableOutboxRequeuesLocalChanges() = runTest {
        val dir = tempDir()
        val local = completed("w1", "2026-01-01T10:00:00.000Z")
        val pulled = completed("w2", "2026-01-01T10:00:00.000Z").copy(updated = "2026-01-01T10:00:00.000Z")
        writeFile(dir, "workouts.json", """{"w1":${workoutJson(local)},"w2":${workoutJson(pulled)}}""")
        writeFile(dir, "outbox.json", "not json")
        withLoaded(dir) { store ->
            // w1 has no server `updated`, so it changed here; w2 is the server's copy and stays put.
            assertEquals(listOf("workouts" to "w1"), store.outbox.value.map { it.collection to it.recordId })
        }
    }
}
