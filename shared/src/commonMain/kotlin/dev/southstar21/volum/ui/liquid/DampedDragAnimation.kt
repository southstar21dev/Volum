// Adapted from Kyant0/AndroidLiquidGlass (Apache-2.0), via the Kaloria app.
package dev.southstar21.volum.ui.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationConstants
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.getVelocityFromNanos
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.TimeSource

/**
 * A value (the tab bar's selection position) that follows a drag on stiff springs, while the
 * glass it moves swells when pressed and stretches with its velocity, like a droplet.
 *
 * The value, its velocity and the drag [offset] get a new target on every pointer move or frame, so
 * they don't each run in a coroutine of their own: a move only sets the target, and one frame loop,
 * alive while any of them is moving, steps all three.
 *
 * With [reduceMotion] a tap moves the value straight to its target, with no swell on the way: only a
 * drag, which the finger leads, still moves it.
 */
class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    initialValue: Float,
    val valueRange: ClosedRange<Float>,
    private val visibilityThreshold: Float,
    private val initialScale: Float,
    private val pressedScale: Float,
    private val reduceMotion: Boolean = false,
    val onDragStopped: DampedDragAnimation.() -> Unit,
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
) {
    private val valueSpec = spring(1f, 1000f, visibilityThreshold)
    private val velocitySpec = spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressSpec = spring(1f, 1000f, 0.001f)
    private val scaleXSpec = spring(0.6f, 250f, 0.001f)
    private val scaleYSpec = spring(0.7f, 250f, 0.001f)
    private val offsetSpec = spring(1f, 300f, 0.5f)

    private val valueAnimation = FrameSpring(initialValue, valueSpec)
    private val velocityAnimation = FrameSpring(0f, velocitySpec)
    private val offsetAnimation = FrameSpring(0f, offsetSpec)
    private val pressAnimation = Animatable(0f, 0.001f)
    private val scaleXAnimation = Animatable(initialScale, 0.001f)
    private val scaleYAnimation = Animatable(initialScale, 0.001f)
    /** The pending deflate after a release; a new press cancels it so the drop stays swollen under the finger. */
    private var releaseJob: Job? = null
    /** The loop stepping the springs; it ends once they all rest, and the next move starts another. */
    private var frameLoop: Job? = null
    /** Whether the value's current animation feeds the velocity: a drag's does, a settle onto an item doesn't. */
    private var valueFeedsVelocity = false
    /**
     * Whether the velocity steps ahead of the value in the next frame, as it does once a drag move has set a
     * resting value going, or once the velocity has started from rest. It decides where a velocity that comes
     * to rest in a frame takes that frame's new target from, so the stretch stays frame for frame what it was
     * when each spring ran in a coroutine of its own. Only frames that feed the velocity read it, and only
     * [updateValue] starts those.
     */
    private var velocityStepsFirst = false
    private val velocityTracker = VelocityTracker()
    private val clock = TimeSource.Monotonic.markNow()

    val value: Float get() = valueAnimation.value
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value
    /** How far the finger has pulled the drop sideways, in pixels, easing back to zero once it lifts. */
    val offset: Float get() = offsetAnimation.value

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectDragGestures(
            onDragStart = { press() },
            onDragEnd = { stopDrag() },
            onDragCancel = { stopDrag() },
        ) { _, dragAmount -> dragBy(size, dragAmount) }
    }

    private fun dragBy(size: IntSize, dragAmount: Offset) {
        onDrag(size, dragAmount)
        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
    }

    private fun stopDrag() {
        onDragStopped()
        offsetAnimation.animateTo(0f, durationScale)
        startFrameLoop()
        release()
    }

    fun press() {
        releaseJob?.cancel()
        releaseJob = null
        velocityTracker.resetTracking()
        animationScope.launch {
            launch { pressAnimation.animateTo(1f, pressSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYSpec) }
        }
    }

    /** The width of [valueRange]; zero with a single item, where nothing can move. */
    private val span: Float get() = valueRange.endInclusive - valueRange.start

    fun release() {
        releaseJob?.cancel()
        releaseJob = animationScope.launch {
            withFrameNanos { }
            if (value != targetValue) {
                val threshold = (span * 0.025f).coerceAtLeast(visibilityThreshold)
                snapshotFlow { valueAnimation.value }.filter { abs(it - valueAnimation.targetValue) < threshold }.first()
            }
            launch { pressAnimation.animateTo(0f, pressSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYSpec) }
        }
    }

    fun updateValue(value: Float) {
        valueFeedsVelocity = true
        velocityStepsFirst = true
        // A moving value turns toward the new target with a zero-time step at once, and every step feeds the velocity.
        if (valueAnimation.animateTo(value.coerceIn(valueRange), durationScale)) {
            sampleVelocity()?.let {
                velocityAnimation.animateTo(it, durationScale)
                velocityStepsFirst = false
            }
        }
        startFrameLoop()
    }

    fun animateToValue(value: Float) {
        if (reduceMotion) {
            valueAnimation.snapTo(value.coerceIn(valueRange))
            return
        }
        press()
        valueFeedsVelocity = false
        valueAnimation.animateTo(value.coerceIn(valueRange), durationScale)
        if (velocity != 0f) velocityAnimation.animateTo(0f, durationScale)
        startFrameLoop()
        release()
    }

    /** Records where the value is now and returns the velocity, in ranges per second, for the stretch to chase. */
    private fun sampleVelocity(): Float? {
        if (span <= 0f) return null
        velocityTracker.addPosition(clock.elapsedNow().inWholeMilliseconds, Offset(value, 0f))
        return velocityTracker.calculateVelocity().x / span
    }

    private fun onFrame(frameTimeNanos: Long, durationScale: Float) {
        var velocityTarget: Float? = null
        if (valueAnimation.isRunning) {
            valueAnimation.step(frameTimeNanos, durationScale)
            if (valueFeedsVelocity) velocityTarget = sampleVelocity()
        }
        val velocitySteps = velocityAnimation.isRunning
        velocityAnimation.step(frameTimeNanos, durationScale)
        offsetAnimation.step(frameTimeNanos, durationScale)
        // The velocity takes this frame's step toward its old target, then turns toward the new one: from that
        // step if it is still moving, or came to rest stepping after the value (and then keeps stepping after
        // it); otherwise from rest on the next frame, stepping ahead of the value.
        velocityTarget?.let {
            val turns = velocityAnimation.isRunning || (velocitySteps && !velocityStepsFirst)
            velocityAnimation.animateTo(it, durationScale, justStepped = turns)
            velocityStepsFirst = !turns
        }
    }

    private fun startFrameLoop() {
        if (frameLoop?.isActive == true) return
        frameLoop = animationScope.launch {
            while (valueAnimation.isRunning || velocityAnimation.isRunning || offsetAnimation.isRunning) {
                val scale = durationScale
                withFrameNanos { onFrame(it, scale) }
            }
        }
    }

    /** The system's animation speed, as an animation launched in [animationScope] reads it. */
    private val durationScale: Float
        get() = animationScope.coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
}

/**
 * A float on a spring that its owner's frame loop steps, so it can take a new target on every pointer
 * move or frame without a coroutine per target. It moves exactly as `Animatable.animateTo` does: a new
 * target starts from the current value and velocity at the last frame's time (with a zero-time step
 * there at once), and a spring at rest, settled or snapped, starts still on the next frame.
 */
private class FrameSpring(initialValue: Float, private val spec: AnimationSpec<Float>) {
    var value by mutableFloatStateOf(initialValue)
        private set
    var targetValue by mutableFloatStateOf(initialValue)
        private set
    private var animation: TargetBasedAnimation<Float, AnimationVector1D>? = null
    private var startTimeNanos = AnimationConstants.UnspecifiedTime
    /** The last step's frame time and velocity, kept when that step settled the spring. */
    private var lastFrameTimeNanos = AnimationConstants.UnspecifiedTime
    private var lastVelocity = 0f

    val isRunning: Boolean get() = animation != null

    /**
     * Heads for [target]; true when that took a step at once, which it does when the spring was moving.
     * [justStepped] marks a retarget in the frame the spring just stepped in: it turns from that step
     * even when the step settled the spring, as a retarget launched from the frame callback did, since
     * it ran before the settled animation reset its clock.
     */
    fun animateTo(target: Float, durationScale: Float, justStepped: Boolean = false): Boolean {
        val moving = isRunning || justStepped
        val initialVelocity = if (moving) lastVelocity else 0f
        animation = TargetBasedAnimation(spec, Float.VectorConverter, value, target, initialVelocity)
        targetValue = target
        startTimeNanos = if (moving) lastFrameTimeNanos else AnimationConstants.UnspecifiedTime
        // Until its first frame the new animation has no frame time of its own, and holds its initial velocity.
        lastFrameTimeNanos = startTimeNanos
        lastVelocity = initialVelocity
        if (startTimeNanos == AnimationConstants.UnspecifiedTime) return false
        step(startTimeNanos, durationScale)
        return true
    }

    fun snapTo(target: Float) {
        animation = null
        value = target
        targetValue = target
    }

    fun step(frameTimeNanos: Long, durationScale: Float) {
        val anim = animation ?: return
        if (startTimeNanos == AnimationConstants.UnspecifiedTime) startTimeNanos = frameTimeNanos
        val playTimeNanos = if (durationScale == 0f) anim.durationNanos else ((frameTimeNanos - startTimeNanos) / durationScale).toLong()
        lastFrameTimeNanos = frameTimeNanos
        value = anim.getValueFromNanos(playTimeNanos)
        lastVelocity = anim.getVelocityFromNanos(playTimeNanos)
        if (anim.isFinishedFromNanos(playTimeNanos)) animation = null
    }
}
