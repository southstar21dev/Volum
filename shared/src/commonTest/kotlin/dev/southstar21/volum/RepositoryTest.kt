@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.southstar21.volum

import dev.southstar21.volum.data.LocalStore
import dev.southstar21.volum.data.MAX_EXERCISE_NAME
import dev.southstar21.volum.data.MAX_ROUTINE_NAME
import dev.southstar21.volum.data.Repository
import dev.southstar21.volum.data.nameClash
import dev.southstar21.volum.data.ownExerciseNamed
import dev.southstar21.volum.model.Exercise
import dev.southstar21.volum.model.Routine
import dev.southstar21.volum.model.RoutineItem
import dev.southstar21.volum.model.SetEntry
import dev.southstar21.volum.model.Workout
import dev.southstar21.volum.model.WorkoutEntry
import dev.southstar21.volum.model.WorkoutStatus
import dev.southstar21.volum.util.newId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RepositoryTest {
    /** A store whose disk writer never runs: these tests only look at what is in memory. */
    private fun newStore() = LocalStore(
        Path(SystemTemporaryDirectory, "volum-repo-${newId()}"),
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(TestCoroutineScheduler())),
    )

    /** How many times [repo] announced a write (each one schedules a sync). */
    private fun TestScope.countWrites(repo: Repository): () -> Int {
        var count = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.writes.collect { count++ } }
        return { count }
    }

    private fun set(id: String) = SetEntry(id = id, weight = 50.0, reps = 5)

    private fun entry(id: String, vararg sets: SetEntry) =
        WorkoutEntry(id = id, exerciseId = "x-$id", name = "Lift $id", sets = sets.toList())

    private fun item(id: String, exerciseId: String, name: String) =
        RoutineItem(id = id, exerciseId = exerciseId, name = name, targetSets = 3, targetReps = 8)

    private fun JsonObject.strings(key: String): List<String>? = this[key]?.jsonArray?.map { it.jsonPrimitive.content }

    private fun LocalStore.queued(recordId: String): JsonObject = outbox.value.single { it.recordId == recordId }.payload

    // ---- Workouts -----------------------------------------------------------------------------

    @Test fun activeWorkoutRemembersTheExercisesAndSetsTheOwnerRemoves() = runTest {
        val store = newStore()
        val repo = Repository(store, OWNER)
        val started = repo.saveWorkout(routineName = "Push", entries = listOf(entry("a", set("s1"), set("s2")), entry("b", set("s3"))))
        assertEquals(WorkoutStatus.Active, started.status)
        assertNull(started.removedEntryIds)

        val trimmed = repo.updateWorkout(started, entries = listOf(entry("a", set("s1"))))

        assertEquals(listOf("b"), trimmed.removedEntryIds)
        assertEquals(listOf("s2"), trimmed.entries.single().removedSetIds)
        assertEquals(trimmed, store.workouts.value[started.id])
        val payload = store.queued(started.id)
        assertEquals(listOf("b"), payload.strings("removedEntryIds"))
        assertEquals(listOf("s2"), payload["entries"]!!.jsonArray.single().jsonObject.strings("removedSetIds"))

        // Entries rebuilt without their removed ids still keep them: the stored copy remembers.
        val emptied = repo.updateWorkout(trimmed, entries = listOf(entry("a")))
        assertEquals(listOf("b"), emptied.removedEntryIds)
        assertEquals(setOf("s1", "s2"), emptied.entries.single().removedSetIds?.toSet())
    }

    @Test fun removedIdsThatComeBackAreNoLongerRemembered() = runTest {
        val store = newStore()
        val repo = Repository(store, OWNER)
        val started = repo.saveWorkout(routineName = "Push", entries = listOf(entry("a", set("s1"), set("s2")), entry("b", set("s3"))))
        val trimmed = repo.updateWorkout(started, entries = listOf(entry("a")))
        assertEquals(listOf("b"), trimmed.removedEntryIds)
        assertEquals(setOf("s1", "s2"), trimmed.entries.single().removedSetIds?.toSet())

        val restored = repo.updateWorkout(trimmed, entries = listOf(entry("a", set("s2")), entry("b", set("s3"))))

        assertNull(restored.removedEntryIds)
        assertEquals(listOf("s1"), restored.entries.first { it.id == "a" }.removedSetIds)
        assertNull(restored.entries.first { it.id == "b" }.removedSetIds)
        assertNull(store.queued(started.id)["removedEntryIds"])

        val whole = repo.updateWorkout(restored, entries = listOf(entry("a", set("s1"), set("s2")), entry("b", set("s3"))))
        assertTrue(whole.entries.all { it.removedSetIds == null })
    }

    @Test fun partnerRemovalsOnAnActiveWorkoutAreKept() = runTest {
        val store = newStore()
        val repo = Repository(store, OWNER)
        val stored = Workout(
            id = "w", owner = OWNER, routineName = "Push", startedAt = T0, status = WorkoutStatus.Active,
            entries = listOf(entry("a", set("s1")).copy(removedSetIds = listOf("p-set"))),
            removedEntryIds = listOf("p-entry"), updatedAt = T0,
        )
        store.transaction { putWorkout(stored) }

        val saved = repo.updateWorkout(stored, entries = listOf(entry("a", set("s1"), set("s9"))))

        assertEquals(listOf("p-entry"), saved.removedEntryIds)
        assertEquals(listOf("p-set"), saved.entries.single().removedSetIds)
    }

    @Test fun completedWorkoutDoesNotTrackRemovals() = runTest {
        val store = newStore()
        val repo = Repository(store, OWNER)
        val stored = Workout(
            id = "w", owner = OWNER, routineName = "Push", startedAt = T0, finishedAt = T0, status = WorkoutStatus.Completed,
            entries = listOf(entry("a", set("s1"), set("s2")), entry("b", set("s3"))),
            removedEntryIds = listOf("old"), updatedAt = T0,
        )
        store.transaction { putWorkout(stored) }

        val saved = repo.updateWorkout(stored, entries = listOf(entry("a", set("s1"))))

        assertEquals(WorkoutStatus.Completed, saved.status)
        assertEquals(listOf("old"), saved.removedEntryIds)
        assertNull(saved.entries.single().removedSetIds)
    }

    @Test fun deletedWorkoutDoesNotTrackRemovals() = runTest {
        val store = newStore()
        val repo = Repository(store, OWNER)
        val stored = Workout(
            id = "w", owner = OWNER, routineName = "Push", startedAt = T0, status = WorkoutStatus.Active,
            entries = listOf(entry("a", set("s1"), set("s2")), entry("b")), updatedAt = T0, deleted = true,
        )
        store.transaction { putWorkout(stored) }

        val saved = repo.updateWorkout(stored, entries = listOf(entry("a", set("s1"))))

        assertTrue(saved.deleted)
        assertNull(saved.removedEntryIds)
        assertNull(saved.entries.single().removedSetIds)
    }

    // ---- Exercises ----------------------------------------------------------------------------

    @Test fun saveExerciseLeavesAPartnersExerciseUnchanged() = runTest {
        val store = newStore()
        val repo = Repository(store, OWNER)
        val writes = countWrites(repo)
        val partners = Exercise(id = "pe", owner = PARTNER, name = "Curl", timed = false, updatedAt = T0, updated = PB_T0)
        store.transaction { putExercise(partners) }

        val result = repo.saveExercise(id = "pe", name = "Hammer curl", timed = true)
        runCurrent()

        assertEquals(partners, result)
        assertEquals(partners, store.exercises.value["pe"])
        assertTrue(store.outbox.value.isEmpty())
        assertEquals(0, writes())
    }

    @Test fun saveExerciseKeepsTheOwnerAndTheFieldsItIsNotGiven() = runTest {
        val store = newStore()
        val repo = Repository(store, OWNER)
        val writes = countWrites(repo)
        val mine = Exercise(id = "e1", owner = OWNER, name = "Plank", timed = true, updatedAt = T0, updated = PB_T0)
        store.transaction { putExercise(mine) }

        val saved = repo.saveExercise(id = "e1", name = "Side plank")
        runCurrent()

        assertEquals(OWNER, saved.owner)
        assertEquals("Side plank", saved.name)
        assertEquals(true, saved.timed)
        assertNull(saved.updated)
        assertEquals(saved, store.exercises.value["e1"])
        assertEquals(OWNER, store.queued("e1")["owner"]?.jsonPrimitive?.content)
        assertEquals(1, writes())

        val created = repo.saveExercise(name = "Row")
        assertEquals(OWNER, created.owner)
        assertNotEquals("e1", created.id)
        assertEquals(false, created.timed)
    }

    @Test fun findOrCreateExerciseNeverReusesAPartnersExercise() = runTest {
        val store = newStore()
        val repo = Repository(store, OWNER)
        val partners = Exercise(id = "pe", owner = PARTNER, name = "Bench", updatedAt = T0)
        val row = Exercise(id = "row", owner = OWNER, name = "Row", updatedAt = T0)
        val archived = Exercise(id = "sq", owner = OWNER, name = "Squat", updatedAt = T0, deleted = true)
        store.transaction { putExercise(partners); putExercise(row); putExercise(archived) }

        val bench = repo.findOrCreateExercise(" bench ")
        assertEquals(OWNER, bench.owner)
        assertNotEquals("pe", bench.id)
        assertEquals("bench", bench.name)
        assertEquals(partners, store.exercises.value["pe"])

        assertEquals(row, repo.findOrCreateExercise("ROW"))
        assertNotEquals("sq", repo.findOrCreateExercise("Squat").id)

        assertEquals(2, store.outbox.value.size)
        assertTrue(store.outbox.value.none { it.recordId == "pe" || it.recordId == "row" })
        assertEquals(bench.id, repo.findOrCreateExercise("Bench").id)
    }

    @Test fun renameExerciseRenamesTheUsersRoutineItems() = runTest {
        val store = newStore()
        val repo = Repository(store, OWNER)
        val writes = countWrites(repo)
        val bench = Exercise(id = "e1", owner = OWNER, name = "Bench", updatedAt = T0)
        val row = Exercise(id = "e2", owner = OWNER, name = "Row", updatedAt = T0)
        val push = Routine(
            id = "r1", owner = OWNER, name = "Push",
            items = listOf(item("i1", "e1", "Bench"), item("i2", "e2", "Row"), item("i3", "e1", "Bench")), updatedAt = T0,
        )
        val partners = Routine(id = "r2", owner = PARTNER, name = "Theirs", items = listOf(item("j1", "e1", "Bench")), updatedAt = T0)
        val archived = Routine(id = "r3", owner = OWNER, name = "Old", items = listOf(item("k1", "e1", "Bench")), updatedAt = T0, deleted = true)
        val alreadyRenamed = Routine(id = "r4", owner = OWNER, name = "Done", items = listOf(item("m1", "e1", "Bench press")), updatedAt = T0)
        val legs = Routine(id = "r5", owner = OWNER, name = "Legs", items = listOf(item("n1", "e2", "Row")), updatedAt = T0)
        store.transaction {
            putExercise(bench)
            putExercise(row)
            listOf(push, partners, archived, alreadyRenamed, legs).forEach { putRoutine(it) }
        }

        val renamed = repo.renameExercise("e1", "Bench press")
        runCurrent()

        assertEquals("Bench press", renamed.name)
        assertEquals(OWNER, renamed.owner)
        assertEquals(renamed, store.exercises.value["e1"])
        assertEquals(row, store.exercises.value["e2"])
        assertEquals(
            listOf("Bench press", "Row", "Bench press"),
            store.routines.value.getValue("r1").items.map { it.name },
        )
        assertEquals(listOf("i1", "i2", "i3"), store.routines.value.getValue("r1").items.map { it.id })
        assertEquals(partners, store.routines.value["r2"])
        assertEquals(archived, store.routines.value["r3"])
        assertEquals(alreadyRenamed, store.routines.value["r4"])
        assertEquals(legs, store.routines.value["r5"])
        assertEquals(
            setOf("exercises" to "e1", "routines" to "r1"),
            store.outbox.value.map { it.collection to it.recordId }.toSet(),
        )
        assertEquals("Bench press", store.queued("e1")["name"]?.jsonPrimitive?.content)
        val queuedItems = store.queued("r1")["items"]!!.jsonArray.map { it.jsonObject["name"]?.jsonPrimitive?.content }
        assertEquals(listOf("Bench press", "Row", "Bench press"), queuedItems)
        assertEquals(1, writes())
    }

    @Test fun renameExerciseDropsTheServerStampLikeEveryLocalSave() = runTest {
        val store = newStore()
        val repo = Repository(store, OWNER)
        store.transaction {
            putExercise(Exercise(id = "e1", owner = OWNER, name = "Bench", updatedAt = T0, updated = PB_T0))
            putRoutine(Routine(id = "r1", owner = OWNER, name = "Push", items = listOf(item("i1", "e1", "Bench")), updatedAt = T0, updated = PB_T0))
        }

        repo.renameExercise("e1", "Bench press")

        // A record without `updated` is what an unreadable outbox is rebuilt from.
        assertNull(store.exercises.value.getValue("e1").updated)
        assertNull(store.routines.value.getValue("r1").updated)
    }

    @Test fun renameExerciseLeavesAPartnersExerciseUnchanged() = runTest {
        val store = newStore()
        val repo = Repository(store, OWNER)
        val writes = countWrites(repo)
        val partners = Exercise(id = "pe", owner = PARTNER, name = "Curl", updatedAt = T0)
        val mine = Routine(id = "r1", owner = OWNER, name = "Arms", items = listOf(item("i1", "pe", "Curl")), updatedAt = T0)
        store.transaction { putExercise(partners); putRoutine(mine) }

        val result = repo.renameExercise("pe", "Hammer curl")
        runCurrent()

        assertEquals(partners, result)
        assertEquals(partners, store.exercises.value["pe"])
        assertEquals(mine, store.routines.value["r1"])
        assertTrue(store.outbox.value.isEmpty())
        assertEquals(0, writes())
    }

    @Test fun renameExerciseOfAnUnknownIdFailsWithoutWriting() = runTest {
        val store = newStore()
        val repo = Repository(store, OWNER)

        assertFailsWith<IllegalStateException> { repo.renameExercise("missing", "Anything") }
        assertTrue(store.exercises.value.isEmpty())
        assertTrue(store.outbox.value.isEmpty())
    }

    // ---- Saving an edit made on an older copy -------------------------------------------------

    /**
     * The screen edits the workout as it last saw it; a sync can merge a partner's set into the store
     * before the save runs. That set is not one the owner removed: it stays, and isn't tombstoned.
     */
    @Test fun aPartnersSetThatArrivedAfterTheScreenReadTheWorkoutIsKeptNotRemoved() = runTest {
        val store = newStore()
        val repo = Repository(store, OWNER)
        val shown = repo.saveWorkout(routineName = "Push", entries = listOf(entry("a", set("s1")), entry("b", set("s3"))))
        // A pull merges in the partner's set p1 and their exercise c.
        val merged = shown.copy(entries = listOf(entry("a", set("s1"), set("p1")), entry("b", set("s3")), entry("c", set("p2"))))
        store.transaction { putWorkout(merged) }

        // The owner ticks s1 on the copy the screen still shows.
        val saved = repo.updateWorkout(shown, entries = shown.entries.map { e -> if (e.id == "a") e.copy(sets = e.sets.map { it.copy(done = true) }) else e })

        assertEquals(listOf("a", "b", "c"), saved.entries.map { it.id })
        assertEquals(listOf("s1", "p1"), saved.entries[0].sets.map { it.id })
        assertTrue(saved.entries[0].sets[0].done)
        assertNull(saved.entries[0].removedSetIds)
        assertNull(saved.removedEntryIds)
    }

    @Test fun whatTheOwnerRemovedFromAnOlderCopyIsStillRemoved() = runTest {
        val store = newStore()
        val repo = Repository(store, OWNER)
        val shown = repo.saveWorkout(routineName = "Push", entries = listOf(entry("a", set("s1"), set("s2")), entry("b", set("s3"))))
        store.transaction { putWorkout(shown.copy(entries = shown.entries.map { if (it.id == "a") it.copy(sets = it.sets + set("p1")) else it })) }

        val saved = repo.updateWorkout(shown, entries = listOf(entry("a", set("s1"))))

        assertEquals(listOf("a"), saved.entries.map { it.id })
        assertEquals(listOf("s1", "p1"), saved.entries.single().sets.map { it.id })
        assertEquals(listOf("s2"), saved.entries.single().removedSetIds)
        assertEquals(listOf("b"), saved.removedEntryIds)
    }

    @Test fun twoQuickAddsMadeOnTheSameCopyAreBothKept() = runTest {
        val store = newStore()
        val repo = Repository(store, OWNER)
        val shown = repo.saveWorkout(routineName = "Push", entries = listOf(entry("a", set("s1"))))

        repo.updateWorkout(shown, entries = listOf(entry("a", set("s1"), set("n1"))))
        val saved = repo.updateWorkout(shown, entries = listOf(entry("a", set("s1"), set("n2"))))

        assertEquals(listOf("s1", "n1", "n2"), saved.entries.single().sets.map { it.id })
    }

    @Test fun aSetThePartnerRemovedMeanwhileStaysRemovedEvenIfTheOlderCopyHadIt() = runTest {
        val store = newStore()
        val repo = Repository(store, OWNER)
        val shown = repo.saveWorkout(routineName = "Push", entries = listOf(entry("a", set("s1"), set("s2"))))
        store.transaction { putWorkout(shown.copy(entries = listOf(entry("a", set("s1")).copy(removedSetIds = listOf("s2"))))) }

        val saved = repo.updateWorkout(shown, entries = listOf(entry("a", set("s1").copy(done = true), set("s2"))))

        assertEquals(listOf("s1"), saved.entries.single().sets.map { it.id })
        assertTrue(saved.entries.single().sets.single().done)
        assertEquals(listOf("s2"), saved.entries.single().removedSetIds)
    }

    @Test fun finishingFromAnOlderCopyKeepsWhatArrivedMeanwhile() = runTest {
        val store = newStore()
        val repo = Repository(store, OWNER)
        val shown = repo.saveWorkout(routineName = "Push", entries = listOf(entry("a", set("s1"))))
        store.transaction { putWorkout(shown.copy(entries = listOf(entry("a", set("s1"), set("p1"))))) }

        val finished = repo.finishWorkout(shown)

        assertEquals(WorkoutStatus.Completed, finished.status)
        assertEquals(listOf("s1", "p1"), finished.entries.single().sets.map { it.id })
    }

    // ---- Discarding and deleting ---------------------------------------------------------------

    @Test fun discardingFromAnOpenPromptSkipsAWorkoutFinishedMeanwhile() = runTest {
        val store = newStore()
        val repo = Repository(store, OWNER)
        val writes = countWrites(repo)
        val open = repo.saveWorkout(routineName = "Push", entries = listOf(entry("a")))
        val other = repo.saveWorkout(routineName = "Pull", entries = listOf(entry("b")))
        // Finished on another device while the prompt was up.
        store.transaction { putWorkout(other.copy(status = WorkoutStatus.Completed, finishedAt = T0, updated = PB_T0)) }
        val before = writes()

        val discarded = repo.discardUnfinished(listOf(open.id, other.id, "gone"))
        runCurrent()

        assertEquals(listOf(open.id), discarded)
        assertTrue(store.workouts.value.getValue(open.id).deleted)
        assertNull(store.workouts.value.getValue(open.id).updated)
        assertFalse(store.workouts.value.getValue(other.id).deleted)
        assertEquals(before + 1, writes())
        assertEquals(emptyList(), repo.discardUnfinished(listOf(other.id)))
    }

    @Test fun aDeleteDropsTheServerStampSoADamagedOutboxRequeuesIt() = runTest {
        val store = newStore()
        val repo = Repository(store, OWNER)
        store.transaction {
            putExercise(Exercise(id = "e1", owner = OWNER, name = "Bench", updatedAt = T0, updated = PB_T0))
            putRoutine(Routine(id = "r1", owner = OWNER, name = "Push", updatedAt = T0, updated = PB_T0))
            putWorkout(Workout(id = "w1", owner = OWNER, routineName = "Push", startedAt = T0, updatedAt = T0, updated = PB_T0))
        }

        repo.deleteExercise("e1")
        repo.deleteRoutine("r1")
        repo.deleteWorkout("w1")

        assertNull(store.exercises.value.getValue("e1").updated)
        assertNull(store.routines.value.getValue("r1").updated)
        assertNull(store.workouts.value.getValue("w1").updated)
        assertTrue(store.exercises.value.getValue("e1").deleted && store.routines.value.getValue("r1").deleted && store.workouts.value.getValue("w1").deleted)
    }

    @Test fun aPartnersRoutineOrExerciseIsNeverWrittenOrDeleted() = runTest {
        val store = newStore()
        val repo = Repository(store, OWNER)
        val writes = countWrites(repo)
        val theirLift = Exercise(id = "pe", owner = PARTNER, name = "Curl", updatedAt = T0)
        val theirRoutine = Routine(id = "pr", owner = PARTNER, name = "Arms", items = listOf(item("i1", "pe", "Curl")), updatedAt = T0)
        store.transaction { putExercise(theirLift); putRoutine(theirRoutine) }

        assertEquals(theirRoutine, repo.saveRoutine("pr", "Mine now", emptyList()))
        repo.deleteRoutine("pr")
        repo.deleteExercise("pe")
        runCurrent()

        assertEquals(theirRoutine, store.routines.value["pr"])
        assertEquals(theirLift, store.exercises.value["pe"])
        assertTrue(store.outbox.value.isEmpty())
        assertEquals(0, writes())
    }

    // ---- Names --------------------------------------------------------------------------------

    @Test fun namesAreTrimmedAndCutToWhatTheServerAccepts() = runTest {
        val store = newStore()
        val repo = Repository(store, OWNER)
        val long = "x".repeat(300)

        assertEquals(MAX_EXERCISE_NAME, repo.saveExercise(name = "  $long").name.length)
        assertEquals(MAX_EXERCISE_NAME, repo.findOrCreateExercise(long).name.length)
        val routine = repo.saveRoutine(name = " $long ", items = listOf(item("i1", "e1", "Bench")))
        assertEquals(MAX_ROUTINE_NAME, routine.name.length)
        assertEquals(MAX_ROUTINE_NAME, repo.saveWorkout(routineName = long, entries = listOf(entry("a"))).routineName.length)
        val lift = repo.saveExercise(name = "Bench")
        assertEquals(MAX_EXERCISE_NAME, repo.renameExercise(lift.id, long).name.length)
        assertEquals("Push A", repo.saveRoutine(name = "  Push A ", items = listOf(item("i2", "e1", "Bench"))).name)
    }

    @Test fun aRenameClashesOnlyWithAnotherOfTheUsersOwnLiveLifts() {
        val lifts = listOf(
            Exercise(id = "mine", owner = OWNER, name = "Bench", updatedAt = T0),
            Exercise(id = "row", owner = OWNER, name = "Row", updatedAt = T0),
            Exercise(id = "old", owner = OWNER, name = "Squat", updatedAt = T0, deleted = true),
            Exercise(id = "theirs", owner = PARTNER, name = "Bench Press", updatedAt = T0),
        ).associateBy { it.id }

        assertEquals("row", nameClash(lifts, OWNER, "mine", " row ")?.id)
        assertNull(nameClash(lifts, OWNER, "mine", "Bench Press"), "someone else's lift of that name doesn't count")
        assertNull(nameClash(lifts, OWNER, "mine", "BENCH"), "renaming to its own name, in another case")
        assertNull(nameClash(lifts, OWNER, "mine", "Squat"), "an archived lift doesn't count")
        assertNull(nameClash(lifts, OWNER, "mine", "  "))
        assertEquals("theirs", nameClash(lifts, PARTNER, "x", "bench press")?.id)
        assertEquals("mine", ownExerciseNamed(lifts, OWNER, "bench")?.id)
    }

    // ---- Unpairing ----------------------------------------------------------------------------

    // The exercise library is visible to every account (migration 1759600008), so a former partner's
    // exercises stay visible after unpairing, and the user's routines may point at them (e1 -> e2 here).
    @Test fun deletePartnerLocalDataDropsOnlyThePartnersRoutines() = runTest {
        val store = newStore()
        val repo = Repository(store, OWNER)
        val mine = Exercise(id = "e1", owner = OWNER, name = "Bench", updatedAt = T0)
        val theirs = Exercise(id = "e2", owner = PARTNER, name = "Curl", updatedAt = T0)
        val myRoutine = Routine(id = "r1", owner = OWNER, name = "Push", items = listOf(item("i1", "e2", "Curl")), updatedAt = T0)
        val theirRoutine = Routine(id = "r2", owner = PARTNER, name = "Arms", updatedAt = T0)
        val myWorkout = Workout(id = "w1", owner = OWNER, routineName = "Push", startedAt = T0, updatedAt = T0)
        store.transaction {
            putExercise(mine); putExercise(theirs)
            putRoutine(myRoutine); putRoutine(theirRoutine)
            putWorkout(myWorkout)
        }

        repo.deletePartnerLocalData()

        assertEquals(mapOf("e1" to mine, "e2" to theirs), store.exercises.value)
        assertEquals(mapOf("r1" to myRoutine), store.routines.value)
        assertEquals(mapOf("w1" to myWorkout), store.workouts.value)
        assertTrue(store.outbox.value.isEmpty())
    }

    @Test fun deletePartnerLocalDataWithoutPartnerRecordsChangesNothing() = runTest {
        val store = newStore()
        val repo = Repository(store, OWNER)
        store.transaction {
            putExercise(Exercise(id = "e1", owner = OWNER, name = "Bench", updatedAt = T0))
            putRoutine(Routine(id = "r1", owner = OWNER, name = "Push", updatedAt = T0))
        }
        val exercises = store.exercises.value
        val routines = store.routines.value

        repo.deletePartnerLocalData()

        assertSame(exercises, store.exercises.value)
        assertSame(routines, store.routines.value)
    }

    private companion object {
        const val OWNER = "me"
        const val PARTNER = "pat"
        const val T0 = "2026-01-01T10:00:00.000Z"
        const val PB_T0 = "2026-01-01 10:00:00.000Z"
    }
}
