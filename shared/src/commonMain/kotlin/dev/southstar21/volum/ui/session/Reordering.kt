package dev.southstar21.volum.ui.session

import dev.southstar21.volum.model.RoutineItem
import dev.southstar21.volum.model.SetTag
import dev.southstar21.volum.model.WorkoutEntry

/**
 * [entries] in the order of [ids]: those named, as named, then any not named (added since the draft
 * was taken) in their own order. Ids with no entry are dropped.
 */
internal fun orderedBy(entries: List<WorkoutEntry>, ids: List<String>): List<WorkoutEntry> {
    val byId = entries.associateBy { it.id }
    val named = ids.mapNotNull { byId[it] }
    val seen = named.mapTo(HashSet()) { it.id }
    return named + entries.filter { it.id !in seen }
}

/**
 * The workout's [entries] with its unfinished ones put in the order of [activeIds]. They go back into
 * the slots unfinished entries held, so finished ones (and the chips showing them) stay where they were.
 */
fun reorderActive(entries: List<WorkoutEntry>, activeIds: List<String>): List<WorkoutEntry> {
    val active = entries.filterNot { it.isComplete() }
    val ordered = orderedBy(active, activeIds).iterator()
    return entries.map { if (it.isComplete()) it else ordered.next() }
}

/**
 * The routine's [items] reordered to follow the session's [entries]. Items whose exercise is in the
 * session move among their own slots in the session's order (the nth time an exercise appears matching
 * the nth); items not in the session keep their slots, and exercises added only to the session are left out.
 */
fun applyOrderToRoutine(items: List<RoutineItem>, entries: List<WorkoutEntry>): List<RoutineItem> {
    // Each exercise's routine items, in routine order, handed out to its entries in session order.
    val pool = items.groupBy { it.exerciseId }.mapValues { (_, v) -> ArrayDeque(v) }
    val sessionOrder = entries.mapNotNull { pool[it.exerciseId]?.removeFirstOrNull() }
    val moved = sessionOrder.mapTo(HashSet()) { it.id }
    val next = sessionOrder.iterator()
    return items.map { if (it.id in moved) next.next() else it }
}

/**
 * The routine's [items] made to match the session's [entries]: its exercises, in the session's order.
 * Entries are matched to items of the same exercise (the nth entry to the nth item); a matched item keeps
 * its id, targets and note, and takes the entry's name. An entry with no item gets a new one ([newId]),
 * its targets taken from what was done. An item no entry matches is dropped: the exercise was deleted
 * from the workout (or swapped for another). A skipped exercise still has its entry, so it stays.
 * Returns [items] itself when nothing changes.
 */
fun applySessionToRoutine(items: List<RoutineItem>, entries: List<WorkoutEntry>, newId: () -> String): List<RoutineItem> {
    val pool = items.groupBy { it.exerciseId }.mapValues { (_, v) -> ArrayDeque(v) }
    val next = entries.map { entry ->
        val item = pool[entry.exerciseId]?.removeFirstOrNull()
        when {
            item == null -> itemFromEntry(entry, newId())
            item.name != entry.name -> item.copy(name = entry.name)
            else -> item
        }
    }
    return if (next == items) items else next
}

private fun itemFromEntry(entry: WorkoutEntry, id: String): RoutineItem {
    val working = entry.sets.filter { it.tag != SetTag.Warmup }
    val top = working.firstOrNull { it.done } ?: working.firstOrNull()
    val amount = if (entry.isTimed) top?.seconds ?: 0 else top?.reps ?: 0
    val fallback = if (entry.isTimed) 30 else 8
    return RoutineItem(id, entry.exerciseId, entry.name, working.size.takeIf { it > 0 } ?: 3, amount.takeIf { it > 0 } ?: fallback)
}

/** What changed between a routine's items and the [next] ones from [applySessionToRoutine]. */
data class RoutineChanges(
    val added: List<String>,
    val removed: List<String>,
    val renamed: List<String>,
    val moved: Boolean,
) {
    /** Exercises added, removed or renamed, not only moved. */
    val beyondOrder: Boolean get() = added.isNotEmpty() || removed.isNotEmpty() || renamed.isNotEmpty()

    /** One short line: "Adds A, B and 2 more. Removes C." */
    fun summary(): String {
        val parts = listOfNotNull(
            added.takeIf { it.isNotEmpty() }?.let { "Adds ${nameList(it)}." },
            removed.takeIf { it.isNotEmpty() }?.let { "Removes ${nameList(it)}." },
            renamed.takeIf { it.isNotEmpty() }?.let { "Renames ${nameList(it)}." },
            "Changes the order.".takeIf { moved && !beyondOrder },
            "Moves exercises around.".takeIf { moved && beyondOrder },
        )
        return parts.joinToString(" ")
    }

    private fun nameList(names: List<String>): String = when {
        names.size <= 2 -> names.joinToString(" and ")
        else -> "${names[0]}, ${names[1]} and ${names.size - 2} more"
    }
}

fun routineChanges(items: List<RoutineItem>, next: List<RoutineItem>): RoutineChanges {
    val before = items.associateBy { it.id }
    val after = next.associateBy { it.id }
    val kept = next.filter { it.id in before }.map { it.id }
    return RoutineChanges(
        added = next.filter { it.id !in before }.map { it.name },
        removed = items.filter { it.id !in after }.map { it.name },
        renamed = next.filter { n -> before[n.id]?.let { it.name != n.name || it.exerciseId != n.exerciseId } == true }.map { it.name },
        moved = kept != items.filter { it.id in after }.map { it.id },
    )
}
