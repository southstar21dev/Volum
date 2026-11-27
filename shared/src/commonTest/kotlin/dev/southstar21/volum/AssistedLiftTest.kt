package dev.southstar21.volum

import dev.southstar21.volum.data.lastTopSetLabel
import dev.southstar21.volum.data.topSetOrder
import dev.southstar21.volum.model.Exercise
import dev.southstar21.volum.model.SetEntry
import dev.southstar21.volum.model.SetTag
import dev.southstar21.volum.model.WeightUnit
import dev.southstar21.volum.model.Workout
import dev.southstar21.volum.model.WorkoutEntry
import dev.southstar21.volum.model.WorkoutStatus
import dev.southstar21.volum.model.assistedIds
import dev.southstar21.volum.model.soundsAssisted
import dev.southstar21.volum.progress.BestSet
import dev.southstar21.volum.progress.Comparison
import dev.southstar21.volum.progress.Sentence
import dev.southstar21.volum.progress.bestSetIn
import dev.southstar21.volum.progress.buildDashboard
import dev.southstar21.volum.progress.compareBest
import dev.southstar21.volum.progress.countPrsThisWeek
import dev.southstar21.volum.progress.topSetSeries
import dev.southstar21.volum.progress.volumeKg
import dev.southstar21.volum.util.newId
import dev.southstar21.volum.util.parseTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Assisted lifts (assisted pull-ups, dips): the logged weight is help, so less of it is progress. */
class AssistedLiftTest {
    private val day1 = "2026-01-01T10:00:00.000Z"
    private val day8 = "2026-01-08T10:00:00.000Z"
    private val now = parseTime(day8) + 60_000
    private val assisted = setOf("apu")

    private fun lift(kg: Double, reps: Int, tag: SetTag? = null) =
        SetEntry(id = newId(), weight = kg, unit = WeightUnit.Kg, reps = reps, tag = tag, done = true)

    private fun session(id: String, at: String, vararg sets: SetEntry, exerciseId: String = "apu") = Workout(
        id = id, owner = "me", routineName = "Pull", startedAt = at, finishedAt = at, status = WorkoutStatus.Completed,
        entries = listOf(WorkoutEntry("e-$id", exerciseId, "Assisted pull-up", sets = sets.toList())), updatedAt = at,
    )

    private fun best(kg: Double, reps: Int) = BestSet(kg, WeightUnit.Kg, kg, reps, 0)

    @Test fun theBestSetIsTheLeastHelpThenTheMostReps() {
        val w = session("w", day1, lift(50.0, 10), lift(30.0, 6), lift(30.0, 8), lift(10.0, 12, SetTag.Warmup))
        val b = assertNotNull(bestSetIn(listOf(w), "apu", assisted = true))
        assertEquals(30.0 to 8, b.weightKg to b.reps)
        // Without the flag the same sets read as a loaded lift.
        assertEquals(50.0, assertNotNull(bestSetIn(listOf(w), "apu")).weightKg)
    }

    @Test fun lessHelpIsProgressNotARegression() {
        val cmp = assertIs<Comparison.LessAssistance>(compareBest(best(20.0, 8), best(50.0, 8), WeightUnit.Kg, assisted = true))
        assertEquals("30 kg", cmp.amount)
        assertEquals(0.6, cmp.gain, 1e-9)
        // Less help counts even with fewer reps, the same order the best set is picked by.
        assertIs<Comparison.LessAssistance>(compareBest(best(40.0, 5), best(50.0, 8), WeightUnit.Kg, assisted = true))
        // Down to no help at all is the full way.
        val unassisted = compareBest(best(0.0, 3), best(20.0, 8), WeightUnit.Kg, assisted = true)
        assertEquals(1.0, assertIs<Comparison.LessAssistance>(unassisted).gain)
    }

    @Test fun moreHelpIsWorseAndTheSameHelpComparesOnReps() {
        assertEquals(Comparison.Worse, compareBest(best(50.0, 8), best(20.0, 8), WeightUnit.Kg, assisted = true))
        val reps = assertIs<Comparison.Reps>(compareBest(best(20.0, 10), best(20.0, 8), WeightUnit.Kg, assisted = true))
        assertEquals("2 reps", reps.amount)
        assertEquals("20 kg assist", reps.at)
        assertEquals(Comparison.Equal, compareBest(best(20.0, 8), best(20.0, 8), WeightUnit.Kg, assisted = true))
        assertEquals(Comparison.Worse, compareBest(best(20.0, 6), best(20.0, 8), WeightUnit.Kg, assisted = true))
        // Unassisted on both: reps are all there is.
        assertIs<Comparison.BodyweightReps>(compareBest(best(0.0, 6), best(0.0, 4), WeightUnit.Kg, assisted = true))
    }

    @Test fun lessHelpThanEverBeforeIsAPersonalRecord() {
        val later = session("b", day1, lift(30.0, 8)).copy(finishedAt = "2026-01-01T11:00:00.000Z")
        val before = listOf(session("a", day1, lift(50.0, 8)), later)
        assertEquals(1, countPrsThisWeek(before + session("c", day8, lift(25.0, 8)), "me", now, assisted))
        assertEquals(0, countPrsThisWeek(before + session("c", day8, lift(40.0, 8)), "me", now, assisted))
        // The old reading, for comparison: more help would have been the record.
        assertEquals(0, countPrsThisWeek(before + session("c", day8, lift(25.0, 8)), "me", now))
    }

    @Test fun helpAddsNoVolume() {
        val w = session("w", day1, lift(30.0, 8))
        assertEquals(0.0, volumeKg(w, assisted))
        assertEquals(240.0, volumeKg(w))
    }

    @Test fun theChartAndLabelsPickTheLeastHelp() {
        val sets = listOf(lift(50.0, 10), lift(30.0, 8), lift(30.0, 6))
        val point = topSetSeries(listOf(session("w", day1, *sets.toTypedArray())), "me", assisted).getValue("apu").single()
        assertEquals(30.0 to 8, point.value to point.reps)
        assertEquals(30.0, sets.maxWith(topSetOrder(timed = false, assisted = true)).weight)
        assertEquals("30 kg assist × 8", lastTopSetLabel(sets, timed = false, assisted = true))
        assertEquals("50 kg × 10", lastTopSetLabel(sets, timed = false))
    }

    @Test fun theDashboardSaysTheLiftNeedsLessHelp() {
        val dash = assertNotNull(buildDashboard(
            listOf(session("a", day1, lift(50.0, 8)), session("b", day8, lift(20.0, 8))),
            "me", mapOf("apu" to "Assisted pull-up"), WeightUnit.Kg, now, assisted,
        ))
        val slide = assertIs<Sentence.Finished>(dash.sentence).slides.single()
        assertTrue(slide.lessHelp)
        assertEquals("30 kg", slide.amount)
        assertEquals(1, dash.prsThisWeek)
        assertTrue(dash.weeks.all { it.volumeKg == 0.0 })
    }

    @Test fun theFlagIsReadFromTheExerciseAndNeverWithTime() {
        fun ex(id: String, timed: Boolean?, assisted: Boolean?) =
            Exercise(id, "me", id, timed = timed, assisted = assisted, updatedAt = day1)
        assertEquals(setOf("a"), assistedIds(listOf(ex("a", null, true), ex("b", true, true), ex("c", null, null))))
        assertTrue(soundsAssisted("Assisted pull-up"))
        assertTrue(soundsAssisted("Dip (ASSISTED)"))
        assertFalse(soundsAssisted("Pull-up"))
    }
}
