// Tab bar construction adapted from Kyant0/AndroidLiquidGlass's LiquidBottomTabs (Apache-2.0), via the Kaloria app.
package dev.southstar21.volum.ui.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.sign
import dev.southstar21.volum.ui.theme.LocalReduceMotion
import dev.southstar21.volum.ui.theme.tappable

/**
 * iOS 26-style Liquid Glass tabs: one pane of glass whose selection is itself a drop of glass you
 * can tap, or press and drag between items. The drop magnifies the item beneath it (shown in the
 * accent colour) with a little chromatic aberration, swells while held and squashes with speed.
 *
 * [onSelect] fires once the drop has nearly arrived, so the page switch doesn't make the drop
 * stutter on its way there. If the caller doesn't take the selection, the drop returns to
 * [selectedIndex]. With reduced motion the drop moves without swelling or stretching.
 *
 * @param item one item's content; [tinted] is the accent copy the drop shows.
 */
@Composable
fun LiquidTabs(
    count: Int,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop,
    accent: Color,
    container: Color,
    modifier: Modifier = Modifier,
    /** The resting fill of the selection drop. */
    selection: Color = accent.copy(alpha = 0.25f),
    height: Dp = 64.dp,
    /** Paints the pane's glass over the refracted backdrop; defaults to a flat [container] fill. */
    surface: (androidx.compose.ui.graphics.drawscope.DrawScope.(androidx.compose.ui.graphics.Shape) -> Unit)? = null,
    /** The drop crossed onto another item: a detent, for a haptic tick. */
    onCross: () -> Unit = {},
    item: @Composable ColumnScope.(index: Int, tinted: Boolean) -> Unit,
) {
    if (count <= 0) return
    val reduce = LocalReduceMotion.current
    val selectedNow by rememberUpdatedState(selectedIndex)
    val onSelectNow by rememberUpdatedState(onSelect)
    val shadowColor = Color.Black.copy(alpha = 0.10f)
    val itemsBackdrop = rememberLayerBackdrop()
    val pillHeight = height - 8.dp

    // The width is state read only by layout, draw and gesture lambdas: the bar narrowing as the +
    // grows in must not recompose the tabs every frame (it made the + stutter in).
    var widthPx by remember { mutableFloatStateOf(0f) }
    Box(
        modifier.onSizeChanged { widthPx = it.width.toFloat() },
        contentAlignment = Alignment.CenterStart,
    ) {
        val density = LocalDensity.current
        val inset = with(density) { 8.dp.toPx() }
        val itemWidthState = remember(count, inset) { derivedStateOf { ((widthPx - inset) / count).coerceAtLeast(1f) } }
        val itemWidth by itemWidthState
        val scope = rememberCoroutineScope()
        // One state for the tabs' lifetime: taps and drags write it, the caller follows it.
        var currentIndex by remember { mutableIntStateOf(selectedIndex) }
        val drag = remember(scope, count, reduce) {
            DampedDragAnimation(
                animationScope = scope,
                initialValue = selectedIndex.toFloat(),
                valueRange = 0f..(count - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = if (reduce) 1f else 78f / 56f,
                reduceMotion = reduce,
                onDragStopped = {
                    val target = targetValue.fastRoundToInt().fastCoerceIn(0, count - 1)
                    currentIndex = target
                    animateToValue(target.toFloat())
                },
                onDrag = { _, amount ->
                    updateValue((targetValue + amount.x / itemWidth).fastCoerceIn(0f, (count - 1).toFloat()))
                },
            )
        }
        val panelOffset by remember(density, drag) {
            derivedStateOf {
                val fraction = (drag.offset / widthPx.coerceAtLeast(1f)).fastCoerceIn(-1f, 1f)
                with(density) { 4.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction)) }
            }
        }
        // Follow selection changes that didn't come from the tabs (back, swipes).
        LaunchedEffect(selectedIndex) {
            if (currentIndex != selectedIndex) {
                currentIndex = selectedIndex
                drag.animateToValue(selectedIndex.toFloat())
            }
        }
        val onCrossNow by rememberUpdatedState(onCross)
        // A detent each time the drop's centre passes onto another item, dragged or animating.
        LaunchedEffect(drag) {
            snapshotFlow { drag.value.fastRoundToInt() }.drop(1).collect { onCrossNow() }
        }
        LaunchedEffect(drag) {
            snapshotFlow { currentIndex }.drop(1).collectLatest { index ->
                drag.animateToValue(index.toFloat())
                snapshotFlow { abs(drag.value - index) < 0.2f }.first { it }
                if (index != selectedNow) {
                    onSelectNow(index)
                    // A caller that refuses the item (or never gets to it) leaves the drop where the
                    // caller's selection is, not on the item it was dropped on. Waits a little, for a
                    // selection that lands asynchronously.
                    val taken = withTimeoutOrNull(SelectionGraceMillis) { snapshotFlow { selectedNow }.first { it == index } }
                    if (taken == null) currentIndex = selectedNow
                }
            }
        }

        // Keyed on the drag too: a new item count makes a new drag, and the glow must follow that one.
        val highlight = remember(scope, drag) {
            InteractiveHighlight(scope, position = { size, _ -> Offset((drag.value + 0.5f) * itemWidth + panelOffset, size.height / 2f) })
        }

        @Composable
        fun items(tinted: Boolean, rowModifier: Modifier, scale: () -> Float = { 1f }) = Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
            repeat(count) { index ->
                Column(
                    Modifier
                        .clip(Capsule())
                        .tappable(role = Role.Tab, highlight = false, debounce = false) { currentIndex = index }
                        .semantics { selected = index == selectedIndex }
                        .fillMaxHeight()
                        .weight(1f)
                        .graphicsLayer { val s = scale(); scaleX = s; scaleY = s },
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { item(index, tinted) }
            }
        }

        // The pane: one piece of glass holding the items.
        items(
            tinted = false,
            rowModifier = Modifier
                .selectableGroup()
                .graphicsLayer { translationX = panelOffset }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = { blur(32.dp.toPx()) },
                    // A faint rim: the system bar's edge barely catches the light.
                    highlight = { Highlight.Default.copy(alpha = 0.3f) },
                    layerBlock = {
                        val s = paneSwell(if (reduce) 0f else 16.dp.toPx(), size.width, drag.pressProgress)
                        scaleX = s; scaleY = s
                    },
                    shadow = { Shadow(radius = 24.dp, color = shadowColor) },
                    onDrawSurface = { if (surface != null) surface(Capsule()) else drawRect(container) },
                )
                .then(highlight.modifier)
                .height(height)
                .fillMaxWidth()
                .padding(4.dp),
        )

        // An invisible accent-tinted copy of the items, recorded so the drop shows the item under
        // it in the accent colour as it passes. Its own glass only exists while the drop is held:
        // at rest a flat fill looks the same through the drop and spares a backdrop per scroll frame.
        val held by remember(drag) { derivedStateOf { drag.pressProgress > 0f } }
        items(
            tinted = true,
            scale = if (reduce) ({ 1f }) else ({ lerp(1f, 1.2f, drag.pressProgress) }),
            rowModifier = Modifier
                .clearAndSetSemantics {}
                .alpha(0f)
                .layerBackdrop(itemsBackdrop)
                .graphicsLayer { translationX = panelOffset }
                .then(
                    if (held) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { Capsule() },
                            effects = {
                                val p = drag.pressProgress
                                vibrancy()
                                blur(8.dp.toPx() * p)
                                lens(24.dp.toPx() * p, 24.dp.toPx() * p)
                            },
                            highlight = { Highlight.Default.copy(alpha = drag.pressProgress) },
                            // Starts out exactly as the resting copy below looks (near-opaque, with the
                            // pill's tint behind the items), so a press doesn't flash the pill to
                            // another colour.
                            onDrawSurface = {
                                drawRect(container.copy(alpha = container.alpha.coerceAtLeast(0.94f)))
                                // The tint stays at full strength through the press: only the glass
                                // (lens, rim, swell) comes and goes, never the pill's colour.
                                drawRect(selection)
                            },
                        )
                    } else {
                        // Seen only through the resting pill, which doesn't blur: nearly opaque, so
                        // the page doesn't show sharp through it.
                        // The pill's tint goes here too, behind the items, so the selected item shows
                        // crisp on it as on iOS instead of under a wash of the tint.
                        Modifier
                            .background(container.copy(alpha = container.alpha.coerceAtLeast(0.94f)), Capsule())
                            .background(selection, Capsule())
                    },
                )
                .then(highlight.modifier)
                .height(pillHeight)
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .graphicsLayer(colorFilter = ColorFilter.tint(accent)),
        )

        // The selection: a drop of glass, always lensing the accent copy under it, that swells and
        // magnifies more while held.
        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .graphicsLayer { translationX = drag.value * itemWidth + panelOffset }
                .then(highlight.gestureModifier)
                .then(drag.modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, itemsBackdrop),
                    shape = { Capsule() },
                    // At rest the selection is a flat pill, as on iOS; it only turns into a lensing
                    // drop of glass while held.
                    effects = {
                        val p = drag.pressProgress
                        if (p > 0f) lens(10.dp.toPx() * p, 14.dp.toPx() * p, chromaticAberration = true)
                    },
                    highlight = { Highlight.Default.copy(alpha = drag.pressProgress) },
                    shadow = { Shadow(radius = 8.dp, alpha = drag.pressProgress) },
                    innerShadow = { InnerShadow(radius = 3.dp + 5.dp * drag.pressProgress, alpha = drag.pressProgress) },
                    layerBlock = {
                        scaleX = drag.scaleX
                        scaleY = drag.scaleY
                        if (!reduce) {
                            val v = drag.velocity / 10f
                            scaleX /= 1f - (v * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                            scaleY *= 1f - (v * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                        }
                    },
                    // Nothing of its own: the pill's tint is drawn in the copy beneath it, at rest and
                    // held alike, so pressing never changes its colour.
                    onDrawSurface = {},
                )
                .height(pillHeight)
                .fillMaxWidth(1f / count),
        )
    }
}

/**
 * How much the pane swells while held: by [grow] pixels across its [width]. The layer's first pass
 * can run before it has a size, and 16dp / 0 made the scale NaN, which the layer kept: the pane and
 * its labels never drew (seen in sheets), leaving only the selection until a press reset it.
 */
private fun paneSwell(grow: Float, width: Float, progress: Float): Float =
    if (width <= 0f) 1f else lerp(1f, 1f + grow / width, progress)

/** How long a tapped item waits for the caller to select it before the drop returns to the selection. */
private const val SelectionGraceMillis = 300L
