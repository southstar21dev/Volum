package dev.southstar21.volum.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Wire and storage format, shared with the web app through PocketBase. Field names must not change. */
val VolumJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

@Serializable
enum class WeightUnit {
    @SerialName("kg") Kg,
    @SerialName("lb") Lb;

    val label: String get() = if (this == Kg) "kg" else "lb"
    val other: WeightUnit get() = if (this == Kg) Lb else Kg
}

@Serializable
enum class SetTag {
    @SerialName("warmup") Warmup,
    @SerialName("drop") Drop,
}

enum class Collection(val wire: String) {
    Exercises("exercises"),
    Routines("routines"),
    Workouts("workouts"),
}

@Serializable
data class Exercise(
    val id: String,
    val owner: String,
    val name: String,
    /** Logged as a duration (seconds) instead of reps, e.g. planks. */
    val timed: Boolean? = null,
    val updatedAt: String,
    val deleted: Boolean = false,
    val updated: String? = null,
) {
    val isTimed: Boolean get() = timed == true
}

@Serializable
data class RoutineItem(
    val id: String,
    val exerciseId: String,
    val name: String,
    val targetSets: Int,
    /** Target reps, or target seconds when the exercise is timed. */
    val targetReps: Int,
    val note: String? = null,
)

@Serializable
data class Routine(
    val id: String,
    val owner: String,
    val name: String,
    val items: List<RoutineItem> = emptyList(),
    val updatedAt: String,
    val deleted: Boolean = false,
    val updated: String? = null,
)

@Serializable
data class SetEntry(
    val id: String,
    val weight: Double = 0.0,
    val unit: WeightUnit = WeightUnit.Kg,
    val reps: Int = 0,
    /** Duration of a timed set; reps stay 0. */
    val seconds: Int? = null,
    val tag: SetTag? = null,
    val note: String? = null,
    val done: Boolean = false,
    /** When this set last changed, so a partner's edit and the owner's sync merge per set. */
    val updatedAt: String? = null,
)

@Serializable
data class WorkoutEntry(
    val id: String,
    val exerciseId: String,
    val name: String,
    val note: String? = null,
    val skipped: Boolean? = null,
    val timed: Boolean? = null,
    val sets: List<SetEntry> = emptyList(),
    /** Sets a partner removed, so the owner's next sync doesn't bring them back. */
    val removedSetIds: List<String>? = null,
    val updatedAt: String? = null,
) {
    val isSkipped: Boolean get() = skipped == true
    val isTimed: Boolean get() = timed == true
}

@Serializable
enum class WorkoutStatus {
    @SerialName("active") Active,
    @SerialName("completed") Completed,
}

@Serializable
data class Workout(
    val id: String,
    val owner: String,
    val routineId: String? = null,
    val routineName: String,
    val startedAt: String,
    val finishedAt: String? = null,
    val status: WorkoutStatus = WorkoutStatus.Active,
    val entries: List<WorkoutEntry> = emptyList(),
    /** Exercises a partner removed, so the owner's next sync doesn't bring them back. */
    val removedEntryIds: List<String>? = null,
    val updatedAt: String,
    val deleted: Boolean = false,
    val updated: String? = null,
)

@Serializable
data class OutboxItem(
    val id: Long,
    val collection: String,
    val recordId: String,
    val payload: kotlinx.serialization.json.JsonObject,
    val createdAt: String,
    val attempts: Int = 0,
    val failed: Boolean = false,
    val lastError: String? = null,
)

@Serializable
data class AuthUser(val id: String, val email: String, val name: String)

@Serializable
data class PartnerUser(val id: String, val name: String = "", val email: String = "") {
    val displayName: String get() = name.ifBlank { email }
}

@Serializable
data class PartnerWorkout(val id: String, val routineName: String, val startedAt: String)

@Serializable
data class IncomingPairRequest(val id: String, val from: PartnerUser)

@Serializable
data class OutgoingPairRequest(val id: String, val to: PartnerUser)

@Serializable
data class PartnerSnapshot(
    val partner: PartnerUser? = null,
    val activeWorkout: PartnerWorkout? = null,
    val incoming: List<IncomingPairRequest> = emptyList(),
    val outgoing: List<OutgoingPairRequest> = emptyList(),
)
