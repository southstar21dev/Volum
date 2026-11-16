package dev.southstar21.volum

import dev.southstar21.volum.model.RoutineItem
import dev.southstar21.volum.model.SetEntry
import dev.southstar21.volum.model.WorkoutEntry
import dev.southstar21.volum.ui.components.Reorder
import dev.southstar21.volum.ui.session.applyOrderToRoutine
import dev.southstar21.volum.ui.session.reorderActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReorderingTest {
    private fun entry(id: String, exerciseId: String = id, done: Boolean = false, skipped: Boolean? = null) =
        WorkoutEntry(id, exerciseId, id, skipped = skipped, sets = listOf(SetEntry("$id-s", done = done)))

    private fun item(id: String, exerciseId: String) = RoutineItem(id, exerciseId, exerciseId, 3, 8)

    @Test fun reorderingKeepsFinishedExercisesInTheirSlots() {
        val entries = listOf(entry("a"), entry("done", done = true), entry("b"), entry("skip", skipped = true), entry("c"))
        val out = reorderActive(entries, listOf("c", "a", "b"))
        assertEquals(listOf("c", "done", "a", "skip", "b"), out.map { it.id })
    }

    @Test fun reorderingIgnoresUnknownIdsAndKeepsUnnamedOnes() {
        val entries = listOf(entry("a"), entry("b"), entry("c"))
        // "gone" was removed meanwhile; "c" arrived after the drag began.
        assertEquals(listOf("b", "a", "c"), reorderActive(entries, listOf("gone", "b", "a")).map { it.id })
    }

    @Test fun routineFollowsTheSessionOrder() {
        val items = listOf(item("1", "bench"), item("2", "row"), item("3", "squat"))
        val entries = listOf(entry("s", "squat"), entry("b", "bench"), entry("r", "row"))
        assertEquals(listOf("3", "1", "2"), applyOrderToRoutine(items, entries).map { it.id })
    }

    @Test fun routineItemsMissingFromTheSessionKeepTheirSlots() {
        val items = listOf(item("1", "bench"), item("2", "dips"), item("3", "row"))
        // Dips was deleted from the session; curls was added to it only.
        val entries = listOf(entry("r", "row"), entry("c", "curl"), entry("b", "bench"))
        assertEquals(listOf("3", "2", "1"), applyOrderToRoutine(items, entries).map { it.id })
    }

    @Test fun repeatedExercisesMatchInTurn() {
        val items = listOf(item("1", "bench"), item("2", "row"), item("3", "bench"))
        val entries = listOf(entry("r", "row"), entry("b1", "bench"), entry("b2", "bench"))
        assertEquals(listOf("2", "1", "3"), applyOrderToRoutine(items, entries).map { it.id })
        // Already in order: nothing to offer.
        assertEquals(items, applyOrderToRoutine(items, listOf(entry("x", "bench"), entry("y", "row"), entry("z", "bench"))))
    }

    // A cancelled scope: the rows' settle animations never start (they need a frame clock).
    private fun reorder() = Reorder(CoroutineScope(Job().apply { cancel() })).apply {
        heights["a"] = 100; heights["b"] = 100; heights["c"] = 100
    }

    @Test fun aHeldRowSwapsOncePastHalfItsNeighbour() {
        val r = reorder()
        val list = mutableListOf("a", "b", "c")
        r.start("a")
        assertFalse(r.dragBy(49f, list) { it })
        assertEquals(listOf("a", "b", "c"), list)
        assertTrue(r.dragBy(2f, list) { it })
        assertEquals(listOf("b", "a", "c"), list)
        r.drop()
    }

    @Test fun aFastDragSwapsSeveralRowsInOneStep() {
        val r = reorder()
        val list = mutableListOf("a", "b", "c")
        r.start("a")
        r.dragBy(260f, list) { it }
        assertEquals(listOf("b", "c", "a"), list)
        // Back up past both.
        r.dragBy(-260f, list) { it }
        assertEquals(listOf("a", "b", "c"), list)
        r.drop()
    }
}
