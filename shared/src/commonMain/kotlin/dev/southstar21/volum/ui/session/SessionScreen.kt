package dev.southstar21.volum.ui.session

import androidx.compose.foundation.horizontalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import dev.southstar21.volum.ui.theme.Durations
import dev.southstar21.volum.ui.theme.Springs
import dev.southstar21.volum.ui.theme.T
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.southstar21.volum.model.Exercise
import dev.southstar21.volum.model.SetEntry
import dev.southstar21.volum.model.WeightUnit
import dev.southstar21.volum.model.Workout
import dev.southstar21.volum.model.WorkoutEntry
import dev.southstar21.volum.model.WorkoutStatus
import dev.southstar21.volum.progress.volumeKg
import dev.southstar21.volum.platform.Haptic
import dev.southstar21.volum.platform.haptic
import dev.southstar21.volum.platform.hapticTick
import dev.southstar21.volum.ui.LocalApp
import dev.southstar21.volum.ui.LocalNav
import dev.southstar21.volum.ui.LocalSession
import dev.southstar21.volum.ui.Route
import dev.southstar21.volum.ui.exercisesState
import dev.southstar21.volum.ui.lastTopSetLabels
import dev.southstar21.volum.ui.routinesState
import dev.southstar21.volum.ui.components.ScreenTitle
import dev.southstar21.volum.ui.components.rememberLast
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.southstar21.volum.ui.rememberNow
import dev.southstar21.volum.ui.workoutsState
import dev.southstar21.volum.ui.components.ActionSheet
import dev.southstar21.volum.ui.components.Avatar
import dev.southstar21.volum.ui.components.CircleButton
import dev.southstar21.volum.ui.components.ConfirmSheet
import dev.southstar21.volum.ui.components.GlassCard
import dev.southstar21.volum.ui.components.Mute
import dev.southstar21.volum.ui.components.Pill
import dev.southstar21.volum.ui.components.PrimaryButton
import dev.southstar21.volum.ui.components.SectionTitle
import dev.southstar21.volum.ui.components.SheetAction
import dev.southstar21.volum.ui.components.Toolbar
import dev.southstar21.volum.ui.components.TopInset
import dev.southstar21.volum.ui.components.Txt
import dev.southstar21.volum.ui.components.accentGel
import dev.southstar21.volum.ui.components.liquidGlass
import dev.southstar21.volum.ui.theme.Bold
import dev.southstar21.volum.ui.theme.ExtraBold
import dev.southstar21.volum.ui.theme.Icon
import dev.southstar21.volum.ui.theme.Icons
import dev.southstar21.volum.ui.theme.Semibold
import dev.southstar21.volum.ui.theme.Volum
import dev.southstar21.volum.ui.theme.tappable
import dev.southstar21.volum.ui.theme.celebrate
import dev.southstar21.volum.ui.theme.AnimatedNumber
import kotlin.math.roundToInt
import dev.southstar21.volum.util.elapsedLabel
import dev.southstar21.volum.util.formatVolume
import dev.southstar21.volum.util.nowMillis
import dev.southstar21.volum.util.parseTime
import dev.southstar21.volum.util.plural
import kotlinx.coroutines.launch

/** The header, exercise chips and entry list shared by your session and a partner's. */
@Composable
fun SessionBody(
    workout: Workout,
    preferred: WeightUnit,
    lastLabels: Map<String, String>,
    actions: EntryActions,
    expandedId: String?,
    toolbar: @Composable () -> Unit,
    heading: @Composable () -> Unit,
    allDone: @Composable () -> Unit,
    bottomSpace: androidx.compose.ui.unit.Dp,
) {
    val c = Volum.colors
    val active = workout.entries.filterNot { it.isComplete() }
    val completed = workout.entries.filter { it.isComplete() }
    val current = active.firstOrNull()
    val shown = expandedId ?: current?.id
    // What was finished before this screen opened, so only exercises completed now celebrate.
    val alreadyDone = remember(workout.id) { completed.map { it.id }.toSet() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopInset(10.dp)
        toolbar()
        heading()
        val total = workout.entries.sumOf { it.sets.size }
        if (total > 0) {
            // How far through the workout you are, filling as sets are logged.
            val done = workout.entries.sumOf { e -> e.sets.count { it.done } }
            val progress by animateFloatAsState(done / total.toFloat(), Springs.settle(), label = "progress")
            Box(
                Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp).fillMaxWidth().height(6.dp).background(c.line, CircleShape).drawBehind {
                    if (progress > 0f) drawRoundRect(
                        Brush.horizontalGradient(listOf(c.accentTop, c.accentBottom)),
                        size = Size(size.width * progress, size.height),
                        cornerRadius = CornerRadius(size.height / 2),
                    )
                },
            )
        }
        if (workout.entries.isNotEmpty()) {
            val chips = rememberScrollState()
            val chipBounds = remember { mutableMapOf<String, Pair<Int, Int>>() }
            var chipsWidth by remember { mutableStateOf(0) }
            // The current exercise's chip slides into view as the workout moves on.
            LaunchedEffect(shown, chipsWidth) {
                val (x, w) = chipBounds[shown] ?: return@LaunchedEffect
                chips.animateScrollTo((x + w / 2 - chipsWidth / 2).coerceIn(0, chips.maxValue), Springs.settle())
            }
            Row(
                Modifier.padding(top = 14.dp).onSizeChanged { chipsWidth = it.width }.horizontalScroll(chips).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                workout.entries.forEach { e ->
                    val done = e.isComplete()
                    Pill(
                        Modifier.onPlaced { chipBounds[e.id] = it.positionInParent().x.toInt() to it.size.width },
                        height = 44.dp, selected = e.id == shown && !done, onClick = { hapticTick(); actions.onExpand(e) },
                    ) {
                        if (done) Icon(if (e.isSkipped) Icons.Skip else Icons.Check, c.mute, size = 13.dp)
                        Txt(
                            chipName(e.name),
                            size = T.Small,
                            weight = if (e.id == shown) Bold else Semibold,
                            color = if (e.id == shown && !done) c.onAccent else if (done) c.mute else c.ink,
                        )
                    }
                }
            }
        }
        // Spacing lives inside each item (not between them), so an item folding away takes its gap too.
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
            if (workout.entries.isEmpty()) {
                GlassCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Txt("No exercises in this workout", size = T.BodyLarge, weight = Bold)
                    Mute("Add one with the + below.", size = T.Small)
                }
            }
            AnimatedVisibility(
                active.isEmpty() && workout.entries.isNotEmpty(),
                enter = fadeIn(tween(Durations.Enter, delayMillis = Durations.Exit)) + expandVertically(Springs.settle(), expandFrom = Alignment.Top),
                exit = fadeOut(tween(Durations.Exit)) + shrinkVertically(Springs.settle(), shrinkTowards = Alignment.Top),
            ) { Box(Modifier.padding(bottom = 12.dp)) { allDone() } }
            // A finished exercise folds out of the list (it reappears under Completed); one added rises in.
            AnimatedItems(active, key = { it.id }) { e ->
                ActiveEntryCard(e, expanded = e.id == shown, lastLabel = lastLabels[e.exerciseId], preferred = preferred, actions = actions, modifier = Modifier.padding(bottom = 12.dp))
            }
        }
        AnimatedVisibility(completed.isNotEmpty(), enter = fadeIn() + expandVertically(Springs.settle()), exit = fadeOut() + shrinkVertically(Springs.settle())) {
            Column {
                SectionTitle("Completed")
                Box(Modifier.padding(horizontal = 16.dp)) {
                    CompletedGroup(completed, preferred, expandedId?.takeIf { id -> completed.any { it.id == id } }, actions, alreadyDone)
                }
            }
        }
        Spacer(Modifier.height(bottomSpace))
    }
}

@Composable
fun SessionScreen(workoutId: String) {
    val session = LocalSession.current
    val app = LocalApp.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val prefs by app.prefs.collectAsStateWithLifecycle()
    val workouts by session.workoutsState()
    val exercises by session.exercisesState()
    val routines by session.routinesState()
    val partnerSnap by session.partner.snapshot.collectAsStateWithLifecycle()
    val live = workouts[workoutId]
    val liveActive = live != null && !live.deleted && live.status == WorkoutStatus.Active
    val repo = session.repository
    val c = Volum.colors
    // Finishing or discarding leaves the screen: while it slides away it keeps showing the workout as
    // it was, not the "ended" state the store now reports. A plain holder, written only from data.
    var leaving by remember { mutableStateOf(false) }
    val lastActive = remember { arrayOfNulls<Workout>(1) }
    if (liveActive) lastActive[0] = live
    val workout = if (liveActive) live else if (leaving) lastActive[0] else null
    fun leave(commit: suspend () -> Unit) {
        // Once: the buttons stay tappable while the screen slides away, and a second finish or discard
        // would write again.
        if (leaving) return
        leaving = true
        // A finished or discarded workout's running holds go with it.
        lastActive[0]?.let { w -> Stopwatches.forget(w.entries.flatMap { e -> e.sets.map { it.id } }) }
        // The session's scope, not this screen's: the write must finish after the screen is gone.
        session.scope.launch { commit() }
        nav.pop()
    }
    // Holds of sets removed here or on another device, or of a workout that ended elsewhere, are dropped.
    ForgetRemovedHolds(remember(workout) { workout?.entries?.flatMap { e -> e.sets.map { it.id } }.orEmpty().toSet() })

    if (workout == null) {
        Column(Modifier.fillMaxSize()) {
            TopInset(10.dp)
            Toolbar(leading = { CircleButton(Icons.ChevronLeft, "Back") { nav.pop() } })
            Column(Modifier.fillMaxWidth().padding(top = 80.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Txt("This workout has ended", size = T.SheetTitle, weight = ExtraBold, align = TextAlign.Center)
                Mute("It was finished or discarded on another device.", Modifier.padding(top = 6.dp), align = TextAlign.Center)
            }
        }
        return
    }

    var expandedId by remember { mutableStateOf<String?>(null) }
    var editTarget by remember { mutableStateOf<Pair<WorkoutEntry, SetEntry>?>(null) }
    var menuEntry by remember { mutableStateOf<WorkoutEntry?>(null) }
    var renameEntry by remember { mutableStateOf<WorkoutEntry?>(null) }
    var deleteEntry by remember { mutableStateOf<WorkoutEntry?>(null) }
    var addOpen by remember { mutableStateOf(false) }
    var workoutMenu by remember { mutableStateOf(false) }
    var renameWorkout by remember { mutableStateOf(false) }
    var finishPrompt by remember { mutableStateOf(false) }
    var discardPrompt by remember { mutableStateOf(false) }

    // The workout [entries] was made from, read now with them: by the time the save runs, a sync may
    // have changed the store, and the save replays the edit onto that (see Repository.saveWorkout).
    fun save(entries: List<WorkoutEntry>) {
        val basis = workouts[workoutId] ?: workout
        scope.launch { repo.updateWorkout(basis, entries) }
    }
    fun latest() = (workouts[workoutId] ?: workout).entries
    fun replaceSet(entry: WorkoutEntry, set: SetEntry) = latest().map { e -> if (e.id == entry.id) e.copy(sets = e.sets.map { if (it.id == set.id) set else it }) else e }

    val routine = workout.routineId?.let { routines[it] }
    val library = remember(exercises) { exercises.values.filter { !it.deleted } }
    val lastLabels = remember(workouts) { repo.lastTopSetLabels(workout.entries.map { it.exerciseId to it.isTimed }) }

    val actions = EntryActions(
        onSet = { entry, set -> save(replaceSet(entry, set)) },
        onToggleDone = { entry, set ->
            haptic(toggleHaptic(latest(), entry, set))
            val next = replaceSet(entry, set.copy(done = !set.done))
            save(next)
            // Folding a finished exercise moves focus to the next one.
            if (!set.done && next.firstOrNull { it.id == entry.id }?.isComplete() == true) expandedId = null
        },
        onEditSet = { entry, set -> editTarget = entry to set },
        onAddSet = { entry ->
            val set = nextSet(entry, prefs.unit)
            save(latest().map { if (it.id == entry.id) it.copy(sets = it.sets + set) else it })
        },
        onMenu = { menuEntry = it },
        onExpand = { e -> expandedId = if (expandedId == e.id) null else e.id },
    )

    val totalSets = workout.entries.sumOf { it.sets.size }
    val doneSets = workout.entries.sumOf { e -> e.sets.count { it.done } }
    val doneEntries = workout.entries.count { it.isComplete() }
    val unlogged = workout.entries.filterNot { it.isSkipped }.sumOf { e -> e.sets.count { !it.done } }
    val partner = partnerSnap.partner
    val partnerWorkout = partnerSnap.activeWorkout

    Box(Modifier.fillMaxSize()) {
        SessionBody(
            workout = workout,
            preferred = prefs.unit,
            lastLabels = lastLabels,
            actions = actions,
            expandedId = expandedId,
            bottomSpace = 120.dp,
            toolbar = {
                Toolbar(
                    leading = { CircleButton(Icons.ChevronLeft, "Leave workout, it stays saved") { nav.pop() } },
                    center = {
                        Pill {
                            Icon(Icons.Timer, c.ink, size = 16.dp)
                            ElapsedTxt(workout.startedAt, size = T.Body, weight = Bold)
                        }
                    },
                    trailing = { CircleButton(Icons.More, "Workout options") { workoutMenu = true } },
                )
            },
            heading = {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp).tappable(highlight = false, onClickLabel = "Rename workout") { renameWorkout = true }) {
                    ScreenTitle(workout.routineName)
                    Mute("$doneSets of $totalSets sets · $doneEntries of ${plural(workout.entries.size, "exercise")}", size = T.Body)
                }
            },
            allDone = {
                GlassCard(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(60.dp).celebrate(true, c.accent, initial = false).accentGel(CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Check, c.onAccent, size = 28.dp) }
                        Spacer(Modifier.height(12.dp))
                        Txt("All exercises completed", size = T.SheetTitle, weight = ExtraBold)
                        Mute("Ready to finish? Tap below to record your workout.", Modifier.padding(top = 4.dp), size = T.Label, align = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AnimatedNumber(volumeKg(workout)) { Stat(formatVolume(it), "volume", Modifier.weight(1f)) }
                            AnimatedNumber(doneSets.toDouble()) { Stat("${it.roundToInt()}", "sets", Modifier.weight(1f)) }
                            ElapsedMinutesStat(workout.startedAt, Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(16.dp))
                        PrimaryButton("Finish workout", haptic = false) { haptic(Haptic.WorkoutDone); leave { repo.finishWorkout(workouts[workoutId] ?: workout) } }
                    }
                }
            },
        )

        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleButton(Icons.Plus, "Add exercise", size = 58.dp, iconSize = 22.dp) { addOpen = true }

            if (partner != null && partnerWorkout != null) {
                Box(Modifier.size(58.dp).tappable { nav.push(Route.PartnerSession(partnerWorkout.id)) }.semantics { contentDescription = "Open ${partner.displayName}'s live workout" }) {
                    Avatar(partner.displayName, size = 58.dp)
                    Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(9.dp).accentGel(CircleShape))
                }
            }
            Box(
                Modifier.weight(1f).height(58.dp).tappable {
                    if (unlogged > 0) { haptic(Haptic.Tap); finishPrompt = true }
                    else { haptic(Haptic.WorkoutDone); leave { repo.finishWorkout(workouts[workoutId] ?: workout) } }
                }.accentGel(RoundedCornerShape(29.dp)),
                contentAlignment = Alignment.Center,
            ) { Txt("Finish workout", size = T.BodyLarge, weight = Bold, color = c.onAccent) }
        }
    }

    // ---- Sheets ----
    SetEditSheet(
        editTarget, prefs.unit,
        onSave = { entry, set -> save(replaceSet(entry, set)) },
        onRemove = { entry, set -> save(latest().map { e -> if (e.id == entry.id) e.copy(sets = e.sets.filterNot { it.id == set.id }) else e }) },
        onDismiss = { editTarget = null },
    )
    val m = rememberLast(menuEntry)
    ActionSheet(
        open = menuEntry != null,
        title = m?.name,
        subtitle = m?.let { e -> "${e.sets.count { it.done }} of ${e.sets.size} sets done" + (lastLabels[e.exerciseId]?.let { " · last $it" } ?: "") },
        actions = if (m == null) emptyList() else listOf(
            SheetAction("Rename", Icons.Pencil, subtitle = "Or swap for another exercise") { renameEntry = m },
            SheetAction(if (m.isSkipped) "Unskip" else "Skip for today", Icons.Skip, subtitle = if (m.isSkipped) null else "Stays in the routine") {
                save(latest().map { if (it.id == m.id) it.copy(skipped = !m.isSkipped) else it })
            },
            SheetAction("Delete…", Icons.Trash, destructive = true) { deleteEntry = m },
        ),
        onDismiss = { menuEntry = null },
    )
    RenameExerciseSheet(
        entry = renameEntry,
        exercises = library,
        canUpdateRoutine = routine != null,
        ownerId = session.user.id,
        onSave = { name, link, updateRoutine, updateLibrary ->
            val e = renameEntry ?: return@RenameExerciseSheet
            scope.launch {
                // From the entry as it is now, not as the sheet opened: a partner may have logged a set since.
                val entries = latest().map { cur ->
                    when {
                        cur.id != e.id -> cur
                        // Relinked to a lift tracked the other way (time or reps), its sets change shape too.
                        link != null -> cur.copy(exerciseId = link.id, name = link.name, timed = link.timed, sets = reshapedSets(cur, link.isTimed))
                        else -> cur.copy(name = name)
                    }
                }
                val updated = entries.firstOrNull { it.id == e.id } ?: return@launch
                repo.updateWorkout(workouts[workoutId] ?: workout, entries)
                // Only your own exercise: the library here also holds your partner's.
                if (updateLibrary && link == null) exercises[e.exerciseId]?.takeIf { it.owner == session.user.id }?.let { repo.saveExercise(it.id, name, it.timed) }
                if (updateRoutine && routine != null) {
                    repo.saveRoutine(routine.id, routine.name, routine.items.map { item ->
                        if (item.exerciseId == e.exerciseId) item.copy(exerciseId = updated.exerciseId, name = updated.name) else item
                    })
                }
            }
        },
        onDismiss = { renameEntry = null },
    )
    val deleting = rememberLast(deleteEntry)
    DeleteExerciseSheet(
        entry = deleteEntry,
        routineName = routine?.name,
        routineKeepsOthers = routine?.items?.any { it.exerciseId != deleting?.exerciseId } == true,
        onDiscardWorkout = if (workout.entries.size <= 1) ({ leave { repo.deleteWorkout(workout.id) } }) else null,
        onDelete = { e, fromRoutine ->
            scope.launch {
                repo.updateWorkout(workouts[workoutId] ?: workout, latest().filterNot { it.id == e.id })
                if (fromRoutine && routine != null) repo.saveRoutine(routine.id, routine.name, routine.items.filterNot { it.exerciseId == e.exerciseId })
            }
        },
        onDismiss = { deleteEntry = null },
    )
    // Built only while the sheet is open (not on every set tap behind it), and kept as it closes.
    val addData = if (addOpen) {
        remember(workouts, library) {
            recentExercises(workouts.values.toList(), library, session.user.id) to session.lastTopSetLabels(library)
        }
    } else {
        null
    }
    val shownAddData = rememberLast(addData)
    AddExerciseSheet(
        open = addOpen,
        exercises = library,
        recent = shownAddData?.first.orEmpty(),
        lastLabels = shownAddData?.second.orEmpty(),
        onPick = { ex -> save(latest() + repo.buildAddedEntry(ex.id, ex.name, ex.isTimed, prefs.unit, fromHistory = true)) },
        onCreate = { name ->
            scope.launch {
                val ex = repo.findOrCreateExercise(name)
                repo.updateWorkout(workouts[workoutId] ?: workout, latest() + repo.buildAddedEntry(ex.id, ex.name, ex.isTimed, prefs.unit, fromHistory = true))
            }
        },
        onDismiss = { addOpen = false },
    )
    ActionSheet(
        open = workoutMenu,
        title = workout.routineName,
        actions = listOf(
            SheetAction("Rename workout", Icons.Pencil) { renameWorkout = true },
            SheetAction("Discard workout", Icons.Trash, destructive = true) { discardPrompt = true },
        ),
        onDismiss = { workoutMenu = false },
    )
    RenameWorkoutSheet(
        open = renameWorkout,
        current = workout.routineName,
        hasRoutine = routine != null,
        onSave = { name, alsoRoutine ->
            scope.launch {
                repo.updateWorkout(workouts[workoutId] ?: workout, latest(), routineName = name)
                if (alsoRoutine && routine != null) repo.saveRoutine(routine.id, name, routine.items)
            }
        },
        onDismiss = { renameWorkout = false },
    )
    ActionSheet(
        open = finishPrompt,
        title = "${plural(unlogged, "set")} ${if (unlogged == 1) "isn't" else "aren't"} logged",
        subtitle = "Unlogged sets won't count.",
        actions = listOf(
            SheetAction("Log them", Icons.Check) {},
            SheetAction("Finish anyway", Icons.ArrowRight) { haptic(Haptic.WorkoutDone); leave { repo.finishWorkout(workouts[workoutId] ?: workout) } },
        ),
        onDismiss = { finishPrompt = false },
    )
    ConfirmSheet(
        open = discardPrompt,
        title = "Discard this workout?",
        message = "Its sets won't be saved to your history.",
        confirm = "Discard workout",
        onConfirm = { leave { repo.deleteWorkout(workout.id) } },
        onDismiss = { discardPrompt = false },
    )
}

/**
 * A column's items that fold in when added and out when removed, instead of appearing and vanishing.
 * A removed item is drawn from its last value, in its old place, until it has folded away. Items there
 * from the start don't animate.
 */
@Composable
fun <T> AnimatedItems(items: List<T>, key: (T) -> String, content: @Composable (T) -> Unit) {
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
    states.keys.retainAll(merged.toSet())
    merged.forEach { id ->
        androidx.compose.runtime.key(id) {
            val st = states.getOrPut(id) { MutableTransitionState(id in initial).apply { targetState = true } }
            st.targetState = id in ids
            val item = last[id]
            if (item != null) AnimatedVisibility(
                visibleState = st,
                enter = fadeIn(tween(Durations.Fade, delayMillis = Durations.Instant)) + expandVertically(Springs.settle(), expandFrom = Alignment.Top),
                exit = fadeOut(tween(Durations.Exit)) + shrinkVertically(Springs.settle(), shrinkTowards = Alignment.Top),
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
private fun ElapsedMinutesStat(startedAt: String, modifier: Modifier) {
    val now = rememberNow()
    val start = remember(startedAt) { parseTime(startedAt) }
    Stat("${elapsedMinutes(start, now)}", "minutes", modifier)
}

/** Whole minutes since [start], so an hour and four reads 64, not the "1:04" of the clock; never negative. */
internal fun elapsedMinutes(start: Long, now: Long): Long = ((now - start) / 60_000).coerceAtLeast(0)

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
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
