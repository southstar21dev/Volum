@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.southstar21.volum

import dev.southstar21.volum.data.JsonFiles
import dev.southstar21.volum.data.LocalStore
import dev.southstar21.volum.model.Exercise
import dev.southstar21.volum.model.OutboxItem
import dev.southstar21.volum.model.Routine
import dev.southstar21.volum.model.RoutineItem
import dev.southstar21.volum.model.VolumJson
import dev.southstar21.volum.model.Workout
import dev.southstar21.volum.model.WorkoutStatus
import dev.southstar21.volum.util.newId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreRecoveryTest {
    /**
     * A store on [dir] whose background writer runs only when [pumpUntil] drives it, on its own
     * virtual clock: nothing reaches disk behind the test's back, and write retries wait for
     * [TestCoroutineScheduler.advanceTimeBy] instead of real time.
     */
    private class ManualStore(dir: Path) {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val store = LocalStore(dir, scope)

        /** Runs the writer (its disk work happens on a real IO thread) until [condition] holds. */
        suspend fun pumpUntil(condition: () -> Boolean) {
            withContext(Dispatchers.Default) {
                withTimeout(10_000) {
                    while (true) {
                        scheduler.runCurrent()
                        if (condition()) break
                        delay(5)
                    }
                }
            }
        }
    }

    /** One test's folder: files written into it, stores opened on it, all removed afterwards. */
    private class Folder(val dir: Path) {
        private val opened = mutableListOf<ManualStore>()

        fun open(): ManualStore = ManualStore(dir).also { opened += it }

        fun path(name: String) = Path(dir, name)

        fun exists(name: String) = SystemFileSystem.exists(path(name))

        fun write(name: String, text: String) {
            SystemFileSystem.createDirectories(dir)
            SystemFileSystem.sink(path(name)).buffered().use { it.writeString(text) }
        }

        fun readText(path: Path): String = SystemFileSystem.source(path).buffered().use { it.readString() }

        /** The copies of [name] moved aside as unreadable. */
        fun quarantined(name: String): List<Path> =
            if (!SystemFileSystem.exists(dir)) emptyList()
            else SystemFileSystem.list(dir).filter { it.name.startsWith("$name.corrupt-") }

        fun close() {
            opened.forEach { it.scope.cancel() }
            runCatching {
                if (SystemFileSystem.exists(dir)) {
                    SystemFileSystem.list(dir).forEach { SystemFileSystem.delete(it, mustExist = false) }
                    SystemFileSystem.delete(dir, mustExist = false)
                }
            }
        }
    }

    private suspend fun inFolder(block: suspend Folder.() -> Unit) {
        val folder = Folder(Path(SystemTemporaryDirectory, "volum-recovery-${newId()}"))
        try {
            folder.block()
        } finally {
            folder.close()
        }
    }

    private fun <T> enc(serializer: KSerializer<T>, value: T) = VolumJson.encodeToString(serializer, value)

    private fun workout(id: String) = Workout(
        id = id, owner = OWNER, routineName = "Push", startedAt = T0, finishedAt = T0,
        status = WorkoutStatus.Completed, updatedAt = T0,
    )

    private fun exercise(id: String) = Exercise(id = id, owner = OWNER, name = "Lift $id", updatedAt = T0)

    private fun routine(id: String) = Routine(
        id = id, owner = OWNER, name = "Routine $id",
        items = listOf(RoutineItem(id = "i-$id", exerciseId = "e1", name = "Lift e1", targetSets = 3, targetReps = 8)),
        updatedAt = T0,
    )

    private val workoutTable = MapSerializer(String.serializer(), Workout.serializer())
    private val exerciseTable = MapSerializer(String.serializer(), Exercise.serializer())
    private val metaTable = MapSerializer(String.serializer(), String.serializer())
    private val outboxList = ListSerializer(OutboxItem.serializer())

    @Test fun damagedTablesAreQuarantinedAndTheirOtherRecordsSalvagedOneByOne() = runTest {
        inFolder {
            val w1 = workout("w1")
            val w4 = workout("w4")
            val e1 = exercise("e1")
            val r1 = routine("r1")
            val workoutsText = """{"w1":${enc(Workout.serializer(), w1)},"w2":{"id":"w2"},"w3":5,"w4":${enc(Workout.serializer(), w4)}}"""
            val exercisesText = """{"e1":${enc(Exercise.serializer(), e1)},"e2":{"id":"e2","owner":"$OWNER"}}"""
            write("workouts.json", workoutsText)
            write("exercises.json", exercisesText)
            write("routines.json", """{"r1":${enc(Routine.serializer(), r1)}}""")
            write("meta.json", """{"lastPull.workouts":"$PB_T0","lastPull.exercises":"$PB_T0","lastPull.routines":"$PB_T0","theme":"dark"}""")

            val disk = open()
            disk.store.load()

            assertTrue(disk.store.loaded.value)
            assertEquals(mapOf("w1" to w1, "w4" to w4), disk.store.workouts.value)
            assertEquals(mapOf("e1" to e1), disk.store.exercises.value)
            assertEquals(mapOf("r1" to r1), disk.store.routines.value)
            assertEquals(mapOf("lastPull.routines" to PB_T0, "theme" to "dark"), disk.store.meta.value)
            assertEquals(listOf(workoutsText), quarantined("workouts.json").map { readText(it) })
            assertEquals(listOf(exercisesText), quarantined("exercises.json").map { readText(it) })
            assertTrue(quarantined("routines.json").isEmpty())
            assertTrue(quarantined("meta.json").isEmpty())
        }
    }

    @Test fun oneUndecodableRoutineCostsOnlyThatRoutine() = runTest {
        inFolder {
            val r1 = routine("r1")
            val bad = """{"id":"r2","owner":"$OWNER","name":"Bad","updatedAt":"$T0","items":[{"id":"i","exerciseId":"e1","name":"n","targetSets":"lots","targetReps":8}]}"""
            write("routines.json", """{"r1":${enc(Routine.serializer(), r1)},"r2":$bad}""")
            write("meta.json", """{"lastPull.routines":"$PB_T0","lastPull.workouts":"$PB_T0"}""")

            val disk = open()
            disk.store.load()

            assertEquals(mapOf("r1" to r1), disk.store.routines.value)
            assertEquals(mapOf("lastPull.workouts" to PB_T0), disk.store.meta.value)
            assertEquals(1, quarantined("routines.json").size)
        }
    }

    @Test fun emptyOrWrongShapedTablesAreQuarantinedAndPulledAgain() = runTest {
        inFolder {
            val e1 = exercise("e1")
            write("workouts.json", "")
            write("routines.json", "[]")
            write("exercises.json", """{"e1":${enc(Exercise.serializer(), e1)}}""")
            write("meta.json", """{"lastPull.workouts":"$PB_T0","lastPull.exercises":"$PB_T0","lastPull.routines":"$PB_T0"}""")

            val disk = open()
            disk.store.load()

            assertTrue(disk.store.workouts.value.isEmpty())
            assertTrue(disk.store.routines.value.isEmpty())
            assertEquals(mapOf("e1" to e1), disk.store.exercises.value)
            assertEquals(mapOf("lastPull.exercises" to PB_T0), disk.store.meta.value)
            assertEquals(1, quarantined("workouts.json").size)
            assertEquals(1, quarantined("routines.json").size)
            assertTrue(quarantined("exercises.json").isEmpty())
        }
    }

    @Test fun damagedTablesAreRewrittenFromWhatWasSalvaged() = runTest {
        inFolder {
            val w1 = workout("w1")
            write("workouts.json", """{"w1":${enc(Workout.serializer(), w1)},"w2":{"id":"w2"}}""")
            write("meta.json", """{"lastPull.workouts":"$PB_T0","theme":"dark"}""")

            val disk = open()
            disk.store.load()
            disk.pumpUntil {
                JsonFiles.read(path("workouts.json"), workoutTable) == mapOf("w1" to w1) &&
                    JsonFiles.read(path("meta.json"), metaTable) == mapOf("theme" to "dark")
            }

            val again = open()
            again.store.load()
            assertEquals(mapOf("w1" to w1), again.store.workouts.value)
            assertEquals(mapOf("theme" to "dark"), again.store.meta.value)
            assertEquals(1, quarantined("workouts.json").size)
            assertTrue(quarantined("meta.json").isEmpty())
        }
    }

    @Test fun damagedMetaKeepsTheEntriesThatStillDecode() = runTest {
        inFolder {
            val w1 = workout("w1")
            val metaText = """{"lastPull.workouts":"$PB_T0","nested":{"x":"y"},"list":["a"],"theme":"dark"}"""
            write("workouts.json", """{"w1":${enc(Workout.serializer(), w1)}}""")
            write("meta.json", metaText)

            val disk = open()
            disk.store.load()

            val salvaged = mapOf("lastPull.workouts" to PB_T0, "theme" to "dark")
            assertEquals(salvaged, disk.store.meta.value)
            assertEquals(mapOf("w1" to w1), disk.store.workouts.value)
            assertEquals(listOf(metaText), quarantined("meta.json").map { readText(it) })
            assertTrue(quarantined("workouts.json").isEmpty())

            disk.pumpUntil { JsonFiles.read(path("meta.json"), metaTable) == salvaged }
            val again = open()
            again.store.load()
            assertEquals(salvaged, again.store.meta.value)
            assertEquals(1, quarantined("meta.json").size)
        }
    }

    @Test fun unparseableMetaIsQuarantinedAndEveryCollectionPulledAgain() = runTest {
        inFolder {
            write("meta.json", "{\"lastPull.workouts\":\"2026-01")

            val disk = open()
            disk.store.load()

            assertTrue(disk.store.meta.value.isEmpty())
            assertEquals(1, quarantined("meta.json").size)
        }
    }

    @Test fun damagedOutboxKeepsDecodableItemsAndRequeuesRecordsWithoutServerUpdated() = runTest {
        inFolder {
            val kept1 = OutboxItem(
                id = 5, collection = "workouts", recordId = "w1",
                payload = buildJsonObject { put("id", "w1"); put("routineName", "queued") }, createdAt = T0,
            )
            val kept2 = OutboxItem(
                id = 7, collection = "exercises", recordId = "e9",
                payload = buildJsonObject { put("id", "e9") }, createdAt = T0, attempts = 3, failed = true, lastError = "rejected",
            )
            val outboxText = "[${enc(OutboxItem.serializer(), kept1)},{\"id\":\"x\"},${enc(OutboxItem.serializer(), kept2)}]"
            write("outbox.json", outboxText)

            // w1 is already queued; w2, e3 and r2 are the server's copies; e2 was deleted before its
            // server copy was made; w3, r1 and e1 (deleted after its server copy) changed here.
            val workouts = listOf(workout("w1"), workout("w2").copy(updated = PB_T0), workout("w3"))
            val exercises = listOf(
                exercise("e1").copy(deleted = true, updatedAt = T1, updated = PB_T0),
                exercise("e2").copy(deleted = true, updatedAt = T1, updated = PB_T2),
                exercise("e3").copy(updatedAt = T1, updated = PB_T0),
            )
            val routines = listOf(routine("r1"), routine("r2").copy(updated = PB_T0))
            write("workouts.json", enc(workoutTable, workouts.associateBy { it.id }))
            write("exercises.json", enc(exerciseTable, exercises.associateBy { it.id }))
            write("routines.json", enc(MapSerializer(String.serializer(), Routine.serializer()), routines.associateBy { it.id }))

            val disk = open()
            disk.store.load()
            val outbox = disk.store.outbox.value

            assertEquals(listOf(kept1, kept2), outbox.take(2))
            val requeued = outbox.drop(2)
            assertEquals(
                setOf("exercises" to "e1", "routines" to "r1", "workouts" to "w3"),
                requeued.map { it.collection to it.recordId }.toSet(),
            )
            assertEquals(requeued.size, requeued.map { it.id }.toSet().size)
            assertTrue(requeued.all { it.id > 7 && !it.failed && it.attempts == 0 })
            for (item in requeued) {
                assertEquals(item.recordId, item.payload["id"]?.jsonPrimitive?.content)
                assertNull(item.payload["updated"])
            }
            assertEquals(true, requeued.single { it.recordId == "e1" }.payload["deleted"]?.jsonPrimitive?.boolean)
            assertEquals(listOf(outboxText), quarantined("outbox.json").map { readText(it) })
            assertTrue(quarantined("workouts.json").isEmpty())

            disk.pumpUntil { JsonFiles.read(path("outbox.json"), outboxList) == outbox }
            val again = open()
            again.store.load()
            assertEquals(outbox, again.store.outbox.value)
            assertEquals(1, quarantined("outbox.json").size)
        }
    }

    @Test fun outboxThatIsNotAListRequeuesEveryUnsyncedRecord() = runTest {
        inFolder {
            write("outbox.json", """{"id":1}""")
            write("workouts.json", enc(workoutTable, mapOf("w1" to workout("w1"), "w2" to workout("w2").copy(updated = PB_T0))))

            val disk = open()
            disk.store.load()

            assertEquals(listOf("workouts" to "w1"), disk.store.outbox.value.map { it.collection to it.recordId })
            assertEquals(1, quarantined("outbox.json").size)
        }
    }

    @Test fun readableOutboxIsNotRequeued() = runTest {
        inFolder {
            write("outbox.json", "[]")
            write("workouts.json", enc(workoutTable, mapOf("w1" to workout("w1"))))

            val disk = open()
            disk.store.load()

            assertTrue(disk.store.outbox.value.isEmpty())
            assertTrue(quarantined("outbox.json").isEmpty())
        }
    }

    @Test fun missingFilesLoadEmptyWithoutQuarantineOrRewrite() = runTest {
        inFolder {
            val disk = open()
            disk.store.load()
            disk.scheduler.runCurrent()

            assertTrue(disk.store.loaded.value)
            assertTrue(disk.store.workouts.value.isEmpty())
            assertTrue(disk.store.outbox.value.isEmpty())
            assertTrue(disk.store.meta.value.isEmpty())
            assertTrue(SystemFileSystem.list(dir).isEmpty())
        }
    }

    @Test fun failedWriteKeepsTheTableDirtyAndIsRetried() = runTest {
        inFolder {
            val disk = open()
            disk.store.load()
            // A directory where the writer's temp file goes makes every write of that table fail.
            val blocker = path("workouts.json.tmp")
            SystemFileSystem.createDirectories(blocker)
            val w1 = workout("w1")

            disk.store.transaction { putWorkout(w1); putMeta("theme", "dark") }
            disk.pumpUntil { disk.store.writeError.value != null }

            assertFalse(exists("workouts.json"))
            assertEquals(mapOf("theme" to "dark"), JsonFiles.read(path("meta.json"), metaTable))
            assertEquals(mapOf("w1" to w1), disk.store.workouts.value)

            // A later change to another table must not drop the failed one.
            disk.store.transaction { putMeta("theme", "light") }
            SystemFileSystem.delete(blocker)
            disk.scheduler.advanceTimeBy(MAX_RETRY_MS)
            disk.pumpUntil {
                disk.store.writeError.value == null &&
                    JsonFiles.read(path("workouts.json"), workoutTable) == mapOf("w1" to w1) &&
                    JsonFiles.read(path("meta.json"), metaTable) == mapOf("theme" to "light")
            }

            val again = open()
            again.store.load()
            assertEquals(mapOf("w1" to w1), again.store.workouts.value)
            assertEquals("light", again.store.meta.value["theme"])
            assertTrue(quarantined("workouts.json").isEmpty())
        }
    }

    /**
     * The repair reaches disk before load returns: a process killed right after (before the writer
     * ever runs) finds the repaired tables and a meta without the dropped marker, not a missing table
     * whose marker says it was pulled.
     */
    @Test fun theRepairIsOnDiskBeforeLoadReturns() = runTest {
        inFolder {
            val w1 = workout("w1")
            val damaged = """{"w1":${enc(Workout.serializer(), w1)},"w2":{"id":"w2"}}"""
            write("workouts.json", damaged)
            write("meta.json", """{"lastPull.workouts":"$PB_T0","lastPull.routines":"$PB_T0"}""")

            val disk = open()
            disk.store.load()
            // No pumpUntil: the writer never runs, as if the process died here.

            assertEquals(mapOf("w1" to w1), JsonFiles.read(path("workouts.json"), workoutTable))
            assertEquals(mapOf("lastPull.routines" to PB_T0), JsonFiles.read(path("meta.json"), metaTable))
            assertEquals(listOf(damaged), quarantined("workouts.json").map { readText(it) })

            val again = open()
            again.store.load()
            assertEquals(mapOf("w1" to w1), again.store.workouts.value)
            assertEquals(mapOf("lastPull.routines" to PB_T0), again.store.meta.value)
            assertEquals(1, quarantined("workouts.json").size)
        }
    }

    @Test fun aDamagedOutboxIsRequeuedOnDiskBeforeLoadReturns() = runTest {
        inFolder {
            write("outbox.json", "[{\"id\":")
            write("workouts.json", enc(workoutTable, mapOf("w1" to workout("w1"))))

            open().store.load()

            assertEquals(listOf("w1"), JsonFiles.read(path("outbox.json"), outboxList)?.map { it.recordId })
        }
    }

    /** Bad luck reading (here: a directory where the file should be) isn't bad content. */
    @Test fun aTableThatCantBeReadFailsTheLoadAndIsLeftAsItIs() = runTest {
        inFolder {
            SystemFileSystem.createDirectories(path("workouts.json"))
            write("meta.json", """{"lastPull.workouts":"$PB_T0"}""")

            val disk = open()
            assertFailsWith<IOException> { disk.store.load() }

            assertFalse(disk.store.loaded.value)
            assertEquals(SystemFileSystem.metadataOrNull(path("workouts.json"))?.isDirectory, true)
            assertTrue(quarantined("workouts.json").isEmpty())
            assertEquals(mapOf("lastPull.workouts" to PB_T0), JsonFiles.read(path("meta.json"), metaTable))
        }
    }

    /**
     * An earlier build moved a damaged table aside first and wrote the repair later; killed in
     * between, it left no table but a marker saying it was pulled. That marker is dropped once.
     */
    @Test fun aTableAnEarlierBuildMovedAsideIsPulledAgainInFull() = runTest {
        inFolder {
            write("workouts.json.corrupt-1700000000000", "{\"w1\":")
            write("meta.json", """{"lastPull.workouts":"$PB_T0","lastPull.exercises":"$PB_T0"}""")

            val disk = open()
            disk.store.load()

            assertEquals(mapOf("lastPull.exercises" to PB_T0), disk.store.meta.value)
            assertEquals(emptyMap(), JsonFiles.read(path("workouts.json"), workoutTable))
            assertEquals(mapOf("lastPull.exercises" to PB_T0), JsonFiles.read(path("meta.json"), metaTable))
        }
    }

    @Test fun aMissingTableWithNothingSetAsideKeepsItsMarker() = runTest {
        inFolder {
            write("meta.json", """{"lastPull.workouts":"$PB_T0"}""")

            val disk = open()
            disk.store.load()

            assertEquals(mapOf("lastPull.workouts" to PB_T0), disk.store.meta.value)
            assertFalse(exists("workouts.json"))
        }
    }

    /** The exercise library and a partner's routines are in the store too; the server refuses them from us. */
    @Test fun aDamagedOutboxRequeuesOnlyTheSignedInUsersRecords() = runTest {
        inFolder {
            write("outbox.json", "not json")
            val strangers = exercise("e2").copy(owner = "someone", deleted = true, updatedAt = T1, updated = PB_T0)
            write("exercises.json", enc(exerciseTable, mapOf("e1" to exercise("e1"), "e2" to strangers, "e3" to exercise("e3").copy(owner = "someone"))))
            write("routines.json", enc(MapSerializer(String.serializer(), Routine.serializer()), mapOf("r1" to routine("r1").copy(owner = "partner"))))
            val scheduler = TestCoroutineScheduler()
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
            try {
                val store = LocalStore(dir, scope, OWNER)
                store.load()
                assertEquals(listOf("exercises" to "e1"), store.outbox.value.map { it.collection to it.recordId })
            } finally {
                scope.cancel()
            }
        }
    }

    /**
     * A store being closed writes what its writer hadn't yet: its scope is about to be cancelled, and
     * a writer that hasn't run (or waits to retry) would never write it.
     */
    @Test fun flushNowWritesWhatTheCancelledWriterNeverDid() = runTest {
        inFolder {
            val disk = open()
            disk.store.load()
            val w1 = workout("w1")
            disk.store.transaction { putWorkout(w1); putMeta("theme", "dark") }
            disk.scope.cancel()

            disk.store.flushNow()

            assertEquals(mapOf("w1" to w1), JsonFiles.read(path("workouts.json"), workoutTable))
            assertEquals(mapOf("theme" to "dark"), JsonFiles.read(path("meta.json"), metaTable))
        }
    }

    @Test fun flushNowWithNothingPendingWritesNothing() = runTest {
        inFolder {
            val disk = open()
            disk.store.load()
            disk.store.flushNow()
            assertTrue(SystemFileSystem.list(dir).isEmpty())

            // Nor for a store that never loaded: empty tables must not replace the files on disk.
            write("workouts.json", enc(workoutTable, mapOf("w1" to workout("w1"))))
            val unloaded = open()
            unloaded.store.transaction { putMeta("theme", "dark") }
            unloaded.scope.cancel()
            unloaded.store.flushNow()
            assertEquals(mapOf("w1" to workout("w1")), JsonFiles.read(path("workouts.json"), workoutTable))
            assertFalse(exists("meta.json"))
        }
    }

    private companion object {
        const val OWNER = "me"
        const val T0 = "2026-01-01T10:00:00.000Z"
        const val T1 = "2026-01-02T10:00:00.000Z"
        const val PB_T0 = "2026-01-01 10:00:00.000Z"
        const val PB_T2 = "2026-01-03 10:00:00.000Z"
        const val MAX_RETRY_MS = 60_000L
    }
}
