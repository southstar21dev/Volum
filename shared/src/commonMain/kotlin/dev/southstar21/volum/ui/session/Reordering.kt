package dev.southstar21.volum.ui.session

import dev.southstar21.volum.model.RoutineItem
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
