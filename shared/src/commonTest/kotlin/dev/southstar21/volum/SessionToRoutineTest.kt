package dev.southstar21.volum

import dev.southstar21.volum.model.RoutineItem
import dev.southstar21.volum.model.SetEntry
import dev.southstar21.volum.model.SetTag
import dev.southstar21.volum.model.WorkoutEntry
import dev.southstar21.volum.ui.session.applySessionToRoutine
import dev.southstar21.volum.ui.session.routineChanges
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SessionToRoutineTest {
    private fun entry(id: String, exerciseId: String = id, name: String = exerciseId, skipped: Boolean? = null, timed: Boolean? = null, sets: List<SetEntry> = emptyList()) =
        WorkoutEntry(id, exerciseId, name, skipped = skipped, timed = timed, sets = sets)

    private fun item(id: String, exerciseId: String, name: String = exerciseId) = RoutineItem(id, exerciseId, name, 4, 10, note = "n-$id")

    private fun apply(items: List<RoutineItem>, entries: List<WorkoutEntry>): List<RoutineItem> {
        var n = 0
        return applySessionToRoutine(items, entries) { "new${++n}" }
    }

    @Test fun nothingChangedReturnsTheSameList() {
        val items = listOf(item("1", "bench"), item("2", "row"))
        val out = apply(items, listOf(entry("a", "bench"), entry("b", "row")))
        assertSame(items, out)
    }

    @Test fun addedExerciseGetsTargetsFromTheSets() {
        val items = listOf(item("1", "bench"))
        val sets = listOf(
            SetEntry("w", reps = 15, tag = SetTag.Warmup, done = true),
            SetEntry("s1", reps = 9, done = true),
            SetEntry("s2", reps = 7, done = true),
            SetEntry("s3", reps = 6, done = false),
        )
        val out = apply(items, listOf(entry("a", "bench"), entry("b", "curl", sets = sets)))
        assertEquals(listOf("1", "new1"), out.map { it.id })
        assertEquals(RoutineItem("new1", "curl", "curl", 3, 9), out[1])
    }

    @Test fun addedTimedExerciseUsesSecondsAndEmptyOnesUseDefaults() {
        val timed = entry("t", "hang", timed = true, sets = listOf(SetEntry("s", seconds = 45, done = true)))
        val bare = entry("p", "plank", timed = true)
        val bareReps = entry("r", "curl")
        val out = apply(emptyList(), listOf(timed, bare, bareReps))
        assertEquals(RoutineItem("new1", "hang", "hang", 1, 45), out[0])
        assertEquals(RoutineItem("new2", "plank", "plank", 3, 30), out[1])
        assertEquals(RoutineItem("new3", "curl", "curl", 3, 8), out[2])
    }

    @Test fun removedExerciseIsDropped() {
        val items = listOf(item("1", "bench"), item("2", "row"), item("3", "squat"))
        val out = apply(items, listOf(entry("a", "bench"), entry("c", "squat")))
        assertEquals(listOf("1", "3"), out.map { it.id })
    }

    @Test fun skippedExerciseStaysWithItsItem() {
        val items = listOf(item("1", "bench"), item("2", "carry"))
        val out = apply(items, listOf(entry("a", "bench"), entry("b", "carry", skipped = true)))
        assertSame(items, out)
    }

    @Test fun renamedExerciseKeepsItsItem() {
        val items = listOf(item("1", "bench", "Bench"))
        val out = apply(items, listOf(entry("a", "bench", "Flat bench")))
        assertEquals(listOf(items[0].copy(name = "Flat bench")), out)
        assertEquals(listOf("Flat bench"), routineChanges(items, out).renamed)
    }

    @Test fun relinkedExerciseIsSwappedForTheNewOne() {
        val items = listOf(item("1", "bench"), item("2", "row"))
        val out = apply(items, listOf(entry("a", "bench"), entry("b", "cable-row")))
        assertEquals(listOf("1", "new1"), out.map { it.exerciseId to it.id }.map { it.second })
        assertEquals("cable-row", out[1].exerciseId)
    }

    @Test fun reorderKeepsItemsIntact() {
        val items = listOf(item("1", "bench"), item("2", "row"), item("3", "squat"))
        val out = apply(items, listOf(entry("s", "squat"), entry("b", "bench"), entry("r", "row")))
        assertEquals(listOf(items[2], items[0], items[1]), out)
        val c = routineChanges(items, out)
        assertTrue(c.moved)
        assertFalse(c.beyondOrder)
        assertEquals("Changes the order.", c.summary())
    }

    @Test fun duplicatedExercisesMatchInOrder() {
        val items = listOf(item("1", "curl"), item("2", "row"), item("3", "curl"))
        val same = apply(items, listOf(entry("a", "curl"), entry("b", "row"), entry("c", "curl")))
        assertSame(items, same)
        // A third set of curls added: it gets a new item; the two existing ones keep theirs.
        val more = apply(items, listOf(entry("a", "curl"), entry("b", "row"), entry("c", "curl"), entry("d", "curl")))
        assertEquals(listOf("1", "2", "3", "new1"), more.map { it.id })
        // One of two removed: the later item goes.
        val less = apply(items, listOf(entry("a", "curl"), entry("b", "row")))
        assertEquals(listOf("1", "2"), less.map { it.id })
    }

    @Test fun summaryListsWhatChanged() {
        val items = listOf(item("1", "a"), item("2", "b"))
        val out = apply(items, listOf(entry("1", "a"), entry("x", "p"), entry("y", "q"), entry("z", "r")))
        val c = routineChanges(items, out)
        assertEquals(listOf("p", "q", "r"), c.added)
        assertEquals(listOf("b"), c.removed)
        assertEquals("Adds p, q and 1 more. Removes b.", c.summary())
        assertEquals("Adds p and q.", routineChanges(items, items + listOf(item("9", "p"), item("8", "q"))).summary())
    }
}
