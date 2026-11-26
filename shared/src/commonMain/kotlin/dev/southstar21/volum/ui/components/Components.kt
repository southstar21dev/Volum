package dev.southstar21.volum.ui.components

import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import dev.southstar21.volum.platform.nativeSegmented
import dev.southstar21.volum.platform.hapticTick
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import dev.southstar21.volum.ui.theme.LocalReduceMotion
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.volum.ui.theme.ExtraBold
import dev.southstar21.volum.ui.theme.Icon
import dev.southstar21.volum.ui.theme.Semibold
import dev.southstar21.volum.ui.theme.T
import dev.southstar21.volum.ui.theme.Volum
import dev.southstar21.volum.ui.theme.tappable
import dev.southstar21.volum.ui.theme.textStyle

val CardShape = RoundedCornerShape(28.dp)

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = CardShape,
    padding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .then(if (onClick != null) Modifier.tappable(onClick = onClick) else Modifier)
            .glassCard(shape)
            .padding(padding),
    ) {
        CompositionLocalProvider(LocalInGlass provides true) { content() }
    }
}

/**
 * Every switcher in the app: the platform's own control where there is one (UISegmentedControl on
 * iOS, see nativeSegmented), otherwise a plain copy of its look: a grey capsule track with a white
 * capsule sliding under the selected label. No glass; only the dock's tabs are liquid.
 */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, height: Dp = 38.dp, fill: Boolean = false) {
    if (options.isEmpty()) return
    val c = Volum.colors
    nativeSegmented?.let { native ->
        native(options, selected, onSelect, modifier, fill)
        return
    }
    val style = textStyle(T.Small, ExtraBold)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Equal segments sized to the longest label, as UISegmentedControl does.
    val segment = remember(options, style, density) {
        with(density) { options.maxOf { measurer.measure(it, style).size.width }.toDp() } + 28.dp
    }
    val reduce = LocalReduceMotion.current
    val position by animateFloatAsState(
        selected.coerceIn(0, options.lastIndex).toFloat(),
        if (reduce) snap() else spring(dampingRatio = 0.82f, stiffness = 520f),
        label = "segment",
    )
    // The track is [height] tall, like the system control; each segment's touch area is the full
    // 48dp minimum, centred on it.
    Box(
        modifier
            .then(if (fill) Modifier.fillMaxWidth() else Modifier.width(segment * options.size + 4.dp))
            .height(maxOf(height, 48.dp)),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(CircleShape)
                .background(Color(0x1F767680))
                .padding(2.dp),
        ) {
            val each = maxWidth / options.size
            Box(
                Modifier
                    .offset { IntOffset((each.toPx() * position).roundToInt(), 0) }
                    .width(each)
                    .fillMaxHeight()
                    .shadow(3.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.12f))
                    .background(Color.White, CircleShape),
            )
        }
        Row(Modifier.fillMaxSize().selectableGroup()) {
            options.forEachIndexed { index, option ->
                val on = index == selected
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(selected = on, role = Role.Tab, indication = null, interactionSource = null) {
                            if (!on) {
                                hapticTick()
                                onSelect(index)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Txt(option, size = T.Small, weight = Semibold, color = c.ink, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier, inset: Dp = 18.dp, end: Dp = inset) {
    Box(modifier.fillMaxWidth().padding(start = inset, end = end).height(1.dp).background(Volum.colors.line))
}

/** Let a clipping child (a scroller) extend [by] past its parent's horizontal padding, so shadows aren't cut off. */
fun Modifier.bleedHorizontally(by: Dp): Modifier = this.layout { measurable, constraints ->
    val px = by.roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + 2 * px, maxWidth = constraints.maxWidth + 2 * px))
    layout(placeable.width - 2 * px, placeable.height) { placeable.place(-px, 0) }
}

/** Space for the status bar plus the screen's top margin. */
@Composable
fun TopInset(extra: Dp = 14.dp) {
    Spacer(Modifier.windowInsetsPadding(WindowInsets.statusBars).height(extra))
}

/** The floating toolbar row: a leading button, a centred item and a trailing item. */
@Composable
fun Toolbar(modifier: Modifier = Modifier, leading: @Composable () -> Unit = { Spacer(Modifier.size(44.dp)) }, center: @Composable () -> Unit = {}, trailing: @Composable () -> Unit = { Spacer(Modifier.size(44.dp)) }) {
    Box(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Box(Modifier.align(Alignment.CenterStart)) { leading() }
        Box(Modifier.align(Alignment.Center)) { center() }
        Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
    }
}

/**
 * A round initial. Screen readers skip it unless given a [contentDescription]: everywhere it sits beside
 * the name it stands for, and the bare letter alone says nothing.
 */
@Composable
fun Avatar(name: String, modifier: Modifier = Modifier, size: Dp = 44.dp, warm: Boolean = false, contentDescription: String? = null, photoUrl: String? = null) {
    val c = Volum.colors
    val letter = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    // The letter until the photo has loaded, and for good when there is none or it can't be fetched.
    val photo = rememberPhoto(photoUrl)
    Box(
        modifier.size(size).then(
            if (photo != null) Modifier.clip(CircleShape).background(c.glass).border(1.5.dp, c.rim, CircleShape)
            else if (warm) Modifier.clip(CircleShape).background(Brush.linearGradient(listOf(c.warmTop, c.warmBottom)))
            else Modifier.liquidGlass(CircleShape),
        ).clearAndSetSemantics { contentDescription?.let { this.contentDescription = it } },
        contentAlignment = Alignment.Center,
    ) {
        if (photo != null) {
            androidx.compose.foundation.Image(photo, null, Modifier.matchParentSize().clip(CircleShape), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        } else {
            Txt(letter, size = (size.value * 0.4f).sp, weight = ExtraBold, color = if (warm) c.warmInk else c.accent)
        }
    }
}

/** A decorative round glass badge (a chevron on a tappable row): not a button of its own. */
@Composable
fun GlassBadge(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 38.dp, iconSize: Dp = 16.dp, tint: Color = Volum.colors.ink) {
    Box(modifier.size(size).liquidGlass(CircleShape).clearAndSetSemantics {}, contentAlignment = Alignment.Center) { Icon(icon, tint, size = iconSize) }
}

/** Keeps a screen's content to a readable column on tablets and in landscape, centred. */
fun Modifier.readableWidth(): Modifier = this.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 640.dp)
