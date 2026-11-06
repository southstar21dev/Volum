package dev.southstar21.volum

import dev.southstar21.volum.model.SetEntry
import dev.southstar21.volum.model.WorkoutEntry
import dev.southstar21.volum.platform.Haptic
import dev.southstar21.volum.ui.session.toggleHaptic
import kotlin.test.Test
import kotlin.test.assertEquals

class HapticTest {
    private fun entry(id: String, vararg done: Boolean, skipped: Boolean = false) =
        WorkoutEntry(id, "x-$id", id, skipped = skipped, sets = done.mapIndexed { i, d -> SetEntry("$id-s$i", done = d) })

    @Test fun aSetMidExerciseIsAClick() {
        val a = entry("a", false, false)
        assertEquals(Haptic.SetDone, toggleHaptic(listOf(a, entry("b", false)), a, a.sets[0]))
    }

    @Test fun anExercisesLastSetSwells() {
        val a = entry("a", true, false)
        assertEquals(Haptic.ExerciseDone, toggleHaptic(listOf(a, entry("b", false)), a, a.sets[1]))
    }

    @Test fun theWorkoutsLastSetIsTheFlourish_skippedExercisesDontCount() {
        val a = entry("a", true, false)
        val workout = listOf(a, entry("b", true), entry("c", false, skipped = true))
        assertEquals(Haptic.WorkoutDone, toggleHaptic(workout, a, a.sets[1]))
    }

    @Test fun untickingIsTheSoftStepBack() {
        val a = entry("a", true, true)
        assertEquals(Haptic.Undo, toggleHaptic(listOf(a), a, a.sets[1]))
    }
}
