package dev.southstar21.volum.ui.components

import dev.southstar21.volum.platform.nativeSegmented
import dev.southstar21.volum.platform.hapticTick
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.animation.core.Animatable
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
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import dev.southstar21.volum.ui.theme.Durations
import dev.southstar21.volum.ui.theme.Easings
import dev.southstar21.volum.ui.theme.Springs
import dev.southstar21.volum.ui.theme.LocalEntrance
import dev.southstar21.volum.ui.theme.LocalReduceMotion
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clip import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import dev.southstar21.volum.ui.theme.Bold
import dev.southstar21.volum.ui.theme.ExtraBold
import dev.southstar21.volum.ui.theme.Icon
import dev.southstar21.volum.ui.theme.Semibold
import dev.southstar21.volum.ui.theme.T
import dev.southstar21.volum.ui.theme.Volum
import dev.southstar21.volum.ui.theme.tappable
import dev.southstar21.volum.ui.theme.riseIn
import dev.southstar21.volum.ui.theme.textStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// ---- Text -----------------------------------------------------------------------------------

@Composable
fun Txt(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = T.Body,
    weight: FontWeight = FontWeight.Normal,
    color: Color = Volum.colors.ink,
    tracking: TextUnit = 0.em,
    align: TextAlign = TextAlign.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    tabular: Boolean = false,
    lineHeight: TextUnit = TextUnit.Unspecified,
) {
    BasicText(
        text,
        modifier,
        style = textStyle(size, weight, color, tracking, align, lineHeight, tabular),
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

@Composable
fun Mute(text: String, modifier: Modifier = Modifier, size: TextUnit = T.Small, weight: FontWeight = FontWeight.Normal, maxLines: Int = Int.MAX_VALUE, align: TextAlign = TextAlign.Unspecified) =
    Txt(text, modifier, size, weight, Volum.colors.mute, maxLines = maxLines, align = align)

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) =
    Txt(text, modifier.riseIn(14f), size = T.Title, weight = ExtraBold, tracking = T.TitleTracking)

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        Txt(text, Modifier.weight(1f), size = T.Subhead, weight = Bold)
        trailing?.let { Mute(it, weight = Semibold) }
    }
}

/** Big number with a small unit: "62.5 kg". */
@Composable
fun NumberUnit(value: String, unit: String, modifier: Modifier = Modifier, size: TextUnit = T.Heading, color: Color = Volum.colors.ink) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Txt(value, size = size, weight = ExtraBold, tracking = (-0.02).em, tabular = true, color = color)
        Spacer(Modifier.width(3.dp))
        Txt(unit, Modifier.padding(bottom = 2.dp), size = T.Caption, weight = Semibold, color = Volum.colors.mute)
    }
}

// ---- Surfaces -------------------------------------------------------------------------------

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

@Composable
fun CircleButton(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 20.dp,
    tint: Color = Volum.colors.ink,
    accent: Boolean = false,
    enabled: Boolean = true,
    iconModifier: Modifier = Modifier,
    /** Meant to be tapped quickly in a row (a stepper): every tap counts, none is taken for a double tap. */
    repeatable: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .tappable(enabled = enabled, haptic = accent, debounce = !repeatable, onClick = onClick)
            .then(if (accent) Modifier.accentGel(CircleShape) else Modifier.liquidGlass(CircleShape))
            .semantics { contentDescription?.let { this.contentDescription = it } },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, if (accent) Volum.colors.onAccent else tint, size = iconSize, modifier = iconModifier)
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, height: Dp = 56.dp, icon: ImageVector? = null, busy: Boolean = false, haptic: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(height / 2)
    Row(
        // [haptic] false: the caller plays its own click (one per press, never two).
        modifier.fillMaxWidth().height(height).tappable(enabled = enabled && !busy, haptic = haptic, onClick = onClick).accentGel(shape),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            Spinner(Volum.colors.onAccent)
        } else {
            Txt(text, size = T.BodyLarge, weight = Bold, color = Volum.colors.onAccent)
            icon?.let { Spacer(Modifier.width(8.dp)); Icon(it, Volum.colors.onAccent, size = 20.dp) }
        }
    }
}

@Composable
fun GlassButton(text: String, modifier: Modifier = Modifier, height: Dp = 52.dp, icon: ImageVector? = null, color: Color = Volum.colors.ink, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(height / 2)
    Row(
        modifier.height(height).tappable(enabled = enabled, onClick = onClick).liquidGlass(shape).padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, color, size = 18.dp); Spacer(Modifier.width(8.dp)) }
        Txt(text, size = T.Body, weight = Bold, color = color, maxLines = 1)
    }
}

@Composable
fun TextButton(text: String, modifier: Modifier = Modifier, color: Color = Volum.colors.accent, size: TextUnit = T.Body, onClick: () -> Unit) {
    // At least 48dp tall, the minimum touch target, however small the text.
    Box(modifier.heightIn(min = 48.dp).tappable(onClick = onClick).padding(horizontal = 6.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Txt(text, size = size, weight = Bold, color = color)
    }
}

/** A small glass pill: chips, the timer, labels on toolbars. */
@Composable
fun Pill(
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    selected: Boolean = false,
    /** False for an action two quick taps should do twice (adding a set), not once. */
    debounce: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(height / 2)
    Row(
        modifier
            .height(height)
            .then(if (onClick != null) Modifier.tappable(debounce = debounce, onClick = onClick).semantics { this.selected = selected } else Modifier)
            .then(if (selected) Modifier.accentGel(shape) else Modifier.liquidGlass(shape))
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun Spinner(color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            rotation.animateTo(360f, tween(900, easing = androidx.compose.animation.core.LinearEasing))
            rotation.snapTo(0f)
        }
    }
    Box(modifier.size(size).graphicsLayer { rotationZ = rotation.value }, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            drawArc(color, 0f, 270f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
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

// ---- Fields ---------------------------------------------------------------------------------

@Composable
fun BareField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    size: TextUnit = T.Subhead,
    weight: FontWeight = Semibold,
    align: TextAlign = TextAlign.Start,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    password: Boolean = false,
    /** The IME action (Done, Go, Search or Send). Null keeps the system's default for [imeAction]: Done closes the keyboard. */
    onDone: (() -> Unit)? = null,
    singleLine: Boolean = true,
    /** What the field is for, read by screen readers (its visible label sits beside it, not inside). */
    label: String? = null,
    /** An autofill hint: email, password, a new password. */
    contentType: ContentType? = null,
) {
    val c = Volum.colors
    val style = textStyle(size, weight, c.ink, align = align)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.semantics {
            label?.let { contentDescription = it }
            contentType?.let { this.contentType = it }
        },
        textStyle = style,
        singleLine = singleLine,
        cursorBrush = SolidColor(c.accent),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = if (onDone == null) KeyboardActions.Default else KeyboardActions(onDone = { onDone() }, onGo = { onDone() }, onSearch = { onDone() }, onSend = { onDone() }),
        decorationBox = { inner ->
            Box(contentAlignment = if (align == TextAlign.Center) Alignment.Center else Alignment.CenterStart) {
                // The full mute colour: any lighter falls under 4.5:1 on the field and milky surfaces.
                if (value.isEmpty()) BasicText(placeholder, style = style.copy(color = c.mute))
                inner()
            }
        },
    )
}

/** A labelled field row inside a grouped milky/glass box. */
@Composable
fun LabeledField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, keyboardType: KeyboardType = KeyboardType.Text, password: Boolean = false, imeAction: ImeAction = ImeAction.Next, contentType: ContentType? = null, onDone: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 11.dp)) {
        // The visible label is read as the field's own description instead.
        Mute(label, Modifier.clearAndSetSemantics {}, size = T.Caption, weight = Semibold)
        Spacer(Modifier.height(2.dp))
        BareField(value, onValueChange, Modifier.fillMaxWidth(), keyboardType = keyboardType, password = password, imeAction = imeAction, onDone = onDone, label = label, contentType = contentType)
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier, inset: Dp = 18.dp, end: Dp = inset) {
    Box(modifier.fillMaxWidth().padding(start = inset, end = end).height(1.dp).background(Volum.colors.line))
}

/** A neumorphic pressed-in field with an optional leading icon and trailing slot. */
@Composable
fun NeuField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    size: TextUnit = T.Subhead,
    align: TextAlign = TextAlign.Start,
    height: Dp = 62.dp,
    label: String? = null,
    onDone: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().height(height).neuIn(RoundedCornerShape(22.dp)).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.let { Icon(it, Volum.colors.mute, size = 20.dp) }
        BareField(value, onValueChange, Modifier.weight(1f), placeholder, size, keyboardType = keyboardType, password = password, imeAction = imeAction, onDone = onDone, align = align, label = label ?: placeholder.ifEmpty { null })
        trailing?.invoke()
    }
}

// ---- Overlays (sheets above the tab bar) ----------------------------------------------------

@Stable
class OverlayHost {
    internal val entries = mutableStateListOf<OverlayEntry>()
    val isShowing: Boolean get() = entries.isNotEmpty()
}

@Stable
internal class OverlayEntry(content: @Composable () -> Unit, locals: CompositionLocalContext) {
    var content by mutableStateOf(content)
    /** The CompositionLocals where the overlay was declared (the screen's session, navigator…). */
    var locals by mutableStateOf(locals)
}

val LocalOverlayHost = staticCompositionLocalOf<OverlayHost> { error("No OverlayHost: overlays need VolumApp's OverlayLayer") }

@Composable
fun OverlayLayer(host: OverlayHost) {
    host.entries.forEach { entry ->
        androidx.compose.runtime.key(entry) {
            // Drawn at the top of the app, but with the locals of the screen that declared it; sheets
            // don't take part in their screen's entrance.
            CompositionLocalProvider(entry.locals) {
                CompositionLocalProvider(LocalEntrance provides null) { entry.content() }
            }
        }
    }
}

/** Draws [content] on the app's top layer while this is composed. */
@Composable
fun Overlay(content: @Composable () -> Unit) {
    val host = LocalOverlayHost.current
    val locals = currentCompositionLocalContext
    val entry = remember { OverlayEntry(content, locals) }
    SideEffect {
        entry.content = content
        entry.locals = locals
    }
    DisposableEffect(host) {
        host.entries.add(entry)
        onDispose { host.entries.remove(entry) }
    }
}

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val latest = rememberUpdatedState(onBack)
    val state = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(state = state, isBackEnabled = enabled, onBackCompleted = { latest.value() })
}

/**
 * A glass bottom sheet over a scrim. Stays composed while it animates out; drag the sheet down or tap
 * the scrim to dismiss, or (for screen readers) activate the grabber. [fill] makes it tall (search
 * sheets). Once [open] turns false it takes no more input, so nothing on it runs twice while it leaves.
 */
@Composable
fun BottomSheet(open: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier, fill: Boolean = false, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val progress = remember { Animatable(0f) }
    val drag = remember { Animatable(0f) }
    val isOpen by rememberUpdatedState(open)
    var mounted by remember { mutableStateOf(open) }
    // With reduced motion the sheet fades in and out where it rests instead of sliding.
    val reduce = LocalReduceMotion.current
    LaunchedEffect(open) {
        if (open) {
            mounted = true
            drag.snapTo(0f) // a drag-dismissed sheet keeps its offset while it slides out
            progress.animateTo(1f, if (reduce) tween<Float>(Durations.Fade, easing = Easings.Enter) else Springs.sheet<Float>())
        } else {
            progress.animateTo(0f, tween(Durations.Fade, easing = Easings.Exit))
            mounted = false
        }
    }
    if (!mounted && !open) return
    val c = Volum.colors
    val scope = rememberCoroutineScope()
    var height by remember { mutableIntStateOf(1) }
    val flick = with(LocalDensity.current) { FlickVelocity.toPx() }
    // A fresh one each time the sheet opens: its actions run once per opening (see [sheetAction]).
    val once = remember(open) { SheetOnce() }
    Overlay {
        BackHandler(open) { onDismiss() }
        Box(Modifier.fillMaxSize()) {
            Box(
                // Faded in the layer, so the animation redraws the scrim without recomposing the sheet.
                // Screen readers dismiss with the sheet's grabber, its own buttons or back, not this backdrop.
                Modifier.fillMaxSize().graphicsLayer { alpha = progress.value }.background(c.scrim)
                    .tappable(role = null, highlight = false, debounce = false) { onDismiss() }
                    .clearAndSetSemantics {},
            )
            Column(
                modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 8.dp)
                    .padding(bottom = 8.dp)
                    .navigationBarsPadding()
                    .imePadding()
                    .then(if (fill) Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(top = 40.dp).fillMaxSize() else Modifier)
                    .onSizeChanged { height = it.height }
                    // Moved in the layer, not by offset: sliding redraws the sheet without re-laying it out.
                    .graphicsLayer {
                        translationY = (if (reduce) 0f else (1f - progress.value) * (height + 40)) + drag.value
                        if (reduce) alpha = progress.value
                    }
                    // The drag also keeps taps on the sheet from reaching the scrim under it.
                    .draggable(
                        rememberDraggableState { d -> scope.launch { drag.snapTo((drag.value + d).coerceAtLeast(0f)) } },
                        Orientation.Vertical,
                        onDragStopped = { v ->
                            if (drag.value > height * 0.25f || v > flick) {
                                // Don't spring back: the exit slides the sheet on down from where it
                                // was let go. Only if the dismiss was refused does it return; a caller
                                // that closes from a coroutine gets a moment to do so first.
                                onDismiss()
                                val closed = withTimeoutOrNull(Durations.Fade.toLong()) { snapshotFlow { isOpen }.first { !it } }
                                if (closed != null) return@draggable
                            }
                            if (reduce) drag.snapTo(0f) else drag.animateTo(0f, Springs.snapBack())
                        },
                    )
                    .sheetGlass(RoundedCornerShape(40.dp))
                    // Leaving: hidden from screen readers too, so nothing on it can be activated again.
                    .then(if (open) Modifier else Modifier.clearAndSetSemantics {})
                    .semantics {
                        isTraversalGroup = true
                        title?.let { paneTitle = it }
                        dismiss { onDismiss(); true }
                    }
                    .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 22.dp),
            ) {
                Box(
                    Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(5.dp).clip(CircleShape).background(c.line)
                        // How a screen reader closes a sheet with no button of its own: VoiceOver has no back.
                        .semantics {
                            contentDescription = "Close"
                            role = Role.Button
                            onClick(label = "Close") { onDismiss(); true }
                        },
                )
                Spacer(Modifier.height(14.dp))
                val still by remember { androidx.compose.runtime.derivedStateOf { open && progress.value == 1f && drag.value == 0f } }
                CompositionLocalProvider(
                    dev.southstar21.volum.platform.LocalNativeViewsSettled provides still,
                    LocalInGlass provides true,
                    LocalSheetOnce provides once,
                ) { content() }
            }
            if (!open) {
                // Over the sheet and scrim while they leave: a second tap lands here, not on an action
                // that already ran or on the screen under a sheet that is still drawn.
                Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } })
            }
        }
    }
}

/** A downward fling past this dismisses a sheet however little it was dragged. */
private val FlickVelocity = 400.dp

/** Lets one action through per opening of a sheet. */
private class SheetOnce {
    private var fired = false

    fun run(action: () -> Unit) {
        if (fired) return
        fired = true
        action()
    }
}

private val LocalSheetOnce = compositionLocalOf<SheetOnce?> { null }

/**
 * [action], run at most once per opening of the [BottomSheet] it's in: a double tap, or a second tap
 * before a caller's asynchronous close lands, doesn't run it twice. Outside a sheet it runs every time.
 */
@Composable
fun sheetAction(action: () -> Unit): () -> Unit {
    val once = LocalSheetOnce.current ?: return action
    return { once.run(action) }
}

/**
 * [value], or while it is null the last value it had: a sheet that closes when its target goes null
 * keeps showing that target while it slides away. A plain holder, not state: it never recomposes.
 */
@Composable
fun <T : Any> rememberLast(value: T?): T? {
    val holder = remember { arrayOfNulls<Any>(1) }
    if (value != null) holder[0] = value
    @Suppress("UNCHECKED_CAST")
    return holder[0] as T?
}

/** An error message under a form: red, and announced by screen readers when it appears. */
@Composable
fun ErrorText(text: String, modifier: Modifier = Modifier, size: TextUnit = T.Small, weight: FontWeight = FontWeight.Normal) {
    Txt(text, modifier.semantics { liveRegion = LiveRegionMode.Polite }, size = size, weight = weight, color = Volum.colors.danger)
}


data class SheetAction(
    val label: String,
    val icon: ImageVector? = null,
    val subtitle: String? = null,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/** A titled sheet of actions in one glass group, with Cancel underneath. */
@Composable
fun ActionSheet(open: Boolean, title: String?, subtitle: String? = null, actions: List<SheetAction>, onDismiss: () -> Unit, modifier: Modifier = Modifier, header: (@Composable () -> Unit)? = null) {
    BottomSheet(open, onDismiss, modifier, title = title) {
        if (header != null) header()
        else if (title != null) {
            Column(Modifier.padding(start = 6.dp, end = 6.dp, bottom = 14.dp)) {
                Txt(title, size = T.SheetTitle, weight = ExtraBold)
                subtitle?.let { Spacer(Modifier.height(2.dp)); Mute(it) }
            }
        }
        Column(Modifier.fillMaxWidth().glassCard(RoundedCornerShape(24.dp)).padding(vertical = 4.dp)) {
            actions.forEachIndexed { i, action ->
                if (i > 0) Divider(inset = 16.dp)
                // The action first: it may read state that dismissing clears.
                SheetActionRow(action) { action.onClick(); onDismiss() }
            }
        }
        Spacer(Modifier.height(12.dp))
        GlassButton("Cancel", Modifier.fillMaxWidth(), height = 56.dp, onClick = onDismiss)
    }
}

/**
 * One action in a sheet's glass group: an optional glass icon, the label and a subtitle. In a sheet it
 * runs once per opening (see [sheetAction]).
 */
@Composable
fun SheetActionRow(action: SheetAction, modifier: Modifier = Modifier, plainIcon: Boolean = false, onClick: () -> Unit) {
    val c = Volum.colors
    val color = if (action.destructive) c.danger else c.ink
    Row(
        modifier.fillMaxWidth().heightIn(min = if (action.subtitle != null) 62.dp else 56.dp).tappable(onClick = sheetAction(onClick)).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        action.icon?.let {
            if (plainIcon) Icon(it, color, size = 20.dp)
            else Box(Modifier.size(36.dp).liquidGlass(CircleShape), contentAlignment = Alignment.Center) { Icon(it, color, size = 18.dp) }
        }
        Column(Modifier.weight(1f)) {
            Txt(action.label, size = T.BodyLarge, weight = Semibold, color = color)
            action.subtitle?.let { Mute(it, size = T.Caption) }
        }
    }
}

/** A short confirm sheet: one destructive or primary choice and Cancel. */
@Composable
fun ConfirmSheet(open: Boolean, title: String, message: String? = null, confirm: String, destructive: Boolean = true, onConfirm: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    BottomSheet(open, onDismiss, modifier, title = title) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Txt(title, size = T.SheetTitle, weight = ExtraBold, align = TextAlign.Center)
            message?.let { Spacer(Modifier.height(6.dp)); Mute(it, size = T.Label, align = TextAlign.Center) }
        }
        // Confirm first: the action may read state that dismissing clears. Once per opening.
        val confirmOnce = sheetAction { onConfirm(); onDismiss() }
        if (destructive) {
            DangerButton(confirm, onClick = confirmOnce)
        } else {
            PrimaryButton(confirm, onClick = confirmOnce)
        }
        Spacer(Modifier.height(10.dp))
        GlassButton("Cancel", Modifier.fillMaxWidth(), height = 56.dp, onClick = onDismiss)
    }
}

@Composable
fun DangerButton(text: String, modifier: Modifier = Modifier, subtitle: String? = null, onClick: () -> Unit) {
    val c = Volum.colors
    Column(
        modifier.fillMaxWidth().heightIn(min = if (subtitle != null) 66.dp else 56.dp).clip(RoundedCornerShape(24.dp)).background(c.dangerSoft).border(1.dp, c.liquidRim.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
            .tappable(onClick = onClick).padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = if (subtitle != null) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        Txt(text, size = T.BodyLarge, weight = Bold, color = c.danger)
        subtitle?.let { Mute(it, size = T.Caption) }
    }
}

// ---- Layout helpers -------------------------------------------------------------------------

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
fun Avatar(name: String, modifier: Modifier = Modifier, size: Dp = 44.dp, warm: Boolean = false, contentDescription: String? = null) {
    val c = Volum.colors
    val letter = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        modifier.size(size).then(
            if (warm) Modifier.clip(CircleShape).background(Brush.linearGradient(listOf(c.warmTop, c.warmBottom)))
            else Modifier.liquidGlass(CircleShape),
        ).clearAndSetSemantics { contentDescription?.let { this.contentDescription = it } },
        contentAlignment = Alignment.Center,
    ) {
        Txt(letter, size = (size.value * 0.4f).sp, weight = ExtraBold, color = if (warm) c.warmInk else c.accent)
    }
}

/** Small tag like PR. */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier) {
    val c = Volum.colors
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(c.soft).padding(horizontal = 7.dp, vertical = 2.dp)) {
        Txt(text, size = T.Tiny, weight = ExtraBold, color = c.accent, tracking = 0.04.em)
    }
}

/** A sheet's title line. */
@Composable
fun SheetTitle(text: String, modifier: Modifier = Modifier) = Txt(text, modifier.padding(horizontal = 6.dp), size = T.SheetTitle, weight = ExtraBold)

/** A decorative round glass badge (a chevron on a tappable row): not a button of its own. */
@Composable
fun GlassBadge(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 38.dp, iconSize: Dp = 16.dp, tint: Color = Volum.colors.ink) {
    Box(modifier.size(size).liquidGlass(CircleShape).clearAndSetSemantics {}, contentAlignment = Alignment.Center) { Icon(icon, tint, size = iconSize) }
}

/** Keeps a screen's content to a readable column on tablets and in landscape, centred. */
fun Modifier.readableWidth(): Modifier = this.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 640.dp)
