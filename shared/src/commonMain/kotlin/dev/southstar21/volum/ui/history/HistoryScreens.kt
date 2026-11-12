package dev.southstar21.volum.ui.history

import androidx.compose.foundation.background
import dev.southstar21.volum.ui.theme.T
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import dev.southstar21.volum.ui.theme.SettledEntrance
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.southstar21.volum.model.SetEntry
import dev.southstar21.volum.model.SetTag
import dev.southstar21.volum.model.Workout
import dev.southstar21.volum.progress.bestSetIn
import dev.southstar21.volum.progress.volumeKg
import dev.southstar21.volum.progress.workoutTime
import dev.southstar21.volum.ui.LocalApp
import dev.southstar21.volum.ui.LocalDockPadding
import dev.southstar21.volum.ui.LocalNav
import dev.southstar21.volum.ui.LocalSession
import dev.southstar21.volum.ui.Route
import dev.southstar21.volum.ui.ownCompleted
import dev.southstar21.volum.ui.rememberNow
import dev.southstar21.volum.ui.workoutsState
import dev.southstar21.volum.ui.components.CircleButton
import dev.southstar21.volum.ui.components.ConfirmSheet
import dev.southstar21.volum.ui.components.Divider
import dev.southstar21.volum.ui.components.GlassCard
import dev.southstar21.volum.ui.components.Mute
import dev.southstar21.volum.ui.components.Segmented
import dev.southstar21.volum.ui.components.Tag
import dev.southstar21.volum.ui.components.TextButton
import dev.southstar21.volum.ui.components.Toolbar
import dev.southstar21.volum.ui.components.TopInset
import dev.southstar21.volum.ui.components.Txt
import dev.southstar21.volum.ui.components.accentGel
import dev.southstar21.volum.ui.components.glassCard
import dev.southstar21.volum.ui.components.liquidGlass
import dev.southstar21.volum.ui.components.ScreenTitle
import dev.southstar21.volum.ui.components.rememberLast
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import dev.southstar21.volum.ui.session.SetEditSheet
import dev.southstar21.volum.ui.session.blankSet
import dev.southstar21.volum.ui.session.weightIn
import dev.southstar21.volum.ui.theme.Bold
import dev.southstar21.volum.ui.theme.ExtraBold
import dev.southstar21.volum.ui.theme.Icon
import dev.southstar21.volum.ui.theme.Icons
import dev.southstar21.volum.ui.theme.Semibold
import dev.southstar21.volum.ui.theme.Volum
import dev.southstar21.volum.ui.theme.tappable
import dev.southstar21.volum.util.convert
import dev.southstar21.volum.util.durationLabel
import dev.southstar21.volum.util.formatSeconds
import dev.southstar21.volum.util.formatThousands
import dev.southstar21.volum.util.formatVolume
import dev.southstar21.volum.util.formatWeightValue
import dev.southstar21.volum.util.localDateOf
import dev.southstar21.volum.util.monthName
import dev.southstar21.volum.util.newId
import dev.southstar21.volum.util.plural
import dev.southstar21.volum.util.timeOfDay
import dev.southstar21.volum.util.toKg
import dev.southstar21.volum.util.weekdayName
import dev.southstar21.volum.util.weekdayShort
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import dev.southstar21.volum.ui.theme.LocalEntrance
import dev.southstar21.volum.ui.theme.LocalReduceMotion
import dev.southstar21.volum.ui.theme.VolumColors
import dev.southstar21.volum.ui.theme.AnimatedNumber
import dev.southstar21.volum.ui.theme.riseIn
import dev.southstar21.volum.ui.theme.textStyle
import dev.southstar21.volum.util.monthShort
import dev.southstar21.volum.util.formatVolume
import dev.southstar21.volum.util.formatWeightValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.graphics.graphicsLayer
import dev.southstar21.volum.ui.components.Pill
import dev.southstar21.volum.ui.components.milky
import dev.southstar21.volum.ui.theme.Durations
import dev.southstar21.volum.ui.theme.Springs
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.math.roundToInt

@Composable
fun HistoryScreen() {
    val session = LocalSession.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val workouts by session.workoutsState()
    val now = rememberNow(60_000)
    val completed = remember(workouts) { ownCompleted(workouts, session.user.id).sortedByDescending { workoutTime(it) } }
    val byDay = remember(completed) { completed.groupBy { localDateOf(workoutTime(it)) } }
    val today = localDateOf(now)
    // Kept by id, so an open delete prompt survives the activity being recreated.
    var deleteTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    val deleteTarget = deleteTargetId?.let { workouts[it] }?.takeIf { !it.deleted }

    val inYear = remember(byDay, today.year) { byDay.entries.sumOf { (d, list) -> if (d.year == today.year) list.size else 0 } }
    val groups = remember(completed, today) {
        val weekStart = today.minus(DatePeriod(days = today.dayOfWeek.ordinal))
        completed.groupBy { w ->
            val d = localDateOf(workoutTime(w))
            when {
                d >= weekStart -> "This week"
                d >= weekStart.minus(DatePeriod(days = 7)) -> "Last week"
                else -> "${monthName(d)} ${d.year}"
            }
        }.toList()
    }
    val dockPadding = LocalDockPadding.current
    // Lazy: a long history only composes the groups on screen (each a card of glass rows).
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "header") {
            Column {
                TopInset(22.dp)
                Column(Modifier.padding(horizontal = 20.dp)) {
                    ScreenTitle("History")
                    Mute("${plural(inYear, "workout")} in ${today.year}", size = T.Body)
                }
                HeatmapCard(today, byDay) { day -> byDay[day]?.firstOrNull()?.let { nav.push(Route.WorkoutDetail(it.id)) } }
                if (completed.isEmpty()) {
                    GlassCard(Modifier.padding(16.dp).fillMaxWidth()) {
                        Txt("No workouts recorded", size = T.Subhead, weight = Bold)
                        Mute("Finished workouts show up here.", size = T.Small)
                    }
                }
            }
        }
        items(groups, key = { it.first }) { (label, list) ->
            SettledEntrance {
                Column {
                    Row(Modifier.padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 8.dp).riseIn(14f), verticalAlignment = Alignment.Bottom) {
                        Txt(label.uppercase(), Modifier.weight(1f), size = T.Small, weight = ExtraBold, color = Volum.colors.accent, tracking = 0.02.em)
                        Mute(plural(list.size, "workout"), size = T.Caption, weight = Semibold)
                    }
                    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().glassCard(RoundedCornerShape(24.dp)).padding(vertical = 2.dp)) {
                        list.forEachIndexed { i, w ->
                            if (i > 0) Divider(inset = 66.dp, end = 14.dp)
                            WorkoutRow(w, onClick = { nav.push(Route.WorkoutDetail(w.id)) }, onLongClick = { deleteTargetId = w.id })
                        }
                    }
                }
            }
        }
        item(key = "end") { Spacer(Modifier.height(dockPadding + 24.dp)) }
    }
    val deleting = rememberLast(deleteTarget)
    ConfirmSheet(
        open = deleteTarget != null,
        title = "Delete ${deleting?.routineName ?: "workout"}?",
        message = deleting?.let { "${weekdayName(localDateOf(workoutTime(it)))} ${localDateOf(workoutTime(it)).day} ${monthName(localDateOf(workoutTime(it)))}. This can't be undone." },
        confirm = "Delete workout",
        onConfirm = { deleting?.let { w -> scope.launch { session.repository.deleteWorkout(w.id) } } },
        onDismiss = { deleteTargetId = null },
    )
}

@Composable
private fun WorkoutRow(w: Workout, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Volum.colors
    val d = localDateOf(workoutTime(w))
    val sets = w.entries.sumOf { e -> e.sets.count { it.done } }
    val volume = formatVolume(volumeKg(w))
    Row(
        Modifier.fillMaxWidth().tappable(onLongClick = onLongClick, onClick = onClick)
            // The long press, offered to screen readers by name.
            .semantics { customActions = listOf(CustomAccessibilityAction("Delete workout") { onLongClick(); true }) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.size(40.dp).liquidGlass(CircleShape), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Txt("${d.day}", size = T.Label, weight = ExtraBold, tabular = true, lineHeight = T.Label)
            Txt(weekdayShort(d).uppercase(), size = T.Minute, weight = ExtraBold, color = c.mute, lineHeight = T.Minute)
        }
        Column(Modifier.weight(1f)) {
            Txt(w.routineName, size = T.Body, weight = Bold, maxLines = 1)
            Mute(listOf(durationLabel(w.startedAt, w.finishedAt), plural(sets, "set")).filter { it.isNotEmpty() }.joinToString(" · "), size = T.Caption)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Txt(volume.substringBeforeLast(' '), size = T.BodyLarge, weight = ExtraBold, tabular = true)
            Txt(" " + volume.substringAfterLast(' '), Modifier.padding(bottom = 1.dp), size = T.Caption, weight = Semibold, color = c.mute)
        }
    }
}

private const val HEAT_WEEKS = 20

/**
 * The last 20 weeks, one square per day, darker the more was lifted that day, with the week streak,
 * workouts a week and this week's volume over it. Squares pop in as a wave on entrance; tapping a
 * trained day opens its workout.
 */
@Composable
private fun HeatmapCard(today: LocalDate, byDay: Map<LocalDate, List<Workout>>, onDay: (LocalDate) -> Unit) {
    val c = Volum.colors
    val thisWeek = today.minus(DatePeriod(days = today.dayOfWeek.ordinal))
    val start = thisWeek.minus(DatePeriod(days = 7 * (HEAT_WEEKS - 1)))
    val volumes = remember(byDay) { byDay.mapValues { (_, list) -> list.sumOf { volumeKg(it) } } }
    val stats = remember(byDay, today) {
        // Weeks in a row with a workout, counting back from this week (or last, if this one is still empty).
        val trainedWeeks = byDay.keys.map { it.minus(DatePeriod(days = it.dayOfWeek.ordinal)) }.toSet()
        var week = if (thisWeek in trainedWeeks) thisWeek else thisWeek.minus(DatePeriod(days = 7))
        var streak = 0
        while (week in trainedWeeks) { streak++; week = week.minus(DatePeriod(days = 7)) }
        // Workouts a week across the window, from the first trained week in it.
        val inWindow = byDay.filterKeys { it >= start && it <= today }
        val first = inWindow.keys.minOrNull()
        val weeks = first?.let { (it.minus(DatePeriod(days = it.dayOfWeek.ordinal)).daysUntil(thisWeek) / 7 + 1).coerceAtLeast(1) } ?: 1
        val perWeek = inWindow.values.sumOf { it.size }.toDouble() / weeks
        val weekVolume = volumes.filterKeys { it >= thisWeek }.values.sum()
        Triple(streak, perWeek, weekVolume)
    }
    val max = remember(volumes) { volumes.filterKeys { it >= start }.values.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0 }
    val count = remember(byDay, start) { byDay.keys.count { it >= start && it <= today } }

    GlassCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Each number counts up in its own scope, so the card around them doesn't recompose per frame.
            AnimatedNumber(stats.first.toDouble()) { HeatStat("${it.roundToInt()}", "", "week streak", Modifier.weight(1f)) }
            AnimatedNumber(stats.second) { avg -> HeatStat(formatWeightValue((avg * 10).roundToInt() / 10.0), "", "a week, avg", Modifier.weight(1f)) }
            AnimatedNumber(stats.third) { v ->
                val vol = formatVolume(v)
                HeatStat(vol.substringBeforeLast(' '), vol.substringAfterLast(' '), "this week", Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(14.dp))
        Heatmap(start, today, volumes, max, count, onDay)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp).clearAndSetSemantics {}, horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Mute("Light", size = T.Tiny, weight = Semibold)
            Spacer(Modifier.width(4.dp))
            for (level in 1..4) {
                Box(Modifier.padding(horizontal = 1.5.dp).size(10.dp).clip(RoundedCornerShape(3.dp)).background(heatBrush(level, c)))
            }
            Spacer(Modifier.width(4.dp))
            Mute("Heavy", size = T.Tiny, weight = Semibold)
        }
    }
}

@Composable
private fun HeatStat(value: String, unit: String, label: String, modifier: Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Txt(value, size = T.Heading, weight = ExtraBold, tracking = (-0.02).em, tabular = true, maxLines = 1)
            if (unit.isNotEmpty()) Txt(" $unit", Modifier.padding(bottom = 3.dp), size = T.Caption, weight = Semibold, color = Volum.colors.mute, maxLines = 1)
        }
        Mute(label, size = T.Micro, weight = Semibold, maxLines = 1)
    }
}

private fun heatBrush(level: Int, c: VolumColors): Brush = when (level) {
    1 -> SolidColor(c.accentTop.copy(alpha = 0.28f))
    2 -> SolidColor(c.accentTop.copy(alpha = 0.5f))
    3 -> SolidColor(c.accentTop.copy(alpha = 0.8f))
    4 -> Brush.verticalGradient(listOf(c.accentTop, c.accentBottom))
    else -> SolidColor(c.ink.copy(alpha = 0.08f))
}

@Composable
private fun Heatmap(start: LocalDate, today: LocalDate, volumes: Map<LocalDate, Double>, max: Double, count: Int, onDay: (LocalDate) -> Unit) {
    val c = Volum.colors
    val measurer = rememberTextMeasurer()
    val labelStyle = textStyle(T.Tiny, Bold, c.mute)
    val entrance = LocalEntrance.current
    val reduce = LocalReduceMotion.current
    val wave = remember { Animatable(if (!reduce && entrance?.active() == true) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (wave.value < 1f) {
            delay(160)
            wave.animateTo(1f, tween(900, easing = LinearEasing))
        }
    }
    fun level(day: LocalDate): Int {
        val v = volumes[day] ?: return 0
        val f = v / max
        return when {
            f < 0.4 -> 1
            f < 0.6 -> 2
            f < 0.85 -> 3
            else -> 4
        }
    }
    val gap = 3.dp
    val labelHeight = 18.dp
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cell = (maxWidth - gap * (HEAT_WEEKS - 1)) / HEAT_WEEKS
        val height = labelHeight + cell * 7 + gap * 6
        Canvas(
            Modifier.fillMaxWidth().height(height)
                .semantics { contentDescription = "${plural(count, "training day")} in the last $HEAT_WEEKS weeks" }
                .pointerInput(start, volumes) {
                    detectTapGestures { pos ->
                        val step = (cell + gap).toPx()
                        val col = (pos.x / step).toInt()
                        val row = ((pos.y - labelHeight.toPx()) / step).toInt()
                        if (col in 0 until HEAT_WEEKS && row in 0..6) {
                            val day = start.plus(DatePeriod(days = col * 7 + row))
                            if (day <= today && volumes.containsKey(day)) onDay(day)
                        }
                    }
                },
        ) {
            val side = cell.toPx()
            val step = side + gap.toPx()
            val top = labelHeight.toPx()
            val radius = CornerRadius(4.dp.toPx())
            // Month names over the first week of each month.
            for (col in 0 until HEAT_WEEKS) {
                val monday = start.plus(DatePeriod(days = col * 7))
                if (monday.day <= 7 && col < HEAT_WEEKS - 1) drawText(measurer, monthShort(monday), Offset(col * step, 0f), labelStyle)
            }
            // The wave runs from the oldest week to today, each square popping with a little overshoot.
            val spread = (HEAT_WEEKS - 1) * 0.6f + 6 * 0.25f
            for (col in 0 until HEAT_WEEKS) for (row in 0..6) {
                val day = start.plus(DatePeriod(days = col * 7 + row))
                val t = ((wave.value * (spread + 4f) - (col * 0.6f + row * 0.25f)) / 4f).coerceIn(0f, 1f)
                if (t <= 0f) continue
                val u = t - 1f
                val scale = 1f + 2.70158f * u * u * u + 1.70158f * u * u
                val s = side * scale
                val origin = Offset(col * step + (side - s) / 2, top + row * step + (side - s) / 2)
                val alpha = (t * 2f).coerceAtMost(1f)
                when {
                    day > today -> drawRoundRect(c.ink.copy(alpha = 0.1f * alpha), origin, Size(s, s), radius, style = Stroke(1.dp.toPx()))
                    else -> drawRoundRect(heatBrush(level(day), c), origin, Size(s, s), radius, alpha = alpha)
                }
                if (day == today) drawRoundRect(c.accent.copy(alpha = alpha), origin, Size(s, s), radius, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

@Composable
fun WorkoutDetailScreen(workoutId: String) {
    val session = LocalSession.current
    val app = LocalApp.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val prefs by app.prefs.collectAsStateWithLifecycle()
    val workouts by session.workoutsState()
    val live = workouts[workoutId]?.takeIf { !it.deleted }
    // Deleting leaves the screen: while it slides away it keeps showing the workout, not "deleted".
    var leaving by remember { mutableStateOf(false) }
    val lastSeen = remember { arrayOfNulls<Workout>(1) }
    if (live != null) lastSeen[0] = live
    val workout = live ?: lastSeen[0]?.takeIf { leaving }
    val c = Volum.colors
    // The set being edited, as (entry id, set id) so it survives the activity being recreated. Resolved
    // once per opening: a sync while the sheet is up mustn't reset what is being typed.
    var editIds by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }
    val editTarget = remember(editIds) {
        editIds?.let { (entryId, setId) -> workout?.entries?.firstOrNull { it.id == entryId }?.let { e -> e.sets.firstOrNull { it.id == setId }?.let { e to it } } }
    }
    var deletePrompt by rememberSaveable { mutableStateOf(false) }
    // Which exercises are open. The first one done starts open, so the sets are a glance away.
    var open by rememberSaveable(stateSaver = IdSetSaver) { mutableStateOf(workout?.entries?.firstOrNull { !it.isSkipped }?.id?.let { setOf(it) } ?: emptySet()) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopInset(10.dp)
        Toolbar(
            leading = { CircleButton(Icons.ChevronLeft, "Back to history") { nav.pop() } },
            trailing = { if (workout != null) CircleButton(Icons.Trash, "Delete workout", tint = c.danger) { deletePrompt = true } },
        )
        if (workout == null) {
            Mute("This workout was deleted.", Modifier.padding(24.dp))
            return@Column
        }
        val d = localDateOf(workoutTime(workout))
        val sets = workout.entries.sumOf { e -> e.sets.count { it.done } }
        val duration = durationLabel(workout.startedAt, workout.finishedAt)

        // The slim header: what and when on the left, the volume on the right.
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).fillMaxWidth().glassCard(RoundedCornerShape(24.dp))
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Txt(workout.routineName, size = T.Big, weight = ExtraBold, maxLines = 2)
                Mute(listOf("${weekdayShort(d)} ${d.day} ${monthShort(d)}", duration, plural(sets, "set")).filter { it.isNotEmpty() }.joinToString(" · "), size = T.Small, maxLines = 1)
            }
            AnimatedNumber(volumeKg(workout)) { v ->
                val volume = formatVolume(v)
                Row(verticalAlignment = Alignment.Bottom) {
                    Txt(volume.substringBeforeLast(' '), size = T.Heading, weight = ExtraBold, tracking = (-0.02).em, tabular = true, maxLines = 1)
                    Txt(" " + volume.substringAfterLast(' '), Modifier.padding(bottom = 3.dp), size = T.Small, weight = Semibold, color = c.mute, maxLines = 1)
                }
            }
        }

        // One card of exercises; each opens to its sets.
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().glassCard(RoundedCornerShape(28.dp)).padding(vertical = 4.dp)) {
            workout.entries.forEachIndexed { i, entry ->
                androidx.compose.runtime.key(entry.id) {
                    if (i > 0) Divider(inset = 18.dp)
                    val expanded = entry.id in open
                    val done = entry.sets.count { it.done }
                    val turn by animateFloatAsState(if (expanded) 180f else 0f, Springs.settle(), label = "chevron")
                    Row(
                        Modifier.fillMaxWidth()
                            .tappable(highlight = false, onClickLabel = if (expanded) "Hide sets" else "Show sets") { open = if (expanded) open - entry.id else open + entry.id }
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Txt(entry.name, Modifier.weight(1f), size = T.Body, weight = Bold, maxLines = 2)
                        Mute(if (entry.isSkipped) "Skipped" else plural(done, "set"), size = T.Small, maxLines = 1)
                        Icon(Icons.ChevronDown, c.mute, Modifier.graphicsLayer { rotationZ = turn }, size = 18.dp)
                    }
                    AnimatedVisibility(
                        expanded,
                        enter = fadeIn(tween(Durations.Fade)) + expandVertically(Springs.settle(), expandFrom = Alignment.Top),
                        exit = fadeOut(tween(Durations.Exit)) + shrinkVertically(Springs.settle(), shrinkTowards = Alignment.Top),
                    ) {
                        val best = bestSetIn(listOf(workout), entry.exerciseId, Int.MAX_VALUE)
                        Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            // The top set, once: the first set that matches the best, so a tie doesn't light up twice.
                            val topId = best?.let { b -> entry.sets.firstOrNull { it.done && it.tag != SetTag.Warmup && toKg(it.weight, it.unit) == b.weightKg && it.reps == b.reps }?.id }
                            var n = 0
                            entry.sets.forEach { set ->
                                val label = when (set.tag) { SetTag.Warmup -> "W"; SetTag.Drop -> "D"; null -> "${++n}" }
                                val top = set.id == topId
                                SetRow(label, setText(set, entry.isTimed, prefs.unit), done = set.done, top = top) { editIds = entry.id to set.id }
                            }
                            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Pill(height = 38.dp, debounce = false, onClick = {
                                    val last = entry.sets.lastOrNull()
                                    val set = last?.copy(id = newId(), updatedAt = null)
                                        ?: blankSet(entry.isTimed, prefs.unit, done = true)
                                    scope.launch { session.repository.updateWorkout(workout, workout.entries.map { if (it.id == entry.id) it.copy(sets = it.sets + set) else it }) }
                                }) {
                                    Icon(Icons.Plus, c.accent, size = 14.dp)
                                    Spacer(Modifier.width(6.dp))
                                    Txt("Add set", size = T.Small, weight = Bold, color = c.accent)
                                }
                                Spacer(Modifier.weight(1f))
                                if (entry.exerciseId.isNotEmpty()) TextButton("Lift history", size = T.Small) { nav.push(Route.ExerciseDetail(entry.exerciseId)) }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(48.dp))
    }

    SetEditSheet(
        editTarget, prefs.unit,
        onSave = { entry, set -> workout?.let { w -> scope.launch { session.repository.updateWorkout(w, w.entries.map { e -> if (e.id == entry.id) e.copy(sets = e.sets.map { if (it.id == set.id) set else it }) else e }) } } },
        onRemove = { entry, set -> workout?.let { w -> scope.launch { session.repository.updateWorkout(w, w.entries.map { e -> if (e.id == entry.id) e.copy(sets = e.sets.filterNot { it.id == set.id }) else e }) } } },
        onDismiss = { editIds = null },
    )
    ConfirmSheet(
        open = deletePrompt,
        title = "Delete this workout?",
        message = "It's removed from your history and charts. This can't be undone.",
        confirm = "Delete workout",
        onConfirm = {
            leaving = true
            // The session's scope: the delete must finish after this screen is gone.
            session.scope.launch { session.repository.deleteWorkout(workoutId) }
            nav.pop()
        },
        onDismiss = { deletePrompt = false },
    )
}

private val IdSetSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })

/** One set: a milky row, the top set in forest gel. Tap to edit. */
@Composable
private fun SetRow(label: String, value: String, done: Boolean, top: Boolean, onClick: () -> Unit) {
    val c = Volum.colors
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().height(42.dp).tappable(onClickLabel = "Edit set", onClick = onClick)
            .then(if (top) Modifier.accentGel(shape) else Modifier.milky(shape))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = when { top -> c.onAccent; done -> c.ink; else -> c.mute }
        Txt(label, Modifier.width(28.dp), size = T.Label, weight = Bold, color = if (top) c.onAccent.copy(alpha = 0.75f) else c.mute, tabular = true)
        Txt(value, size = T.Label, weight = if (top) ExtraBold else Semibold, color = ink, tabular = true)
    }
}

private fun setText(set: SetEntry, timed: Boolean, unit: dev.southstar21.volum.model.WeightUnit): String {
    val w = formatWeightValue(set.weightIn(unit))
    return when {
        timed && set.weight > 0 -> "$w ${unit.label} × ${formatSeconds(set.seconds ?: 0)}"
        timed -> formatSeconds(set.seconds ?: 0)
        else -> "$w ${unit.label} × ${set.reps}"
    }
}

