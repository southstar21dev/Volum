package dev.southstar21.volum.ui.session

import dev.southstar21.volum.platform.Haptic
import dev.southstar21.volum.platform.haptic

import androidx.compose.foundation.layout.Arrangement
import dev.southstar21.volum.ui.theme.T
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.southstar21.volum.ui.components.ErrorText
import dev.southstar21.volum.ui.components.rememberLast
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.southstar21.volum.data.PbException
import dev.southstar21.volum.data.WorkoutMutation
import dev.southstar21.volum.data.applyWorkoutMutation
import dev.southstar21.volum.data.partnerErrorMessage
import dev.southstar21.volum.model.SetEntry
import dev.southstar21.volum.model.Workout
import dev.southstar21.volum.model.WorkoutEntry
import dev.southstar21.volum.model.WorkoutStatus
import dev.southstar21.volum.ui.LocalApp
import dev.southstar21.volum.ui.LocalNav
import dev.southstar21.volum.ui.LocalSession
import dev.southstar21.volum.ui.exercisesState
import dev.southstar21.volum.ui.components.ActionSheet
import dev.southstar21.volum.ui.components.Avatar
import dev.southstar21.volum.ui.components.CircleButton
import dev.southstar21.volum.ui.components.GlassButton
import dev.southstar21.volum.ui.components.Mute
import dev.southstar21.volum.ui.components.Pill
import dev.southstar21.volum.ui.components.SheetAction
import dev.southstar21.volum.ui.components.Spinner
import dev.southstar21.volum.ui.components.Toolbar
import dev.southstar21.volum.ui.components.TopInset
import dev.southstar21.volum.ui.components.Txt
import dev.southstar21.volum.ui.components.accentGel
import dev.southstar21.volum.ui.theme.Bold
import dev.southstar21.volum.ui.theme.ExtraBold
import dev.southstar21.volum.ui.theme.Icons
import dev.southstar21.volum.ui.theme.Volum
import dev.southstar21.volum.util.plural
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A partner's live workout: view and adjust their weights, reps and sets, atomically on the server. */
@Composable
fun PartnerSessionScreen(workoutId: String) {
    val session = LocalSession.current
    val app = LocalApp.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val prefs by app.prefs.collectAsStateWithLifecycle()
    val snapshot by session.partner.snapshot.collectAsStateWithLifecycle()
    val exercises by session.exercisesState()
    val c = Volum.colors

    var workout by remember { mutableStateOf<Workout?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var ended by remember { mutableStateOf(false) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var editTarget by remember { mutableStateOf<Pair<WorkoutEntry, SetEntry>?>(null) }
    var menuEntry by remember { mutableStateOf<WorkoutEntry?>(null) }
    var renameEntry by remember { mutableStateOf<WorkoutEntry?>(null) }
    var deleteEntry by remember { mutableStateOf<WorkoutEntry?>(null) }
    var addOpen by remember { mutableStateOf(false) }

    // Edits sent but not yet answered. While any are out, server copies (polls, live events, earlier
    // answers) predate them and would briefly undo them on screen, so only the last answer lands.
    val inFlight = remember { intArrayOf(0) }
    fun receive(w: Workout) {
        if (w.status != WorkoutStatus.Active || w.deleted) ended = true
        else if (inFlight[0] == 0) { workout = w; error = null }
    }

    // Any failure, not just the server's: one escaping into this screen's scope would crash the app.
    fun fail(e: Exception) {
        if (e is PbException && e.status == 404) ended = true
        else error = if (e is PbException) partnerErrorMessage(e) else "Something went wrong"
    }

    suspend fun load() {
        try {
            val w = session.partner.fetchWorkout(workoutId)
            if (w == null) ended = true else receive(w)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e)
        }
    }
    // Polled and followed live only while the app is on screen.
    LifecycleStartEffect(workoutId) {
        val poll = scope.launch {
            while (true) {
                load()
                delay(4_000)
            }
        }
        val live = session.partner.subscribe(scope, workoutId) { w -> receive(w) }
        onStopOrDispose {
            poll.cancel()
            live.cancel()
        }
    }

    fun mutate(m: WorkoutMutation) {
        val current = workout ?: return
        workout = current.copy(entries = applyWorkoutMutation(current.entries, m))
        inFlight[0]++
        scope.launch {
            try {
                // Counted down however the call ends, or receive() would ignore every later server copy.
                val answer = try { session.partner.mutate(workoutId, m) } finally { inFlight[0]-- }
                answer?.let(::receive)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
                if (inFlight[0] == 0) load()
            }
        }
    }

    val partnerName = snapshot.partner?.displayName ?: "Partner"
    val library = remember(exercises) { exercises.values.filter { !it.deleted } }
    val actions = EntryActions(
        onSet = { entry, set -> mutate(WorkoutMutation.Set(entry.id, set.id, set)) },
        onToggleDone = { entry, set ->
            haptic(toggleHaptic(workout?.entries ?: listOf(entry), entry, set))
            mutate(WorkoutMutation.Set(entry.id, set.id, set.copy(done = !set.done)))
        },
        onEditSet = { entry, set -> editTarget = entry to set },
        onAddSet = { entry -> mutate(WorkoutMutation.AddSet(entry.id, nextSet(entry, prefs.unit))) },
        onMenu = { menuEntry = it },
        onExpand = { e -> expandedId = if (expandedId == e.id) null else e.id },
        keepSetUnits = true,
    )

    val w = workout
    // Holds of sets removed here or by the owner, or of a workout that ended, are dropped.
    val shownSets = remember(w, ended) { if (ended) emptySet() else w?.entries?.flatMap { e -> e.sets.map { it.id } }.orEmpty().toSet() }
    ForgetRemovedHolds(shownSets)
    // So its holds go when the workout ends even after this screen is closed (see RetainLiveHolds).
    SideEffect { if (shownSets.isNotEmpty()) Stopwatches.showPartnerSets(workoutId, shownSets) }
    when {
        ended || w == null -> Column(Modifier.fillMaxSize()) {
            TopInset(10.dp)
            Toolbar(leading = { CircleButton(Icons.ChevronLeft, "Back") { nav.pop() } })
            Column(Modifier.fillMaxWidth().padding(top = 90.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (ended) {
                    Txt("Session ended", size = T.SheetTitle, weight = ExtraBold)
                    Mute("$partnerName finished or left this workout.", Modifier.padding(top = 6.dp), align = TextAlign.Center)
                } else if (error != null) {
                    Txt(error.orEmpty(), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, size = T.BodyLarge, weight = Bold, align = TextAlign.Center)
                } else {
                    Spinner(c.accent, size = 28.dp)
                }
            }
        }
        else -> Box(Modifier.fillMaxSize()) {
            val total = w.entries.sumOf { it.sets.size }
            val done = w.entries.sumOf { e -> e.sets.count { it.done } }
            SessionBody(
                workout = w,
                preferred = prefs.unit,
                lastLabels = emptyMap(),
                actions = actions,
                expandedId = expandedId,
                bottomSpace = 110.dp,
                toolbar = {
                    Toolbar(
                        leading = { CircleButton(Icons.ChevronLeft, "Back") { nav.pop() } },
                        center = {
                            Pill {
                                Box(Modifier.size(8.dp).accentGel(CircleShape))
                                ElapsedTxt(w.startedAt, size = T.Label, weight = Bold, prefix = "Live · ")
                            }
                        },
                    )
                },
                heading = {
                    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Avatar(partnerName, size = 52.dp, warm = true)
                        Column {
                            Txt("$partnerName · ${w.routineName}", size = T.Headline, weight = ExtraBold, tracking = T.TitleTracking, maxLines = 2)
                            Mute("$done of $total sets · ${w.entries.count { it.isComplete() }} of ${plural(w.entries.size, "exercise")}", size = T.Label)
                        }
                    }
                    error?.let { ErrorText(it, Modifier.padding(start = 20.dp, top = 8.dp)) }
                },
                allDone = {
                    dev.southstar21.volum.ui.components.GlassCard(Modifier.fillMaxWidth()) {
                        Txt("All exercises completed", size = T.Subhead, weight = Bold)
                        Mute("$partnerName can finish the workout from their phone.", size = T.Small)
                    }
                },
            )
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircleButton(Icons.Plus, "Add exercise for $partnerName", size = 58.dp, iconSize = 22.dp) { addOpen = true }
                GlassButton("Back to my session", Modifier.weight(1f), height = 58.dp, icon = Icons.ChevronLeft) { nav.pop() }
            }
        }
    }

    SetEditSheet(
        editTarget, prefs.unit,
        onSave = { entry, set -> mutate(WorkoutMutation.Set(entry.id, set.id, set)) },
        onRemove = { entry, set -> mutate(WorkoutMutation.RemoveSet(entry.id, set.id)) },
        onDismiss = { editTarget = null },
        keepSetUnit = true,
    )
    val m = rememberLast(menuEntry)
    ActionSheet(
        open = menuEntry != null,
        title = m?.name,
        actions = if (m == null) emptyList() else listOf(
            SheetAction("Rename", Icons.Pencil) { renameEntry = m },
            SheetAction(if (m.isSkipped) "Unskip" else "Skip", Icons.Skip) { mutate(WorkoutMutation.UpdateEntry(m.id, skipped = !m.isSkipped)) },
            // Not the last one: the server refuses a workout with no exercises, and only its owner can discard it.
            if ((w?.entries?.size ?: 0) > 1) SheetAction("Delete", Icons.Trash, destructive = true) { deleteEntry = m } else null,
        ).filterNotNull(),
        onDismiss = { menuEntry = null },
    )
    RenameExerciseSheet(
        entry = renameEntry,
        exercises = library,
        canUpdateRoutine = false,
        // Neither their routine nor their library is yours to change from here.
        ownerId = null,
        linkOwner = workout?.owner,
        onSave = { name, link, _, _ ->
            val e = renameEntry ?: return@RenameExerciseSheet
            if (link == null) {
                mutate(WorkoutMutation.UpdateEntry(e.id, name = name))
            } else {
                val current = workout?.entries?.firstOrNull { it.id == e.id } ?: e
                mutate(WorkoutMutation.UpdateEntry(e.id, name = link.name, exerciseId = link.id, timed = link.isTimed))
                // Relinked to a lift tracked the other way (time or reps), its sets change shape too.
                if (current.isTimed != link.isTimed) current.sets.forEach { set -> mutate(WorkoutMutation.Set(e.id, set.id, reshapeSet(set, link.isTimed))) }
            }
        },
        onDismiss = { renameEntry = null },
    )
    DeleteExerciseSheet(deleteEntry, null, onDelete = { e, _ -> mutate(WorkoutMutation.RemoveEntry(e.id)) }, onDismiss = { deleteEntry = null })
    AddExerciseSheet(
        open = addOpen,
        exercises = library,
        recent = emptyList(),
        lastLabels = emptyMap(),
        onPick = { ex -> mutate(WorkoutMutation.AddEntry(session.repository.buildAddedEntry(ex.id, ex.name, ex.isTimed, prefs.unit, fromHistory = false))) },
        onCreate = { name ->
            scope.launch {
                val ex = try {
                    session.repository.findOrCreateExercise(name)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail(e)
                    return@launch
                }
                mutate(WorkoutMutation.AddEntry(session.repository.buildAddedEntry(ex.id, ex.name, ex.isTimed, prefs.unit, fromHistory = false)))
            }
        },
        onDismiss = { addOpen = false },
    )
}
