package dev.southstar21.volum

import dev.southstar21.volum.data.PocketBase
import dev.southstar21.volum.data.WorkoutMutation
import dev.southstar21.volum.data.applyWorkoutMutation
import dev.southstar21.volum.data.cleanRecord
import dev.southstar21.volum.data.decodeRecord
import dev.southstar21.volum.data.mergeWorkoutEntries
import dev.southstar21.volum.data.stampChangedSets
import dev.southstar21.volum.model.Exercise
import dev.southstar21.volum.model.SetEntry
import dev.southstar21.volum.model.VolumJson
import dev.southstar21.volum.model.WeightUnit
import dev.southstar21.volum.model.Workout
import dev.southstar21.volum.model.WorkoutEntry
import dev.southstar21.volum.progress.Comparison
import dev.southstar21.volum.progress.BestSet
import dev.southstar21.volum.progress.Sentence
import dev.southstar21.volum.progress.bestSetIn
import dev.southstar21.volum.progress.buildDashboard
import dev.southstar21.volum.progress.compareBest
import dev.southstar21.volum.progress.countPrsThisWeek
import dev.southstar21.volum.progress.volumeKg
import dev.southstar21.volum.progress.formatLoad
import dev.southstar21.volum.progress.topSetSeries
import dev.southstar21.volum.ui.session.filterDecimal
import dev.southstar21.volum.ui.session.parseNumber
import dev.southstar21.volum.model.SetTag
import dev.southstar21.volum.model.WorkoutStatus
import dev.southstar21.volum.util.convert
import dev.southstar21.volum.util.formatSeconds
import dev.southstar21.volum.util.formatVolume
import dev.southstar21.volum.util.formatWeightValue
import dev.southstar21.volum.util.isoOf
import dev.southstar21.volum.util.newId
import dev.southstar21.volum.util.parseTime
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogicTest {
    private fun set(id: String, reps: Int, at: String?) = SetEntry(id = id, weight = 60.0, reps = reps, updatedAt = at)

    @Test fun mergeKeepsPartnersNewerSetAndOwnersNewSet() {
        val server = listOf(WorkoutEntry("e1", "x", "Bench", sets = listOf(set("s1", 9, "2026-01-01T10:05:00.000Z"))))
        val snapshot = listOf(WorkoutEntry("e1", "x", "Bench", sets = listOf(set("s1", 8, "2026-01-01T10:00:00.000Z"), set("s2", 8, "2026-01-01T10:01:00.000Z"))))
        val merged = mergeWorkoutEntries(server, snapshot, "2026-01-01T10:01:00.000Z")
        assertEquals(listOf(9, 8), merged.single().sets.map { it.reps })
    }

    @Test fun mergeDropsSetsAPartnerRemoved() {
        val server = listOf(WorkoutEntry("e1", "x", "Bench", sets = emptyList(), removedSetIds = listOf("s1")))
        val snapshot = listOf(WorkoutEntry("e1", "x", "Bench", sets = listOf(set("s1", 8, null))))
        assertTrue(mergeWorkoutEntries(server, snapshot, null).single().sets.isEmpty())
    }

    @Test fun mergeKeepsAnExerciseAPartnerAddedAfterTheSnapshot() {
        val added = WorkoutEntry("e2", "y", "Curl", updatedAt = "2026-01-01T10:10:00.000Z")
        val merged = mergeWorkoutEntries(listOf(added), emptyList(), "2026-01-01T10:00:00.000Z")
        assertEquals(listOf("e2"), merged.map { it.id })
    }

    @Test fun stampOnlyTouchesChangedSets() {
        val before = listOf(WorkoutEntry("e1", "x", "Bench", sets = listOf(set("s1", 8, "old"), set("s2", 8, "old"))))
        val after = listOf(before[0].copy(sets = listOf(set("s1", 8, "old"), set("s2", 9, "old"))))
        val stamped = stampChangedSets(before, after, "now")
        assertEquals(listOf("old", "now"), stamped.single().sets.map { it.updatedAt })
    }

    @Test fun mutationsApply() {
        val entries = listOf(WorkoutEntry("e1", "x", "Bench", sets = listOf(set("s1", 8, null))))
        val added = applyWorkoutMutation(entries, WorkoutMutation.AddSet("e1", set("s2", 6, null)))
        assertEquals(2, added.single().sets.size)
        assertTrue(applyWorkoutMutation(added, WorkoutMutation.RemoveEntry("e1")).isEmpty())
    }

    @Test fun pocketBaseRecordsDecode() {
        val json = """{"id":"w1","owner":"u1","routineId":"","routineName":"Push A","startedAt":"2026-01-01 10:00:00.000Z",
            "finishedAt":"","status":"active","entries":[{"id":"e1","exerciseId":"x","name":"Bench","sets":[{"id":"s1","weight":60,"unit":"kg","reps":8,"tag":null,"done":true}]}],
            "removedEntryIds":null,"updatedAt":"2026-01-01 10:00:00.000Z","deleted":false,"updated":"2026-01-01 10:00:00.123Z","collectionId":"c"}"""
        val w = decodeRecord(Workout.serializer(), VolumJson.parseToJsonElement(json).jsonObject)!!
        assertEquals(null, w.routineId)
        assertEquals(null, w.finishedAt)
        assertEquals(60.0, w.entries.single().sets.single().weight)
        assertTrue(cleanRecord(VolumJson.parseToJsonElement(json).jsonObject)["finishedAt"] == null)
    }

    @Test fun emptyRequiredTextFieldStillDecodes() {
        // PocketBase sends a blank required text field as ""; the record must not be dropped for it.
        val json = """{"id":"x1","owner":"u1","name":"","timed":null,"updatedAt":"2026-01-01 10:00:00.000Z","deleted":false,"updated":""}"""
        val e = decodeRecord(Exercise.serializer(), VolumJson.parseToJsonElement(json).jsonObject)
        assertEquals("", e?.name)
        assertEquals(null, e?.updated)
    }

    @Test fun filterQuotesValues() {
        assertEquals("owner = 'a\\'b'", PocketBase.filter("owner = {:o}", mapOf("o" to "a'b")))
    }

    @Test fun timeRoundTrips() {
        val t = parseTime("2026-03-04 05:06:07.089Z")
        assertEquals("2026-03-04T05:06:07.089Z", isoOf(t))
    }

    @Test fun formatting() {
        assertEquals("62.5", formatWeightValue(62.5))
        assertEquals("60", formatWeightValue(60.0))
        assertEquals("137.8", formatWeightValue(convert(62.5, WeightUnit.Kg, WeightUnit.Lb)))
        assertEquals("45 s", formatSeconds(45))
        assertEquals("1:05", formatSeconds(65))
        assertEquals("2.5 kg", formatLoad(2.5, WeightUnit.Kg, true))
        assertEquals(15, newId().length)
    }

    @Test fun comparisonFindsWeightGain() {
        val now = BestSet(65.0, WeightUnit.Kg, 65.0, 8, 0)
        val then = BestSet(62.5, WeightUnit.Kg, 62.5, 8, 0)
        val cmp = compareBest(now, then, WeightUnit.Kg)
        assertTrue(cmp is Comparison.Weight && cmp.amount == "2.5 kg")
    }

    @Test fun topSetSeriesKeepsOneHeaviestWorkingSetPerSessionOldestFirst() {
        fun w(id: String, at: String, owner: String = "me", vararg entries: WorkoutEntry) =
            Workout(id = id, owner = owner, routineName = "Push", startedAt = at, finishedAt = at, status = WorkoutStatus.Completed, entries = entries.toList(), updatedAt = at)
        fun s(kg: Double, reps: Int, tag: SetTag? = null) = SetEntry(id = newId(), weight = kg, reps = reps, done = true, tag = tag)
        val workouts = listOf(
            w("b", "2026-01-08T10:00:00.000Z", "me", WorkoutEntry("e1", "bench", "Bench", sets = listOf(s(100.0, 1, SetTag.Warmup), s(65.0, 8), s(62.5, 10))), WorkoutEntry("e2", "bench", "Bench", sets = listOf(s(67.5, 5)))),
            w("a", "2026-01-01T10:00:00.000Z", "me", WorkoutEntry("e1", "bench", "Bench", sets = listOf(s(60.0, 8)))),
            w("c", "2026-01-09T10:00:00.000Z", "partner", WorkoutEntry("e1", "bench", "Bench", sets = listOf(s(90.0, 8)))),
        )
        val series = topSetSeries(workouts, "me").getValue("bench")
        assertEquals(listOf("a", "b"), series.map { it.workoutId })
        assertEquals(listOf(60.0, 67.5), series.map { it.value })
        assertEquals(5, series.last().reps)
    }

    @Test fun weightTypedFromTheSeparatorKeepsItsValue() {
        // Typed one character at a time, as the field sees it: nothing may be inserted ahead of the cursor.
        fun typed(text: String) = text.fold("") { acc, ch -> filterDecimal(acc + ch) }
        assertEquals(".5", typed(".5"))
        assertEquals(0.5, parseNumber(typed(".5")))
        assertEquals(0.75, parseNumber(typed(",75")))
        assertEquals(62.5, parseNumber(typed("62,5")))
        assertEquals(62.0, parseNumber(typed("62.")))
        assertEquals(0.0, parseNumber(typed(".")), "a lone separator is no weight")
        assertEquals("1.25", typed("1.255x"))
    }

    // ---- Volume in the preferred unit -----------------------------------------------------------

    @Test fun volumeReadsInKilogramsAndTonnes() {
        assertEquals("640 kg", formatVolume(640.0, WeightUnit.Kg))
        assertEquals("999 kg", formatVolume(999.4, WeightUnit.Kg))
        assertEquals("1 t", formatVolume(1000.0, WeightUnit.Kg))
        assertEquals("6.4 t", formatVolume(6400.0, WeightUnit.Kg))
        assertEquals("0 kg", formatVolume(0.0, WeightUnit.Kg))
    }

    @Test fun volumeReadsInPoundsForAPoundUser() {
        // 290.3 kg is 640 lb; 6400 kg is 14,110 lb.
        assertEquals("640 lb", formatVolume(convert(640.0, WeightUnit.Lb, WeightUnit.Kg), WeightUnit.Lb))
        assertEquals("1.5k lb", formatVolume(convert(1500.0, WeightUnit.Lb, WeightUnit.Kg), WeightUnit.Lb))
        assertEquals("14.1k lb", formatVolume(6400.0, WeightUnit.Lb))
        assertEquals("0 lb", formatVolume(0.0, WeightUnit.Lb))
        // Never tonnes, and the number and unit split on the last space as the screens expect.
        for (kg in listOf(0.0, 100.0, 453.0, 454.0, 6400.0, 123_456.0)) {
            val text = formatVolume(kg, WeightUnit.Lb)
            assertTrue(text.endsWith(" lb") && " t" !in text, text)
            assertEquals("lb", text.substringAfterLast(' '))
        }
    }

    // ---- Bodyweight sets ------------------------------------------------------------------------

    private val day1 = "2026-01-01T10:00:00.000Z"
    private val day8 = "2026-01-08T10:00:00.000Z"

    private fun lift(kg: Double, reps: Int, unit: WeightUnit = WeightUnit.Kg, tag: SetTag? = null, done: Boolean = true) =
        SetEntry(id = newId(), weight = kg, unit = unit, reps = reps, tag = tag, done = done)

    private fun session(id: String, at: String, exerciseId: String, vararg sets: SetEntry, timed: Boolean = false) = Workout(
        id = id, owner = "me", routineName = "Pull", startedAt = at, finishedAt = at, status = WorkoutStatus.Completed,
        entries = listOf(WorkoutEntry("e-$id", exerciseId, "Pull-up", timed = if (timed) true else null, sets = sets.toList())), updatedAt = at,
    )

    @Test fun aBodyweightSetIsTheBestSetRankedByReps() {
        val w = session("w", day1, "pullup", lift(0.0, 8), lift(0.0, 12), lift(0.0, 10), lift(0.0, 20, tag = SetTag.Warmup), lift(0.0, 15, done = false), lift(0.0, 0))
        val best = assertNotNull(bestSetIn(listOf(w), "pullup"))
        assertEquals(0.0, best.weightKg)
        assertEquals(12, best.reps)
        assertTrue(best.isBodyweight)
    }

    @Test fun aBodyweightSetCountsPastTheRepCapThatLoadedSetsHave() {
        // The cap guards load comparisons; at bodyweight reps are all there is.
        val w = session("w", day1, "pushup", lift(0.0, 25), lift(0.0, 20))
        assertEquals(25, assertNotNull(bestSetIn(listOf(w), "pushup")).reps)
        val loaded = session("l", day1, "bench", lift(60.0, 15), lift(50.0, 10))
        assertEquals(50.0, assertNotNull(bestSetIn(listOf(loaded), "bench")).weightKg, "a loaded set over the cap still doesn't count")
    }

    @Test fun anyLoadedSetBeatsABodyweightSet() {
        val w = session("w", day1, "pullup", lift(0.0, 15), lift(5.0, 3, WeightUnit.Lb), lift(0.0, 20))
        val best = assertNotNull(bestSetIn(listOf(w), "pullup", Int.MAX_VALUE))
        assertEquals(5.0, best.weight)
        assertEquals(WeightUnit.Lb, best.unit)
        assertEquals(3, best.reps)
    }

    @Test fun loadedLiftsKeepTheirBestSetAndVolume() {
        val w = session("w", day1, "bench", lift(60.0, 8), lift(62.5, 5), lift(0.0, 12), lift(40.0, 10, tag = SetTag.Warmup))
        val best = assertNotNull(bestSetIn(listOf(w), "bench"))
        assertEquals(62.5 to 5, best.weightKg to best.reps)
        assertEquals(60.0 * 8 + 62.5 * 5, volumeKg(w))
    }

    @Test fun bodyweightVolumeStaysZero() {
        assertEquals(0.0, volumeKg(session("w", day1, "pullup", lift(0.0, 12), lift(0.0, 10))))
    }

    @Test fun aTimedLiftHasNoBestRepSet() {
        val plank = session("w", day1, "plank", SetEntry(id = newId(), seconds = 60, done = true), timed = true)
        assertNull(bestSetIn(listOf(plank), "plank"))
        assertEquals(0.0, volumeKg(plank))
    }

    @Test fun bodyweightComparisonIsOnReps() {
        fun bw(reps: Int) = BestSet(0.0, WeightUnit.Kg, 0.0, reps, 0)
        val more = compareBest(bw(12), bw(9), WeightUnit.Kg)
        assertIs<Comparison.BodyweightReps>(more)
        assertEquals("3 reps", more.amount)
        assertEquals(12.0 / 9 - 1, more.gain)
        assertEquals("1 rep", assertIs<Comparison.BodyweightReps>(compareBest(bw(10), bw(9), WeightUnit.Lb)).amount)
        assertEquals(Comparison.Equal, compareBest(bw(9), bw(9), WeightUnit.Kg))
        assertEquals(Comparison.Worse, compareBest(bw(8), bw(9), WeightUnit.Kg))
        // Dropping from a loaded set to bodyweight is no gain.
        assertEquals(Comparison.Worse, compareBest(bw(20), BestSet(10.0, WeightUnit.Kg, 10.0, 5, 0), WeightUnit.Kg))
    }

    @Test fun movingFromBodyweightToAWeightedSetGivesAFiniteGain() {
        val bw = BestSet(0.0, WeightUnit.Kg, 0.0, 10, 0)
        val loaded = BestSet(10.0, WeightUnit.Kg, 10.0, 5, 0)
        val cmp = assertIs<Comparison.Weight>(compareBest(loaded, bw, WeightUnit.Kg))
        assertTrue(cmp.gain.isFinite())
    }

    @Test fun prCountingIsOneForwardPassOverEveryEarlierWorkout() {
        val now = parseTime(day8) + 60_000
        // Two earlier sessions: the heavier one sets the bar, so a lighter set this week is no record.
        val a = session("a", day1, "bench", lift(60.0, 5))
        val b = session("b", day1, "bench", lift(70.0, 5)).copy(finishedAt = isoOf(parseTime(day1) + 3_600_000))
        assertEquals(0, countPrsThisWeek(listOf(a, b, session("c", day8, "bench", lift(65.0, 5))), "me", now))
        assertEquals(1, countPrsThisWeek(listOf(a, b, session("c", day8, "bench", lift(72.5, 5))), "me", now))
        // The dashboard carries the same count, so the screen need not recompute it.
        val dash = assertNotNull(buildDashboard(listOf(a, b, session("c", day8, "bench", lift(72.5, 5))), "me", mapOf("bench" to "Bench"), WeightUnit.Kg, now))
        assertEquals(1, dash.prsThisWeek)
    }

    @Test fun moreBodyweightRepsThanEverBeforeIsAPersonalRecord() {
        val now = parseTime(day8) + 60_000
        val before = session("a", day1, "pullup", lift(0.0, 10))
        assertEquals(1, countPrsThisWeek(listOf(before, session("b", day8, "pullup", lift(0.0, 11))), "me", now))
        assertEquals(0, countPrsThisWeek(listOf(before, session("b", day8, "pullup", lift(0.0, 10))), "me", now))
        // A loaded lift still needs more weight, not more reps.
        val bench = session("c", day1, "bench", lift(60.0, 5))
        assertEquals(0, countPrsThisWeek(listOf(bench, session("d", day8, "bench", lift(60.0, 8))), "me", now))
        assertEquals(1, countPrsThisWeek(listOf(bench, session("d", day8, "bench", lift(62.5, 3))), "me", now))
    }

    @Test fun todayReadsMoreBodyweightRepsAsReps() {
        val latest = session("b", day8, "pullup", lift(0.0, 12), lift(0.0, 10))
        val dashboard = assertNotNull(buildDashboard(listOf(session("a", day1, "pullup", lift(0.0, 9)), latest), "me", mapOf("pullup" to "Pull-up"), WeightUnit.Kg, parseTime(day8) + 60_000))
        val sentence = assertIs<Sentence.Finished>(dashboard.sentence)
        val slide = sentence.slides.single()
        assertEquals("3 reps", slide.amount)
        assertEquals("since last", slide.lead)
        assertEquals("Pull.", slide.value)
        assertTrue(dashboard.weeks.all { it.volumeKg == 0.0 })
    }

    @Test fun todayMatchesEqualBodyweightReps() {
        val dashboard = assertNotNull(buildDashboard(
            listOf(session("a", day1, "pullup", lift(0.0, 9)), session("b", day8, "pullup", lift(0.0, 9))),
            "me", mapOf("pullup" to "Pull-up"), WeightUnit.Kg, parseTime(day8) + 60_000,
        ))
        assertEquals(1, assertIs<Sentence.Matched>(dashboard.sentence).matched)
    }
}
