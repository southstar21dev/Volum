package dev.southstar21.volum.ui.session

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import dev.southstar21.volum.ui.theme.Durations
import dev.southstar21.volum.ui.theme.Springs
import dev.southstar21.volum.ui.theme.T
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.southstar21.volum.model.Exercise
import dev.southstar21.volum.model.Workout
import dev.southstar21.volum.ui.rememberNow
import dev.southstar21.volum.ui.components.Mute
import dev.southstar21.volum.ui.components.Txt
import dev.southstar21.volum.ui.components.liquidGlass
import dev.southstar21.volum.ui.theme.ExtraBold
import dev.southstar21.volum.util.elapsedLabel
import dev.southstar21.volum.util.nowMillis
import dev.southstar21.volum.util.parseTime

/**
 * A column's items that fold in when added and out when removed, instead of appearing and vanishing.
 * A removed item is drawn from its last value, in its old place, until it has folded away. Items there
 * from the start don't animate.
 */
@Composable
fun <T> AnimatedItems(items: List<T>, key: (T) -> String, itemModifier: (T) -> Modifier = { Modifier }, content: @Composable (T) -> Unit) {
    val initial = remember { items.map(key).toSet() }
    val states = remember { mutableMapOf<String, MutableTransitionState<Boolean>>() }
    val last = remember { mutableMapOf<String, T>() }
    val order = remember { arrayOf(items.map(key)) }
    val ids = items.map(key)
    items.forEach { last[key(it)] = it }
    val merged = ids.toMutableList()
    order[0].forEachIndexed { i, id ->
        val st = states[id]
        if (id !in ids && st != null && !(st.isIdle && !st.currentState)) merged.add(i.coerceAtMost(merged.size), id)
    }
    order[0] = merged
    // The transition states are snapshot state: moved to where they may be written, after composition.
    SideEffect {
        val shown = merged.toSet()
        states.keys.retainAll(shown)
        // Items that have folded away are forgotten, so they aren't held in memory for the screen's life.
        last.keys.retainAll(shown)
        for ((id, st) in states) st.targetState = id in ids
    }
    merged.forEach { id ->
        androidx.compose.runtime.key(id) {
            val st = states.getOrPut(id) { MutableTransitionState(id in initial).apply { targetState = true } }
            val item = last[id]
            if (item != null) AnimatedVisibility(
                visibleState = st,
                enter = fadeIn(tween(Durations.Fade, delayMillis = Durations.Instant)) + expandVertically(Springs.settle(), expandFrom = Alignment.Top),
                exit = fadeOut(tween(Durations.Exit)) + shrinkVertically(Springs.settle(), shrinkTowards = Alignment.Top),
                modifier = itemModifier(item),
            ) { content(item) }
        }
    }
}

/** The clock's own scope: only this text recomposes each second, not the whole session screen. */
@Composable
fun ElapsedTxt(startedAt: String, size: androidx.compose.ui.unit.TextUnit, weight: androidx.compose.ui.text.font.FontWeight, prefix: String = "") {
    val now = rememberNow()
    val start = remember(startedAt) { parseTime(startedAt) }
    Txt(prefix + elapsedLabel(start, now), size = size, weight = weight, tabular = true)
}

@Composable
internal fun ElapsedMinutesStat(startedAt: String, modifier: Modifier) {
    val now = rememberNow()
    val start = remember(startedAt) { parseTime(startedAt) }
    Stat("${elapsedMinutes(start, now)}", "minutes", modifier)
}

/** Whole minutes since [start], so an hour and four reads 64, not the "1:04" of the clock; never negative. */
internal fun elapsedMinutes(start: Long, now: Long): Long = ((now - start) / 60_000).coerceAtLeast(0)

@Composable
internal fun Stat(value: String, label: String, modifier: Modifier) {
    Column(modifier.liquidGlass(RoundedCornerShape(18.dp)).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Txt(value, size = T.Big, weight = ExtraBold, tabular = true, maxLines = 1)
        Mute(label, size = T.Micro)
    }
}

/** Up to six exercises from the last week of your workouts, newest first. */
fun recentExercises(workouts: List<Workout>, library: List<Exercise>, userId: String): List<Exercise> {
    val cutoff = nowMillis() - 7 * 86_400_000L
    val ids = workouts.filter { it.owner == userId && !it.deleted && parseTime(it.startedAt) > cutoff }
        .sortedByDescending { it.startedAt }
        .flatMap { w -> w.entries.map { it.exerciseId } }
        .distinct()
    return ids.mapNotNull { id -> library.firstOrNull { it.id == id } }.take(6)
}

/** "Incline DB press" -> "Incline": the chip row shows a short handle per exercise. */
fun chipName(name: String): String {
    val words = dev.southstar21.volum.util.displayName(name).split(' ').filter { it.isNotBlank() }
    val first = words.firstOrNull() ?: name
    return if (first.length > 12) first.take(11) + "…" else first
}
