package dev.southstar21.volum.ui.components

import androidx.compose.ui.draw.clip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import dev.southstar21.volum.ui.theme.Durations
import dev.southstar21.volum.ui.theme.Easings
import dev.southstar21.volum.ui.theme.Springs
import dev.southstar21.volum.ui.theme.LocalReduceMotion
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.southstar21.volum.ui.theme.ExtraBold
import dev.southstar21.volum.ui.theme.Icon
import dev.southstar21.volum.ui.theme.Semibold
import dev.southstar21.volum.ui.theme.T
import dev.southstar21.volum.ui.theme.Volum
import dev.southstar21.volum.ui.theme.tappable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

/** A sheet's title line. */
@Composable
fun SheetTitle(text: String, modifier: Modifier = Modifier) = Txt(text, modifier.padding(horizontal = 6.dp), size = T.SheetTitle, weight = ExtraBold)
