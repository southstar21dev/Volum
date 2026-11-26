package dev.southstar21.volum

import dev.southstar21.volum.data.WorkoutMutation
import dev.southstar21.volum.data.applyWorkoutMutation
import dev.southstar21.volum.data.mergeWorkoutEntries
import dev.southstar21.volum.data.monotonicNow
import dev.southstar21.volum.data.stampChangedSets
import dev.southstar21.volum.model.SetEntry
import dev.southstar21.volum.model.VolumJson
import dev.southstar21.volum.model.WorkoutEntry
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Runs test-fixtures/workout-merge against WorkoutMerge.kt. backend/tests/workout_merge.test.js runs
 * the same files against workout_merge.js, so the two merges can't drift apart unnoticed.
 */
class WorkoutMergeParityTest {
    private val entries = ListSerializer(WorkoutEntry.serializer())

    private fun entriesOf(json: JsonElement?): List<WorkoutEntry> = VolumJson.decodeFromJsonElement(entries, json ?: JsonArray(emptyList()))

    @Test
    fun mergeMatchesTheFixtures() {
        val cases = VolumJson.parseToJsonElement(MergeFixtures.merge).jsonArray
        assertEquals(true, cases.isNotEmpty())
        for (case in cases.map { it.jsonObject }) {
            val actual = mergeWorkoutEntries(
                server = entriesOf(case["server"]),
                snapshot = entriesOf(case["snapshot"]),
                snapshotAt = case["snapshotAt"]?.jsonPrimitive?.contentOrNull,
                removedEntryIds = case["removedEntryIds"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            )
            assertEquals(entriesOf(case["expected"]), actual, case["name"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun mutationsMatchTheFixtures() {
        val cases = VolumJson.parseToJsonElement(MergeFixtures.mutate).jsonArray
        assertEquals(true, cases.isNotEmpty())
        for (case in cases.map { it.jsonObject }) {
            val before = entriesOf(case["entries"])
            val actual = applyWorkoutMutation(before, mutationOf(case.getValue("mutation").jsonObject), case.getValue("now").jsonPrimitive.content)
            // A refusal (expected null) leaves the entries as they were.
            val expected = if (case["expected"] is JsonNull) before else entriesOf(case["expected"])
            assertEquals(expected, actual, case["name"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun stampsNeverGoBackwardsBehindAPartnersEdit() {
        val partner = WorkoutEntry("e1", "x", "A", sets = listOf(SetEntry("s1", updatedAt = "2026-01-01T10:00:50.000Z")), updatedAt = "2026-01-01T10:00:50.000Z")
        assertEquals("2026-01-01T10:00:50.001Z", monotonicNow("2026-01-01T10:00:01.000Z", listOf(partner)))
        assertEquals("2026-01-01T10:01:00.000Z", monotonicNow("2026-01-01T10:01:00.000Z", listOf(partner)))
        // The owner's next save, on a clock that is behind, still beats the stored partner edit.
        val edited = partner.copy(sets = listOf(SetEntry("s1", reps = 9, updatedAt = "2026-01-01T10:00:50.000Z")))
        val stamped = stampChangedSets(listOf(partner), listOf(edited), "2026-01-01T10:00:01.000Z")
        assertEquals("2026-01-01T10:00:50.001Z", stamped.single().sets.single().updatedAt)
    }

    /** The wire form of a mutation (see mutationJson in AppContainer.kt) back into the app's type. */
    private fun mutationOf(json: JsonObject): WorkoutMutation {
        fun text(key: String) = json.getValue(key).jsonPrimitive.content
        fun setOf(element: JsonElement) = VolumJson.decodeFromJsonElement(SetEntry.serializer(), element)
        return when (text("kind")) {
            "set" -> WorkoutMutation.Set(text("entryId"), text("setId"), setOf(json.getValue("patch")))
            "addSet" -> WorkoutMutation.AddSet(text("entryId"), setOf(json.getValue("set")))
            "removeSet" -> WorkoutMutation.RemoveSet(text("entryId"), text("setId"))
            "addEntry" -> WorkoutMutation.AddEntry(VolumJson.decodeFromJsonElement(WorkoutEntry.serializer(), json.getValue("entry")))
            "updateEntry" -> {
                val patch = json.getValue("patch").jsonObject
                WorkoutMutation.UpdateEntry(
                    entryId = text("entryId"),
                    name = patch["name"]?.jsonPrimitive?.contentOrNull,
                    exerciseId = patch["exerciseId"]?.jsonPrimitive?.contentOrNull,
                    timed = patch["timed"]?.jsonPrimitive?.booleanOrNull,
                    skipped = patch["skipped"]?.jsonPrimitive?.booleanOrNull,
                )
            }
            "removeEntry" -> WorkoutMutation.RemoveEntry(text("entryId"))
            else -> error("unknown mutation kind in the fixtures")
        }
    }
}
