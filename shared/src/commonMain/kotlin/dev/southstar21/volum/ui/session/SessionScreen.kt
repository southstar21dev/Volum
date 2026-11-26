package dev.southstar21.volum.ui.session

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.southstar21.volum.data.SyncState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.southstar21.volum.model.SetEntry
import dev.southstar21.volum.model.Workout
import dev.southstar21.volum.model.WorkoutEntry
import dev.southstar21.volum.model.WorkoutStatus
import dev.southstar21.volum.progress.volumeKg
import dev.southstar21.volum.platform.Haptic
import dev.southstar21.volum.platform.haptic
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
import dev.southstar21.volum.ui.workoutsState
import dev.southstar21.volum.ui.components.ActionSheet
import dev.southstar21.volum.ui.components.Avatar
import dev.southstar21.volum.ui.components.photoUrl
import dev.southstar21.volum.ui.components.CircleButton
import dev.southstar21.volum.ui.components.ConfirmSheet
import dev.southstar21.volum.ui.components.GlassCard
import dev.southstar21.volum.ui.components.Mute
import dev.southstar21.volum.ui.components.Pill
import dev.southstar21.volum.ui.components.PrimaryButton
import dev.southstar21.volum.ui.components.SheetAction
import dev.southstar21.volum.ui.components.Toolbar
import dev.southstar21.volum.ui.components.TopInset
import dev.southstar21.volum.ui.components.Txt
import dev.southstar21.volum.ui.components.accentGel
import dev.southstar21.volum.ui.theme.Bold
import dev.southstar21.volum.ui.theme.ExtraBold
import dev.southstar21.volum.ui.theme.Icon
import dev.southstar21.volum.ui.theme.Icons
import dev.southstar21.volum.ui.theme.Volum
import dev.southstar21.volum.ui.theme.tappable
import dev.southstar21.volum.ui.theme.celebrate
import dev.southstar21.volum.ui.theme.AnimatedNumber
import kotlin.math.roundToInt
import dev.southstar21.volum.util.formatVolume
import dev.southstar21.volum.util.newId
import dev.southstar21.volum.util.nowMillis
import dev.southstar21.volum.util.parseTime
import dev.southstar21.volum.util.plural
import kotlinx.coroutines.launch

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
    // The routine made to match this session (exercises added, removed, renamed or moved), when that's a
    // change: offered from the workout menu and when finishing. Your own routines only.
    val routineUpdate = routine?.takeIf { it.owner == session.user.id }?.let { r -> applySessionToRoutine(r.items, workout.entries, ::newId).takeIf { it != r.items } }
    val updateChanges = routineUpdate?.let { routineChanges(routine.items, it) }
    var orderPrompt by remember { mutableStateOf(false) }
    fun finish(saveOrder: Boolean) {
        haptic(Haptic.WorkoutDone)
        val r = routine
        val order = routineUpdate
        leave {
            if (saveOrder && r != null && order != null) repo.saveRoutine(r.id, r.name, order)
            repo.finishWorkout(workouts[workoutId] ?: workout)
        }
    }
    // Finishing a workout whose exercises differ from the routine first asks whether the routine should keep them.
    fun finish() {
        if (routine != null && routineUpdate != null) { haptic(Haptic.Tap); orderPrompt = true }
        else finish(saveOrder = false)
    }
    val library = remember(exercises) { exercises.values.filter { !it.deleted } }
    // Keyed on what the labels are made of: the exercises shown and the history (lastEntries() is the
    // same map until a completed workout changes), not on every set tapped in the active workout.
    val shownExercises = workout.entries.map { it.exerciseId to it.isTimed }
    val lastLabels = remember(repo.lastEntries(), shownExercises) { repo.lastTopSetLabels(shownExercises) }

    val actions = EntryActions(
        onSet = { entry, set -> save(replaceSet(entry, set)) },
        onNudge = { entry, setId, change -> scope.launch { repo.mutateSet(workoutId, entry.id, setId, change) } },
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
        onMove = { ids -> save(reorderActive(latest(), ids)) },
    )

    val totalSets = workout.entries.sumOf { it.sets.size }
    val doneSets = workout.entries.sumOf { e -> e.sets.count { it.done } }
    val doneEntries = workout.entries.count { it.isComplete() }
    val unlogged = workout.entries.filterNot { it.isSkipped }.sumOf { e -> e.sets.count { !it.done } }
    val partner = partnerSnap.partner
    val partnerWorkout = partnerSnap.activeWorkout
    // How this workout's sync is keeping up, for the slow-connection banner (only when paired).
    val outbox by session.store.outbox.collectAsStateWithLifecycle()
    val pendingSince = remember(outbox) { outbox.filter { it.recordId == workoutId && !it.failed }.minOfOrNull { parseTime(it.createdAt) } }
    val lastPulledAt by session.sync.lastPulledAt.collectAsStateWithLifecycle()
    val syncStatus by session.sync.status.collectAsStateWithLifecycle()
    // The sync only polls while the app is on screen, so the wait for news starts again on return.
    var openedAt by remember { mutableStateOf(nowMillis()) }
    LifecycleStartEffect(Unit) {
        openedAt = nowMillis()
        onStopOrDispose {}
    }

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
                    Mute("$doneSets of $totalSets sets, $doneEntries of ${plural(workout.entries.size, "exercise")}", size = T.Body)
                }
                if (partner != null) {
                    // Your partner's edits reach you with the 10 s poll: two missed in a row is slow.
                    SyncLagBanner(
                        partner.displayName,
                        offline = syncStatus.state == SyncState.Offline,
                        pendingSince = pendingSince,
                        lastHeardAt = maxOf(lastPulledAt ?: 0L, openedAt),
                        staleAfterMs = 25_000,
                    )
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
                            AnimatedNumber(volumeKg(workout)) { Stat(formatVolume(it, prefs.unit), "volume", Modifier.weight(1f)) }
                            AnimatedNumber(doneSets.toDouble()) { Stat("${it.roundToInt()}", "sets", Modifier.weight(1f)) }
                            ElapsedMinutesStat(workout.startedAt, Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(16.dp))
                        PrimaryButton("Finish workout", haptic = false) { finish() }
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
                    Avatar(partner.displayName, size = 58.dp, photoUrl = partner.photoUrl())
                    // Ringed, so the live dot stays visible on a photo as well as on the letter.
                    Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(13.dp).clip(CircleShape).background(Color.White).padding(2.dp).accentGel(CircleShape))
                }
            }
            Box(
                Modifier.weight(1f).height(58.dp).tappable {
                    if (unlogged > 0) { haptic(Haptic.Tap); finishPrompt = true }
                    else finish()
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
        subtitle = m?.let { e -> "${e.sets.count { it.done }} of ${e.sets.size} sets done" + (lastLabels[e.exerciseId]?.let { ", last $it" } ?: "") },
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
        actions = listOfNotNull(
            SheetAction("Rename workout", Icons.Pencil) { renameWorkout = true },
            if (routine != null && routineUpdate != null && updateChanges != null) {
                if (updateChanges.beyondOrder) {
                    SheetAction("Save changes to routine", Icons.Grip, subtitle = updateChanges.summary()) {
                        scope.launch { repo.saveRoutine(routine.id, routine.name, routineUpdate) }
                    }
                } else SheetAction("Save order to routine", Icons.Grip, subtitle = "${routine.name} starts in this order next time") {
                    scope.launch { repo.saveRoutine(routine.id, routine.name, routineUpdate) }
                }
            } else null,
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
            SheetAction("Finish anyway", Icons.ArrowRight) { finish() },
        ),
        onDismiss = { finishPrompt = false },
    )
    val promptName = rememberLast(routine?.name)
    val lastChanges = rememberLast(updateChanges)
    ActionSheet(
        open = orderPrompt,
        title = "Update ${promptName ?: "the routine"}?",
        subtitle = lastChanges?.summary() ?: "You changed this workout.",
        actions = listOf(
            SheetAction("Update ${promptName ?: "the routine"}", Icons.Check, subtitle = "It starts like this next time") { finish(saveOrder = true) },
            SheetAction("Keep routine as is", Icons.ArrowRight) { finish(saveOrder = false) },
        ),
        onDismiss = { orderPrompt = false },
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
