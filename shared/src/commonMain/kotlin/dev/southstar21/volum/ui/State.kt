package dev.southstar21.volum.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.southstar21.volum.data.Repository
import dev.southstar21.volum.data.UserSession
import dev.southstar21.volum.data.lastTopSetLabel
import dev.southstar21.volum.model.Exercise
import dev.southstar21.volum.model.Routine
import dev.southstar21.volum.model.Workout
import dev.southstar21.volum.model.WorkoutStatus
import dev.southstar21.volum.util.localDateOf
import dev.southstar21.volum.util.nowMillis
import kotlinx.coroutines.delay
import kotlinx.datetime.daysUntil

@Composable fun UserSession.exercisesState(): State<Map<String, Exercise>> = store.exercises.collectAsStateWithLifecycle()
@Composable fun UserSession.routinesState(): State<Map<String, Routine>> = store.routines.collectAsStateWithLifecycle()
@Composable fun UserSession.workoutsState(): State<Map<String, Workout>> = store.workouts.collectAsStateWithLifecycle()

/** Each exercise's top set the last time it was done, keyed by exercise id; never-done ones are left out. */
fun UserSession.lastTopSetLabels(exercises: Collection<Exercise>): Map<String, String> =
    repository.lastTopSetLabels(exercises.map { it.id to it.isTimed })

/**
 * Last time's top set for each exercise id, read as timed or not by the paired flag (an entry may be
 * logged in a different shape than its exercise is tracked in now); never-done ones are left out.
 */
fun Repository.lastTopSetLabels(exercises: List<Pair<String, Boolean>>): Map<String, String> = buildMap {
    for ((id, timed) in exercises) lastEntryFor(id)?.let { lastTopSetLabel(it.sets, timed) }?.let { put(id, it) }
}

/**
 * [compute] at [tick], recomputed in step with [keys] (synchronously, so a data change shows in the
 * same frame) but on a background thread when only the clock [tick] moved, with the previous value on
 * screen meanwhile: a periodic refresh never blocks the main thread.
 */
@Composable
fun <T> rememberTicking(vararg keys: Any?, tick: Long, compute: (Long) -> T): T {
    val holder = remember(*keys) { BackgroundValue(tick, compute(tick)) }
    val block by rememberUpdatedState(compute)
    LaunchedEffect(holder, tick) {
        if (holder.tick == tick) return@LaunchedEffect
        val value = withContext(Dispatchers.Default) { block(tick) }
        holder.tick = tick
        holder.value = value
    }
    return holder.value
}

@Stable
private class BackgroundValue<T>(var tick: Long, initial: T) {
    var value by mutableStateOf(initial)
}

/** A clock that ticks every [periodMs] while composed. */
@Composable
fun rememberNow(periodMs: Long = 1_000): Long {
    var now by remember { mutableLongStateOf(nowMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            delay(periodMs)
            now = nowMillis()
        }
    }
    return now
}

fun ownActives(workouts: Map<String, Workout>, userId: String): List<Workout> =
    workouts.values.filter { it.owner == userId && it.status == WorkoutStatus.Active && !it.deleted }.sortedByDescending { it.startedAt }

fun ownCompleted(workouts: Map<String, Workout>, userId: String): List<Workout> =
    workouts.values.filter { it.owner == userId && it.status == WorkoutStatus.Completed && !it.deleted }

fun lastPerformed(workouts: Map<String, Workout>): Map<String, String> {
    val latest = mutableMapOf<String, String>()
    for (w in workouts.values) {
        if (w.status != WorkoutStatus.Completed || w.deleted) continue
        val rid = w.routineId ?: continue
        val f = w.finishedAt ?: continue
        if ((latest[rid] ?: "") < f) latest[rid] = f
    }
    return latest
}

/** Least-recently-trained first; never-trained routines first. */
fun rotationOrder(routines: List<Routine>, last: Map<String, String>): List<Routine> =
    routines.sortedWith(compareBy<Routine> { last[it.id] ?: "" }.thenBy { it.name.lowercase() })

/** "today", "yesterday", "4 days ago", "3 weeks ago" — or "Not done yet". */
fun lastDoneLabel(iso: String?, now: Long): String {
    if (iso == null) return "Not done yet"
    val days = localDateOf(dev.southstar21.volum.util.parseTime(iso)).daysUntil(localDateOf(now))
    return when {
        days <= 0 -> "today"
        days == 1 -> "yesterday"
        days < 14 -> "$days days ago"
        days < 60 -> "${days / 7} weeks ago"
        else -> "${days / 30} months ago"
    }
}
