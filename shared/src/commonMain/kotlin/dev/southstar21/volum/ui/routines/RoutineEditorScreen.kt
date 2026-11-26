package dev.southstar21.volum.ui.routines

import dev.southstar21.volum.data.MAX_ROUTINE_NAME
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.DpOffset
import dev.southstar21.volum.ui.components.outerShadow
import dev.southstar21.volum.ui.components.Reorder
import dev.southstar21.volum.ui.components.ReorderAutoScroll
import dev.southstar21.volum.ui.components.reorderRow
import dev.southstar21.volum.data.MAX_EXERCISE_NAME
import dev.southstar21.volum.model.Exercise
import dev.southstar21.volum.ui.components.BottomSheet
import dev.southstar21.volum.ui.components.PrimaryButton
import dev.southstar21.volum.ui.components.rememberLast
import dev.southstar21.volum.ui.session.Check
import dev.southstar21.volum.ui.session.renameLinkTarget
import dev.southstar21.volum.ui.theme.Durations
import dev.southstar21.volum.ui.theme.Springs
import dev.southstar21.volum.ui.theme.riseIn
import dev.southstar21.volum.ui.theme.T
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import dev.southstar21.volum.model.VolumJson
import dev.southstar21.volum.ui.components.ErrorText
import kotlinx.serialization.builtins.ListSerializer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.southstar21.volum.model.RoutineItem
import dev.southstar21.volum.ui.LocalNav
import dev.southstar21.volum.ui.LocalSession
import dev.southstar21.volum.ui.exercisesState
import dev.southstar21.volum.ui.lastTopSetLabels
import dev.southstar21.volum.ui.routinesState
import dev.southstar21.volum.ui.components.BackHandler
import dev.southstar21.volum.ui.components.BareField
import dev.southstar21.volum.ui.components.CircleButton
import dev.southstar21.volum.ui.components.ConfirmSheet
import dev.southstar21.volum.ui.components.Divider
import dev.southstar21.volum.ui.components.GlassButton
import dev.southstar21.volum.ui.components.Mute
import dev.southstar21.volum.ui.components.Pill
import dev.southstar21.volum.ui.components.TextButton
import dev.southstar21.volum.ui.components.Toolbar
import dev.southstar21.volum.ui.components.TopInset
import dev.southstar21.volum.ui.components.Txt
import dev.southstar21.volum.ui.components.glassCard
import dev.southstar21.volum.ui.components.milky
import dev.southstar21.volum.ui.session.AddExerciseSheet
import dev.southstar21.volum.ui.theme.Bold
import dev.southstar21.volum.ui.theme.ExtraBold
import dev.southstar21.volum.ui.theme.Icon
import dev.southstar21.volum.ui.theme.Icons
import dev.southstar21.volum.ui.theme.Volum
import dev.southstar21.volum.ui.theme.tappable
import dev.southstar21.volum.util.newId
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun RoutineEditorScreen(routineId: String?) {
    val session = LocalSession.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val routines by session.routinesState()
    val exercises by session.exercisesState()
    // Deleting the routine leaves the screen: while it slides away it keeps its title and buttons.
    val live = routineId?.let { routines[it] }?.takeIf { !it.deleted }
    val lastExisting = remember { arrayOfNulls<dev.southstar21.volum.model.Routine>(1) }
    if (live != null) lastExisting[0] = live
    val existing = live ?: lastExisting[0]
    val c = Volum.colors

    // The draft survives the activity being recreated (rotation, dark mode, process death).
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    val items = rememberSaveable(saver = RoutineItemsSaver) { mutableStateListOf<RoutineItem>().apply { addAll(existing?.items.orEmpty()) } }
    var expanded by rememberSaveable { mutableStateOf(items.firstOrNull()?.id) }
    var addOpen by rememberSaveable { mutableStateOf(false) }
    var deletePrompt by rememberSaveable { mutableStateOf(false) }
    var discardPrompt by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val reorder = remember { Reorder(scope) }
    // Rows that arrive while the screen is open (picked from the sheet) grow in; the routine's own
    // rows are there from the start and rise in with the screen instead.
    val initialIds = remember { items.map { it.id }.toSet() }
    val rowStates = remember { mutableMapOf<String, MutableTransitionState<Boolean>>() }
    fun remove(id: String) {
        if (expanded == id) expanded = null
        rowStates[id]?.targetState = false
    }

    // A removed row stays in [items] until it has folded away; it is already gone from the routine.
    fun kept(): List<RoutineItem> = items.filter { rowStates[it.id]?.targetState != false }

    fun save() {
        if (saving) return
        val toSave = kept()
        if (name.isBlank()) { error = "Give the routine a name."; return }
        if (toSave.isEmpty()) { error = "Add at least one exercise."; return }
        // Single-shot: a second tap while the screen leaves would save a new routine twice.
        saving = true
        scope.launch {
            var saved = false
            try {
                session.repository.saveRoutine(existing?.id, name.trim(), toSave)
                saved = true
            } finally {
                if (!saved) saving = false
            }
            nav.pop()
        }
    }

    // Cancel, back and the edge swipe ask before dropping unsaved edits.
    val dirty = !saving && !leaving && (name.trim() != (existing?.name ?: "") || kept() != existing?.items.orEmpty())
    fun close() { if (dirty) discardPrompt = true else nav.pop() }
    BackHandler(enabled = dirty) { discardPrompt = true }

    val library = remember(exercises) { exercises.values.filter { !it.deleted } }
    fun move(id: String, by: Int) {
        val from = items.indexOfFirst { it.id == id }
        val to = (from + by).coerceIn(0, items.lastIndex)
        if (from < 0 || to == from) return
        items.add(to, items.removeAt(from))
    }
    val scrollState = rememberScrollState()
    var viewport by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Column(Modifier.fillMaxSize().imePadding().onGloballyPositioned { viewport = it }.verticalScroll(scrollState)) {
        TopInset(10.dp)
        Toolbar(
            leading = { CircleButton(Icons.X, "Cancel") { close() } },
            center = { Txt(if (existing == null) "New routine" else "Edit routine", size = T.Subhead, weight = Bold) },
            trailing = { dev.southstar21.volum.ui.components.PrimaryButton("Save", Modifier.width(84.dp), height = 44.dp) { save() } },
        )
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp).riseIn().fillMaxWidth().glassCard(RoundedCornerShape(24.dp)).padding(horizontal = 18.dp, vertical = 12.dp)) {
            Mute("Routine name", Modifier.clearAndSetSemantics {}, size = T.Caption)
            BareField(name, { name = it.take(MAX_ROUTINE_NAME); error = null }, Modifier.fillMaxWidth(), placeholder = "Push A", size = T.Heading, weight = ExtraBold, imeAction = ImeAction.Done, label = "Routine name")
        }
        AnimatedVisibility(error != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            var shown by remember { mutableStateOf("") }
            error?.let { shown = it }
            ErrorText(shown, Modifier.padding(start = 24.dp, top = 8.dp))
        }
        Row(Modifier.padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 10.dp).riseIn(), verticalAlignment = Alignment.Bottom) {
            Txt("Exercises", size = T.Subhead, weight = Bold)
            AnimatedVisibility(items.size > 1, enter = fadeIn(), exit = fadeOut()) { Mute(", hold one to reorder", size = T.Label) }
        }
        AnimatedVisibility(
            items.isNotEmpty(),
            enter = fadeIn(tween(Durations.Fade)) + expandVertically(Springs.settle(), expandFrom = Alignment.Top),
            exit = fadeOut(tween(Durations.Exit)) + shrinkVertically(Springs.settle(), shrinkTowards = Alignment.Top),
        ) {
            Column(
                Modifier.padding(horizontal = 16.dp).riseIn().fillMaxWidth().glassCard(RoundedCornerShape(28.dp)).padding(4.dp),
            ) {
                items.forEachIndexed { index, item ->
                  // Keyed by item: a swap while dragging moves the row (and its drag gesture) with it,
                  // rather than handing the slot, and the gesture, to another item.
                  key(item.id) {
                    val visible = rowStates.getOrPut(item.id) { MutableTransitionState(item.id in initialIds).apply { targetState = true } }
                    // A removed row shrinks away first, and leaves the list once it's gone.
                    if (visible.isIdle && !visible.currentState && !visible.targetState) {
                        LaunchedEffect(Unit) { items.removeAll { it.id == item.id }; rowStates.remove(item.id); reorder.forget(item.id) }
                    }
                    val timed = exercises[item.exerciseId]?.isTimed == true
                    val dragging = reorder.draggingId == item.id
                    val isOpen = expanded == item.id
                    val previous = items.getOrNull(index - 1)?.id
                    fun update(change: (RoutineItem) -> RoutineItem) {
                        val at = items.indexOfFirst { it.id == item.id }
                        if (at >= 0) items[at] = change(items[at])
                    }
                    // Everything that changes with a row's state eases: the milky panel of the open row,
                    // the lift of the dragged one, the dividers either side of both.
                    val open by animateFloatAsState(if (isOpen) 1f else 0f, Springs.settle(), label = "open")
                    val lift by animateFloatAsState(if (dragging) 1f else 0f, Springs.settle(), label = "lift")
                    val divider by animateFloatAsState(
                        if (index > 0 && !isOpen && expanded != previous && !dragging && reorder.draggingId != previous) 1f else 0f,
                        tween(Durations.Fade), label = "divider",
                    )
                    val panel = RoundedCornerShape(22.dp)
                    AnimatedVisibility(
                        visibleState = visible,
                        enter = fadeIn(tween(Durations.Fade, delayMillis = Durations.Instant)) + expandVertically(Springs.settle(), expandFrom = Alignment.Top),
                        exit = fadeOut(tween(Durations.Exit)) + shrinkVertically(Springs.settle(), shrinkTowards = Alignment.Top),
                        modifier = Modifier.zIndex(if (dragging) 2f else if (lift > 0f) 1f else 0f),
                    ) {
                    Column(
                        Modifier
                            // Screen readers reorder with actions; the row itself is a long-press drag.
                            .semantics {
                                customActions = buildList {
                                    if (index > 0) add(CustomAccessibilityAction("Move ${item.name} up") { move(item.id, -1); true })
                                    if (index < items.lastIndex) add(CustomAccessibilityAction("Move ${item.name} down") { move(item.id, 1); true })
                                }
                            }
                            .onSizeChanged { reorder.heights[item.id] = it.height }
                            .graphicsLayer {
                                translationY = reorder.offsetOf(item.id)
                                val s = 1f + 0.025f * lift
                                scaleX = s; scaleY = s
                            }
                            .outerShadow(panel, Shadow(radius = 22.dp, color = c.drop, offset = DpOffset(0.dp, 10.dp)), alpha = { lift })
                            .drawBehind {
                                val inset = 4.dp.toPx() * (1f - lift)
                                val r = 22.dp.toPx()
                                // The open row's milky panel; lifted, the row turns near-opaque so the
                                // rows it passes over don't show through it.
                                val a = maxOf(open, lift)
                                if (a > 0f) drawRoundRect(
                                    if (lift > open) c.sheet else c.row,
                                    topLeft = Offset(inset, inset), size = Size(size.width - 2 * inset, size.height - 2 * inset),
                                    cornerRadius = CornerRadius(r), alpha = a,
                                )
                                // The divider above the row draws on it, so it takes no height of its own:
                                // every row's height is just the row, which the drag's maths relies on.
                                if (divider > 0f) drawRect(c.line, topLeft = Offset(14.dp.toPx(), 0f), size = Size(size.width - 28.dp.toPx(), 1.dp.toPx()), alpha = divider)
                            }
                            .padding(4.dp),
                    ) {
                        // The whole row is the target: a tap opens or closes it (chevron included), a hold
                        // picks it up to reorder.
                        Row(
                            Modifier.fillMaxWidth().height(64.dp)
                                .reorderRow(
                                    reorder, item.id,
                                    onTap = { expanded = if (expanded == item.id) null else item.id },
                                    onDrag = { dy -> reorder.dragBy(dy, items) { it.id } },
                                    tapLabel = if (isOpen) "Close ${item.name}" else "Edit ${item.name}",
                                )
                                .padding(start = 14.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Txt(item.name, size = T.BodyLarge, weight = Bold, maxLines = 1)
                                Mute("${item.targetSets} sets × ${item.targetReps}${if (timed) " s" else " reps"}", size = T.Caption)
                            }
                            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.ChevronDown, c.mute, size = 20.dp, modifier = Modifier.graphicsLayer { rotationZ = 180f * open })
                            }
                        }
                        AnimatedVisibility(
                            isOpen,
                            enter = fadeIn(tween(Durations.Fade, delayMillis = Durations.Instant)) + expandVertically(Springs.settle(), expandFrom = Alignment.Top),
                            exit = fadeOut(tween(Durations.Exit)) + shrinkVertically(Springs.settle(), shrinkTowards = Alignment.Top),
                        ) {
                            Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                StepperRow("Sets", item.targetSets, 1, 20) { v -> update { it.copy(targetSets = v) } }
                                StepperRow(if (timed) "Seconds" else "Reps", item.targetReps, if (timed) 5 else 1, if (timed) 600 else 100, step = if (timed) 5 else 1) { v -> update { it.copy(targetReps = v) } }
                                Box(Modifier.fillMaxWidth().glassCard(RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
                                    BareField(item.note.orEmpty(), { text -> update { it.copy(note = text.ifEmpty { null }) } }, Modifier.fillMaxWidth(), placeholder = "Note", size = T.Label, weight = androidx.compose.ui.text.font.FontWeight.Normal, label = "Note for ${item.name}")
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    TextButton("Rename") { renaming = item.id }
                                    TextButton("Remove from routine", color = c.danger) { remove(item.id) }
                                }
                            }
                        }
                    }
                    }
                  }
                }
            }
        }
        // While a row is held near the top or bottom of the screen, the page scrolls under it.
        ReorderAutoScroll(reorder, scrollState, { viewport }) { moved -> reorder.dragBy(moved, items) { it.id } }
        Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).riseIn()) {
            GlassButton("Add exercise", Modifier.fillMaxWidth(), icon = Icons.Plus, color = c.accent) { addOpen = true }
        }
        if (existing != null) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                TextButton("Delete routine", color = c.danger) { deletePrompt = true }
            }
        }
        Spacer(Modifier.height(60.dp))
    }

    AddExerciseSheet(
        open = addOpen,
        exercises = library,
        recent = emptyList(),
        lastLabels = remember(library) { session.lastTopSetLabels(library) },
        onPick = { ex ->
            val item = RoutineItem(newId(), ex.id, ex.name, 3, if (ex.isTimed) 30 else 8)
            items.add(item)
            expanded = item.id
        },
        onCreate = { n ->
            scope.launch {
                val ex = session.repository.findOrCreateExercise(n)
                val item = RoutineItem(newId(), ex.id, ex.name, 3, if (ex.isTimed) 30 else 8)
                items.add(item)
                expanded = item.id
            }
        },
        onDismiss = { addOpen = false },
    )
    RenameItemSheet(
        item = renaming?.let { id -> items.firstOrNull { it.id == id } },
        exercises = library,
        ownerId = session.user.id,
        onSave = { newName, link, updateLibrary ->
            val at = items.indexOfFirst { it.id == renaming }
            if (at >= 0) {
                val cur = items[at]
                if (link != null) {
                    // Linked to a lift tracked the other way (time or reps), the target starts over.
                    val wasTimed = exercises[cur.exerciseId]?.isTimed == true
                    val reps = if (wasTimed == link.isTimed) cur.targetReps else if (link.isTimed) 30 else 8
                    items[at] = cur.copy(exerciseId = link.id, name = link.name, targetReps = reps)
                } else if (updateLibrary) {
                    // The library rename reaches every routine item of this lift, so the draft's too.
                    items.indices.forEach { i -> if (items[i].exerciseId == cur.exerciseId) items[i] = items[i].copy(name = newName) }
                    scope.launch { session.repository.renameExercise(cur.exerciseId, newName) }
                } else {
                    items[at] = cur.copy(name = newName)
                }
            }
        },
        onDismiss = { renaming = null },
    )
    ConfirmSheet(
        open = deletePrompt,
        title = "Delete ${existing?.name ?: "routine"}?",
        message = "Your workout history stays.",
        confirm = "Delete routine",
        onConfirm = { existing?.let { r -> leaving = true; scope.launch { session.repository.deleteRoutine(r.id); nav.pop() } } },
        onDismiss = { deletePrompt = false },
    )
    ConfirmSheet(
        open = discardPrompt,
        title = if (existing == null) "Discard this routine?" else "Discard your changes?",
        message = if (existing == null) "It hasn't been saved." else "Your edits to ${existing.name} won't be saved.",
        confirm = if (existing == null) "Discard routine" else "Discard changes",
        onConfirm = { leaving = true; nav.pop() },
        onDismiss = { discardPrompt = false },
    )
}

/** The routine draft's exercises, saved as JSON with the screen's state. */
private val RoutineItemsSaver = Saver<SnapshotStateList<RoutineItem>, String>(
    save = { VolumJson.encodeToString(ListSerializer(RoutineItem.serializer()), it.toList()) },
    // A malformed or incompatible saved draft (an older build wrote it) restores as empty, not a crash.
    restore = { saved -> mutableStateListOf<RoutineItem>().apply { addAll(runCatching { VolumJson.decodeFromString(ListSerializer(RoutineItem.serializer()), saved) }.getOrDefault(emptyList())) } },
)

/** The stepper's next value down: the grid line of [step] below [value] (32 → 30 for 5), within [min]..[max]. */
internal fun stepDown(value: Int, step: Int, min: Int, max: Int): Int = ((value - 1) / step * step).coerceIn(min, max)

/** The stepper's next value up: the grid line of [step] above [value] (32 → 35 for 5), within [min]..[max]. */
internal fun stepUp(value: Int, step: Int, min: Int, max: Int): Int = ((value / step + 1) * step).coerceIn(min, max)

/** Steps along multiples of [step], so a value off the grid (from before, or another device) snaps onto it. */
@Composable
private fun StepperRow(label: String, value: Int, min: Int, max: Int, step: Int = 1, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Txt(label, Modifier.weight(1f), size = T.Body, weight = Bold)
        CircleButton(Icons.Minus, "Fewer $label".lowercase(), enabled = value > min, repeatable = true) { onChange(stepDown(value, step, min, max)) }
        Txt("$value", Modifier.width(48.dp), size = T.SheetTitle, weight = ExtraBold, tabular = true, align = androidx.compose.ui.text.style.TextAlign.Center)
        CircleButton(Icons.Plus, "More $label".lowercase(), enabled = value < max, repeatable = true) { onChange(stepUp(value, step, min, max)) }
    }
}

/**
 * Rename an exercise in the routine, or link it to another lift by typing that lift's name. The
 * library copy can be renamed too when it's the user's own ([ownerId]).
 */
@Composable
private fun RenameItemSheet(
    item: RoutineItem?,
    exercises: List<Exercise>,
    ownerId: String,
    onSave: (newName: String, linkTo: Exercise?, updateLibrary: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var updateLibrary by remember { mutableStateOf(false) }
    LaunchedEffect(item?.id) { item?.let { name = it.name; updateLibrary = false } }
    val shown = rememberLast(item)
    BottomSheet(open = item != null, onDismiss = onDismiss, title = "Rename exercise") {
        val e = shown ?: return@BottomSheet
        Txt("Rename exercise", Modifier.padding(horizontal = 6.dp), size = T.SheetTitle, weight = ExtraBold)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().milky(RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 16.dp)) {
            BareField(name, { name = it.take(MAX_EXERCISE_NAME) }, Modifier.fillMaxWidth(), placeholder = "Exercise name", imeAction = ImeAction.Done, label = "Exercise name")
        }
        val q = name.trim()
        val link = renameLinkTarget(exercises, q, e.exerciseId, ownerId)
        if (link != null) Mute("Links this to ${link.name} in your library.", Modifier.padding(8.dp, 10.dp), size = T.Small)
        Spacer(Modifier.height(8.dp))
        // A partner's exercise isn't yours to rename in the library.
        val canUpdateLibrary = link == null && exercises.any { it.id == e.exerciseId && it.owner == ownerId }
        if (canUpdateLibrary) Check("Also rename in exercise library", updateLibrary) { updateLibrary = it }
        Spacer(Modifier.height(14.dp))
        PrimaryButton("Save", enabled = q.isNotEmpty()) {
            if (item != null && (q != e.name || link != null)) onSave(q, link, canUpdateLibrary && updateLibrary)
            onDismiss()
        }
    }
}
