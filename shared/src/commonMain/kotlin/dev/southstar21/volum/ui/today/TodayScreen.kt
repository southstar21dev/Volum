package dev.southstar21.volum.ui.today

import androidx.compose.animation.core.tween
import dev.southstar21.volum.ui.theme.T
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.border
import androidx.compose.ui.draw.dropShadow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.southstar21.volum.data.SyncState
import dev.southstar21.volum.model.Routine
import dev.southstar21.volum.model.Workout
import dev.southstar21.volum.progress.Sentence
import dev.southstar21.volum.progress.buildDashboard
import dev.southstar21.volum.progress.countPrsThisWeek
import dev.southstar21.volum.ui.LocalApp
import dev.southstar21.volum.ui.LocalDockPadding
import dev.southstar21.volum.ui.LocalNav
import dev.southstar21.volum.ui.LocalSession
import dev.southstar21.volum.ui.Route
import dev.southstar21.volum.ui.exercisesState
import dev.southstar21.volum.ui.lastDoneLabel
import dev.southstar21.volum.ui.lastPerformed
import dev.southstar21.volum.ui.ownActives
import dev.southstar21.volum.ui.rememberNow
import dev.southstar21.volum.ui.rotationOrder
import dev.southstar21.volum.ui.routinesState
import dev.southstar21.volum.ui.workoutsState
import dev.southstar21.volum.ui.components.ActionSheet
import dev.southstar21.volum.ui.components.Avatar
import dev.southstar21.volum.ui.components.CircleButton
import dev.southstar21.volum.ui.components.ConfirmSheet
import dev.southstar21.volum.ui.components.GlassCard
import dev.southstar21.volum.ui.components.Mute
import dev.southstar21.volum.ui.components.PrimaryButton
import dev.southstar21.volum.ui.components.SectionTitle
import dev.southstar21.volum.ui.components.SheetAction
import dev.southstar21.volum.ui.components.TopInset
import dev.southstar21.volum.ui.components.Txt
import dev.southstar21.volum.ui.components.accentGel
import dev.southstar21.volum.ui.components.glassCard
import dev.southstar21.volum.ui.components.liquidGlass
import dev.southstar21.volum.ui.components.milky
import dev.southstar21.volum.ui.components.GlassBadge
import dev.southstar21.volum.ui.components.SheetActionRow
import dev.southstar21.volum.ui.components.rememberLast
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.southstar21.volum.util.weekdayName
import dev.southstar21.volum.ui.theme.Bold
import dev.southstar21.volum.ui.theme.ExtraBold
import dev.southstar21.volum.ui.theme.Icon
import dev.southstar21.volum.ui.theme.Icons
import dev.southstar21.volum.ui.theme.Semibold
import dev.southstar21.volum.ui.theme.Volum
import dev.southstar21.volum.ui.theme.tappable
import dev.southstar21.volum.ui.theme.animatedNumber
import dev.southstar21.volum.ui.theme.growUp
import dev.southstar21.volum.ui.theme.popIn
import dev.southstar21.volum.ui.theme.riseIn
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.graphics.graphicsLayer
import dev.southstar21.volum.ui.theme.textStyle
import dev.southstar21.volum.util.elapsedLabel
import dev.southstar21.volum.util.formatVolume
import dev.southstar21.volum.util.localDateOf
import dev.southstar21.volum.util.localDateTimeOf
import dev.southstar21.volum.util.monthShort
import dev.southstar21.volum.util.parseTime
import dev.southstar21.volum.util.plural
import kotlinx.coroutines.delay
import dev.southstar21.volum.ui.rememberTicking
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus
import kotlinx.datetime.plus

@Composable
fun TodayScreen() {
    val session = LocalSession.current
    val app = LocalApp.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val prefs by app.prefs.collectAsStateWithLifecycle()
    val user = session.user
    val routines by session.routinesState()
    val workouts by session.workoutsState()
    val exercises by session.exercisesState()
    val partner by session.partner.snapshot.collectAsStateWithLifecycle()
    val sync by session.sync.status.collectAsStateWithLifecycle()
    val now = rememberNow(30_000)
    val c = Volum.colors

    // Only rebuilt when the data changes, not on every clock tick or sync-status flip.
    val mine = remember(routines, user.id) { routines.values.filter { !it.deleted && it.owner == user.id } }
    val last = remember(workouts) { lastPerformed(workouts) }
    val actives = remember(workouts, user.id) { ownActives(workouts, user.id) }
    val ownActive = actives.firstOrNull()
    val ordered = remember(mine, last, ownActive?.routineId) {
        rotationOrder(mine, last).let { list ->
            val active = ownActive?.routineId?.let { id -> list.firstOrNull { it.id == id } }
            if (active != null) listOf(active) + (list - active) else list
        }
    }
    val ownWorkouts = remember(workouts, user.id) { workouts.values.filter { it.owner == user.id && !it.deleted } }
    val dashboard = rememberTicking(workouts, exercises, prefs.unit, tick = now) { at ->
        buildDashboard(workouts.values.toList(), user.id, exercises.mapValues { it.value.name }, prefs.unit, at)
    }

    var menuFor by remember { mutableStateOf<Routine?>(null) }
    var startPrompt by remember { mutableStateOf<Routine?>(null) }
    var deleteFor by remember { mutableStateOf<Routine?>(null) }
    var activeMenu by remember { mutableStateOf(false) }

    fun start(routine: Routine) {
        scope.launch {
            val w = session.repository.startSession(routine, prefs.unit)
            nav.push(Route.Session(w.id))
        }
    }
    fun requestStart(routine: Routine) {
        when {
            ownActive != null && routine.id == ownActive.routineId -> nav.push(Route.Session(ownActive.id))
            ownActive != null -> startPrompt = routine
            else -> start(routine)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopInset(22.dp)
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).riseIn(14f), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Txt(greeting(now), size = T.Title, weight = ExtraBold, tracking = T.TitleTracking)
                Spacer(Modifier.height(4.dp))
                ProgressSentence(dashboard?.sentence, mine.isEmpty())
            }
            SyncButton(sync.state) { scope.launch { session.sync.retryNow() } }
        }

        if (sync.state == SyncState.Offline) {
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth().height(48.dp).liquidGlass(RoundedCornerShape(24.dp)).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.CloudOff, c.ink, size = 20.dp)
                Txt("Offline.", size = T.Label, weight = Bold)
                Mute("Changes are saved on this phone.", size = T.Label, maxLines = 1)
            }
        }

        ownActive?.let { w ->
            LiveRow(
                title = "You're training",
                subtitle = "${w.routineName} · ${elapsedLabel(parseTime(w.startedAt), now)}" + if (actives.size > 1) " (+${actives.size - 1} more)" else "",
                avatar = { Box(Modifier.size(38.dp).accentGel(CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Play, c.onAccent, size = 14.dp) } },
                onClick = { nav.push(Route.Session(w.id)) },
                onMenu = { activeMenu = true },
            )
        }
        val partnerUser = partner.partner
        val partnerWorkout = partner.activeWorkout
        if (partnerUser != null && partnerWorkout != null) {
            LiveRow(
                title = "${partnerUser.displayName} is training",
                subtitle = "${partnerWorkout.routineName} · live now",
                avatar = { Avatar(partnerUser.displayName, size = 38.dp, warm = true) },
                onClick = { nav.push(Route.PartnerSession(partnerWorkout.id)) },
            )
        }

        WeekStrip(ownWorkouts, now)

        dashboard?.weeks?.takeIf { it.size > 1 }?.let { weeks ->
            val prs = rememberTicking(workouts, tick = now) { at -> countPrsThisWeek(workouts.values.toList(), user.id, at) }
            val today = localDateOf(now)
            val month = remember(ownWorkouts, today) { ownWorkouts.count { it.finishedAt != null && localDateOf(parseTime(it.finishedAt)).let { d -> d.year == today.year && d.month == today.month } } }
            VolumeCard(weeks.map { it.start to it.volumeKg }, prs, month)
        }

        if (mine.isEmpty()) {
            EmptyRoutines { nav.push(Route.RoutineEditor(null)) }
        } else {
            SectionTitle("My routines", trailing = "${mine.size}")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ordered.chunked(2).forEachIndexed { row, pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEachIndexed { i, routine ->
                            RoutineCard(
                                routine = routine,
                                subtitle = "${plural(routine.items.size, "exercise")} · ${lastDoneLabel(last[routine.id], now)}",
                                highlight = row == 0 && i == 0,
                                active = ownActive?.routineId == routine.id,
                                modifier = Modifier.weight(1f),
                                onOpen = { menuFor = routine },
                                onStart = { requestStart(routine) },
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        Spacer(Modifier.height(LocalDockPadding.current + 24.dp))
    }

    // ---- Sheets ----
    // Each keeps its subject while it slides away (see rememberLast).
    val menuRoutine = rememberLast(menuFor)
    val isActiveRoutine = menuRoutine != null && ownActive?.routineId == menuRoutine.id
    RoutineMenuSheet(
        routine = menuRoutine,
        open = menuFor != null,
        active = isActiveRoutine,
        onDismiss = { menuFor = null },
        onStart = { menuRoutine?.let { requestStart(it) } },
        onEdit = { menuRoutine?.let { nav.push(Route.RoutineEditor(it.id)) } },
        onDuplicate = { menuRoutine?.let { r -> scope.launch { session.repository.duplicateRoutine(r) } } },
        onDelete = { deleteFor = menuRoutine },
        onDiscard = { ownActive?.let { w -> scope.launch { session.repository.deleteWorkout(w.id) } } },
    )
    val deleting = rememberLast(deleteFor)
    ConfirmSheet(
        open = deleteFor != null,
        title = "Delete ${deleting?.name ?: "routine"}?",
        message = "Your workout history stays.",
        confirm = "Delete routine",
        onConfirm = { deleteFor?.let { r -> scope.launch { session.repository.deleteRoutine(r.id) } } },
        onDismiss = { deleteFor = null },
    )
    val starting = rememberLast(startPrompt)
    ActionSheet(
        open = startPrompt != null,
        title = "You're already training",
        subtitle = ownActive?.routineName,
        actions = listOfNotNull(
            ownActive?.let { w -> SheetAction("Resume ${w.routineName}", Icons.Play) { nav.push(Route.Session(w.id)) } },
            starting?.let { r ->
                SheetAction("Discard and start ${r.name}", Icons.Trash, destructive = true) {
                    scope.launch {
                        actives.forEach { session.repository.deleteWorkout(it.id) }
                        start(r)
                    }
                }
            },
        ),
        onDismiss = { startPrompt = null },
    )
    ActionSheet(
        open = activeMenu,
        title = ownActive?.routineName,
        actions = listOfNotNull(
            ownActive?.let { w -> SheetAction("Resume", Icons.Play) { nav.push(Route.Session(w.id)) } },
            if (actives.size > 1) SheetAction("Discard all ${actives.size} unfinished", Icons.Trash, destructive = true) {
                scope.launch { actives.forEach { session.repository.deleteWorkout(it.id) } }
            } else null,
            ownActive?.let { w -> SheetAction("Discard this workout", Icons.Trash, destructive = true) { scope.launch { session.repository.deleteWorkout(w.id) } } },
        ),
        onDismiss = { activeMenu = false },
    )
}

private fun greeting(now: Long): String = when (localDateTimeOf(now).hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
private fun SyncButton(state: SyncState, onRetry: () -> Unit) {
    val c = Volum.colors
    val (icon, tint, label) = when (state) {
        SyncState.Synced -> Triple(Icons.CloudCheck, c.ink, "All changes synced")
        SyncState.Pending -> Triple(Icons.CloudSync, c.ink, "Syncing")
        SyncState.Offline -> Triple(Icons.CloudOff, c.mute, "Offline")
        SyncState.Error -> Triple(Icons.CloudOff, c.danger, "Sync error, tap to retry")
    }
    if (state == SyncState.Pending) {
        // Turns only while a sync is running, which is brief.
        val spin by rememberInfiniteTransition(label = "sync").animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "spin")
        CircleButton(icon, label, tint = tint, iconModifier = Modifier.graphicsLayer { rotationZ = spin }, onClick = onRetry)
    } else {
        CircleButton(icon, label, tint = tint, onClick = onRetry)
    }
}

/** The dashboard's progress sentence: plain text with the key words in ink. */
@Composable
private fun ProgressSentence(sentence: Sentence?, noRoutines: Boolean) {
    val c = Volum.colors
    val lines: List<String> = when (sentence) {
        null -> listOf(if (noRoutines) "Let's set up your\nfirst routine." else "Start a routine to\nsee your progress.")
        is Sentence.Improving -> sentence.slides.map { s ->
            if (s.lead == "at") "${s.name} is up **${s.amount}**\nat ${s.value}" else "${s.name} is up\n**${s.amount}** ${s.lead} ${s.value}"
        }
        is Sentence.Finished -> sentence.slides.map { s -> "${s.name} is up\n**${s.amount}** ${if (s.lead == "at") "at" else s.lead} ${s.value}" }
        is Sentence.Matched -> listOf(if (sentence.kept) "You matched your last\n**${sentence.routineName}** on **${plural(sentence.matched, "lift")}**." else "**${sentence.routineName} done.**\nYou matched last session.")
        is Sentence.FirstRoutine -> listOf("**First ${sentence.routineName}\nlogged.**")
        is Sentence.Done -> listOf("**${sentence.routineName} done.**")
        is Sentence.Plateau -> listOf("Your progress has\n**plateaued a little.**")
        is Sentence.Break -> listOf("**Welcome back**\nfrom your break.")
        is Sentence.Returning -> listOf("**Welcome back.** ${plural(sentence.sinceBreak, "workout")}\nsince your break.")
    }
    val under: String? = when (sentence) {
        // PRs and this month's workouts already sit in the weekly volume card.
        is Sentence.Improving -> null
        is Sentence.Finished -> if (sentence.duration.isEmpty()) "${plural(sentence.improved, "lift")} beat last time on ${sentence.routineName}." else "${sentence.routineName} done in ${sentence.duration}. ${plural(sentence.improved, "lift")} beat last time."
        is Sentence.Matched -> if (sentence.kept) "Add a rep or a little weight to beat it." else sentence.duration.takeIf { it.isNotEmpty() }?.let { "Done in $it." }
        is Sentence.Done -> sentence.duration.takeIf { it.isNotEmpty() }?.let { "Done in $it." }
        is Sentence.FirstRoutine -> "Next time you'll see what improved."
        is Sentence.Plateau -> if (sentence.monthWorkouts == 0) null else "${plural(sentence.monthWorkouts, "workout")} this month."
        is Sentence.Break -> "Your last workout was ${sentence.ago}."
        is Sentence.Returning -> "Weights come back quickly. Keep going."
        null -> null
    }
    var index by remember(lines) { mutableIntStateOf(0) }
    LaunchedEffect(lines) {
        while (lines.size > 1) {
            delay(4_500)
            index = (index + 1) % lines.size
        }
    }
    AnimatedContent(lines.getOrElse(index) { lines.first() }, transitionSpec = {
        (fadeIn(tween(280, delayMillis = 200)) + slideInVertically(tween(320, delayMillis = 200)) { it / 5 }) togetherWith
            (fadeOut(tween(180)) + slideOutVertically(tween(180)) { -it / 6 }) using SizeTransform(clip = false)
    }, label = "sentence") { line ->
        BasicText(rich(line, c.mute, c.accent), style = textStyle(T.Heading, FontWeight.Medium, c.mute, lineHeight = 27.sp, tracking = (-0.01).em))
    }
    under?.let {
        Spacer(Modifier.height(6.dp))
        Mute(it, size = T.Small)
    }
}

private fun rich(text: String, base: Color, strong: Color): AnnotatedString = buildAnnotatedString {
    val parts = text.split("**")
    parts.forEachIndexed { i, part ->
        if (i % 2 == 1) withStyle(SpanStyle(color = strong, fontWeight = FontWeight.ExtraBold)) { append(part) }
        else withStyle(SpanStyle(color = base)) { append(part) }
    }
}

@Composable
private fun LiveRow(title: String, subtitle: String, avatar: @Composable () -> Unit, onClick: () -> Unit, onMenu: (() -> Unit)? = null) {
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).fillMaxWidth().tappable(onClick = onClick).glassCard(RoundedCornerShape(24.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        avatar()
        Column(Modifier.weight(1f)) {
            Txt(title, size = T.Body, weight = Bold, maxLines = 1)
            Mute(subtitle, size = T.Caption, maxLines = 1)
        }
        if (onMenu != null) CircleButton(Icons.More, "Workout options", size = 38.dp, iconSize = 18.dp, onClick = onMenu)
        // The whole row opens it: the chevron only says so.
        else GlassBadge(Icons.ChevronRight)
    }
}

@Composable
private fun WeekStrip(workouts: List<Workout>, now: Long) {
    val c = Volum.colors
    val today = localDateOf(now)
    val monday = today.minus(DatePeriod(days = today.dayOfWeek.ordinal))
    val trained = remember(workouts) { workouts.mapNotNull { w -> w.finishedAt?.let { localDateOf(parseTime(it)) } ?: localDateOf(parseTime(w.startedAt)) }.toSet() }
    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).fillMaxWidth().glassCard(RoundedCornerShape(30.dp)).padding(5.dp)) {
        for (i in 0 until 7) {
            val day = monday.plus(DatePeriod(days = i))
            val isToday = day == today
            val future = day > today
            val spoken = "${weekdayName(day)} ${day.day}" + when {
                isToday && day in trained -> ", today, trained"
                isToday -> ", today"
                day in trained -> ", trained"
                else -> ""
            }
            Column(
                Modifier.weight(1f).heightIn(min = 60.dp).then(if (isToday) Modifier.liquidGlass(RoundedCornerShape(22.dp)) else Modifier)
                    .clearAndSetSemantics { contentDescription = spoken }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Txt("MTWTFSS"[i].toString(), size = T.Micro, weight = Semibold, color = if (isToday) c.accent else c.mute)
                Txt(day.day.toString(), size = T.BodyLarge, weight = Bold, color = when { isToday -> c.accent; future -> c.ink.copy(alpha = 0.45f); else -> c.ink }, tabular = true)
                Box(Modifier.padding(top = 2.dp).size(4.dp).popIn(260L + i * 40L).clip(CircleShape).background(if (day in trained) c.accent else Color.Transparent))
            }
        }
    }
}

@Composable
private fun VolumeCard(weeks: List<Pair<kotlinx.datetime.LocalDate, Double>>, prs: Int, monthWorkouts: Int) {
    val c = Volum.colors
    val max = weeks.maxOf { it.second }.coerceAtLeast(1.0)
    GlassCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Txt("Weekly volume", size = T.BodyLarge, weight = Bold)
                Mute(listOfNotNull(if (prs > 0) "${plural(prs, "PR")} this week" else null, "${plural(monthWorkouts, "workout")} this month").joinToString(" · "), size = T.Caption)
            }
            val text = formatVolume(animatedNumber(weeks.sumOf { it.second }))
            Row(verticalAlignment = Alignment.Bottom) {
                Txt(text.substringBeforeLast(' '), size = T.Heading, weight = ExtraBold, tabular = true)
                Txt(" " + text.substringAfterLast(' '), Modifier.padding(bottom = 2.dp), size = T.Caption, color = c.mute, weight = Semibold)
            }
        }
        Spacer(Modifier.height(40.dp))
        Row(Modifier.fillMaxWidth().height(108.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Bottom) {
            weeks.forEachIndexed { i, (_, vol) ->
                val fraction = (vol / max).toFloat().coerceIn(0.06f, 1f)
                val current = i == weeks.lastIndex
                Box(Modifier.weight(1f).fillMaxHeight(fraction), contentAlignment = Alignment.TopCenter) {
                    Box(
                        // The bar grows; the chip on it pops separately so it isn't squashed.
                        Modifier.matchParentSize().growUp(i).then(
                            if (current) currentBar(c.accentTop, c.accentBottom)
                            else Modifier.milky(RoundedCornerShape(20.dp)),
                        ),
                    )
                    if (current) {
                        // Unbounded both ways: a short bar mustn't squash the chip to its own height.
                        Box(Modifier.offset(y = (-30).dp).wrapContentSize(Alignment.TopCenter, unbounded = true).popIn(520L + i * 55L).liquidGlass(RoundedCornerShape(11.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                            Txt(formatVolume(vol), size = T.Micro, weight = ExtraBold, tabular = true, maxLines = 1)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            weeks.forEachIndexed { i, (start, _) ->
                val label = when {
                    i == weeks.lastIndex -> "Now"
                    i == 0 || start.day <= 7 -> "${monthShort(start)} ${start.day}"
                    else -> start.day.toString()
                }
                Txt(label, Modifier.weight(1f), size = T.Tiny, weight = if (i == weeks.lastIndex) ExtraBold else Semibold, color = if (i == weeks.lastIndex) c.ink else c.mute, align = TextAlign.Center, maxLines = 1)
            }
        }
    }
}

/** The current week's bar, as in the mockups: forest gel with fine white stripes. */
private fun currentBar(top: Color, bottom: Color): Modifier {
    val shape = RoundedCornerShape(20.dp)
    return Modifier
        .clip(shape)
        .background(Brush.verticalGradient(listOf(top, bottom)))
        .hatch(Color.White.copy(alpha = 0.3f), period = 6.dp, width = 1.5.dp)
}

/** Diagonal stripes over a filled bar, [period] apart measured across them like CSS repeating gradients. */
fun Modifier.hatch(stripe: Color, period: androidx.compose.ui.unit.Dp = 6.dp, width: androidx.compose.ui.unit.Dp = 1.6.dp): Modifier = drawBehind {
    val step = period.toPx() * 1.4142f
    val w = width.toPx()
    var x = -size.height
    while (x < size.width) {
        drawLine(stripe, androidx.compose.ui.geometry.Offset(x, size.height), androidx.compose.ui.geometry.Offset(x + size.height, 0f), w)
        x += step
    }
}

@Composable
private fun RoutineCard(routine: Routine, subtitle: String, highlight: Boolean, active: Boolean, modifier: Modifier, onOpen: () -> Unit, onStart: () -> Unit) {
    GlassCard(modifier, shape = RoundedCornerShape(24.dp), padding = androidx.compose.foundation.layout.PaddingValues(14.dp), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(routine.name, Modifier.weight(1f), size = T.Subhead, weight = ExtraBold, maxLines = 2)
            CircleButton(Icons.Play, if (active) "Resume ${routine.name}" else "Start ${routine.name}", size = 38.dp, iconSize = 14.dp, accent = highlight || active, onClick = onStart)
        }
        Spacer(Modifier.height(10.dp))
        Mute(if (active) "In progress" else subtitle, size = T.Caption, maxLines = 2)
    }
}

@Composable
private fun EmptyRoutines(onCreate: () -> Unit) {
    val c = Volum.colors
    GlassCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 24.dp)) {
        Row(Modifier.fillMaxWidth().height(120.dp).padding(horizontal = 30.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            listOf(0.4f, 0.6f, 0.8f, 1f).forEachIndexed { i, f ->
                Box(Modifier.weight(1f).fillMaxHeight(f).growUp(i).clip(RoundedCornerShape(20.dp)).background(if (i == 3) c.soft else c.line.copy(alpha = 0.5f)))
            }
        }
        Spacer(Modifier.height(22.dp))
        Txt("No routines yet", Modifier.fillMaxWidth(), size = T.SheetTitle, weight = ExtraBold, align = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Mute("Build a routine once. Every session after that starts pre-filled with last time's weights and reps.", Modifier.fillMaxWidth(), size = T.Label, align = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        PrimaryButton("Create routine", onClick = onCreate)
    }
}

/** Push A: the routine's actions. */
@Composable
fun RoutineMenuSheet(
    routine: Routine?,
    open: Boolean,
    active: Boolean,
    onDismiss: () -> Unit,
    onStart: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onDiscard: () -> Unit,
) {
    val c = Volum.colors
    dev.southstar21.volum.ui.components.BottomSheet(open = open, onDismiss = onDismiss, title = routine?.name) {
        val r = routine ?: return@BottomSheet
        Row(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp).liquidGlass(RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Dumbbell, c.accent, size = 20.dp) }
            Column {
                Txt(r.name, size = T.Heading, weight = ExtraBold, tracking = (-0.02).em)
                Mute(plural(r.items.size, "exercise"), size = T.Label)
            }
        }
        PrimaryButton(if (active) "Resume workout" else "Start workout") { onDismiss(); onStart() }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth().glassCard(RoundedCornerShape(24.dp)).padding(vertical = 4.dp)) {
            val rows = if (active) listOf(
                SheetAction("Discard this workout", Icons.Trash, destructive = true, onClick = onDiscard),
            ) else listOf(
                SheetAction("Edit routine", Icons.Pencil, onClick = onEdit),
                SheetAction("Duplicate", Icons.Copy, onClick = onDuplicate),
                SheetAction("Delete routine", Icons.Trash, destructive = true, onClick = onDelete),
            )
            rows.forEachIndexed { i, a ->
                if (i > 0) dev.southstar21.volum.ui.components.Divider(inset = 16.dp)
                SheetActionRow(a, plainIcon = true) { onDismiss(); a.onClick() }
            }
        }
    }
}
