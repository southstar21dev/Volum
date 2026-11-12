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

    /**
     * Creates or updates one of the user's exercises. A partner's exercise is left as it is and
     * returned unchanged: writing it would either take it over or be rejected and stick in the outbox.
     */
    suspend fun saveExercise(id: String? = null, name: String, timed: Boolean? = null): Exercise {
        val record = store.transaction {
            val existing = id?.let { exercises[it] }
            if (existing != null && existing.owner != owner) return@transaction existing
            val record = Exercise(
                id = existing?.id ?: id ?: newId(),
                owner = existing?.owner ?: owner,
                name = name.trim().take(MAX_EXERCISE_NAME),
                timed = timed ?: existing?.timed ?: false,
                updatedAt = nowIso(),
                deleted = existing?.deleted ?: false,
            )
            putExercise(record)
            queue(Collection.Exercises, record.id, payload(Exercise.serializer(), record.copy(updated = null)))
            record
        }
        if (record.owner == owner) announce()
        return record
    }

    /**
     * Renames one of the user's exercises and every item of the user's routines that points at it:
     * routine items carry their own copy of the name, and a new session takes the name from there.
     * A partner's exercise or routine is never written (see [saveExercise]).
     */
    suspend fun renameExercise(id: String, name: String): Exercise {
        val record = store.transaction {
            val existing = exercises[id] ?: error("no exercise $id")
            if (existing.owner != owner) return@transaction existing
            // Rebuilt without the server's `updated`, like every local save: a record without it is one
            // a damaged outbox re-queues (LocalStore.requeueUnsynced).
            val newName = name.trim().take(MAX_EXERCISE_NAME)
            val record = existing.copy(name = newName, updatedAt = nowIso(), updated = null)
            putExercise(record)
            queue(Collection.Exercises, id, payload(Exercise.serializer(), record))
            routines.values
                .filter { it.owner == owner && !it.deleted && it.items.any { i -> i.exerciseId == id && i.name != newName } }
                .forEach {
                    val r = it.copy(items = it.items.map { i -> if (i.exerciseId == id) i.copy(name = newName) else i }, updatedAt = nowIso(), updated = null)
                    putRoutine(r)
                    queue(Collection.Routines, r.id, payload(Routine.serializer(), r))
                }
            record
        }
        if (record.owner == owner) announce()
        return record
    }

    /** Deletes one of the user's exercises; anyone else's is left alone (see [saveExercise]). */
    suspend fun deleteExercise(id: String) {
        val deleted = store.transaction {
            val existing = exercises[id]?.takeIf { it.owner == owner } ?: return@transaction false
            // Without the server's `updated`, like every local save, so a damaged outbox re-queues it.
            val record = existing.copy(deleted = true, updatedAt = nowIso(), updated = null)
            putExercise(record)
            queue(Collection.Exercises, id, payload(Exercise.serializer(), record))
            true
        }
        if (deleted) announce()
    }

    /**
     * The user's own exercise by this name, or a new one. Anyone else's is never reused: the user
     * couldn't rename or delete it, and its owner could.
     */
    suspend fun findOrCreateExercise(name: String, timed: Boolean = false): Exercise {
        val trimmed = name.trim().take(MAX_EXERCISE_NAME)
        val existing = ownExerciseNamed(store.exercises.value, owner, trimmed)
        return existing ?: saveExercise(name = trimmed, timed = timed)
    }

    // ---- Routines -----------------------------------------------------------------------------

    /** Creates or updates one of the user's routines; a partner's is returned unchanged (see [saveExercise]). */
    suspend fun saveRoutine(id: String? = null, name: String, items: List<RoutineItem>): Routine {
        val record = store.transaction {
            val existing = id?.let { routines[it] }
            if (existing != null && existing.owner != owner) return@transaction existing
            val record = Routine(
                id = existing?.id ?: id ?: newId(),
                owner = existing?.owner ?: owner,
                name = name.trim().take(MAX_ROUTINE_NAME),
                items = items,
                updatedAt = nowIso(),
                deleted = existing?.deleted ?: false,
            )
            putRoutine(record)
            queue(Collection.Routines, record.id, payload(Routine.serializer(), record.copy(updated = null)))
            record
        }
        if (record.owner == owner) announce()
        return record
    }

    suspend fun deleteRoutine(id: String) {
        val deleted = store.transaction {
            val existing = routines[id]?.takeIf { it.owner == owner } ?: return@transaction false
            val record = existing.copy(deleted = true, updatedAt = nowIso(), updated = null)
            putRoutine(record)
            queue(Collection.Routines, id, payload(Routine.serializer(), record))
            true
        }
        if (deleted) announce()
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
        basis: List<WorkoutEntry>? = null,
    ): Workout {
        val record = store.transaction {
            val existing = id?.let { workouts[it] }
            val now = nowIso()
            // [entries] is an edit of [basis], the copy the screen showed. A sync may have brought a
            // partner's sets in since; they aren't the user's removals, so the edit is replayed onto
            // the stored entries instead of replacing them.
            val entries = if (existing != null && basis != null) rebaseEntries(basis, existing.entries, entries) else entries
            // While a partner can edit the workout, what the owner removes is remembered like a
            // partner's removals, so a merge with a server copy that still has it doesn't bring it back.
            val active = existing?.takeIf { it.status == WorkoutStatus.Active && !it.deleted }
            val record = Workout(
                id = existing?.id ?: id ?: newId(),
                owner = owner,
                routineId = routineId ?: existing?.routineId,
                routineName = routineName.trim().take(MAX_ROUTINE_NAME),
                startedAt = startedAt ?: existing?.startedAt ?: now,
                finishedAt = finishedAt ?: existing?.finishedAt,
                status = status ?: existing?.status ?: WorkoutStatus.Active,
                entries = stampChangedSets(existing?.entries, if (active != null) withRemovedSets(active.entries, entries) else entries, now),
                removedEntryIds = if (active != null) removedEntries(active, entries) else existing?.removedEntryIds,
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

    /** [existing]'s removed exercises plus those missing from [entries]. */
    private fun removedEntries(existing: Workout, entries: List<WorkoutEntry>): List<String>? {
        val kept = entries.map { it.id }.toSet()
        return (existing.removedEntryIds.orEmpty() + existing.entries.map { it.id })
            .distinct().filter { it !in kept }.ifEmpty { null }
    }

    /** [next] with each exercise's removed sets: those it had before, plus those missing since [previous]. */
    private fun withRemovedSets(previous: List<WorkoutEntry>, next: List<WorkoutEntry>): List<WorkoutEntry> =
        next.map { entry ->
            val before = previous.find { it.id == entry.id } ?: return@map entry
            val kept = entry.sets.map { it.id }.toSet()
            val removed = (before.removedSetIds.orEmpty() + entry.removedSetIds.orEmpty() + before.sets.map { it.id })
                .distinct().filter { it !in kept }
            entry.copy(removedSetIds = removed.ifEmpty { null })
        }

    /** Saves [entries], an edit of [workout] as the screen had it (see [saveWorkout]'s basis). */
    suspend fun updateWorkout(workout: Workout, entries: List<WorkoutEntry> = workout.entries, routineName: String = workout.routineName): Workout =
        saveWorkout(
            id = workout.id,
            routineId = workout.routineId,
            routineName = routineName,
            startedAt = workout.startedAt,
            finishedAt = workout.finishedAt,
            status = workout.status,
            entries = entries,
            basis = workout.entries,
        )

    suspend fun deleteWorkout(id: String) {
        store.transaction {
            val existing = workouts[id] ?: return@transaction
            val record = existing.copy(deleted = true, updatedAt = nowIso(), updated = null)
            putWorkout(record)
            queue(Collection.Workouts, id, payload(Workout.serializer(), record))
        }
        announce()
    }

    /**
     * Discards the workouts in [ids] that are still unfinished. A prompt can stay open for a while,
     * and one of them may have been finished on another device meanwhile: that one is kept.
     * Returns the ids discarded.
     */
    suspend fun discardUnfinished(ids: List<String>): List<String> {
        val discarded = store.transaction {
            val now = nowIso()
            ids.mapNotNull { id ->
                val existing = workouts[id]?.takeIf { it.status == WorkoutStatus.Active && !it.deleted && it.owner == owner } ?: return@mapNotNull null
                val record = existing.copy(deleted = true, updatedAt = now, updated = null)
                putWorkout(record)
                queue(Collection.Workouts, id, payload(Workout.serializer(), record))
                id
            }
        }
        if (discarded.isNotEmpty()) announce()
        return discarded
    }

    suspend fun finishWorkout(workout: Workout): Workout = saveWorkout(
        id = workout.id,
        routineId = workout.routineId,
        routineName = workout.routineName,
        startedAt = workout.startedAt,
        finishedAt = nowIso(),
        status = WorkoutStatus.Completed,
        entries = workout.entries,
        basis = workout.entries,
    )

    suspend fun startSession(routine: Routine, preferred: WeightUnit): Workout {
        val entries = buildSessionEntries(routine, preferred)
        return saveWorkout(routineId = routine.id, routineName = routine.name, entries = entries)
    }

    /**
     * Drops a former partner's routines after unpairing: they're no longer visible. Exercises stay:
     * the server's exercise library is visible to every account (migration 1759600008), so the
     * partner's are still pulled, and the user's routines and history may point at them.
     */
    suspend fun deletePartnerLocalData() {
        store.transaction {
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

/** Longest names the server accepts (pb_migrations: routines.name and workouts.routineName, exercises.name). */
const val MAX_ROUTINE_NAME = 120
const val MAX_EXERCISE_NAME = 200

/** [owner]'s live exercise called [name] (ignoring case and surrounding spaces), if any. */
fun ownExerciseNamed(exercises: Map<String, Exercise>, owner: String, name: String, exceptId: String? = null): Exercise? {
    val wanted = name.trim()
    return exercises.values.firstOrNull { it.owner == owner && !it.deleted && it.id != exceptId && it.name.trim().equals(wanted, ignoreCase = true) }
}

/**
 * Another of [owner]'s lifts already called [name], which renaming [exceptId] to it would make
 * ambiguous. Other users' lifts don't count: the exercise library is shared by every account, and
 * name matching ([Repository.findOrCreateExercise]) only ever picks the user's own.
 */
fun nameClash(exercises: Map<String, Exercise>, owner: String, exceptId: String, name: String): Exercise? =
    if (name.isBlank()) null else ownExerciseNamed(exercises, owner, name, exceptId)

/**
 * The order every view picks a top set by: the heaviest set in kg, then the most reps; for a timed
 * lift the longest hold, then the heaviest.
 */
fun topSetOrder(timed: Boolean): Comparator<SetEntry> =
    if (timed) compareBy<SetEntry> { it.seconds ?: 0 }.thenBy { dev.southstar21.volum.util.toKg(it.weight, it.unit) }
    else compareBy<SetEntry> { dev.southstar21.volum.util.toKg(it.weight, it.unit) }.thenBy { it.reps }

/** "62.5 kg × 8" for the top working set of last time, or a hold length for timed lifts. */
fun lastTopSetLabel(sets: List<SetEntry>, timed: Boolean): String? {
    val qualifying = sets.filter { it.done && it.tag != SetTag.Warmup }
    if (qualifying.isEmpty()) return null
    val top = qualifying.maxWith(topSetOrder(timed))
    return if (timed) {
        if (top.weight > 0) "${formatWeight(top.weight, top.unit)} × ${formatSeconds(top.seconds ?: 0)}" else formatSeconds(top.seconds ?: 0)
    } else {
        "${formatWeight(top.weight, top.unit)} × ${top.reps}"
    }
}
