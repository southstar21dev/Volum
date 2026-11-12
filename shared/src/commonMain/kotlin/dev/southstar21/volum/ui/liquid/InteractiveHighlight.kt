// Adapted from Kyant0/AndroidLiquidGlass (Apache-2.0), via the Kaloria app.
package dev.southstar21.volum.ui.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.util.fastCoerceIn
import com.kyant.backdrop.RuntimeShader
import com.kyant.backdrop.asComposeShader
import com.kyant.backdrop.isRuntimeShaderSupported
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Liquid Glass touch feedback: on press the material "illuminates from within", a soft glow that
 * starts under the fingertip and follows it.
 */
class InteractiveHighlight(
    private val animationScope: CoroutineScope,
    private val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset },
) {
    private val pressSpec = spring(0.5f, 300f, 0.001f)
    private val positionSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)
    private val pressAnimation = Animatable(0f, 0.001f)
    private val positionAnimation = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)
    private var startPosition = Offset.Zero

    // While a finger is down the glow follows it directly: a move sets the position, no coroutine
    // per pointer event. The spring only runs on release, back to where the press began.
    private var following by mutableStateOf(false)
    private var fingerPosition by mutableStateOf(Offset.Zero)
    private var gesture = 0

    private val shader = if (isRuntimeShaderSupported()) {
        RuntimeShader(
            """
uniform float2 size;
layout(color) uniform half4 color;
uniform float radius;
uniform float2 position;

half4 main(float2 coord) {
    float dist = distance(coord, position);
    float intensity = smoothstep(radius, radius * 0.5, dist);
    return color * intensity;
}""",
        )
    } else {
        null
    }

    val modifier: Modifier = Modifier.drawWithContent {
        val progress = pressAnimation.value
        if (progress > 0f) {
            if (shader != null) {
                drawRect(Color.White.copy(0.08f * progress), blendMode = BlendMode.Plus)
                shader.apply {
                    val p = position(size, if (following) fingerPosition else positionAnimation.value)
                    setFloatUniform("size", size.width, size.height)
                    setColorUniform("color", Color.White.copy(0.15f * progress))
                    setFloatUniform("radius", size.minDimension * 1.5f)
                    setFloatUniform("position", p.x.fastCoerceIn(0f, size.width), p.y.fastCoerceIn(0f, size.height))
                }
                // Made per frame on purpose: on Skia the Compose shader is a snapshot of the uniforms
                // set above, so a cached one would freeze the glow. Only drawn while pressed.
                drawRect(ShaderBrush(shader.asComposeShader()), blendMode = BlendMode.Plus)
            } else {
                drawRect(Color.White.copy(0.25f * progress), blendMode = BlendMode.Plus)
            }
        }
        drawContent()
    }

    val gestureModifier: Modifier = Modifier.pointerInput(animationScope) {
        inspectDragGestures(
            onDragStart = { down ->
                gesture++
                startPosition = down.position
                fingerPosition = down.position
                following = true
                animationScope.launch { pressAnimation.animateTo(1f, pressSpec) }
            },
            onDragEnd = { release() },
            onDragCancel = { release() },
        ) { change, _ -> fingerPosition = change.position }
    }

    private fun release() {
        val released = gesture
        val from = fingerPosition
        animationScope.launch {
            launch { pressAnimation.animateTo(0f, pressSpec) }
            positionAnimation.snapTo(from)
            // Handed over to the spring only now, so no frame draws the glow anywhere in between;
            // a press that began meanwhile keeps following its finger.
            if (released == gesture) following = false
            positionAnimation.animateTo(startPosition, positionSpec)
        }
    }
}
