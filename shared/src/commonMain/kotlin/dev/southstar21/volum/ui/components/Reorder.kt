package dev.southstar21.volum.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.southstar21.volum.platform.hapticTick
import dev.southstar21.volum.ui.theme.Springs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The drag-to-reorder state. Rows can be any height (an open row is tall), so swaps use each row's
 * measured height: the held row swaps with a neighbour once it has moved past half of that
 * neighbour, and the neighbour slides into the gap it leaves instead of jumping there.
 *
 * The list being reordered is the caller's; [dragBy] moves items in it as the row passes others.
 */
@Stable
class Reorder(private val scope: CoroutineScope) {
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

    /** Moves the held row [dy] and swaps it past neighbours in [items]; true if it swapped. */
    fun <T> dragBy(dy: Float, items: MutableList<T>, idOf: (T) -> String): Boolean {
        val id = draggingId ?: return false
        dragOffset += dy
        var at = items.indexOfFirst { idOf(it) == id }
        if (at < 0) return false
        val own = (heights[id] ?: 0).toFloat()
        var swapped = false
        // Several swaps in one step when the finger moves fast.
        while (true) {
            val next = items.getOrNull(at + 1)?.let(idOf)
            val prev = items.getOrNull(at - 1)?.let(idOf)
            val nextH = next?.let { heights[it] }?.toFloat()
            val prevH = prev?.let { heights[it] }?.toFloat()
            if (next != null && nextH != null && dragOffset > nextH / 2) {
                items.add(at + 1, items.removeAt(at))
                dragOffset -= nextH
                nudge(next, own)
                at++
            } else if (prev != null && prevH != null && dragOffset < -prevH / 2) {
                items.add(at - 1, items.removeAt(at))
                dragOffset += prevH
                nudge(prev, -own)
                at--
            } else break
            swapped = true
            hapticTick()
        }
        // The held row stays within the list: the card clips whatever is drawn outside it.
        val above = items.take(at).sumOf { heights[idOf(it)] ?: 0 }.toFloat()
        val below = items.drop(at + 1).sumOf { heights[idOf(it)] ?: 0 }.toFloat()
        dragOffset = dragOffset.coerceIn(-above, below)
        return swapped
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

/**
 * A row's grip: held, it picks the row up and drags it. [onDrag] gets each step's vertical move and
 * passes it to [Reorder.dragBy] with the caller's list. The callbacks are read when the gesture
 * starts, once per [id]: pass ones that read current state (rememberUpdatedState), not a snapshot.
 */
fun Modifier.reorderGrip(
    reorder: Reorder,
    id: String,
    onDrag: (dy: Float) -> Unit,
    onStart: () -> Unit = {},
    onDrop: () -> Unit = {},
): Modifier =
    onGloballyPositioned { reorder.grips[id] = it }
        .pointerInput(id) {
            detectDragGesturesAfterLongPress(
                onDragStart = { at ->
                    onStart()
                    reorder.start(id)
                    reorder.pointer(id, at)
                    hapticTick()
                },
                onDragEnd = { reorder.drop(); onDrop() },
                onDragCancel = { reorder.drop(); onDrop() },
                onDrag = { change, amount ->
                    change.consume()
                    reorder.pointer(id, change.position)
                    onDrag(amount.y)
                },
            )
        }

/** While a row is held near the top or bottom of [viewport], [scroll] moves under it; [onScrolled] gets how far. */
@Composable
fun ReorderAutoScroll(reorder: Reorder, scroll: ScrollState, viewport: () -> LayoutCoordinates?, onScrolled: (Float) -> Unit) {
    val density = LocalDensity.current
    LaunchedEffect(reorder.draggingId) {
        if (reorder.draggingId == null) return@LaunchedEffect
        while (true) {
            withFrameNanos {}
            val speed = reorder.edgeSpeed(viewport(), density.run { 96.dp.toPx() }, density.run { 18.dp.toPx() })
            if (speed != 0f) {
                val moved = scroll.scrollBy(speed)
                if (moved != 0f) onScrolled(moved)
            }
        }
    }
}
