package dev.southstar21.volum.ui.components

import androidx.compose.ui.draw.clip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.southstar21.volum.ui.theme.Bold
import dev.southstar21.volum.ui.theme.Icon
import dev.southstar21.volum.ui.theme.T
import dev.southstar21.volum.ui.theme.Volum
import dev.southstar21.volum.ui.theme.tappable

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

/** A 38 dp glass circle inside a 48 dp touch target. */
@Composable
fun SmallCircleButton(icon: ImageVector, contentDescription: String, iconSize: Dp, accent: Boolean = false, onClick: () -> Unit) {
    val c = Volum.colors
    Box(
        Modifier.size(48.dp).tappable(haptic = accent, onClick = onClick).semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(38.dp).then(if (accent) Modifier.accentGel(CircleShape) else Modifier.liquidGlass(CircleShape)), contentAlignment = Alignment.Center) {
            Icon(icon, if (accent) c.onAccent else c.ink, size = iconSize)
        }
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
