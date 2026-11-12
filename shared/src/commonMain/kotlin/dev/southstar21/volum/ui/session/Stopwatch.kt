package dev.southstar21.volum.ui.session

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.southstar21.volum.data.UserSession
import dev.southstar21.volum.model.Workout
import dev.southstar21.volum.model.WorkoutStatus
import dev.southstar21.volum.platform.Haptic
import dev.southstar21.volum.platform.haptic
import dev.southstar21.volum.platform.hapticTick
import dev.southstar21.volum.ui.components.CircleButton
import dev.southstar21.volum.ui.components.Txt
import dev.southstar21.volum.ui.components.accentGel
import dev.southstar21.volum.ui.components.liquidGlass
import dev.southstar21.volum.ui.theme.Bold
import dev.southstar21.volum.ui.theme.Icon
import dev.southstar21.volum.ui.theme.Icons
import dev.southstar21.volum.ui.theme.T
import dev.southstar21.volum.ui.theme.Volum
import dev.southstar21.volum.ui.theme.tappable
import dev.southstar21.volum.util.nowMillis
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Running holds, by set id: when each started. Held for the app's life rather than in a screen, so
 * a hold keeps counting while its card is folded, the screen is left, or the app is in the background.
 */
object Stopwatches {
    private val started = mutableStateMapOf<String, Long>()

    fun startedAt(setId: String): Long? = started[setId]
    fun start(setId: String) { started[setId] = nowMillis() }
    /** Stops [setId]'s hold and returns how many whole seconds it ran. */
    fun stop(setId: String): Int {
        val at = started.remove(setId) ?: return 0
        return ((nowMillis() - at) / 1000).toInt()
    }
    fun cancel(setId: String) { started.remove(setId) }
    /** Drops the holds of sets that no longer exist. */
    fun forget(setIds: Collection<String>) { for (id in setIds) if (id in started) started.remove(id) }

    /** The running holds' set ids. Changes only as a hold starts or stops, never per frame. */
    internal fun running(): Set<String> = started.keys.toSet()

    // What [retain] remembers between passes: whose holds these are, the holds seen on this device's
    // own workouts, and the sets that were live on the last pass.
    private var owner: String? = null
    private val local = HashSet<String>()
    private var liveBefore: Set<String> = emptySet()

    /**
     * Starts following [userId]'s workouts. Another account's holds can't belong to this one's sets,
     * so switching accounts drops them.
     */
    internal fun follow(userId: String) {
        if (owner == userId) return
        owner = userId
        started.clear()
        local.clear()
        liveBefore = emptySet()
        partnerSets.clear()
    }

    // The sets of each partner workout a partner screen has shown, by workout id: a hold on one of
    // them goes when that workout stops being the partner's live one ([partnerWorkoutChanged]), even
    // with the screen closed.
    private val partnerSets = HashMap<String, Set<String>>()

    /** Records the sets [workoutId] (a partner's live workout, shown on screen) has now. */
    internal fun showPartnerSets(workoutId: String, setIds: Set<String>) {
        partnerSets[workoutId] = setIds
    }

    /**
     * The partner's live workout went from [previous] to [current] (null: none, or no partner): the
     * holds on [previous]'s sets go with it. Only a change drops anything, so a workout the pairing
     * state doesn't name (it names one) is never taken for ended.
     */
    internal fun partnerWorkoutChanged(previous: String?, current: String?) {
        if (previous == current) return
        // By key: on Kotlin/Native a copied entry still reads through to the map and fails once it changes.
        for (workoutId in partnerSets.keys.toList()) {
            if (workoutId == current) continue
            if (workoutId == previous || current == null) partnerSets.remove(workoutId)?.let(::forget)
        }
    }

    /**
     * Drops the holds of this device's sets that are no longer [live] (in an active workout that isn't
     * deleted): their workout was finished, discarded or edited elsewhere while its screen was closed.
     * A hold whose set is live is always kept. A hold whose set this device has never had as its own
     * (one on a partner's workout, see [ForgetRemovedHolds]) is left to that screen. [stored] tells
     * whether a set is in any workout on this device, live or not, and is asked only of the holds
     * that are neither live nor known to be local.
     */
    internal fun retain(live: Set<String>, stored: (String) -> Boolean) {
        for (id in started.keys.toList()) {
            when {
                id in live -> local.add(id)
                id in local || id in liveBefore || stored(id) -> started.remove(id)
            }
        }
        local.retainAll(started.keys)
        liveBefore = live
    }
}

/** The set ids of [workouts] that are active and not deleted: the sets a hold may run for. */
internal fun liveSetIds(workouts: Collection<Workout>): Set<String> {
    val ids = HashSet<String>()
    for (w in workouts) if (w.status == WorkoutStatus.Active && !w.deleted) for (e in w.entries) for (s in e.sets) ids.add(s.id)
    return ids
}

/**
 * For the life of [session], drops the holds of sets that stop being live, wherever that happens:
 * a workout finished, discarded or edited on another device while its Session screen is closed, too.
 * Runs as the store's live sets change or a hold starts or stops, never per frame.
 */
@Composable
fun RetainLiveHolds(session: UserSession?) {
    if (session == null) return
    LaunchedEffect(session) {
        Stopwatches.follow(session.user.id)
        val workouts = session.store.workouts
        combine(
            workouts.map { liveSetIds(it.values) }.distinctUntilChanged(),
            snapshotFlow { Stopwatches.running() },
        ) { _, _ -> }.collect {
            // Read from the store as it is now, not as the flow last saw it: a hold just started on a
            // just-added set must find that set live.
            val now = workouts.value.values
            // Only this account's own workouts: a partner's set never counts as this device's.
            Stopwatches.retain(liveSetIds(now)) { id ->
                now.any { w -> w.owner == session.user.id && w.entries.any { e -> e.sets.any { it.id == id } } }
            }
        }
    }
    // A partner's workout is never in the store: its holds follow the pairing state's live workout,
    // which changes when they finish or discard it, or unpair.
    LaunchedEffect(session) {
        var previous: String? = null
        session.partner.snapshot.map { it.partner?.let { _ -> it.activeWorkout?.id } }.distinctUntilChanged().collect { current ->
            Stopwatches.partnerWorkoutChanged(previous, current)
            previous = current
        }
    }
}

/**
 * Forgets the holds of sets that drop out of [setIds] while this is composed: a set removed, its
 * exercise deleted, or the workout ended or discarded (pass an empty set). Leaving the screen does not
 * forget them: a hold keeps counting until you come back.
 */
@Composable
fun ForgetRemovedHolds(setIds: Set<String>) {
    val known = remember { HashSet<String>() }
    SideEffect {
        val gone = known.filterNot { it in setIds }
        if (gone.isNotEmpty()) Stopwatches.forget(gone)
        known.clear()
        known.addAll(setIds)
    }
}

/** "0:07", "1:32": a running hold reads as a clock. */
fun clockLabel(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

/**
 * Milliseconds since [startedAt], ticking every frame while it's on screen. Read it in a draw lambda or
 * through [derivedStateOf], so the frames redraw or recompose only what changes.
 */
@Composable
fun rememberElapsed(startedAt: Long): LongState {
    val elapsed = remember(startedAt) { mutableLongStateOf(nowMillis() - startedAt) }
    LaunchedEffect(startedAt) {
        while (true) withFrameMillis { elapsed.longValue = nowMillis() - startedAt }
    }
    return elapsed
}

@Composable
private fun rememberSeconds(elapsed: LongState): Int {
    val seconds by remember(elapsed) { derivedStateOf { (elapsed.longValue / 1000).toInt() } }
    return seconds
}

/**
 * The current timed set's stopwatch. Idle ([elapsed] null): a Start button. Running: a bar filling
 * toward [target] seconds (last time's hold) that turns to the accent gel once it's beaten, and Stop,
 * which logs the hold and ticks the set off. [elapsed] is the set's [rememberElapsed].
 */
@Composable
fun HoldTimer(setId: String, elapsed: LongState?, target: Int, onLog: (seconds: Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Volum.colors
    if (elapsed == null) {
        Row(
            modifier.fillMaxWidth().height(46.dp).tappable { haptic(Haptic.Tap); Stopwatches.start(setId) }.liquidGlass(RoundedCornerShape(23.dp)),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Play, c.accent, size = 16.dp)
            Spacer(Modifier.width(8.dp))
            Txt("Start timer", size = T.Label, weight = Bold, color = c.accent)
        }
        return
    }
    // Recomposes once a second; the bar below reads the frames as it draws.
    val seconds = rememberSeconds(elapsed)
    val beaten = target > 0 && seconds >= target
    // A double pulse as the hold passes last time's, and a tiny tick every ten seconds before and
    // after, so you can feel the time go by without looking. Only for changes seen while on screen:
    // a card folded and opened again doesn't replay where the hold already is.
    LaunchedEffect(elapsed, target) {
        var lastTens = (elapsed.longValue / 10_000).toInt()
        var wasBeaten = target > 0 && elapsed.longValue / 1000 >= target
        snapshotFlow { (elapsed.longValue / 1000).toInt() }.collect { s ->
            val isBeaten = target > 0 && s >= target
            val tens = s / 10
            if (isBeaten && !wasBeaten) haptic(Haptic.Milestone)
            else if (tens != lastTens && tens > 0) hapticTick()
            wasBeaten = isBeaten
            lastTens = tens
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (target > 0) {
            val fill by animateColorAsState(if (beaten) c.accentTop else c.accent.copy(alpha = 0.55f), tween(300), label = "hold fill")
            Box(
                Modifier.fillMaxWidth().height(8.dp).background(c.line, CircleShape).drawBehind {
                    val fraction = (elapsed.longValue / (target * 1000f)).coerceIn(0f, 1f)
                    val r = size.height / 2
                    drawRoundRect(
                        if (beaten) Brush.horizontalGradient(listOf(c.accentTop, c.accentBottom)) else Brush.horizontalGradient(listOf(fill, fill)),
                        size = Size(size.width * fraction, size.height),
                        cornerRadius = CornerRadius(r),
                    )
                },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircleButton(Icons.X, "Cancel the timer", size = 46.dp) { haptic(Haptic.Undo); Stopwatches.cancel(setId) }
            Row(
                Modifier.weight(1f).height(46.dp).tappable {
                    // Once: a second tap would log a 0 s hold over the one just logged.
                    if (Stopwatches.startedAt(setId) != null) onLog(Stopwatches.stop(setId))
                }.accentGel(RoundedCornerShape(23.dp))
                    .semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Stop, c.onAccent, size = 14.dp)
                Spacer(Modifier.width(8.dp))
                Txt("Stop and log ${clockLabel(seconds)}", size = T.Label, weight = Bold, color = c.onAccent, tabular = true)
            }
        }
    }
}

/** The set's seconds slot while its hold runs: the live clock, in the accent. [elapsed] is the set's [rememberElapsed]. */
@Composable
fun RunningClock(elapsed: LongState) {
    val seconds = rememberSeconds(elapsed)
    Txt(clockLabel(seconds), size = T.Heading, weight = dev.southstar21.volum.ui.theme.ExtraBold, color = Volum.colors.accent, tabular = true)
}

