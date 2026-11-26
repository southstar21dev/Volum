package dev.southstar21.volum.ui.session

import androidx.compose.ui.draw.clip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.horizontalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.zIndex
import dev.southstar21.volum.ui.components.Reorder
import dev.southstar21.volum.ui.components.ReorderAutoScroll
import dev.southstar21.volum.ui.components.reorderRow
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.southstar21.volum.model.WeightUnit
import dev.southstar21.volum.model.Workout
import dev.southstar21.volum.platform.hapticTick
import androidx.compose.ui.semantics.semantics
import dev.southstar21.volum.ui.components.GlassCard
import dev.southstar21.volum.ui.components.Mute
import dev.southstar21.volum.ui.components.Pill
import dev.southstar21.volum.ui.components.SectionTitle
import dev.southstar21.volum.ui.components.TopInset
import dev.southstar21.volum.ui.components.Txt
import dev.southstar21.volum.ui.theme.Bold
import dev.southstar21.volum.ui.theme.Icon
import dev.southstar21.volum.ui.theme.Icons
import dev.southstar21.volum.ui.theme.Semibold
import dev.southstar21.volum.ui.theme.Volum
import dev.southstar21.volum.ui.theme.celebrate

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
    val scope = rememberCoroutineScope()
    val reorder = remember { Reorder(scope) }
    // While a card is dragged, and until the saved order comes back from the store, the unfinished
    // cards follow this draft of their ids instead of the workout.
    val draft = remember { mutableStateListOf<String>() }
    val stored = workout.entries.filterNot { it.isComplete() }
    val active = if (draft.isEmpty()) stored else orderedBy(stored, draft)
    val completed = workout.entries.filter { it.isComplete() }
    // The open card stays put while one is dragged: a card opening or closing mid-drag would throw off
    // the heights the swaps are measured by.
    val current = (if (reorder.draggingId != null) stored else active).firstOrNull()
    val activeIds = active.map { it.id }
    val storedIds = stored.map { it.id }
    LaunchedEffect(storedIds, reorder.draggingId) {
        if (reorder.draggingId == null && draft.isNotEmpty() && draft.toList() == storedIds) draft.clear()
    }
    val onMove = actions.onMove?.takeIf { active.size > 1 }
    val latestIds by rememberUpdatedState(activeIds)
    val storedNow by rememberUpdatedState(storedIds)
    fun latestStoredIds() = storedNow
    val latestMove by rememberUpdatedState(onMove)
    fun moveBy(id: String, by: Int) {
        val ids = latestIds.toMutableList()
        val from = ids.indexOf(id)
        val to = (from + by).coerceIn(0, ids.lastIndex)
        if (from < 0 || to == from) return
        ids.add(to, ids.removeAt(from))
        latestMove?.invoke(ids)
    }
    val scroll = rememberScrollState()
    var viewport by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val shown = expandedId ?: current?.id
    // What was finished before this screen opened, so only exercises completed now celebrate.
    val alreadyDone = remember(workout.id) { completed.map { it.id }.toSet() }
    Column(Modifier.fillMaxSize().onGloballyPositioned { viewport = it }.verticalScroll(scroll)) {
        TopInset(10.dp)
        toolbar()
        heading()
        val total = workout.entries.sumOf { it.sets.size }
        if (total > 0) {
            // How far through the workout you are, filling as sets are logged.
            val done = workout.entries.sumOf { e -> e.sets.count { it.done } }
            val progress by animateFloatAsState(done / total.toFloat(), Springs.settle(), label = "progress")
            val fillBrush = remember(c.accentTop, c.accentBottom) { Brush.horizontalGradient(listOf(c.accentTop, c.accentBottom)) }
            Box(
                Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp).fillMaxWidth().height(6.dp).background(c.line, CircleShape).drawBehind {
                    if (progress > 0f) drawRoundRect(
                        fillBrush,
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
            AnimatedItems(
                active,
                key = { it.id },
                itemModifier = { e -> Modifier.zIndex(if (reorder.draggingId == e.id) 1f else 0f) },
            ) { e ->
                val dragging = reorder.draggingId == e.id
                val lift by animateFloatAsState(if (dragging) 1f else 0f, Springs.settle(), label = "lift")
                val index = activeIds.indexOf(e.id)
                // The gesture outlives recompositions, so its tap reads the card and actions as they are now.
                val tapCard by rememberUpdatedState({ actions.onExpand(e) })
                Box(
                    Modifier
                        .then(if (onMove == null) Modifier else Modifier.semantics {
                            customActions = buildList {
                                if (index > 0) add(CustomAccessibilityAction("Move ${e.name} up") { moveBy(e.id, -1); true })
                                if (index < activeIds.lastIndex) add(CustomAccessibilityAction("Move ${e.name} down") { moveBy(e.id, 1); true })
                            }
                        })
                        // Measured with its gap below, so the drag's swaps step a whole card.
                        .onSizeChanged { reorder.heights[e.id] = it.height }
                        .graphicsLayer {
                            translationY = reorder.offsetOf(e.id)
                            val s = 1f + 0.025f * lift
                            scaleX = s; scaleY = s
                        }
                        .padding(bottom = 12.dp),
                ) {
                    ActiveEntryCard(
                        e, expanded = e.id == shown, lastLabel = lastLabels[e.exerciseId], preferred = preferred, actions = actions,
                        holdToReorder = if (onMove == null) null else Modifier.reorderRow(
                            reorder, e.id,
                            onTap = { tapCard() },
                            onStart = { if (draft.isEmpty()) draft.addAll(latestIds) },
                            onDrag = { dy -> reorder.dragBy(dy, draft) { it } },
                            onDrop = {
                                val ids = draft.toList()
                                if (ids == latestStoredIds()) draft.clear() else latestMove?.invoke(ids)
                            },
                            tapLabel = "Expand ${e.name}",
                        ),
                    )
                }
            }
        }
        ReorderAutoScroll(reorder, scroll, { viewport }) { moved -> reorder.dragBy(moved, draft) { it } }
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
