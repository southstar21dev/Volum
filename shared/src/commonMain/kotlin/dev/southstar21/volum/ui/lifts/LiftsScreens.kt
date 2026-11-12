package dev.southstar21.volum.ui.lifts

import dev.southstar21.volum.data.MAX_EXERCISE_NAME
import dev.southstar21.volum.data.nameClash
import androidx.compose.foundation.Canvas
import dev.southstar21.volum.ui.theme.T
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.southstar21.volum.ui.components.ScreenTitle
import dev.southstar21.volum.ui.theme.SettledEntrance
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.southstar21.volum.model.WeightUnit
import dev.southstar21.volum.progress.ChartPoint
import dev.southstar21.volum.progress.ChartRange
import dev.southstar21.volum.progress.bestSetIn
import dev.southstar21.volum.progress.filterRange
import dev.southstar21.volum.progress.workoutTime
import dev.southstar21.volum.data.lastTopSetLabel
import dev.southstar21.volum.ui.LocalApp
import dev.southstar21.volum.ui.LocalDockPadding
import dev.southstar21.volum.ui.LocalNav
import dev.southstar21.volum.ui.LocalSession
import dev.southstar21.volum.ui.Route
import dev.southstar21.volum.ui.exercisesState
import dev.southstar21.volum.ui.rememberNow
import dev.southstar21.volum.ui.workoutsState
import dev.southstar21.volum.ui.components.ActionSheet
import dev.southstar21.volum.ui.components.BareField
import dev.southstar21.volum.ui.components.BottomSheet
import dev.southstar21.volum.ui.components.CircleButton
import dev.southstar21.volum.ui.components.ConfirmSheet
import dev.southstar21.volum.ui.components.Divider
import dev.southstar21.volum.ui.components.ErrorText
import dev.southstar21.volum.ui.components.GlassCard
import dev.southstar21.volum.ui.components.Mute
import dev.southstar21.volum.ui.components.Pill
import dev.southstar21.volum.ui.components.PrimaryButton
import dev.southstar21.volum.ui.components.Segmented
import dev.southstar21.volum.ui.components.SheetAction
import dev.southstar21.volum.ui.components.Tag
import dev.southstar21.volum.ui.components.Toolbar
import dev.southstar21.volum.ui.components.TopInset
import dev.southstar21.volum.ui.components.Txt
import dev.southstar21.volum.ui.components.glassCard
import dev.southstar21.volum.ui.components.liquidGlass
import dev.southstar21.volum.ui.components.milky
import dev.southstar21.volum.ui.theme.Bold
import dev.southstar21.volum.ui.theme.Medium
import dev.southstar21.volum.util.toKg
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.drawBehind
import dev.southstar21.volum.ui.theme.ExtraBold
import dev.southstar21.volum.ui.theme.Icon
import dev.southstar21.volum.ui.theme.Icons
import dev.southstar21.volum.ui.theme.Semibold
import dev.southstar21.volum.ui.theme.Volum
import dev.southstar21.volum.ui.theme.tappable
import dev.southstar21.volum.ui.theme.textStyle
import dev.southstar21.volum.util.convert
import dev.southstar21.volum.util.formatSeconds
import dev.southstar21.volum.util.formatWeightValue
import dev.southstar21.volum.util.localDateOf
import dev.southstar21.volum.util.monthShort
import dev.southstar21.volum.util.plural
import dev.southstar21.volum.util.shortDate
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.drawscope.clipRect
import dev.southstar21.volum.model.Exercise
import dev.southstar21.volum.model.SetTag
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.saveable.rememberSaveable
import dev.southstar21.volum.progress.topSetSeries
import dev.southstar21.volum.ui.components.accentGel
import dev.southstar21.volum.ui.theme.Durations
import dev.southstar21.volum.ui.theme.Easings
import dev.southstar21.volum.ui.theme.LocalEntrance
import dev.southstar21.volum.ui.theme.LocalReduceMotion
import dev.southstar21.volum.ui.theme.growUp
import dev.southstar21.volum.ui.theme.riseIn
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class LiftSort { Alphabetical, Recent }

@Composable
fun LiftsScreen() {
    val session = LocalSession.current
    val app = LocalApp.current
    val nav = LocalNav.current
    val prefs by app.prefs.collectAsStateWithLifecycle()
    val exercises by session.exercisesState()
    val workouts by session.workoutsState()
    val c = Volum.colors
    var query by rememberSaveable { mutableStateOf("") }
    var sort by remember { mutableStateOf(LiftSort.Alphabetical) }
    var sortMenu by remember { mutableStateOf(false) }
    val list = rememberLazyListState()

    // Each lift's top set per session, oldest first: the rail's bars and the rows' trend lines.
    val series = remember(workouts) { topSetSeries(workouts.values, session.user.id) }
    // What the rail and the trend lines draw: each lift's points in the shape of its latest session.
    val shaped = remember(series) { series.mapValues { (_, points) -> latestShape(points) } }
    val lastEntries = remember(workouts) { session.repository.lastEntries() }
    val live = remember(exercises) { exercises.values.filter { !it.deleted } }
    val visible = remember(live, query, sort, series) {
        val matching = live.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
        when (sort) {
            LiftSort.Alphabetical -> matching.sortedBy { it.name.lowercase() }
            LiftSort.Recent -> matching.sortedWith(compareByDescending<Exercise> { series[it.id]?.lastOrNull()?.time ?: 0L }.thenBy { it.name.lowercase() })
        }
    }
    val recent = remember(live, series) {
        live.filter { series[it.id]?.isNotEmpty() == true }.sortedByDescending { series[it.id]!!.last().time }.take(8)
    }
    val showRail = query.isBlank() && sort == LiftSort.Alphabetical && recent.isNotEmpty()
    val groups = remember(visible, sort) {
        if (sort == LiftSort.Recent) listOf(' ' to visible)
        else visible.groupBy { it.name.firstOrNull()?.uppercaseChar()?.takeIf(Char::isLetter) ?: '#' }.toList()
    }

    Box(Modifier.fillMaxSize()) {
        // Lazy: a big library composes only the letter groups on screen. Each group is one item, a
        // glass card of rows.
        LazyColumn(Modifier.fillMaxSize(), state = list) {
            item(key = "title", contentType = "title") {
                Column {
                    TopInset(22.dp)
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        ScreenTitle("Lifts")
                        Mute(plural(live.size, "exercise"), size = T.Body)
                    }
                }
            }
            item(key = "search", contentType = "search") {
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).riseIn(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    // The end padding is narrower so the clear button's 48dp touch area fits: its visible
                    // circle still sits 16dp from the edge.
                    Row(
                        Modifier.weight(1f).height(48.dp).liquidGlass(RoundedCornerShape(24.dp)).padding(start = 16.dp, end = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Search, c.mute, size = 20.dp)
                        Spacer(Modifier.width(10.dp))
                        BareField(query, { query = it }, Modifier.weight(1f), placeholder = "Search", size = T.BodyLarge, weight = androidx.compose.ui.text.font.FontWeight.Normal, imeAction = androidx.compose.ui.text.input.ImeAction.Search, label = "Search exercises")
                        if (query.isNotEmpty()) {
                            Box(
                                Modifier.size(48.dp).tappable { query = "" }.semantics { contentDescription = "Clear search" },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(Modifier.size(30.dp).liquidGlass(CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.X, c.ink, size = 14.dp) }
                            }
                        }
                    }
                    CircleButton(Icons.Sort, if (sort == LiftSort.Alphabetical) "Sort, A to Z" else "Sort, recently done first", size = 48.dp, tint = c.accent) { sortMenu = true }
                }
            }
            if (showRail) {
                item(key = "recent-label", contentType = "label") { ListLabel("RECENT", Modifier.padding(top = 20.dp)) }
                item(key = "recent", contentType = "rail") {
                    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(recent, key = { it.id }) { ex ->
                            val (timed, points) = shaped.getValue(ex.id)
                            RecentTile(ex.name, points, timed, prefs.unit) { nav.push(Route.ExerciseDetail(ex.id)) }
                        }
                    }
                }
            }
            item(key = "list-label", contentType = "label") {
                ListLabel(if (sort == LiftSort.Alphabetical) "ALL · A–Z" else "RECENTLY DONE", Modifier.padding(top = if (showRail) 4.dp else 20.dp))
            }
            if (visible.isEmpty()) {
                item(key = "empty", contentType = "empty") {
                    GlassCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                        Mute(if (query.isBlank()) "No exercises yet. Add them from a routine." else "No match for “$query”. Check spelling or add it from a routine.", size = T.Label)
                    }
                }
            }
            groups.forEach { (letter, group) ->
                if (sort == LiftSort.Alphabetical) {
                    item(key = "letter-$letter", contentType = "letter") {
                        SettledEntrance {
                            Txt(letter.toString(), Modifier.padding(start = 22.dp, top = 6.dp, bottom = 8.dp).riseIn(14f), size = T.Small, weight = ExtraBold, color = c.accent)
                        }
                    }
                }
                item(key = "group-$letter", contentType = "group") {
                    SettledEntrance {
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp).fillMaxWidth().glassCard(RoundedCornerShape(24.dp)).padding(vertical = 2.dp)) {
                            group.forEachIndexed { i, ex ->
                                androidx.compose.runtime.key(ex.id) {
                                    if (i > 0) Divider(inset = 14.dp)
                                    val points = shaped[ex.id]?.second.orEmpty()
                                    val label = lastEntries[ex.id]?.let { lastTopSetLabel(it.sets, it.isTimed) }
                                    Row(
                                        Modifier.fillMaxWidth().tappable { nav.push(Route.ExerciseDetail(ex.id)) }.padding(horizontal = 14.dp, vertical = 11.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Txt(ex.name, size = T.Body, weight = Bold, maxLines = 1)
                                            Mute(label ?: if (ex.isTimed) "Timed · not done yet" else "Not done yet", size = T.Caption, maxLines = 1)
                                        }
                                        if (points.size > 1) Sparkline(points.takeLast(6))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item(key = "end") { Spacer(Modifier.height(LocalDockPadding.current + 24.dp)) }
        }
    }

    ActionSheet(
        open = sortMenu,
        title = "Sort lifts",
        actions = listOf(
            SheetAction("A–Z", if (sort == LiftSort.Alphabetical) Icons.Check else null) { sort = LiftSort.Alphabetical },
            SheetAction("Recently done", if (sort == LiftSort.Recent) Icons.Check else null) { sort = LiftSort.Recent },
        ),
        onDismiss = { sortMenu = false },
    )
}

@Composable
private fun ListLabel(text: String, modifier: Modifier = Modifier) {
    Txt(text, modifier.padding(start = 22.dp, end = 22.dp, bottom = 8.dp).riseIn(14f), size = T.Small, weight = ExtraBold, color = Volum.colors.accent, tracking = 0.02.em)
}

/**
 * The shape of the latest session, and the points logged in that shape, so seconds and kilograms never
 * share a scale. [ChartPoint.timed] is how the entry was logged, not the lift's current setting:
 * switching a lift between reps and time leaves older entries as they were.
 */
private fun latestShape(points: List<ChartPoint>): Pair<Boolean, List<ChartPoint>> {
    val timed = points.lastOrNull()?.timed ?: false
    return timed to points.filter { it.timed == timed }
}

/** A recently trained lift: its last top set big, and its last six sessions as bars, the latest in forest. */
@Composable
private fun RecentTile(name: String, points: List<ChartPoint>, timed: Boolean, unit: WeightUnit, onClick: () -> Unit) {
    val c = Volum.colors
    val last = points.last()
    val bodyweight = !timed && last.value <= 0.0
    val (figure, figureUnit, sub) = when {
        timed -> Triple(formatSeconds(last.value.toInt()), "", "hold")
        bodyweight -> Triple("${last.reps}", " reps", "bodyweight")
        else -> Triple(formatWeightValue(convert(last.value, WeightUnit.Kg, unit)), " ${unit.label}", plural(last.reps, "rep"))
    }
    val recent = points.takeLast(6)
    // Bodyweight lifts trend on reps; everything else on the top set.
    val values = recent.map { if (bodyweight) it.reps.toDouble() else it.value }
    val max = values.max().coerceAtLeast(1.0)
    Column(
        Modifier.width(150.dp).tappable(onClick = onClick).glassCard(RoundedCornerShape(24.dp))
            .semantics(mergeDescendants = true) {}
            .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
    ) {
        Txt(name, size = T.Label, weight = Bold, maxLines = 1)
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
            Txt(figure, size = T.Headline, weight = ExtraBold, tracking = (-0.02).em, tabular = true, maxLines = 1)
            if (figureUnit.isNotEmpty()) Txt(figureUnit, Modifier.padding(bottom = 3.dp), size = T.Small, weight = Semibold, color = c.mute, maxLines = 1)
        }
        Mute(sub, size = T.Caption, maxLines = 1)
        Row(Modifier.padding(top = 8.dp).fillMaxWidth().height(30.dp).clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
            // Always six slots, newest on the right: sessions not done yet are faint stubs.
            val empty = 6 - values.size
            repeat(empty) { i -> Box(Modifier.weight(1f).fillMaxHeight(0.14f).growUp(i).clip(RoundedCornerShape(6.dp)).background(c.ink.copy(alpha = 0.07f))) }
            values.forEachIndexed { i, v ->
                val latest = i == values.lastIndex
                Box(
                    Modifier.weight(1f).fillMaxHeight((v / max).toFloat().coerceIn(0.18f, 1f)).growUp(empty + i)
                        .then(if (latest) Modifier.accentGel(RoundedCornerShape(6.dp)) else Modifier.milky(RoundedCornerShape(6.dp))),
                )
            }
        }
    }
}

/** A small trend line of a lift's recent top sets, drawn left to right on entrance. Flat reads muted. */
@Composable
private fun Sparkline(points: List<ChartPoint>) {
    val c = Volum.colors
    val values = points.map { if (it.value > 0) it.value else it.reps.toDouble() }
    val flat = values.max() - values.min() < 0.01
    val color = if (flat) c.mute else c.accent
    val entrance = LocalEntrance.current
    val reduce = LocalReduceMotion.current
    val draw = remember { Animatable(if (!reduce && entrance?.active() == true) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (draw.value < 1f) {
            delay(220)
            draw.animateTo(1f, tween(Durations.Count, easing = Easings.Enter))
        }
    }
    Canvas(Modifier.size(60.dp, 22.dp).clearAndSetSemantics {}) {
        val pad = 3.dp.toPx()
        val lo = values.min()
        val hi = values.max()
        fun x(i: Int) = pad + i * (size.width - 2 * pad) / (values.size - 1)
        fun y(v: Double) = if (flat) size.height / 2 else (size.height - pad - (v - lo) / (hi - lo) * (size.height - 2 * pad)).toFloat()
        val path = Path()
        values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
        val p = draw.value
        clipRect(right = size.width * p) {
            drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        if (p >= 0.98f) drawCircle(color, 2.6.dp.toPx() * ((p - 0.98f) / 0.02f).coerceIn(0f, 1f), Offset(x(values.lastIndex), y(values.last())))
    }
}

@Composable
fun ExerciseDetailScreen(exerciseId: String) {
    val session = LocalSession.current
    val app = LocalApp.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val prefs by app.prefs.collectAsStateWithLifecycle()
    val exercises by session.exercisesState()
    val workouts by session.workoutsState()
    val now = rememberNow(60_000)
    val c = Volum.colors
    val exercise = exercises[exerciseId]
    val own = remember(workouts) { workouts.values.filter { it.owner == session.user.id } }
    val firstEntry = own.firstNotNullOfOrNull { w -> w.entries.firstOrNull { it.exerciseId == exerciseId } }
    val name = exercise?.name ?: firstEntry?.name ?: "Exercise"
    val timed = exercise?.isTimed ?: firstEntry?.isTimed ?: false
    val unit = prefs.unit
    // The same top sets as the Lifts list, keeping the sessions logged in the lift's current shape.
    val series = remember(workouts, timed) {
        topSetSeries(own, session.user.id)[exerciseId].orEmpty().filter { it.timed == timed }
    }
    var range by remember { mutableStateOf(ChartRange.M3) }
    val shown = filterRange(series, range, now).ifEmpty { if (range == ChartRange.All) emptyList() else series.takeLast(1) }
    var menu by remember { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf(name) }
    var archive by remember { mutableStateOf(false) }

    fun display(kg: Double): Double = if (timed) kg else convert(kg, WeightUnit.Kg, unit)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopInset(10.dp)
        Toolbar(
            leading = { CircleButton(Icons.ChevronLeft, "Back") { nav.pop() } },
            trailing = { if (exercise != null && exercise.owner == session.user.id) CircleButton(Icons.More, "More options for $name") { menu = true } },
        )
        ScreenTitle(name, Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp))
        val pr = series.maxByOrNull { it.value }
        val latest = series.lastOrNull()
        val previous = series.dropLast(1).lastOrNull()
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(
                if (timed) "Longest hold" else "Personal record",
                pr?.let { if (timed) formatSeconds(it.value.toInt()) else formatWeightValue(display(it.value)) } ?: "—",
                if (timed) "" else unit.label,
                pr?.let { if (timed) shortDate(it.time) else "${it.reps} reps · ${shortDate(it.time)}" },
                null,
                Modifier.weight(1f).fillMaxHeight(),
            )
            val delta = if (latest != null && previous != null) latest.value - previous.value else null
            StatCard(
                if (timed) "Latest hold" else "Latest top set",
                latest?.let { if (timed) formatSeconds(it.value.toInt()) else formatWeightValue(display(it.value)) } ?: "—",
                if (timed) "" else unit.label,
                null,
                delta?.takeIf { abs(it) > 0.01 }?.let { d -> (if (d > 0) "+" else "−") + if (timed) "${abs(d).toInt()} s" else "${formatWeightValue(abs(display(d)))} ${unit.label}" },
                Modifier.weight(1f).fillMaxHeight(),
            )
        }
        GlassCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
            Segmented(ChartRange.entries.map { it.label }, range.ordinal, { range = ChartRange.entries[it] }, Modifier.fillMaxWidth(), height = 36.dp, fill = true)
            Spacer(Modifier.height(14.dp))
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { Mute("Log a session to see your trend.") }
            } else {
                ProgressChart(shown, timed, unit)
            }
            Mute(if (timed) "Longest hold per session." else "Capsules show reps on the top set.", Modifier.padding(start = 4.dp, top = 6.dp), size = T.Caption)
        }
        val sessions = remember(own) { own.filter { it.finishedAt != null && !it.deleted && it.entries.any { e -> e.exerciseId == exerciseId && !e.isSkipped } }.sortedByDescending { workoutTime(it) } }
        Row(Modifier.padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
            Txt("Session history", Modifier.weight(1f), size = T.Subhead, weight = Bold)
            Mute(plural(sessions.size, "session"), weight = Semibold)
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            sessions.take(30).forEach { w ->
                val entry = w.entries.first { it.exerciseId == exerciseId && !it.isSkipped }
                // Each session reads in the shape it was logged in.
                val entryTimed = entry.isTimed
                GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 12.dp), onClick = { nav.push(Route.WorkoutDetail(w.id)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt("${shortDate(workoutTime(w))} · ${w.routineName}", Modifier.weight(1f), size = T.Body, weight = Bold, maxLines = 1)
                        Icon(Icons.ChevronRight, c.mute, size = 18.dp)
                    }
                    Spacer(Modifier.height(2.dp))
                    // As in the mockup: numbered set lines with a dotted leader, the top set in forest.
                    val done = entry.sets.filter { it.done }
                    // The top set as History marks it: the best working set, or the longest hold. Warm-ups never count.
                    val topId = if (entryTimed) {
                        done.filter { it.tag != SetTag.Warmup && (it.seconds ?: 0) > 0 }.maxByOrNull { it.seconds ?: 0 }?.id
                    } else {
                        bestSetIn(listOf(w), exerciseId, Int.MAX_VALUE)?.let { b -> done.firstOrNull { it.tag != SetTag.Warmup && toKg(it.weight, it.unit) == b.weightKg && it.reps == b.reps }?.id }
                    }
                    done.forEachIndexed { i, s ->
                        val v = convert(s.weight, s.unit, unit)
                        SetLine("${i + 1}", if (entryTimed) formatSeconds(s.seconds ?: 0) else "${formatWeightValue(v)} ${unit.label} × ${s.reps}", strong = s.id == topId)
                    }
                }
            }
        }
        Spacer(Modifier.height(48.dp))
    }

    ActionSheet(
        open = menu,
        title = name,
        actions = listOf(
            // Each opening starts from the current name, not a cancelled draft.
            SheetAction("Rename", Icons.Pencil) { draft = name; renaming = true },
            SheetAction(if (timed) "Track as reps" else "Track as time (seconds)", Icons.Timer) {
                exercise?.let { e -> scope.launch { session.repository.saveExercise(e.id, e.name, !timed) } }
            },
            SheetAction("Archive", Icons.Trash, destructive = true) { archive = true },
        ),
        onDismiss = { menu = false },
    )
    BottomSheet(open = renaming, onDismiss = { renaming = false }, title = "Rename exercise") {
        Txt("Rename exercise", Modifier.padding(horizontal = 6.dp), size = T.SheetTitle, weight = ExtraBold)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().milky(RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 16.dp)) {
            BareField(draft, { draft = it.take(MAX_EXERCISE_NAME) }, Modifier.fillMaxWidth(), placeholder = "Exercise name", imeAction = androidx.compose.ui.text.input.ImeAction.Done, label = "Exercise name")
        }
        val newName = draft.trim()
        // Two of your lifts with one name would be ambiguous wherever exercises are matched by name.
        // Other people's lifts don't count: the library is shared by every account.
        val clash = nameClash(exercises, session.user.id, exerciseId, newName)
        clash?.let { ErrorText("There's already a lift called ${it.name}.", Modifier.padding(8.dp)) }
        Spacer(Modifier.height(14.dp))
        PrimaryButton("Save", enabled = newName.isNotEmpty() && clash == null) {
            // Routine items carry their own copy of the name, so the rename reaches them too.
            if (newName != exercise?.name) exercise?.let { e -> scope.launch { session.repository.renameExercise(e.id, newName) } }
            renaming = false
        }
    }
    ConfirmSheet(
        open = archive,
        title = "Archive $name?",
        message = "It leaves your library and routine pickers. Past workouts keep it.",
        confirm = "Archive",
        onConfirm = { scope.launch { session.repository.deleteExercise(exerciseId); nav.pop() } },
        onDismiss = { archive = false },
    )
}

@Composable
private fun SetLine(label: String, value: String, strong: Boolean) {
    val c = Volum.colors
    val line = c.line
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, Modifier.width(20.dp), size = T.Label, color = c.mute, tabular = true)
        Box(Modifier.weight(1f).padding(horizontal = 8.dp).height(10.dp).drawBehind {
            drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx())), cap = StrokeCap.Round)
        })
        Txt(value, size = T.Label, weight = if (strong) Bold else Medium, color = if (strong) c.accent else c.ink, tabular = true)
    }
}

@Composable
private fun StatCard(label: String, value: String, unit: String, sub: String?, chip: String?, modifier: Modifier) {
    val c = Volum.colors
    GlassCard(modifier, shape = RoundedCornerShape(24.dp), padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Mute(label, size = T.Caption, weight = Semibold)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Txt(value, size = T.Figure, weight = ExtraBold, tracking = (-0.02).em, tabular = true)
            if (unit.isNotEmpty()) Txt(" $unit", Modifier.padding(bottom = 3.dp), size = T.Small, color = c.mute, weight = Semibold)
        }
        sub?.let { Mute(it, size = T.Caption) }
        chip?.let { Spacer(Modifier.height(4.dp)); Tag(it) }
    }
}

/** Top set per session: a forest line over milky rep capsules, tap a point for its numbers. */
@Composable
private fun ProgressChart(points: List<ChartPoint>, timed: Boolean, unit: WeightUnit) {
    val c = Volum.colors
    val measurer = rememberTextMeasurer()
    val labelStyle = textStyle(T.Micro, Semibold, c.mute)
    var selected by remember(points) { mutableStateOf(points.lastIndex) }
    val values = points.map { if (timed) it.value else convert(it.value, WeightUnit.Kg, unit) }
    val maxV = values.max()
    val minV = values.min()
    val pad = ((maxV - minV) * 0.15).coerceAtLeast(if (timed) 5.0 else 2.5)
    val top = maxV + pad
    val bottom = (minV - pad).coerceAtLeast(0.0)
    fun spoken(i: Int): String {
        val p = points[i]
        return if (timed) "${formatSeconds(p.value.toInt())} on ${shortDate(p.time)}" else "${formatWeightValue(values[i])} ${unit.label} for ${p.reps} reps on ${shortDate(p.time)}"
    }
    val trend = "Top set per session, ${plural(points.size, "session")}: from ${spoken(0)} to ${spoken(points.lastIndex)}."
    Box(Modifier.fillMaxWidth().height(200.dp)) {
        Canvas(
            Modifier.fillMaxSize()
                // Points are picked by tap; screen readers hear the trend and step through points.
                .semantics {
                    contentDescription = trend
                    customActions = listOf(
                        CustomAccessibilityAction("Previous session") { if (selected > 0) { selected--; true } else false },
                        CustomAccessibilityAction("Next session") { if (selected < points.lastIndex) { selected++; true } else false },
                    )
                }
                .pointerInput(points) {
                    detectTapGestures { pos ->
                        val left = 34.dp.toPx()
                        val step = if (points.size > 1) (size.width - left - 16.dp.toPx()) / (points.size - 1) else 0f
                        selected = if (points.size == 1) 0 else ((pos.x - left) / step).roundToInt().coerceIn(0, points.lastIndex)
                    }
                },
        ) {
            val left = 34.dp.toPx()
            val right = size.width - 16.dp.toPx()
            val topY = 12.dp.toPx()
            val baseY = size.height - 24.dp.toPx()
            fun y(v: Double) = (baseY - (v - bottom) / (top - bottom) * (baseY - topY)).toFloat()
            fun x(i: Int) = if (points.size == 1) (left + right) / 2 else left + i * (right - left) / (points.size - 1)
            // Grid and axis labels
            for (k in 0..2) {
                val v = bottom + (top - bottom) * k / 2
                val gy = y(v)
                drawLine(c.line, Offset(left, gy), Offset(right, gy), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())))
                val text = if (timed) "${v.roundToInt()}" else formatWeightValue(v)
                drawText(measurer, text, Offset(0f, gy - 7.dp.toPx()), labelStyle)
            }
            // Rep capsules
            val capW = 18.dp.toPx().coerceAtMost((right - left) / points.size * 0.6f)
            points.forEachIndexed { i, p ->
                val px = x(i)
                val py = y(values[i])
                drawRoundRect(if (i == selected) c.soft else c.row, Offset(px - capW / 2, py - capW / 2), Size(capW, baseY - py + capW / 2), CornerRadius(capW / 2))
            }
            // Line
            val path = Path()
            points.indices.forEach { i -> if (i == 0) path.moveTo(x(i), y(values[i])) else path.lineTo(x(i), y(values[i])) }
            drawPath(path, c.accent, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            points.indices.forEach { i ->
                val center = Offset(x(i), y(values[i]))
                if (i == selected) {
                    drawCircle(c.accent, 7.dp.toPx(), center)
                    drawCircle(Color.White, 7.dp.toPx(), center, style = Stroke(3.dp.toPx()))
                } else {
                    drawCircle(Color.White, 5.dp.toPx(), center)
                    drawCircle(c.accent, 5.dp.toPx(), center, style = Stroke(2.5.dp.toPx()))
                }
            }
            // X labels: first, middle and last
            listOf(0, points.size / 2, points.lastIndex).distinct().forEach { i ->
                val d = localDateOf(points[i].time)
                val t = "${monthShort(d)} ${d.day}"
                val m = measurer.measure(t, labelStyle)
                drawText(m, topLeft = Offset((x(i) - m.size.width / 2).coerceIn(0f, size.width - m.size.width), size.height - m.size.height.toFloat()))
            }
        }
        val p = points.getOrNull(selected)
        if (p != null) {
            val label = if (timed) "${formatSeconds(p.value.toInt())} · ${shortDate(p.time)}" else "${formatWeightValue(values[selected])} ${unit.label} × ${p.reps} · ${monthShort(localDateOf(p.time))} ${localDateOf(p.time).day}"
            Box(
                Modifier.align(Alignment.TopCenter).liquidGlass(RoundedCornerShape(14.dp))
                    .clearAndSetSemantics { contentDescription = spoken(selected); liveRegion = LiveRegionMode.Polite }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Txt(label, size = T.Caption, weight = Bold, tabular = true, align = TextAlign.Center)
            }
        }
    }
}
