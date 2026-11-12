package dev.southstar21.volum.ui.session

import kotlin.math.roundToLong
import androidx.compose.animation.AnimatedVisibility
import dev.southstar21.volum.ui.theme.T
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import dev.southstar21.volum.ui.theme.Durations
import dev.southstar21.volum.ui.theme.Springs
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import dev.southstar21.volum.ui.components.outerShadow
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.southstar21.volum.data.topSetOrder
import dev.southstar21.volum.model.SetEntry
import dev.southstar21.volum.platform.Haptic
import dev.southstar21.volum.platform.haptic
import dev.southstar21.volum.model.SetTag
import dev.southstar21.volum.model.WeightUnit
import dev.southstar21.volum.model.WorkoutEntry
import dev.southstar21.volum.ui.components.CircleButton
import dev.southstar21.volum.ui.components.Divider
import dev.southstar21.volum.ui.components.GlassCard
import dev.southstar21.volum.ui.components.Mute
import dev.southstar21.volum.ui.components.Txt
import dev.southstar21.volum.ui.components.accentGel
import dev.southstar21.volum.ui.components.glassCard
import dev.southstar21.volum.ui.components.liquidGlass
import dev.southstar21.volum.ui.components.milky
import dev.southstar21.volum.ui.theme.Bold
import dev.southstar21.volum.ui.theme.ExtraBold
import dev.southstar21.volum.ui.theme.Icon
import dev.southstar21.volum.ui.theme.Icons
import dev.southstar21.volum.ui.theme.Semibold
import dev.southstar21.volum.ui.theme.Volum
import dev.southstar21.volum.ui.theme.tappable
import dev.southstar21.volum.ui.theme.celebrate
import dev.southstar21.volum.util.convert
import dev.southstar21.volum.util.formatSeconds
import dev.southstar21.volum.util.formatWeightValue
import dev.southstar21.volum.util.newId

fun WorkoutEntry.isComplete(): Boolean = isSkipped || (sets.isNotEmpty() && sets.all { it.done })

/**
 * The haptic for ticking [set] of [entry] on or off, scaled to the moment: the workout's last set is
 * a flourish, an exercise's last set a swell, any other set a firm click, and un-ticking a soft step back.
 */
fun toggleHaptic(entries: List<WorkoutEntry>, entry: WorkoutEntry, set: SetEntry): Haptic {
    if (set.done) return Haptic.Undo
    val after = entries.map { e -> if (e.id == entry.id) e.copy(sets = e.sets.map { if (it.id == set.id) it.copy(done = true) else it }) else e }
    // The entry can be gone already: a card folding away after its exercise was deleted still takes taps.
    val exerciseDone = after.firstOrNull { it.id == entry.id }?.isComplete() ?: return Haptic.SetDone
    return when {
        exerciseDone && after.all { it.isComplete() } -> Haptic.WorkoutDone
        exerciseDone -> Haptic.ExerciseDone
        else -> Haptic.SetDone
    }
}

/** The weight shown in [preferred] units (converted if logged in the other). */
fun SetEntry.weightIn(preferred: WeightUnit): Double = convert(weight, unit, preferred)

/** A 38 dp glass circle in a 48 dp touch target: small on the card, still easy to hit. */
@Composable
private fun CardCircleButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).tappable(onClick = onClick).semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(38.dp).liquidGlass(CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, Volum.colors.ink, size = 18.dp)
        }
    }
}

/** One nudge: 2.5 kg / 5 lb, or 5 s for a hold. */
fun stepWeight(preferred: WeightUnit) = if (preferred == WeightUnit.Kg) 2.5 else 5.0

/** A set with nothing to copy from: 8 reps, or a 30 s hold when [timed]. */
fun blankSet(timed: Boolean, unit: WeightUnit, done: Boolean = false): SetEntry =
    SetEntry(id = newId(), unit = unit, reps = if (timed) 0 else 8, seconds = if (timed) 30 else null, done = done)

/** The set "Add set" appends to [entry]: a copy of its last one, untagged and not done, or a [blankSet]. */
fun nextSet(entry: WorkoutEntry, unit: WeightUnit): SetEntry =
    entry.sets.lastOrNull()?.copy(id = newId(), done = false, tag = null, updatedAt = null) ?: blankSet(entry.isTimed, unit)

/**
 * [set] reshaped for a lift tracked by time ([timed]) or by reps. What was logged in the other shape
 * doesn't carry over, so the set is un-ticked and keeps a count it had before or gets 30 s / 8 reps.
 * Weight, unit and tag stay.
 */
fun reshapeSet(set: SetEntry, timed: Boolean): SetEntry =
    if (timed) set.copy(reps = 0, seconds = set.seconds?.takeIf { it > 0 } ?: 30, done = false)
    else set.copy(reps = set.reps.takeIf { it > 0 } ?: 8, seconds = null, done = false)

/** [entry]'s sets once it's relinked to a lift that is [timed] or not: unchanged if the shape is the same. */
fun reshapedSets(entry: WorkoutEntry, timed: Boolean): List<SetEntry> =
    if (entry.isTimed == timed) entry.sets else entry.sets.map { reshapeSet(it, timed) }

/** Callbacks the list raises; the owner's session and a partner's session handle them differently. */
data class EntryActions(
    val onSet: (entry: WorkoutEntry, set: SetEntry) -> Unit,
    val onToggleDone: (entry: WorkoutEntry, set: SetEntry) -> Unit,
    val onEditSet: (entry: WorkoutEntry, set: SetEntry) -> Unit,
    val onAddSet: (entry: WorkoutEntry) -> Unit,
    val onMenu: (entry: WorkoutEntry) -> Unit,
    val onExpand: (entry: WorkoutEntry) -> Unit,
    /** The sets are someone else's (a partner's session): nudges keep each set's own unit. */
    val keepSetUnits: Boolean = false,
)

/** An exercise in progress: its sets, with the current one raised and its −/+ nudges. */
@Composable
fun ActiveEntryCard(entry: WorkoutEntry, expanded: Boolean, lastLabel: String?, preferred: WeightUnit, actions: EntryActions, modifier: Modifier = Modifier) {
    val c = Volum.colors
    val doneCount = entry.sets.count { it.done }
    val current = entry.sets.indexOfFirst { !it.done }
    GlassCard(modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(start = 10.dp, end = 10.dp, top = 14.dp, bottom = 10.dp)) {
      Column(Modifier.fillMaxWidth().then(if (entry.isSkipped) Modifier.alpha(0.55f) else Modifier)) {
        val gap by androidx.compose.animation.core.animateDpAsState(if (expanded) 10.dp else 0.dp, Springs.settle(), label = "header gap")
        // The spring overshoots past 0 as it closes, and padding can't be negative.
        // No end padding: the buttons' 48 dp touch targets leave their circles 5 dp in from the edge.
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, bottom = gap.coerceAtLeast(0.dp)), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).tappable(highlight = false) { actions.onExpand(entry) }) {
                Txt(entry.name, size = T.Big, weight = Bold, maxLines = 2)
                Spacer(Modifier.height(2.dp))
                when {
                    entry.isSkipped -> Mute("Skipped", size = T.Small)
                    expanded && lastLabel != null -> Row {
                        Mute("Last time ", size = T.Small)
                        Txt(lastLabel, size = T.Small, weight = Bold, tabular = true)
                    }
                    else -> Mute("$doneCount of ${entry.sets.size} sets" + (lastLabel?.let { " · Last $it" } ?: ""), size = T.Small, maxLines = 1)
                }
            }
            CardCircleButton(Icons.More, "More options for ${entry.name}") { actions.onMenu(entry) }
            AnimatedVisibility(!expanded, enter = fadeIn() + androidx.compose.animation.expandHorizontally(), exit = fadeOut() + androidx.compose.animation.shrinkHorizontally()) {
                CardCircleButton(Icons.ChevronDown, "Expand ${entry.name}") { actions.onExpand(entry) }
            }
        }
        AnimatedVisibility(
            expanded && !entry.isSkipped,
            enter = fadeIn(tween(Durations.Fade, delayMillis = Durations.Instant)) + expandVertically(Springs.settle(), expandFrom = Alignment.Top),
            exit = fadeOut(tween(Durations.Exit)) + shrinkVertically(Springs.settle(), shrinkTowards = Alignment.Top),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                entry.sets.forEachIndexed { i, set ->
                    androidx.compose.runtime.key(set.id) { SetRow(i, set, i == current, entry.isTimed, preferred, entry, actions) }
                }
                Row(
                    // Not debounced: two quick taps are two sets, like the steppers.
                    Modifier.fillMaxWidth().height(44.dp).tappable(debounce = false) { haptic(Haptic.Tap); actions.onAddSet(entry) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Plus, c.accent, size = 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Txt("Add set", size = T.Label, weight = Bold, color = c.accent)
                }
            }
        }
      }
    }
}

@Composable
private fun SetRow(index: Int, set: SetEntry, current: Boolean, timed: Boolean, preferred: WeightUnit, entry: WorkoutEntry, actions: EntryActions) {
    val c = Volum.colors
    val shape = RoundedCornerShape(20.dp)
    val weight = set.weightIn(preferred)
    val running = if (timed) Stopwatches.startedAt(set.id) else null
    // One frame clock per running hold, shared by the seconds slot and the timer below.
    val elapsed = running?.let { rememberElapsed(it) }
    // Becoming the current set eases in (its ring, glow and the nudges below) rather than snapping, as
    // does the step back of the sets around it.
    val cur by animateFloatAsState(if (current) 1f else 0f, Springs.settle(), label = "current")
    val dim by animateFloatAsState(if (!set.done && !current) 0.78f else 1f, tween(Durations.Fade), label = "dim")
    fun toggle() {
        // Ticking off a set whose hold is running logs the hold.
        val logged = if (running != null && !set.done) set.copy(seconds = Stopwatches.stop(set.id)) else set
        actions.onToggleDone(entry, logged)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .outerShadow(shape, Shadow(radius = 24.dp, color = c.currentGlow, offset = DpOffset(0.dp, 10.dp), spread = (-10).dp), alpha = { cur })
            .milky(shape)
            .border(1.5.dp, c.accent.copy(alpha = cur), shape)
            .graphicsLayer { alpha = dim },
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 7.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(30.dp)) {
                when (set.tag) {
                    SetTag.Warmup -> Txt("W", size = T.Small, weight = ExtraBold, color = c.mute)
                    SetTag.Drop -> Txt("D", size = T.Small, weight = ExtraBold, color = c.mute)
                    null -> Txt("${index + 1}", size = T.Small, weight = if (current) ExtraBold else Bold, color = if (current) c.accent else c.mute, tabular = true)
                }
            }
            Column(Modifier.weight(1f).tappable(highlight = false, onClickLabel = "Edit set ${index + 1}") { actions.onEditSet(entry, set) }) {
                if (timed && weight <= 0) {
                    Txt(if (running != null) "Holding" else "Hold", size = T.Small, weight = Semibold, color = if (running != null) c.accent else c.mute)
                    if (running != null && (set.seconds ?: 0) > 0) Mute("Last ${formatSeconds(set.seconds ?: 0)}", size = T.Micro)
                } else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Txt(formatWeightValue(weight), size = T.Heading, weight = ExtraBold, tracking = (-0.02).em, tabular = true)
                        Txt(" ${preferred.label}", Modifier.padding(bottom = 2.dp), size = T.Caption, weight = Semibold, color = c.mute)
                    }
                    Txt("${formatWeightValue(convert(weight, preferred, preferred.other))} ${preferred.other.label}", size = T.Micro, color = c.mute, tabular = true)
                }
            }
            Row(Modifier.width(84.dp).tappable(highlight = false, onClickLabel = "Edit set ${index + 1}") { actions.onEditSet(entry, set) }, verticalAlignment = Alignment.Bottom) {
                if (timed) {
                    if (elapsed != null) RunningClock(elapsed)
                    else Txt(formatSeconds(set.seconds ?: 0), size = T.Heading, weight = ExtraBold, tabular = true)
                } else {
                    Txt("${set.reps}", size = T.Heading, weight = ExtraBold, tabular = true)
                    Txt(" reps", Modifier.padding(bottom = 2.dp), size = T.Caption, weight = Semibold, color = c.mute)
                }
            }
            Box(
                Modifier.size(44.dp).celebrate(set.done, c.accent).tappable { toggle() }.then(
                    when {
                        set.done -> Modifier.accentGel(CircleShape)
                        current -> Modifier.liquidGlass(CircleShape)
                        else -> Modifier.border(1.5.dp, c.line, CircleShape)
                    },
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Check, when { set.done -> c.onAccent; current -> c.accent; else -> c.mute }, size = 20.dp, contentDescription = if (set.done) "Set ${index + 1} done" else "Log set ${index + 1}")
            }
        }
        AnimatedVisibility(
            current,
            enter = fadeIn(tween(Durations.Fade, delayMillis = Durations.Instant)) + expandVertically(Springs.settle(), expandFrom = Alignment.Top),
            exit = fadeOut(tween(Durations.Exit)) + shrinkVertically(Springs.settle(), shrinkTowards = Alignment.Top),
        ) {
          Column {
            // While a hold runs its clock is the control: the nudges step aside.
            AnimatedVisibility(running == null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Row(Modifier.fillMaxWidth().padding(start = 42.dp, end = 7.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!timed || weight > 0) {
                        val unit = nudgeUnit(set, preferred, actions.keepSetUnits)
                        val step = stepWeight(unit)
                        CircleButton(Icons.Minus, "Weight down ${formatWeightValue(step)} ${unit.label}", repeatable = true) {
                            haptic(Haptic.Tap); actions.onSet(entry, nudgedSet(set, preferred, actions.keepSetUnits, up = false))
                        }
                        CircleButton(Icons.Plus, "Weight up ${formatWeightValue(step)} ${unit.label}", repeatable = true) {
                            haptic(Haptic.Tap); actions.onSet(entry, nudgedSet(set, preferred, actions.keepSetUnits, up = true))
                        }
                    }
                }
                Row(Modifier.width(84.dp + 44.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (timed) {
                        CircleButton(Icons.Minus, "5 seconds less", repeatable = true) { haptic(Haptic.Tap); actions.onSet(entry, set.copy(seconds = ((set.seconds ?: 0) - 5).coerceAtLeast(0))) }
                        CircleButton(Icons.Plus, "5 seconds more", repeatable = true) { haptic(Haptic.Tap); actions.onSet(entry, set.copy(seconds = (set.seconds ?: 0) + 5)) }
                    } else {
                        CircleButton(Icons.Minus, "One rep fewer", repeatable = true) { haptic(Haptic.Tap); actions.onSet(entry, set.copy(reps = (set.reps - 1).coerceAtLeast(0))) }
                        CircleButton(Icons.Plus, "One more rep", repeatable = true) { haptic(Haptic.Tap); actions.onSet(entry, set.copy(reps = set.reps + 1)) }
                    }
                }
            }
            }
            if (timed && !set.done) {
                HoldTimer(
                    set.id,
                    elapsed,
                    target = set.seconds ?: 0,
                    onLog = { held -> actions.onToggleDone(entry, set.copy(seconds = held)) },
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                )
            }
          }
        }
    }
}

/** Finished or skipped exercises, folded into one glass group. */
@Composable
fun CompletedGroup(entries: List<WorkoutEntry>, preferred: WeightUnit, expandedId: String?, actions: EntryActions, alreadyDone: Set<String> = emptySet()) {
    val c = Volum.colors
    // Rows finished during this session fold in; the ones already there don't.
    val initial = androidx.compose.runtime.remember { entries.map { it.id }.toSet() }
    Column(Modifier.fillMaxWidth().glassCard(RoundedCornerShape(28.dp)).padding(vertical = 4.dp)) {
        entries.forEachIndexed { i, entry -> androidx.compose.runtime.key(entry.id) {
          val visible = androidx.compose.runtime.remember { androidx.compose.animation.core.MutableTransitionState(entry.id in initial).apply { targetState = true } }
          AnimatedVisibility(
              visibleState = visible,
              enter = fadeIn(tween(Durations.Fade, delayMillis = Durations.Exit)) + expandVertically(Springs.settle(), expandFrom = Alignment.Top),
          ) {
          Column {
            if (i > 0) Divider(inset = 16.dp)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 58.dp).tappable(highlight = false) { actions.onExpand(entry) }.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // An exercise finished during this session lands here with a little burst.
                val fresh = !entry.isSkipped && entry.id !in alreadyDone
                Box(Modifier.size(28.dp).celebrate(fresh, c.accent, initial = false).then(if (entry.isSkipped) Modifier.liquidGlass(CircleShape) else Modifier.accentGel(CircleShape)), contentAlignment = Alignment.Center) {
                    Icon(if (entry.isSkipped) Icons.Skip else Icons.Check, if (entry.isSkipped) c.mute else c.onAccent, size = 13.dp)
                }
                Column(Modifier.weight(1f)) {
                    Txt(entry.name, size = T.Body, weight = Bold, maxLines = 1)
                    Mute(if (entry.isSkipped) "Skipped" else summaryOf(entry, preferred), size = T.Caption, maxLines = 1)
                }
                val turn by animateFloatAsState(if (expandedId == entry.id) 180f else 0f, Springs.settle(), label = "chevron")
                Icon(Icons.ChevronDown, c.mute, size = 18.dp, modifier = Modifier.graphicsLayer { rotationZ = turn })
            }
            AnimatedVisibility(
                expandedId == entry.id,
                enter = fadeIn(tween(Durations.Fade, delayMillis = Durations.Instant)) + expandVertically(Springs.settle(), expandFrom = Alignment.Top),
                exit = fadeOut(tween(Durations.Exit)) + shrinkVertically(Springs.settle(), shrinkTowards = Alignment.Top),
            ) {
                Box(Modifier.padding(horizontal = 6.dp).padding(bottom = 8.dp)) {
                    ActiveEntryCard(entry, expanded = true, lastLabel = null, preferred = preferred, actions = actions)
                }
            }
          }
          }
        } }
    }
}

/**
 * The unit a weight nudge steps in. A partner's set always keeps its own (their 60 kg set stays in
 * kg for a viewer in lb, even after being nudged down to nothing and back up). Your own sets step
 * in your unit: one logged before a unit switch moves into it on the first nudge.
 */
internal fun nudgeUnit(set: SetEntry, preferred: WeightUnit, keepSetUnits: Boolean): WeightUnit =
    if (keepSetUnits) set.unit else preferred

/** [set] one weight step up or down in [nudgeUnit], from its weight rounded to 0.01 there; never below 0. */
internal fun nudgedSet(set: SetEntry, preferred: WeightUnit, keepSetUnits: Boolean, up: Boolean): SetEntry {
    val unit = nudgeUnit(set, preferred, keepSetUnits)
    val base = if (unit == set.unit) set.weight else (convert(set.weight, set.unit, unit) * 100).roundToLong() / 100.0
    val step = stepWeight(unit)
    return set.copy(weight = if (up) base + step else (base - step).coerceAtLeast(0.0), unit = unit)
}

fun summaryOf(entry: WorkoutEntry, preferred: WeightUnit): String {
    val done = entry.sets.filter { it.done && it.tag != SetTag.Warmup }
    if (done.isEmpty()) return "${entry.sets.size} sets"
    if (entry.isTimed) return "${done.size} sets · ${formatSeconds(done.maxOf { it.seconds ?: 0 })} best"
    val top = done.maxWith(topSetOrder(timed = false))
    return "${done.size} sets · top ${formatWeightValue(top.weightIn(preferred))} ${preferred.label} × ${top.reps}"
}
