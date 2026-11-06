package dev.southstar21.volum

import dev.southstar21.volum.data.PocketBase
import dev.southstar21.volum.data.WorkoutMutation
import dev.southstar21.volum.data.applyWorkoutMutation
import dev.southstar21.volum.data.cleanRecord
import dev.southstar21.volum.data.decodeRecord
import dev.southstar21.volum.data.mergeWorkoutEntries
import dev.southstar21.volum.data.stampChangedSets
import dev.southstar21.volum.model.SetEntry
import dev.southstar21.volum.model.VolumJson
import dev.southstar21.volum.model.WeightUnit
import dev.southstar21.volum.model.Workout
import dev.southstar21.volum.model.WorkoutEntry
import dev.southstar21.volum.progress.Comparison
import dev.southstar21.volum.progress.BestSet
import dev.southstar21.volum.progress.compareBest
import dev.southstar21.volum.progress.formatLoad
import dev.southstar21.volum.progress.topSetSeries
import dev.southstar21.volum.model.SetTag
import dev.southstar21.volum.model.WorkoutStatus
import dev.southstar21.volum.util.convert
import dev.southstar21.volum.util.formatSeconds
import dev.southstar21.volum.util.formatWeightValue
import dev.southstar21.volum.util.isoOf
import dev.southstar21.volum.util.newId
import dev.southstar21.volum.util.parseTime
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
