package dev.southstar21.volum.data

import dev.southstar21.volum.model.Collection
import dev.southstar21.volum.model.Exercise
import dev.southstar21.volum.model.OutboxItem
import dev.southstar21.volum.model.Routine
import dev.southstar21.volum.model.RoutineItem
import dev.southstar21.volum.model.SetEntry
import dev.southstar21.volum.model.SetTag
import dev.southstar21.volum.model.VolumJson
import dev.southstar21.volum.model.WeightUnit
import dev.southstar21.volum.model.Workout
import dev.southstar21.volum.model.WorkoutEntry
import dev.southstar21.volum.model.WorkoutStatus
import dev.southstar21.volum.util.formatSeconds
import dev.southstar21.volum.util.formatWeight
import dev.southstar21.volum.util.newId
import dev.southstar21.volum.util.nowIso
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Every local write: saved to the store, queued for PocketBase, and announced so sync runs soon. */
class Repository(val store: LocalStore, val owner: String) {
    private val _writes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val writes: SharedFlow<Unit> = _writes

    private fun <T> payload(serializer: KSerializer<T>, value: T): JsonObject =
        VolumJson.encodeToJsonElement(serializer, value).jsonObject

    private fun LocalStore.Tx.queue(collection: Collection, recordId: String, payload: JsonObject) {
        replaceOutbox(collection.wire, recordId) { id -> OutboxItem(id = id, collection = collection.wire, recordId = recordId, payload = payload, createdAt = nowIso()) }
    }

    private fun announce() { _writes.tryEmit(Unit) }

    // ---- Exercises ----------------------------------------------------------------------------

    suspend fun saveExercise(id: String? = null, name: String, timed: Boolean? = null): Exercise {
        val record = store.transaction {
            val existing = id?.let { exercises[it] }
            val record = Exercise(
                id = existing?.id ?: id ?: newId(),
                owner = owner,
                name = name,
                timed = timed ?: existing?.timed ?: false,
                updatedAt = nowIso(),
                deleted = existing?.deleted ?: false,
            )
            putExercise(record)
            queue(Collection.Exercises, record.id, payload(Exercise.serializer(), record.copy(updated = null)))
            record
        }
        announce()
        return record
    }

    suspend fun deleteExercise(id: String) {
        store.transaction {
            val existing = exercises[id] ?: return@transaction
            val record = existing.copy(deleted = true, updatedAt = nowIso())
            putExercise(record)
            queue(Collection.Exercises, id, payload(Exercise.serializer(), record.copy(updated = null)))
        }
        announce()
    }

    suspend fun findOrCreateExercise(name: String, timed: Boolean = false): Exercise {
        val trimmed = name.trim()
        val existing = store.exercises.value.values.firstOrNull { !it.deleted && it.name.equals(trimmed, ignoreCase = true) }
        return existing ?: saveExercise(name = trimmed, timed = timed)
    }

    // ---- Routines -----------------------------------------------------------------------------

    suspend fun saveRoutine(id: String? = null, name: String, items: List<RoutineItem>): Routine {
        val record = store.transaction {
            val existing = id?.let { routines[it] }
            val record = Routine(
                id = existing?.id ?: id ?: newId(),
                owner = existing?.owner ?: owner,
                name = name,
                items = items,
                updatedAt = nowIso(),
                deleted = existing?.deleted ?: false,
            )
            putRoutine(record)
            queue(Collection.Routines, record.id, payload(Routine.serializer(), record.copy(updated = null)))
            record
        }
        announce()
        return record
    }

    suspend fun deleteRoutine(id: String) {
        store.transaction {
            val existing = routines[id] ?: return@transaction
            val record = existing.copy(deleted = true, updatedAt = nowIso())
            putRoutine(record)
            queue(Collection.Routines, id, payload(Routine.serializer(), record.copy(updated = null)))
        }
        announce()
    }

    suspend fun duplicateRoutine(routine: Routine): Routine =
        saveRoutine(name = "${routine.name} (copy)", items = routine.items.map { it.copy(id = newId()) })

    // ---- Workouts -----------------------------------------------------------------------------

    suspend fun saveWorkout(
        id: String? = null,
        routineId: String? = null,
        routineName: String,
        startedAt: String? = null,
        finishedAt: String? = null,
        status: WorkoutStatus? = null,
        entries: List<WorkoutEntry>,
    ): Workout {
        val record = store.transaction {
            val existing = id?.let { workouts[it] }
            val now = nowIso()
            val record = Workout(
                id = existing?.id ?: id ?: newId(),
                owner = owner,
                routineId = routineId ?: existing?.routineId,
                routineName = routineName,
                startedAt = startedAt ?: existing?.startedAt ?: now,
                finishedAt = finishedAt ?: existing?.finishedAt,
                status = status ?: existing?.status ?: WorkoutStatus.Active,
                entries = stampChangedSets(existing?.entries, entries, now),
                removedEntryIds = existing?.removedEntryIds,
                updatedAt = now,
                deleted = existing?.deleted ?: false,
            )
            putWorkout(record)
            queue(Collection.Workouts, record.id, payload(Workout.serializer(), record.copy(updated = null)))
            record
        }
        announce()
        return record
    }

    suspend fun updateWorkout(workout: Workout, entries: List<WorkoutEntry> = workout.entries, routineName: String = workout.routineName): Workout =
        saveWorkout(
            id = workout.id,
            routineId = workout.routineId,
            routineName = routineName,
            startedAt = workout.startedAt,
            finishedAt = workout.finishedAt,
            status = workout.status,
            entries = entries,
        )

    suspend fun deleteWorkout(id: String) {
        store.transaction {
            val existing = workouts[id] ?: return@transaction
            val record = existing.copy(deleted = true, updatedAt = nowIso())
            putWorkout(record)
            queue(Collection.Workouts, id, payload(Workout.serializer(), record.copy(updated = null)))
        }
        announce()
    }

    suspend fun finishWorkout(workout: Workout): Workout = saveWorkout(
        id = workout.id,
        routineId = workout.routineId,
        routineName = workout.routineName,
        startedAt = workout.startedAt,
        finishedAt = nowIso(),
        status = WorkoutStatus.Completed,
        entries = workout.entries,
    )

    suspend fun startSession(routine: Routine, preferred: WeightUnit): Workout {
        val entries = buildSessionEntries(routine, preferred)
        return saveWorkout(routineId = routine.id, routineName = routine.name, entries = entries)
    }

    /** Drops the partner's exercises and routines after unpairing (they're no longer visible). */
    suspend fun deletePartnerLocalData() {
        store.transaction {
            removeExercises(exercises.values.filter { it.owner != owner }.map { it.id }.toSet())
            removeRoutines(routines.values.filter { it.owner != owner }.map { it.id }.toSet())
        }
    }

    // ---- Prefill ------------------------------------------------------------------------------

    private var lastEntriesSource: Map<String, Workout>? = null
    private var lastEntriesCompleted: List<Workout> = emptyList()
    private var lastEntries: Map<String, WorkoutEntry> = emptyMap()

    /**
     * Each exercise's entry in its last completed session, built in one pass over the history and
     * kept until the workouts change (lists ask for it once per row).
     */
    fun lastEntries(): Map<String, WorkoutEntry> {
        val workouts = store.workouts.value
        if (workouts === lastEntriesSource) return lastEntries
        lastEntriesSource = workouts
        // Ticking a set changes the map (the active workout) but no completed workout: the store is
        // copy-on-write, so when every completed workout is the same object as before, nothing to redo.
        val completed = workouts.values.filter { it.status == WorkoutStatus.Completed && !it.deleted && it.owner == owner }
        if (completed.size == lastEntriesCompleted.size && completed.indices.all { completed[it] === lastEntriesCompleted[it] }) return lastEntries
        val out = HashMap<String, WorkoutEntry>()
        completed
            .sortedByDescending { it.finishedAt ?: "" }
            .forEach { w -> for (e in w.entries) if (e.exerciseId !in out) out[e.exerciseId] = e }
        lastEntries = out
        lastEntriesCompleted = completed
        return lastEntries
    }

    /** The last completed session's entry for [exerciseId], if any. */
    fun lastEntryFor(exerciseId: String): WorkoutEntry? = lastEntries()[exerciseId]

    fun buildSessionEntries(routine: Routine, preferred: WeightUnit): List<WorkoutEntry> =
        routine.items.map { item ->
            val timed = store.exercises.value[item.exerciseId]?.isTimed == true
            val source = lastEntryFor(item.exerciseId)?.sets?.filter { it.done && it.tag != SetTag.Warmup }.orEmpty()
            val sets = if (source.isNotEmpty()) {
                source.map { set ->
                    SetEntry(
                        id = newId(), weight = set.weight, unit = set.unit,
                        reps = if (timed) 0 else set.reps,
                        seconds = if (timed) set.seconds ?: item.targetReps else null,
                    )
                }
            } else {
                List(item.targetSets) {
                    SetEntry(
                        id = newId(), weight = 0.0, unit = preferred,
                        reps = if (timed) 0 else item.targetReps,
                        seconds = if (timed) item.targetReps else null,
                    )
                }
            }
            WorkoutEntry(id = newId(), exerciseId = item.exerciseId, name = item.name, note = item.note, timed = timed, sets = sets)
        }

    /** An exercise added mid-workout: last time's sets, or 3 × 8 (3 × 30 s) for a first time. */
    fun buildAddedEntry(exerciseId: String, name: String, timed: Boolean, preferred: WeightUnit, fromHistory: Boolean): WorkoutEntry {
        val source = if (fromHistory) lastEntryFor(exerciseId)?.sets?.filter { it.done && it.tag != SetTag.Warmup }.orEmpty() else emptyList()
        val sets = if (source.isNotEmpty()) {
            source.map { SetEntry(id = newId(), weight = it.weight, unit = it.unit, reps = if (timed) 0 else it.reps, seconds = if (timed) it.seconds ?: 30 else null) }
        } else {
            List(3) { SetEntry(id = newId(), weight = 0.0, unit = preferred, reps = if (timed) 0 else 8, seconds = if (timed) 30 else null) }
        }
        return WorkoutEntry(id = newId(), exerciseId = exerciseId, name = name, timed = timed, sets = sets)
    }
}

/** "62.5 kg × 8" for the top working set of last time, or a hold length for timed lifts. */
fun lastTopSetLabel(sets: List<SetEntry>, timed: Boolean): String? {
    val qualifying = sets.filter { it.done && it.tag != SetTag.Warmup }
    if (qualifying.isEmpty()) return null
    val top = qualifying.maxBy { dev.southstar21.volum.util.toKg(it.weight, it.unit) * 1000 + (if (timed) (it.seconds ?: 0) else it.reps) }
    return if (timed) {
        if (top.weight > 0) "${formatWeight(top.weight, top.unit)} × ${formatSeconds(top.seconds ?: 0)}" else formatSeconds(top.seconds ?: 0)
    } else {
        "${formatWeight(top.weight, top.unit)} × ${top.reps}"
    }
}
