package dev.southstar21.volum.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.TimeSource

/*
 * Volum's motion. Everything here is one-shot and drawn in a graphics layer (translation, scale,
 * alpha) or a draw pass, so it never re-measures or recomposes per frame. Nothing loops, and the
 * colour field behind the glass never moves: animating it would re-render every glass surface.
 */

/** Springs shared across the app. Every spring in Volum is one of these. */
object Springs {
    /** A soft settle. */
    fun <T> settle() = spring<T>(dampingRatio = 0.74f, stiffness = 300f)
    /** Pops: badges, the done tick. */
    fun <T> bouncy() = spring<T>(dampingRatio = 0.42f, stiffness = 520f)
    /** Things rising into place on a screen's entrance. */
    fun <T> rise() = spring<T>(dampingRatio = 0.7f, stiffness = 280f)
    /** Chart bars growing up. */
    fun <T> grow() = spring<T>(dampingRatio = 0.58f, stiffness = 210f)
    /** A pressed control sinking. */
    fun <T> press() = spring<T>(dampingRatio = 1f, stiffness = 900f)
    /** A released control springing back, with a little overshoot. */
    fun <T> release() = spring<T>(dampingRatio = 0.38f, stiffness = 420f)
    /** A sheet sliding up. */
    fun <T> sheet() = spring<T>(dampingRatio = 0.86f, stiffness = 420f)
    /** Something dragged snapping back to rest. */
    fun <T> snapBack() = spring<T>(dampingRatio = 1f, stiffness = 550f)
}

/** Durations for the tweens that aren't springs, in milliseconds. Nothing runs past 600 ms. */
object Durations {
    const val Instant = 70
    /** Things leaving: a sheet closing, the dock hiding. */
    const val Exit = 160
    const val Fade = 200
    /** Things arriving: the dock showing. */
    const val Enter = 220
    /** A page pushing or popping. */
    const val Page = 320
    /** The done burst. */
    const val Burst = 560
    /** Counting a number up. */
    const val Count = 600
}

/**
 * Easings: things arriving decelerate into place, things leaving accelerate away, and things moving
 * across the screen use the standard curve (Material 3's emphasized decelerate and accelerate).
 */
object Easings {
    val Enter: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val Exit: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard: Easing = FastOutSlowInEasing
}

/**
 * A screen's entrance clock. Each [riseIn] composed under it takes the next slot of a short stagger;
 * things that appear once the stagger has passed (data that loads later) rise straight away.
 * [animate] is false when popping back to a screen, so it doesn't replay.
 */
@Stable
class Entrance(private val animate: Boolean) {
    private val start = TimeSource.Monotonic.markNow()
    private var count = 0

    private fun elapsed() = start.elapsedNow().inWholeMilliseconds

    /** Whether something composed now should animate in. */
    fun active(): Boolean = animate || elapsed() > 350

    /** True once the screen's entrance has played out. */
    fun settled(): Boolean = elapsed() > 350

    /** How long the next item waits before rising, in milliseconds. */
    fun nextDelay(step: Long = 45): Long {
        val slot = count++
        return (slot * step - elapsed()).coerceIn(0L, 380L)
    }
}

val LocalEntrance = staticCompositionLocalOf<Entrance?> { null }

/**
 * For rows of a lazy list. In a plain scrolling column every row is composed with the screen and
 * has finished rising by the time it scrolls into view; a lazy list composes rows as they scroll in,
 * so rows composed after the entrance show as they would have: already in place.
 */
@Composable
fun SettledEntrance(content: @Composable () -> Unit) {
    val entrance = LocalEntrance.current
    val settled = remember { entrance?.settled() == true }
    if (settled) androidx.compose.runtime.CompositionLocalProvider(LocalEntrance provides null, content = content) else content()
}

/** Rises and fades in on first composition, staggered with its siblings under the current [Entrance]. */
@Composable
fun Modifier.riseIn(distance: Float = 22f): Modifier {
    val entrance = LocalEntrance.current
    val reduce = LocalReduceMotion.current
    val wait = remember { if (!reduce && entrance?.active() == true) entrance.nextDelay() else -1L }
    val p = remember { Animatable(if (wait >= 0) 0f else 1f) }
    if (wait >= 0) {
        LaunchedEffect(Unit) {
            delay(wait)
            p.animateTo(1f, Springs.rise())
        }
    }
    return graphicsLayer {
        val v = p.value
        if (v != 1f) {
            alpha = (v * 1.5f).coerceIn(0f, 1f)
            translationY = (1f - v) * distance.dp.toPx()
            val s = 0.96f + 0.04f * v
            scaleX = s
            scaleY = s
        }
    }
}

/** Grows up from its bottom edge on entrance (chart bars), the [index]th in a row. */
@Composable
fun Modifier.growUp(index: Int): Modifier {
    val entrance = LocalEntrance.current
    val reduce = LocalReduceMotion.current
    val animate = remember { !reduce && entrance?.active() == true }
    val p = remember { Animatable(if (animate) 0f else 1f) }
    if (animate) {
        LaunchedEffect(Unit) {
            delay(180L + index * 55L)
            p.animateTo(1f, Springs.grow())
        }
    }
    return graphicsLayer {
        transformOrigin = TransformOrigin(0.5f, 1f)
        scaleY = p.value.coerceAtLeast(0f)
    }
}

/** Pops in with a bounce after [delayMillis] on entrance (badges, chips, dots). */
@Composable
fun Modifier.popIn(delayMillis: Long = 0): Modifier {
    val entrance = LocalEntrance.current
    val reduce = LocalReduceMotion.current
    val animate = remember { !reduce && entrance?.active() == true }
    val p = remember { Animatable(if (animate) 0f else 1f) }
    if (animate) {
        LaunchedEffect(Unit) {
            delay(delayMillis)
            p.animateTo(1f, Springs.bouncy())
        }
    }
    return graphicsLayer {
        val v = p.value
        scaleX = v.coerceAtLeast(0f)
        scaleY = v.coerceAtLeast(0f)
        alpha = (v * 2f).coerceIn(0f, 1f)
    }
}

/**
 * Animates a number toward [target], counting up from zero on entrance. Reading the value recomposes the
 * caller on every frame of the count; prefer [animatedNumberState] or [AnimatedNumber], which keep that
 * to the scope that shows the number.
 */
@Composable
fun animatedNumber(target: Double): Double = animatedNumberState(target).value

/** [animatedNumber] as state: read it only where the number is drawn, so only that scope recomposes. */
@Composable
fun animatedNumberState(target: Double): State<Double> {
    val entrance = LocalEntrance.current
    val reduce = LocalReduceMotion.current
    val v = remember { Animatable(if (!reduce && entrance?.active() == true) 0f else target.toFloat()) }
    LaunchedEffect(target, reduce) {
        if (reduce) v.snapTo(target.toFloat()) else v.animateTo(target.toFloat(), tween(Durations.Count, easing = Easings.Standard))
    }
    return remember(v) { derivedStateOf { v.value.toDouble() } }
}

/** Shows [content] with [target] counting toward it; only [content] recomposes as the number moves. */
@Composable
fun AnimatedNumber(target: Double, content: @Composable (value: Double) -> Unit) {
    val value by animatedNumberState(target)
    content(value)
}

/**
 * A little celebration when [on] turns true: the element squashes and bounces back, and a ring and a
 * few sparks burst out of it. [initial] is what [on] counts as before (pass false to play on entry).
 */
@Composable
fun Modifier.celebrate(on: Boolean, color: Color, initial: Boolean = on): Modifier {
    val pop = remember { Animatable(1f) }
    val burst = remember { Animatable(1f) }
    val reduce = rememberUpdatedState(LocalReduceMotion.current)
    val onNow = rememberUpdatedState(on)
    // One collector for the element's lifetime: a burst that has started plays out (each Animatable
    // runs to rest) even if [on] flips back, instead of being cancelled halfway and left squashed.
    LaunchedEffect(Unit) {
        var previous = initial
        snapshotFlow { onNow.value }.collect { now ->
            val fire = now && !previous
            previous = now
            if (fire && !reduce.value) {
                launch { pop.snapTo(0.72f); pop.animateTo(1f, Springs.bouncy()) }
                launch { burst.snapTo(0f); burst.animateTo(1f, tween(Durations.Burst, easing = Easings.Standard)) }
            }
        }
    }
    return this
        .drawBehind {
            val b = burst.value
            if (b >= 1f) return@drawBehind
            val r = size.minDimension / 2
            val fade = 1f - b
            drawCircle(color.copy(alpha = 0.45f * fade), radius = r * (1f + 0.6f * b), style = Stroke(width = (1f + 2.5f * fade) * 1.dp.toPx()))
            val sparks = 7
            for (i in 0 until sparks) {
                val a = (i.toFloat() / sparks) * 2f * PI.toFloat() - PI.toFloat() / 2
                val d = r * (1.0f + 0.45f * b)
                drawCircle(
                    color.copy(alpha = 0.7f * fade),
                    radius = (2.6f * fade + 0.4f) * 1.dp.toPx(),
                    center = center + Offset(cos(a) * d, sin(a) * d),
                )
            }
        }
        .graphicsLayer {
            scaleX = pop.value
            scaleY = pop.value
        }
}
