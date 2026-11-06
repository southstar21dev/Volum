package dev.southstar21.volum.ui.session

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.southstar21.volum.platform.Haptic
import dev.southstar21.volum.platform.haptic
import dev.southstar21.volum.platform.hapticTick
import dev.southstar21.volum.ui.components.CircleButton
import dev.southstar21.volum.ui.components.Txt
import dev.southstar21.volum.ui.components.accentGel
import dev.southstar21.volum.ui.components.liquidGlass
import dev.southstar21.volum.ui.theme.Bold
import dev.southstar21.volum.ui.theme.Icon
import dev.southstar21.volum.ui.theme.Icons
import dev.southstar21.volum.ui.theme.T
import dev.southstar21.volum.ui.theme.Volum
import dev.southstar21.volum.ui.theme.tappable
import dev.southstar21.volum.util.nowMillis

/**
 * Running holds, by set id: when each started. Held for the app's life rather than in a screen, so
 * a hold keeps counting while its card is folded, the screen is left, or the app is in the background.
 */
object Stopwatches {
    private val started = mutableStateMapOf<String, Long>()

    fun startedAt(setId: String): Long? = started[setId]
    fun start(setId: String) { started[setId] = nowMillis() }
    /** Stops [setId]'s hold and returns how many whole seconds it ran. */
    fun stop(setId: String): Int {
        val at = started.remove(setId) ?: return 0
        return ((nowMillis() - at) / 1000).toInt()
    }
    fun cancel(setId: String) { started.remove(setId) }
}

/** "0:07", "1:32": a running hold reads as a clock. */
fun clockLabel(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

/** Milliseconds since [startedAt], ticking every frame while it's on screen. */
@Composable
fun rememberElapsed(startedAt: Long): Long {
    var elapsed by remember(startedAt) { mutableLongStateOf(nowMillis() - startedAt) }
    LaunchedEffect(startedAt) {
        while (true) withFrameMillis { elapsed = nowMillis() - startedAt }
    }
    return elapsed
}

/**
 * The current timed set's stopwatch. Idle: a Start button. Running: a bar filling toward [target]
 * seconds (last time's hold) that turns to the accent gel once it's beaten, and Stop, which logs the
 * hold and ticks the set off.
 */
@Composable
fun HoldTimer(setId: String, target: Int, onLog: (seconds: Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Volum.colors
    val startedAt = Stopwatches.startedAt(setId)
    if (startedAt == null) {
        Row(
            modifier.fillMaxWidth().height(46.dp).tappable { haptic(Haptic.Tap); Stopwatches.start(setId) }.liquidGlass(RoundedCornerShape(23.dp)),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Play, c.accent, size = 16.dp)
            Spacer(Modifier.width(8.dp))
            Txt("Start timer", size = T.Label, weight = Bold, color = c.accent)
        }
        return
    }
    val elapsedMs = rememberElapsed(startedAt)
    val seconds = (elapsedMs / 1000).toInt()
    val beaten = target > 0 && seconds >= target
    // A double pulse as the hold passes last time's, and a tiny tick every ten seconds before and
    // after, so you can feel the time go by without looking.
    LaunchedEffect(beaten) { if (beaten) haptic(Haptic.Milestone) }
    val tens = seconds / 10
    LaunchedEffect(tens) { if (tens > 0 && !(beaten && seconds - target < 1)) hapticTick() }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (target > 0) {
            val fraction = (elapsedMs / (target * 1000f)).coerceIn(0f, 1f)
            val fill by animateColorAsState(if (beaten) c.accentTop else c.accent.copy(alpha = 0.55f), tween(300), label = "hold fill")
            Box(
                Modifier.fillMaxWidth().height(8.dp).background(c.line, CircleShape).drawBehind {
                    val r = size.height / 2
                    drawRoundRect(
                        if (beaten) Brush.horizontalGradient(listOf(c.accentTop, c.accentBottom)) else Brush.horizontalGradient(listOf(fill, fill)),
                        size = Size(size.width * fraction, size.height),
                        cornerRadius = CornerRadius(r),
                    )
                },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircleButton(Icons.X, "Cancel the timer", size = 46.dp) { haptic(Haptic.Undo); Stopwatches.cancel(setId) }
            Row(
                Modifier.weight(1f).height(46.dp).tappable { onLog(Stopwatches.stop(setId)) }.accentGel(RoundedCornerShape(23.dp))
                    .semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Stop, c.onAccent, size = 14.dp)
                Spacer(Modifier.width(8.dp))
                Txt("Stop and log ${clockLabel(seconds)}", size = T.Label, weight = Bold, color = c.onAccent, tabular = true)
            }
        }
    }
}

/** The set's seconds slot while its hold runs: the live clock, in the accent. */
@Composable
fun RunningClock(startedAt: Long) {
    val seconds = (rememberElapsed(startedAt) / 1000).toInt()
    Txt(clockLabel(seconds), size = T.Heading, weight = dev.southstar21.volum.ui.theme.ExtraBold, color = Volum.colors.accent, tabular = true)
}

