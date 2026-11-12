package dev.southstar21.volum

import androidx.compose.runtime.saveable.SaverScope
import dev.southstar21.volum.data.lastTopSetLabel
import dev.southstar21.volum.model.SetEntry
import dev.southstar21.volum.model.SetTag
import dev.southstar21.volum.model.WeightUnit
import dev.southstar21.volum.model.Workout
import dev.southstar21.volum.model.WorkoutEntry
import dev.southstar21.volum.model.WorkoutStatus
import dev.southstar21.volum.platform.Haptic
import dev.southstar21.volum.progress.bestSetIn
import dev.southstar21.volum.progress.topSetSeries
import dev.southstar21.volum.ui.Navigator
import dev.southstar21.volum.ui.Route
import dev.southstar21.volum.ui.Tab
import dev.southstar21.volum.ui.routines.stepDown
import dev.southstar21.volum.ui.routines.stepUp
import dev.southstar21.volum.ui.session.Stopwatches
import dev.southstar21.volum.ui.session.blankSet
import dev.southstar21.volum.ui.session.editableWeight
import dev.southstar21.volum.ui.session.editedSet
import dev.southstar21.volum.ui.session.elapsedMinutes
import dev.southstar21.volum.ui.session.nudgeUnit
import dev.southstar21.volum.ui.session.nudgedSet
import dev.southstar21.volum.ui.session.renameLinkTarget
import dev.southstar21.volum.ui.session.sheetUnit
import dev.southstar21.volum.ui.session.filterDecimal
import dev.southstar21.volum.ui.session.nextSet
import dev.southstar21.volum.ui.session.parseNumber
import dev.southstar21.volum.ui.session.reshapeSet
import dev.southstar21.volum.ui.session.reshapedSets
import dev.southstar21.volum.ui.session.stepWeight
import dev.southstar21.volum.ui.session.summaryOf
import dev.southstar21.volum.ui.session.toggleHaptic
import dev.southstar21.volum.util.convert
import dev.southstar21.volum.util.durationLabel
import dev.southstar21.volum.util.elapsedLabel
import dev.southstar21.volum.util.formatWeight
import dev.southstar21.volum.util.formatWeightValue
import dev.southstar21.volum.util.newId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UiLogicTest {
    private val at = "2026-01-08T10:00:00.000Z"

    private fun s(
        weight: Double,
        reps: Int,
        unit: WeightUnit = WeightUnit.Kg,
        tag: SetTag? = null,
        done: Boolean = true,
        seconds: Int? = null,
    ) = SetEntry(id = newId(), weight = weight, unit = unit, reps = reps, seconds = seconds, tag = tag, done = done)

    private fun hold(seconds: Int, weight: Double = 0.0, tag: SetTag? = null, done: Boolean = true) =
        s(weight, 0, tag = tag, done = done, seconds = seconds)

    private fun bench(id: String = "e1", sets: List<SetEntry>, timed: Boolean = false) =
        WorkoutEntry(id, "bench", "Bench", timed = if (timed) true else null, sets = sets)

    private fun workout(id: String, time: String, vararg entries: WorkoutEntry, owner: String = "me") = Workout(
        id = id, owner = owner, routineName = "Push", startedAt = time, finishedAt = time,
        status = WorkoutStatus.Completed, entries = entries.toList(), updatedAt = time,
    )

    /** Typed one character at a time, as the field sees it. */
    private fun typed(text: String) = text.fold("") { acc, ch -> filterDecimal(acc + ch) }

    // ---- Weight field ---------------------------------------------------------------------------

    @Test fun weightFieldReadsLeadingAndTrailingSeparators() {
        assertEquals(0.5, parseNumber(".5"))
        assertEquals(0.75, parseNumber(",75"))
        assertEquals(62.0, parseNumber("62."))
        assertEquals(62.0, parseNumber("62,"))
        assertEquals(62.5, parseNumber("62,5"))
        assertEquals(0.0, parseNumber("0."))
    }

    @Test fun weightFieldLoneSeparatorOrBlankIsNoWeight() {
        assertEquals(0.0, parseNumber("."))
        assertEquals(0.0, parseNumber(","))
        assertEquals(0.0, parseNumber(""))
        assertEquals(0.0, parseNumber("   "))
    }

    @Test fun weightFieldKeepsLeadingZerosAsTheSameNumber() {
        assertEquals(7.5, parseNumber("007.5"))
        assertEquals(5.0, parseNumber("0005"))
        assertEquals(0.0, parseNumber("00"))
        assertEquals(0.5, parseNumber("0,5"))
        assertEquals("007.5", typed("007.5"))
    }

    @Test fun weightFieldDropsNonAsciiDigits() {
        assertEquals("", filterDecimal("١٢"), "Arabic-Indic digits")
        assertEquals("", filterDecimal("６０"), "fullwidth digits")
        assertEquals("", filterDecimal("०१"), "Devanagari digits")
        assertEquals("6", filterDecimal("6٠"))
        assertEquals("", typed("٥٠"))
        assertEquals(0.0, parseNumber(filterDecimal("٥٠")))
    }

    @Test fun weightFieldKeepsOnlyTheFirstSeparator() {
        assertEquals("1.23", typed("1.2.3"))
        assertEquals("1,25", typed("1,2.5"))
        assertEquals(1.25, parseNumber(typed("1,2.5")))
        assertEquals("62.50", filterDecimal("62.5.0"))
        assertEquals("6.2", filterDecimal("6.,2"))
        assertEquals(6.2, parseNumber(filterDecimal("6.,2")))
    }

    @Test fun weightFieldKeepsTwoDecimalsAndDropsOtherCharacters() {
        assertEquals("62.55", typed("62.555"))
        assertEquals("62.5", filterDecimal("62.5 kg"))
        assertEquals("2.5", filterDecimal("-2.5"))
        assertEquals("13", filterDecimal("1e3"))
        assertEquals("", filterDecimal("kg"))
    }

    @Test fun everythingTheWeightFieldAcceptsParses() {
        val inputs = listOf(
            ".", ",", ".5", ",75", "62.", "62,", "007.5", "1.2.3", "1,2,3", "..", ",,", ".,", "-.5", "+1",
            "1e3", "NaN", "Infinity", "١.٢", "６０.5", " 6 2 . 5 ", "62.555", "0x1A", "",
        )
        for (raw in inputs) {
            val shown = filterDecimal(raw)
            val viaTyping = typed(raw)
            val parsed = assertNotNull(parseNumber(shown), "\"$raw\" -> \"$shown\" must parse")
            assertTrue(parsed >= 0.0 && parsed.isFinite(), "\"$raw\" -> $parsed")
            assertNotNull(parseNumber(viaTyping), "typed \"$raw\" -> \"$viaTyping\" must parse")
            assertEquals(shown, filterDecimal(shown), "filtering is idempotent for \"$raw\"")
        }
    }

    @Test fun weightFieldRejectsTextItCouldNeverHaveProduced() {
        assertNull(parseNumber("abc"))
        assertNull(parseNumber("6 2"))
    }

    // ---- Nudges -------------------------------------------------------------------------------

    @Test fun nudgeStepsInTheUnitItWritesIn() {
        assertEquals(2.5, stepWeight(WeightUnit.Kg))
        assertEquals(5.0, stepWeight(WeightUnit.Lb))
    }

    @Test fun convertingToTheSameUnitKeepsTheStoredValueExactly() {
        for (w in listOf(61.25, 61.234, 137.277, 0.01, 135.0)) {
            for (u in WeightUnit.entries) assertEquals(w, convert(w, u, u))
        }
    }

    // ---- Clock labels -------------------------------------------------------------------------

    @Test fun elapsedLabelShowsHoursPastOneHour() {
        assertEquals("34:12", elapsedLabel(0, (34 * 60 + 12) * 1000L))
        assertEquals("1:04:12", elapsedLabel(0, (3600 + 4 * 60 + 12) * 1000L))
        assertEquals("10:00:00", elapsedLabel(0, 36_000_000L))
        assertEquals("0:00", elapsedLabel(10_000, 0), "a clock behind the start shows zero")
        assertEquals("59:59", elapsedLabel(0, 3_599_999L))
        assertEquals("1:00:00", elapsedLabel(0, 3_600_000L))
    }

    @Test fun durationLabelPastOneHour() {
        assertEquals("45 min", durationLabel("2026-01-01T10:00:00.000Z", "2026-01-01T10:45:00.000Z"))
        assertEquals("1h 4m", durationLabel("2026-01-01T10:00:00.000Z", "2026-01-01T11:04:12.000Z"))
        assertEquals("2h", durationLabel("2026-01-01T10:00:00.000Z", "2026-01-01T12:00:00.000Z"))
        assertEquals("", durationLabel("2026-01-01T10:00:00.000Z", null))
    }

    // ---- Navigator ----------------------------------------------------------------------------

    @Test fun aCoveredScreenCannotPushASecondScreen() {
        val nav = Navigator.initial()
        val root = nav.forScreen(nav.stack.last().id)
        assertTrue(root.push(Route.Session("w1")))
        assertFalse(root.push(Route.Session("w1")), "a double tap on the same row")
        assertFalse(root.push(Route.WorkoutDetail("w2")), "a second tap on another row")
        assertEquals(listOf<Route>(Route.Root(Tab.Today), Route.Session("w1")), nav.stack.map { it.route })
    }

    @Test fun pushingTheRouteAlreadyOnTopIsIgnored() {
        val nav = Navigator.initial()
        assertTrue(nav.push(Route.Session("w1")))
        assertFalse(nav.push(Route.Session("w1")))
        assertEquals(2, nav.stack.size)
        assertTrue(nav.push(Route.WorkoutDetail("w1")), "another route on top is still a push")
        assertTrue(nav.push(Route.Session("w1")), "the same route further down the stack may be pushed")
        assertEquals(4, nav.stack.size)
        assertEquals(nav.stack.size, nav.stack.map { it.id }.toSet().size, "every screen has its own id")
    }

    @Test fun aPoppedScreenCannotPopTwice() {
        val nav = Navigator.initial()
        nav.push(Route.ExerciseDetail("x"))
        nav.push(Route.WorkoutDetail("w"))
        val detail = nav.forScreen(nav.stack.last().id)
        assertTrue(detail.pop())
        assertFalse(detail.pop())
        assertEquals(Route.ExerciseDetail("x"), nav.top)
        assertFalse(nav.forward)
        assertFalse(detail.push(Route.PairPartner), "a popped screen's push is dropped too")
        assertEquals(2, nav.stack.size)
    }

    @Test fun theScreenNowOnTopCanMoveAfterTheOneAboveItLeaves() {
        val nav = Navigator.initial()
        nav.push(Route.ExerciseDetail("x"))
        val lift = nav.forScreen(nav.stack.last().id)
        assertTrue(lift.push(Route.WorkoutDetail("w")))
        val detail = nav.forScreen(nav.stack.last().id)
        assertTrue(detail.pop())
        assertTrue(lift.push(Route.WorkoutDetail("w2")))
        assertTrue(nav.forward)
    }

    @Test fun theRootCannotBePopped() {
        val nav = Navigator.initial()
        assertFalse(nav.pop())
        assertFalse(nav.forScreen(nav.stack.single().id).pop())
        assertTrue(nav.atRoot)
        assertEquals(Tab.Today, nav.tab)
    }

    @Test fun pickingATabLeavesEarlierScreensUnableToMove() {
        val nav = Navigator.initial()
        nav.push(Route.Session("w1"))
        val session = nav.forScreen(nav.stack.last().id)
        nav.select(Tab.Lifts)
        assertTrue(nav.atRoot)
        assertEquals(Tab.Lifts, nav.tab)
        assertFalse(session.pop())
        assertFalse(session.push(Route.WorkoutDetail("w1")))
        assertEquals(1, nav.stack.size)
        val lifts = nav.forScreen(nav.stack.single().id)
        assertTrue(lifts.push(Route.ExerciseDetail("x")))
    }

    @Test fun aRestoredStackKeepsItsScreensAndGivesNewOnesFreshIds() {
        val nav = Navigator.initial()
        nav.push(Route.Session("w1"))
        nav.push(Route.WorkoutDetail("w2"))
        val scope = object : SaverScope { override fun canBeSaved(value: Any): Boolean = true }
        val saved = assertNotNull(with(Navigator.Saver) { scope.save(nav) })
        val restored = assertNotNull(Navigator.Saver.restore(saved))
        assertEquals(nav.stack.toList(), restored.stack.toList())
        assertTrue(restored.push(Route.PairPartner))
        val ids = restored.stack.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.last() > nav.stack.maxOf { it.id })
        assertFalse(restored.forScreen(nav.stack.last().id).pop(), "the restored detail screen is covered now")
    }

    @Test fun aSavedStackThatIsUnreadableIsNotRestored() {
        assertNull(Navigator.Saver.restore("not json"))
        assertNull(Navigator.Saver.restore("[]"))
    }

    // ---- Top set ------------------------------------------------------------------------------

    /** Every place that names a non-timed entry's top set picks the same one: the heaviest in kg, then most reps. */
    private fun assertOneTopSet(sets: List<SetEntry>): SetEntry {
        val entry = bench(sets = sets)
        val w = workout("w1", at, entry)
        val best = assertNotNull(bestSetIn(listOf(w), "bench", Int.MAX_VALUE))
        val point = topSetSeries(listOf(w), "me").getValue("bench").single()
        assertEquals(best.weightKg, point.value, "chart top set")
        assertEquals(best.reps, point.reps, "chart top set reps")
        assertEquals("${formatWeight(best.weight, best.unit)} × ${best.reps}", lastTopSetLabel(sets, timed = false), "last label")
        for (unit in WeightUnit.entries) {
            val shown = "top ${formatWeightValue(convert(best.weight, best.unit, unit))} ${unit.label} × ${best.reps}"
            assertEquals(shown, summaryOf(entry, unit).substringAfter(" · "), "summary in ${unit.label}")
        }
        return sets.first { it.weight == best.weight && it.unit == best.unit && it.reps == best.reps && it.done && it.tag != SetTag.Warmup }
    }

    @Test fun topSetIsTheSameInEveryViewForMixedUnitsWarmupsAndUndoneSets() {
        val sets = listOf(
            s(100.0, 1, tag = SetTag.Warmup),
            s(60.0, 10),
            s(135.0, 5, WeightUnit.Lb),
            s(62.5, 3),
            s(62.5, 4),
            s(80.0, 5, done = false),
        )
        val top = assertOneTopSet(sets)
        assertEquals(62.5, top.weight)
        assertEquals(4, top.reps)
        assertEquals("4 sets · top 62.5 kg × 4", summaryOf(bench(sets = sets), WeightUnit.Kg))
        assertEquals("4 sets · top 137.8 lb × 4", summaryOf(bench(sets = sets), WeightUnit.Lb))
    }

    @Test fun topSetCountsDropSetsAndBreaksAWeightTieOnReps() {
        val top = assertOneTopSet(listOf(s(60.0, 8), s(60.0, 10, tag = SetTag.Drop), s(57.5, 12)))
        assertEquals(10, top.reps)
    }

    @Test fun topSetIsTheHeavierSetEvenWhenANearlyEqualOneHasManyMoreReps() {
        // 135 lb is 61.235 kg: lighter than 61.25 kg, however many reps it has.
        val sets = listOf(s(135.0, 20, WeightUnit.Lb), s(61.25, 1))
        val top = assertOneTopSet(sets)
        assertEquals(WeightUnit.Kg, top.unit)
        assertEquals(1, top.reps)
    }

    @Test fun topSetOfAnEntryWithNothingDone() {
        val sets = listOf(s(60.0, 8, done = false), s(60.0, 8, done = false), s(40.0, 10, tag = SetTag.Warmup))
        assertNull(lastTopSetLabel(sets, timed = false))
        assertEquals("3 sets", summaryOf(bench(sets = sets), WeightUnit.Kg))
        assertNull(bestSetIn(listOf(workout("w1", at, bench(sets = sets))), "bench"))
        assertNull(topSetSeries(listOf(workout("w1", at, bench(sets = sets))), "me")["bench"])
    }

    @Test fun timedTopSetIsTheLongestHoldInEveryView() {
        val sets = listOf(hold(30, weight = 10.0), hold(90), hold(120, tag = SetTag.Warmup), hold(200, done = false))
        val entry = bench(sets = sets, timed = true)
        assertEquals("2 sets · 1:30 best", summaryOf(entry, WeightUnit.Kg))
        val point = topSetSeries(listOf(workout("w1", at, entry)), "me").getValue("bench").single()
        assertTrue(point.timed)
        assertEquals(90.0, point.value)
        assertEquals("1:30", lastTopSetLabel(sets, timed = true))
    }

    @Test fun timedTopSetShowsTheWeightOfAWeightedLongestHold() {
        val sets = listOf(hold(90, weight = 10.0), hold(60))
        assertEquals("10 kg × 1:30", lastTopSetLabel(sets, timed = true))
        assertEquals("2 sets · 1:30 best", summaryOf(bench(sets = sets, timed = true), WeightUnit.Kg))
    }

    // ---- Chart series -------------------------------------------------------------------------

    private fun topOf(w: Workout) = topSetSeries(listOf(w), "me").getValue("bench").single().let { it.value to it.reps }

    @Test fun aLiftLoggedTwiceInOneSessionHasTheTopSetItWouldHaveAsOneEntry() {
        val a = s(60.0, 5)
        val b = s(60.0, 8)
        val asOne = workout("w", at, bench("e1", listOf(a, b)))
        val split = workout("w", at, bench("e1", listOf(a)), bench("e2", listOf(b)))
        val splitReversed = workout("w", at, bench("e1", listOf(b)), bench("e2", listOf(a)))
        assertEquals(60.0 to 8, topOf(asOne))
        assertEquals(topOf(asOne), topOf(split))
        assertEquals(topOf(asOne), topOf(splitReversed))
        val best = assertNotNull(bestSetIn(listOf(split), "bench", Int.MAX_VALUE))
        assertEquals(60.0 to 8, best.weightKg to best.reps)
    }

    @Test fun aLiftLoggedTwiceInOneSessionIsOnePointAtTheHeavierEntry() {
        val w = workout("w", at, bench("e1", listOf(s(62.5, 3))), bench("e2", listOf(s(60.0, 10))))
        val reversed = workout("w", at, bench("e1", listOf(s(60.0, 10))), bench("e2", listOf(s(62.5, 3))))
        assertEquals(62.5 to 3, topOf(w))
        assertEquals(62.5 to 3, topOf(reversed))
    }

    @Test fun aTimedLiftLoggedTwiceInOneSessionIsOnePointAtTheLongestHold() {
        val w = workout("w", at, bench("e1", listOf(hold(45)), timed = true), bench("e2", listOf(hold(60)), timed = true))
        val point = topSetSeries(listOf(w), "me").getValue("bench").single()
        assertEquals(60.0, point.value)
        assertTrue(point.timed)
    }

    @Test fun chartPointsKeepTheShapeTheyWereLoggedIn() {
        val older = workout("a", "2026-01-01T10:00:00.000Z", bench(sets = listOf(s(60.0, 5))))
        val newer = workout("b", "2026-01-08T10:00:00.000Z", bench(sets = listOf(hold(60)), timed = true))
        val series = topSetSeries(listOf(newer, older), "me").getValue("bench")
        assertEquals(listOf("a", "b"), series.map { it.workoutId })
        assertEquals(listOf(false, true), series.map { it.timed })
        assertEquals(listOf(60.0, 60.0), series.map { it.value })
        assertEquals(listOf(5, 0), series.map { it.reps })
    }

    @Test fun chartSkipsOthersWorkoutsUnfinishedSessionsAndEmptyHolds() {
        val partner = workout("p", at, bench(sets = listOf(s(90.0, 8))), owner = "partner")
        val active = workout("x", at, bench(sets = listOf(s(90.0, 8)))).copy(status = WorkoutStatus.Active)
        val deleted = workout("d", at, bench(sets = listOf(s(90.0, 8)))).copy(deleted = true)
        val zeroHold = workout("z", at, bench(sets = listOf(hold(0)), timed = true))
        val skipped = workout("k", at, bench(sets = listOf(s(90.0, 8))).copy(skipped = true))
        assertTrue(topSetSeries(listOf(partner, active, deleted, zeroHold, skipped), "me").isEmpty())
    }

    // ---- Timed and reps shapes ----------------------------------------------------------------

    @Test fun aRepSetMovedToATimedLiftGetsAHoldAndIsUnticked() {
        val set = s(20.0, 8, tag = SetTag.Drop)
        val timed = reshapeSet(set, timed = true)
        assertEquals(0, timed.reps)
        assertEquals(30, timed.seconds)
        assertFalse(timed.done)
        assertEquals(20.0, timed.weight)
        assertEquals(SetTag.Drop, timed.tag)
        assertEquals(set.id, timed.id)
    }

    @Test fun aSetThatHadAHoldBeforeKeepsIt() {
        val set = s(0.0, 8, seconds = 45)
        assertEquals(45, reshapeSet(set, timed = true).seconds)
    }

    @Test fun aHoldMovedToARepLiftGetsRepsAndLosesItsSeconds() {
        val back = reshapeSet(hold(45, weight = 10.0), timed = false)
        assertEquals(8, back.reps)
        assertNull(back.seconds)
        assertFalse(back.done)
        assertEquals(10.0, back.weight)
        assertEquals(5, reshapeSet(s(0.0, 5, seconds = 40), timed = false).reps, "reps it had before stay")
    }

    @Test fun relinkingToALiftOfTheSameShapeKeepsTheSetsUntouched() {
        val reps = bench(sets = listOf(s(60.0, 8)))
        assertSame(reps.sets, reshapedSets(reps, timed = false))
        val timed = bench(sets = listOf(hold(30)), timed = true)
        assertSame(timed.sets, reshapedSets(timed, timed = true))
    }

    @Test fun relinkingToALiftOfTheOtherShapeReshapesEverySet() {
        val reps = bench(sets = listOf(s(60.0, 8), s(60.0, 6)))
        val out = reshapedSets(reps, timed = true)
        assertEquals(listOf(30, 30), out.map { it.seconds })
        assertEquals(listOf(0, 0), out.map { it.reps })
        assertTrue(out.none { it.done })
        assertEquals(reps.sets.map { it.id }, out.map { it.id })
    }

    @Test fun aNewSetOnAnEmptyEntryHasTheEntrysShape() {
        val timed = nextSet(bench(sets = emptyList(), timed = true), WeightUnit.Lb)
        assertEquals(0, timed.reps)
        assertEquals(30, timed.seconds)
        assertEquals(WeightUnit.Lb, timed.unit)
        assertFalse(timed.done)
        val reps = nextSet(bench(sets = emptyList()), WeightUnit.Kg)
        assertEquals(8, reps.reps)
        assertNull(reps.seconds)
        assertEquals(WeightUnit.Kg, reps.unit)
    }

    @Test fun aNewSetCopiesTheLastOneUntaggedAndUnticked() {
        val last = s(135.0, 6, WeightUnit.Lb, tag = SetTag.Warmup).copy(updatedAt = "2026-01-01T10:00:00.000Z")
        val next = nextSet(bench(sets = listOf(s(60.0, 8), last)), WeightUnit.Kg)
        assertNotEquals(last.id, next.id)
        assertEquals(135.0, next.weight)
        assertEquals(WeightUnit.Lb, next.unit, "a copy keeps the unit it was logged in")
        assertEquals(6, next.reps)
        assertNull(next.tag)
        assertFalse(next.done)
        assertNull(next.updatedAt)
    }

    @Test fun blankSetsHaveTheirShapeAndFreshIds() {
        val a = blankSet(timed = true, unit = WeightUnit.Kg, done = true)
        val b = blankSet(timed = false, unit = WeightUnit.Lb)
        assertEquals(0, a.reps)
        assertEquals(30, a.seconds)
        assertTrue(a.done)
        assertEquals(8, b.reps)
        assertNull(b.seconds)
        assertFalse(b.done)
        assertEquals(WeightUnit.Lb, b.unit)
        assertNotEquals(a.id, b.id)
    }

    @Test fun tickingASetOnACardThatIsFoldingAwayDoesNotCrash() {
        val gone = WorkoutEntry("gone", "x", "Gone", sets = listOf(SetEntry("s1")))
        val other = WorkoutEntry("other", "y", "Other", sets = listOf(SetEntry("s2")))
        assertEquals(Haptic.SetDone, toggleHaptic(listOf(other), gone, gone.sets[0]))
        assertEquals(Haptic.SetDone, toggleHaptic(emptyList(), gone, gone.sets[0]))
    }

    // ---- Set edit sheet ---------------------------------------------------------------------

    @Test fun aWeightLeftAsTheSheetOpenedIsSavedExactlyAsStored() {
        // 61.235 kg shows as "61.24"; saving without touching it must not round the stored value.
        val set = s(61.235, 5)
        val opened = editableWeight(convert(set.weight, set.unit, WeightUnit.Kg))
        assertEquals("61.24", opened)
        val saved = assertNotNull(editedSet(set, timed = false, weightText = opened, openedWeight = opened, unit = WeightUnit.Kg, count = 6, tag = SetTag.Drop))
        assertEquals(61.235, saved.weight)
        assertEquals(WeightUnit.Kg, saved.unit)
        assertEquals(6, saved.reps)
        assertEquals(SetTag.Drop, saved.tag)

        // Shown in lb to a lb user, untouched: still the stored kg value and unit.
        val inLb = editableWeight(convert(set.weight, set.unit, WeightUnit.Lb))
        val keptKg = assertNotNull(editedSet(set, false, inLb, inLb, WeightUnit.Lb, 5, null))
        assertEquals(61.235, keptKg.weight)
        assertEquals(WeightUnit.Kg, keptKg.unit)
    }

    @Test fun aChangedWeightIsSavedInTheSheetsUnitAndAHoldKeepsNoReps() {
        val set = s(60.0, 8)
        val changed = assertNotNull(editedSet(set, timed = false, weightText = "135", openedWeight = "132.28", unit = WeightUnit.Lb, count = 5, tag = null))
        assertEquals(135.0, changed.weight)
        assertEquals(WeightUnit.Lb, changed.unit)

        val held = assertNotNull(editedSet(hold(30, 10.0), timed = true, weightText = "", openedWeight = "10", unit = WeightUnit.Kg, count = 45, tag = null))
        assertEquals(0.0, held.weight, "a cleared field is no weight")
        assertEquals(45, held.seconds)
        assertEquals(0, held.reps)
        assertNull(editedSet(set, false, "1.2.3", "60", WeightUnit.Kg, 8, null), "text that doesn't parse saves nothing")
    }

    @Test fun aPartnersSetIsEditedInItsOwnUnitEvenAtNoWeight() {
        val theirs = s(0.0, 8, unit = WeightUnit.Kg)
        assertEquals(WeightUnit.Kg, sheetUnit(theirs, WeightUnit.Lb, keepSetUnit = true))
        assertEquals(WeightUnit.Kg, sheetUnit(s(60.0, 8), WeightUnit.Lb, keepSetUnit = true))
        assertEquals(WeightUnit.Lb, sheetUnit(s(60.0, 8), WeightUnit.Lb, keepSetUnit = false), "yours is edited in your unit")
        val typed = assertNotNull(editedSet(theirs, false, "20", "", sheetUnit(theirs, WeightUnit.Lb, true), 8, null))
        assertEquals(WeightUnit.Kg, typed.unit)
    }

    @Test fun editableWeightShowsAtMostTwoDecimalsAndNothingForNoWeight() {
        assertEquals("", editableWeight(0.0))
        assertEquals("60", editableWeight(60.0))
        assertEquals("62.5", editableWeight(62.5))
        assertEquals("61.24", editableWeight(61.235))
        assertEquals("0.05", editableWeight(0.05))
        assertEquals("100.1", editableWeight(100.1))
    }

    // ---- Weight nudges ----------------------------------------------------------------------

    @Test fun aPartnersSetNudgedThroughZeroStaysInTheirUnit() {
        var set = s(2.5, 8, unit = WeightUnit.Kg)
        set = nudgedSet(set, WeightUnit.Lb, keepSetUnits = true, up = false)
        assertEquals(0.0, set.weight)
        assertEquals(WeightUnit.Kg, set.unit)
        set = nudgedSet(set, WeightUnit.Lb, keepSetUnits = true, up = true)
        assertEquals(2.5, set.weight)
        assertEquals(WeightUnit.Kg, set.unit)
    }

    @Test fun yourSetLoggedBeforeAUnitSwitchMovesIntoYourUnitOnTheFirstNudge() {
        val set = s(60.0, 8, unit = WeightUnit.Kg)
        val up = nudgedSet(set, WeightUnit.Lb, keepSetUnits = false, up = true)
        assertEquals(WeightUnit.Lb, up.unit)
        assertEquals(132.28 + stepWeight(WeightUnit.Lb), up.weight, 1e-9)
        val down = nudgedSet(s(1.0, 8), WeightUnit.Kg, keepSetUnits = false, up = false)
        assertEquals(0.0, down.weight, "never below zero")
        assertEquals(WeightUnit.Kg, nudgeUnit(s(0.0, 8, unit = WeightUnit.Kg), WeightUnit.Kg, keepSetUnits = false))
        assertEquals(WeightUnit.Lb, nudgeUnit(s(0.0, 8, unit = WeightUnit.Kg), WeightUnit.Lb, keepSetUnits = false))
    }

    // ---- Routine steppers -------------------------------------------------------------------

    @Test fun steppersSnapOntoTheirGrid() {
        assertEquals(30, stepDown(32, 5, 5, 600))
        assertEquals(35, stepUp(32, 5, 5, 600))
        assertEquals(25, stepDown(30, 5, 5, 600))
        assertEquals(35, stepUp(30, 5, 5, 600))
        assertEquals(5, stepUp(1, 5, 5, 600), "1 is off the grid and below the minimum")
        assertEquals(5, stepDown(5, 5, 5, 600), "never below the minimum")
        assertEquals(600, stepUp(600, 10, 5, 600), "never above the maximum")
        assertEquals(20, stepDown(30, 10, 5, 600))
        assertEquals(45, stepUp(30, 15, 5, 600))
        assertEquals(7, stepDown(8, 1, 1, 50))
        assertEquals(9, stepUp(8, 1, 1, 50))
    }

    @Test fun elapsedMinutesAreWholeAndNeverNegative() {
        val start = 1_000_000L
        assertEquals(0, elapsedMinutes(start, start + 59_999))
        assertEquals(64, elapsedMinutes(start, start + 64 * 60_000 + 30_000))
        assertEquals(0, elapsedMinutes(start, start - 120_000), "a clock that went back")
    }

    // ---- Renaming an entry ------------------------------------------------------------------

    @Test fun renamingAnEntryLinksToTheOwnersLiftBeforeAnyoneElses() {
        val lifts = listOf(
            dev.southstar21.volum.model.Exercise(id = "stranger", owner = "x", name = "Squat", updatedAt = at),
            dev.southstar21.volum.model.Exercise(id = "partners", owner = "pat", name = "squat", updatedAt = at),
            dev.southstar21.volum.model.Exercise(id = "mine", owner = "me", name = "Squat ", updatedAt = at),
            dev.southstar21.volum.model.Exercise(id = "archived", owner = "me", name = "Lunge", updatedAt = at, deleted = true),
            dev.southstar21.volum.model.Exercise(id = "row", owner = "x", name = "Row", updatedAt = at),
        )
        assertEquals("mine", renameLinkTarget(lifts, " SQUAT", exceptId = "e0", owner = "me")?.id)
        assertEquals("partners", renameLinkTarget(lifts, "squat", exceptId = "e0", owner = "pat")?.id, "a partner's session links to their lift")
        assertEquals("row", renameLinkTarget(lifts, "row", exceptId = "e0", owner = "me")?.id, "anyone's, when you have none")
        assertEquals("stranger", renameLinkTarget(lifts, "Squat", exceptId = "mine", owner = "me")?.id, "never the entry's own lift")
        assertNull(renameLinkTarget(lifts, "Lunge", exceptId = "e0", owner = "me"), "not an archived one")
        assertNull(renameLinkTarget(lifts, " ", exceptId = "e0", owner = "me"))
    }

    // ---- Holds ------------------------------------------------------------------------------

    @Test fun holdsOnAPartnersWorkoutGoWhenItStopsBeingTheirLiveOne() {
        Stopwatches.follow("viewer-${newId()}")
        Stopwatches.start("p1")
        Stopwatches.start("p2")
        Stopwatches.start("mine")
        Stopwatches.showPartnerSets("pw", setOf("p1", "p2"))

        Stopwatches.partnerWorkoutChanged(null, "pw")
        assertNotNull(Stopwatches.startedAt("p1"), "becoming known as live drops nothing")

        Stopwatches.partnerWorkoutChanged("pw", null)
        assertNull(Stopwatches.startedAt("p1"))
        assertNull(Stopwatches.startedAt("p2"))
        assertNotNull(Stopwatches.startedAt("mine"), "your own holds aren't the partner's")
    }

    @Test fun aPartnerStartingAnotherWorkoutDropsOnlyTheEndedOnesHolds() {
        Stopwatches.follow("viewer-${newId()}")
        Stopwatches.start("a1")
        Stopwatches.start("b1")
        Stopwatches.showPartnerSets("wa", setOf("a1"))
        Stopwatches.showPartnerSets("wb", setOf("b1"))

        Stopwatches.partnerWorkoutChanged("wa", "wb")

        assertNull(Stopwatches.startedAt("a1"))
        assertNotNull(Stopwatches.startedAt("b1"))
        Stopwatches.partnerWorkoutChanged("wb", "wb")
        assertNotNull(Stopwatches.startedAt("b1"), "no change, nothing dropped")
    }
}
