package dev.southstar21.volum.data

import dev.southstar21.volum.model.SetEntry
import dev.southstar21.volum.model.WorkoutEntry
import dev.southstar21.volum.util.parseTime

/**
 * Merges the owner's snapshot of an active workout into the server copy, exercise by exercise and
 * set by set, so a partner's edits survive the owner's whole-record sync.
 *
 * Mirrors mergeEntries in backend/pb_hooks/workout_merge.js — keep the two in sync.
 */
fun mergeWorkoutEntries(
    server: List<WorkoutEntry>,
    snapshot: List<WorkoutEntry>,
    snapshotAt: String?,
    removedEntryIds: List<String> = emptyList(),
): List<WorkoutEntry> {
    val snapshotTime = parseTime(snapshotAt)
    val removedEntries = removedEntryIds.toSet()
    val snapshotEntryIds = snapshot.map { it.id }.toSet()
    val merged = snapshot.filter { it.id !in removedEntries }.map { entry ->
        val stored = server.find { it.id == entry.id } ?: return@map entry
        val removed = (stored.removedSetIds.orEmpty() + entry.removedSetIds.orEmpty()).toSet()
        val snapshotIds = entry.sets.map { it.id }.toSet()
        val sets = entry.sets.filter { it.id !in removed }.map { set ->
            val other = stored.sets.find { it.id == set.id }
            if (other != null && parseTime(other.updatedAt) > parseTime(set.updatedAt)) other else set
        }.toMutableList()
        for (set in stored.sets) {
            if (set.id !in snapshotIds && set.id !in removed && parseTime(set.updatedAt) > snapshotTime) sets.add(set)
        }
        val base = if (parseTime(stored.updatedAt) > parseTime(entry.updatedAt)) stored else entry
        if (removed.isNotEmpty()) base.copy(sets = sets, removedSetIds = removed.toList()) else base.copy(sets = sets)
    }.toMutableList()
    for (stored in server) {
        if (stored.id !in snapshotEntryIds && stored.id !in removedEntries && parseTime(stored.updatedAt) > snapshotTime) {
            merged.add(stored)
        }
    }
    return merged
}

/**
 * [next], an edit of [basis], replayed onto [current]: the stored entries, which may have changed
 * since [basis] was read (a sync merged in a partner's sets, or a quick second tap saved first).
 * What the edit removed from [basis] is removed and what it changed is taken from it; everything it
 * didn't touch comes from [current]. Exercises and sets that arrived meanwhile are kept, ahead of
 * the ones this edit adds; something gone from [current] meanwhile (a partner removed it) stays gone.
 */
fun rebaseEntries(basis: List<WorkoutEntry>, current: List<WorkoutEntry>, next: List<WorkoutEntry>): List<WorkoutEntry> {
    if (basis == current) return next
    return rebase(basis, current, next, { it.id }) { b, c, n -> rebaseEntry(b, c, n) }
}

private fun rebaseEntry(basis: WorkoutEntry, current: WorkoutEntry, next: WorkoutEntry): WorkoutEntry {
    if (basis == current) return next
    val head = if (sameEntry(basis, next)) current else next
    val sets = rebase(basis.sets, current.sets, next.sets, { it.id }) { b, c, n -> if (n == b) c else n }
    val removed = (current.removedSetIds.orEmpty() + next.removedSetIds.orEmpty()).distinct()
    return head.copy(sets = sets, removedSetIds = removed.ifEmpty { null })
}

/**
 * The three-way merge behind [rebaseEntries], for one list: [next]'s items that were in [basis] and
 * are still in [current] (combined by [merge]), then items only [current] has, then [next]'s new ones.
 */
private fun <T> rebase(basis: List<T>, current: List<T>, next: List<T>, id: (T) -> String, merge: (T, T, T) -> T): List<T> {
    val before = basis.associateBy(id)
    val now = current.associateBy(id)
    val nextIds = next.mapTo(HashSet(), id)
    val kept = next.mapNotNull { n ->
        val b = before[id(n)] ?: return@mapNotNull null
        val c = now[id(n)] ?: return@mapNotNull null
        merge(b, c, n)
    }
    val arrived = current.filter { id(it) !in before && id(it) !in nextIds }
    val added = next.filter { id(it) !in before }
    return kept + arrived + added
}

/** Stamps every exercise and set that is new or changed since [previous], for the merge above. */
fun stampChangedSets(previous: List<WorkoutEntry>?, next: List<WorkoutEntry>, now: String): List<WorkoutEntry> =
    next.map { entry ->
        val before = previous?.find { it.id == entry.id }
        val stampedEntry = if (before != null && sameEntry(before, entry)) entry else entry.copy(updatedAt = now)
        stampedEntry.copy(
            sets = entry.sets.map { set ->
                val old = before?.sets?.find { it.id == set.id }
                if (old != null && old.copy(updatedAt = null) == set.copy(updatedAt = null)) set else set.copy(updatedAt = now)
            },
        )
    }

private fun sameEntry(a: WorkoutEntry, b: WorkoutEntry): Boolean =
    a.exerciseId == b.exerciseId && a.name == b.name && a.timed == b.timed && a.skipped == b.skipped && a.note == b.note

/** A partner's single change to an active workout, applied atomically on the server. */
sealed interface WorkoutMutation {
    data class Set(val entryId: String, val setId: String, val patch: SetEntry) : WorkoutMutation
    data class AddSet(val entryId: String, val set: SetEntry) : WorkoutMutation
    data class RemoveSet(val entryId: String, val setId: String) : WorkoutMutation
    data class AddEntry(val entry: WorkoutEntry) : WorkoutMutation
    data class UpdateEntry(val entryId: String, val name: String? = null, val exerciseId: String? = null, val timed: Boolean? = null, val skipped: Boolean? = null) : WorkoutMutation
    data class RemoveEntry(val entryId: String) : WorkoutMutation
}

fun applyWorkoutMutation(entries: List<WorkoutEntry>, mutation: WorkoutMutation): List<WorkoutEntry> = when (mutation) {
    is WorkoutMutation.Set -> entries.map { e ->
        if (e.id != mutation.entryId) e else e.copy(sets = e.sets.map { if (it.id == mutation.setId) mutation.patch.copy(id = it.id) else it })
    }
    is WorkoutMutation.AddSet -> entries.map { if (it.id == mutation.entryId) it.copy(sets = it.sets + mutation.set) else it }
    is WorkoutMutation.RemoveSet -> entries.map { e -> if (e.id == mutation.entryId) e.copy(sets = e.sets.filterNot { it.id == mutation.setId }) else e }
    is WorkoutMutation.AddEntry -> entries + mutation.entry
    is WorkoutMutation.UpdateEntry -> entries.map {
        if (it.id != mutation.entryId) it else it.copy(
            name = mutation.name ?: it.name,
            exerciseId = mutation.exerciseId ?: it.exerciseId,
            timed = mutation.timed ?: it.timed,
            skipped = mutation.skipped ?: it.skipped,
        )
    }
    is WorkoutMutation.RemoveEntry -> entries.filterNot { it.id == mutation.entryId }
}
