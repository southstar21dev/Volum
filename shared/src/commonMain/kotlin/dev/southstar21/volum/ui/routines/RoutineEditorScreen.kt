package dev.southstar21.volum.ui.routines

import dev.southstar21.volum.data.MAX_ROUTINE_NAME
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import dev.southstar21.volum.ui.components.outerShadow
import dev.southstar21.volum.ui.theme.Durations
import dev.southstar21.volum.ui.theme.Springs
import dev.southstar21.volum.ui.theme.riseIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import dev.southstar21.volum.ui.theme.T
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.input.pointer.pointerInput
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
    val density = LocalDensity.current
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
            AnimatedVisibility(items.size > 1, enter = fadeIn(), exit = fadeOut()) { Mute(" · hold the grip to reorder", size = T.Label) }
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
                            // Screen readers reorder with actions; the grip is a long-press drag.
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
                        Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 2.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(44.dp)
                                    .onGloballyPositioned { reorder.grips[item.id] = it }
                                    .pointerInput(item.id) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { at ->
                                                reorder.start(item.id)
                                                reorder.pointer(item.id, at)
                                                dev.southstar21.volum.platform.hapticTick()
                                            },
                                            onDragEnd = { reorder.drop() },
                                            onDragCancel = { reorder.drop() },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                reorder.pointer(item.id, change.position)
                                                reorder.dragBy(amount.y, items)
                                            },
                                        )
                                    },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Grip, if (dragging) c.accent else c.mute, size = 20.dp, contentDescription = "Reorder ${item.name}") }
                            Column(Modifier.weight(1f).tappable(highlight = false) { expanded = if (isOpen) null else item.id }) {
                                Txt(item.name, size = T.BodyLarge, weight = Bold, maxLines = 1)
                                Mute("${item.targetSets} sets × ${item.targetReps}${if (timed) " s" else " reps"}", size = T.Caption)
                            }
                            Icon(Icons.ChevronDown, c.mute, size = 20.dp, modifier = Modifier.graphicsLayer { rotationZ = 180f * open })
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
                                TextButton("Remove from routine", color = c.danger) { remove(item.id) }
                            }
                        }
                    }
                    }
                  }
                }
            }
        }
        // While a row is held near the top or bottom of the screen, the page scrolls under it.
        LaunchedEffect(reorder.draggingId) {
            if (reorder.draggingId == null) return@LaunchedEffect
            while (true) {
                withFrameNanos {}
                val speed = reorder.edgeSpeed(viewport, density.run { 96.dp.toPx() }, density.run { 18.dp.toPx() })
                if (speed != 0f) {
                    val moved = scrollState.scrollBy(speed)
                    if (moved != 0f) reorder.dragBy(moved, items)
                }
            }
        }
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

/**
 * The drag-to-reorder state. Rows can be any height (an open row is tall), so swaps use each row's
 * measured height: the held row swaps with a neighbour once it has moved past half of that
 * neighbour, and the neighbour slides into the gap it leaves instead of jumping there.
 */
@Stable
private class Reorder(private val scope: CoroutineScope) {
    var draggingId by mutableStateOf<String?>(null)
        private set
    /** How far the held row is drawn from its slot. */
    private var dragOffset by mutableFloatStateOf(0f)
    /** Rows easing into place: after a swap, or the held row after it's let go. */
    private val settling = mutableStateMapOf<String, Float>()
    private val jobs = mutableMapOf<String, Job>()
    val heights = mutableMapOf<String, Int>()
    /** Each row's grip, to place the finger on screen (it moves with its row). */
    val grips = mutableMapOf<String, LayoutCoordinates>()
    /** The finger, in root coordinates. */
    private var pointerY = Float.NaN

    fun forget(id: String) { heights.remove(id); grips.remove(id) }

    fun offsetOf(id: String): Float = (if (id == draggingId) dragOffset else 0f) + (settling[id] ?: 0f)

    fun start(id: String) {
        // A row still settling from a previous drop is picked up where it's drawn.
        jobs.remove(id)?.cancel()
        dragOffset = settling.remove(id) ?: 0f
        draggingId = id
    }

    fun pointer(id: String, local: Offset) {
        val grip = grips[id]?.takeIf { it.isAttached } ?: return
        pointerY = grip.localToRoot(local).y
    }

    fun dragBy(dy: Float, items: SnapshotStateList<RoutineItem>) {
        val id = draggingId ?: return
        dragOffset += dy
        var at = items.indexOfFirst { it.id == id }
        if (at < 0) return
        val own = (heights[id] ?: 0).toFloat()
        // Several swaps in one step when the finger moves fast.
        while (true) {
            val next = items.getOrNull(at + 1)
            val prev = items.getOrNull(at - 1)
            val nextH = next?.let { heights[it.id] }?.toFloat()
            val prevH = prev?.let { heights[it.id] }?.toFloat()
            if (next != null && nextH != null && dragOffset > nextH / 2) {
                items.add(at + 1, items.removeAt(at))
                dragOffset -= nextH
                nudge(next.id, own)
                at++
            } else if (prev != null && prevH != null && dragOffset < -prevH / 2) {
                items.add(at - 1, items.removeAt(at))
                dragOffset += prevH
                nudge(prev.id, -own)
                at--
            } else break
            dev.southstar21.volum.platform.hapticTick()
        }
        // The held row stays within the list: the card clips whatever is drawn outside it.
        val above = items.take(at).sumOf { heights[it.id] ?: 0 }.toFloat()
        val below = items.drop(at + 1).sumOf { heights[it.id] ?: 0 }.toFloat()
        dragOffset = dragOffset.coerceIn(-above, below)
    }

    fun drop() {
        val id = draggingId ?: return
        draggingId = null
        val from = dragOffset
        dragOffset = 0f
        pointerY = Float.NaN
        nudge(id, from)
    }

    /** Speed to scroll at while the finger is within [edge] of the viewport's top or bottom. */
    fun edgeSpeed(viewport: LayoutCoordinates?, edge: Float, max: Float): Float {
        val v = viewport?.takeIf { it.isAttached } ?: return 0f
        if (draggingId == null || pointerY.isNaN()) return 0f
        val top = v.localToRoot(Offset.Zero).y
        val bottom = top + v.size.height
        return when {
            pointerY < top + edge -> -max * ((top + edge - pointerY) / edge).coerceIn(0f, 1f)
            pointerY > bottom - edge -> max * ((pointerY - (bottom - edge)) / edge).coerceIn(0f, 1f)
            else -> 0f
        }
    }

    /** Draw [id] [by] away from its slot, then ease it home. */
    private fun nudge(id: String, by: Float) {
        val start = (settling[id] ?: 0f) + by
        settling[id] = start
        jobs.remove(id)?.cancel()
        jobs[id] = scope.launch {
            animate(start, 0f, animationSpec = Springs.settle()) { v, _ -> settling[id] = v }
            settling.remove(id)
        }
    }
}

/** The routine draft's exercises, saved as JSON with the screen's state. */
private val RoutineItemsSaver = Saver<SnapshotStateList<RoutineItem>, String>(
    save = { VolumJson.encodeToString(ListSerializer(RoutineItem.serializer()), it.toList()) },
    restore = { saved -> mutableStateListOf<RoutineItem>().apply { addAll(VolumJson.decodeFromString(ListSerializer(RoutineItem.serializer()), saved)) } },
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
