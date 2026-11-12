package dev.southstar21.volum.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import dev.southstar21.volum.platform.hapticTick
import dev.southstar21.volum.platform.isReduceMotionEnabled
import dev.southstar21.volum.resources.Res
import dev.southstar21.volum.resources.jost
import dev.southstar21.volum.resources.plus_jakarta_sans
import org.jetbrains.compose.resources.Font
import kotlin.time.TimeSource

/** The locked brand palette: sage & forest with a butter glow. Volum is light-only. */
@Immutable
data class VolumColors(
    // Field
    val baseTop: Color, val baseMid: Color, val baseBottom: Color,
    val glow1: Color, val glow2: Color, val glow3: Color, val glow4: Color,
    // Surfaces
    val glass: Color, val rim: Color, val spec: Color, val drop: Color, val row: Color,
    val sheet: Color, val scrim: Color,
    val liquidTop: Color, val liquidBottom: Color, val liquidRim: Color,
    // Text and accents
    val ink: Color, val mute: Color, val line: Color,
    val accent: Color, val onAccent: Color, val accentTop: Color, val accentBottom: Color, val soft: Color,
    val danger: Color, val dangerSoft: Color,
    // Neumorphism
    val neuBg: Color, val neuDark: Color, val neuLight: Color,
    // Nested liquid glass (a control on a card or sheet), the accent gel's drop, the current set's glow
    val milkTop: Color, val milkBottom: Color, val accentShadow: Color, val currentGlow: Color,
    // The partner's warm avatar
    val warmTop: Color, val warmBottom: Color, val warmInk: Color,
    // The app icon's drop shadow
    val iconShadow: Color,
)

val LightColors = VolumColors(
    baseTop = Color(0xFFF3F4EE), baseMid = Color(0xFFDCE5D3), baseBottom = Color(0xFFC6D5BE),
    glow1 = Color(0x61F4CE58), glow2 = Color(0x38F4CE58), glow3 = Color(0xD9FFFFFF), glow4 = Color(0xBFA8C4A0),
    glass = Color(0x6BFFFFFF), rim = Color(0xB8FFFFFF), spec = Color(0xF2FFFFFF), drop = Color(0x291E3C28), row = Color(0xB3FFFFFF),
    sheet = Color(0xCCF6F8F2), scrim = Color(0x381C3324),
    liquidTop = Color(0x9EFFFFFF), liquidBottom = Color(0x38FFFFFF), liquidRim = Color(0xCCFFFFFF),
    ink = Color(0xFF1C3324), mute = Color(0xFF4D6455), line = Color(0x1F1C3324),
    accent = Color(0xFF1F4D33), onAccent = Color.White, accentTop = Color(0xFF3E7354), accentBottom = Color(0xFF1F4D33), soft = Color(0x1F1F4D33),
    danger = Color(0xFFB3261E), dangerSoft = Color(0x1AB3261E),
    neuBg = Color(0xFFE2E9DA), neuDark = Color(0x7378966E), neuLight = Color(0xF2FFFFFF),
    milkTop = Color(0xE6FFFFFF), milkBottom = Color(0x99FFFFFF), accentShadow = Color(0x801F4D33), currentGlow = Color(0x661F4D33),
    warmTop = Color(0xFFF7E6A6), warmBottom = Color(0xFFBFD6B5), warmInk = Color(0xFF1C3324),
    iconShadow = Color(0x551E3C28),
)

val LocalColors = staticCompositionLocalOf { LightColors }
val LocalFonts = staticCompositionLocalOf<VolumFonts> { error("No fonts") }

@Immutable
data class VolumFonts(val sans: FontFamily, val wordmark: FontFamily)

object Volum {
    val colors: VolumColors @Composable get() = LocalColors.current
    val fonts: VolumFonts @Composable get() = LocalFonts.current
}

@Composable
private fun rememberFonts(): VolumFonts {
    val sans = FontFamily(
        listOf(400, 500, 600, 700, 800).map { w ->
            Font(Res.font.plus_jakarta_sans, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
        },
    )
    val jost = FontFamily(
        listOf(500, 600, 700).map { w ->
            Font(Res.font.jost, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
        },
    )
    return remember(sans, jost) { VolumFonts(sans, jost) }
}

/** True when the system asks for less motion (see [isReduceMotionEnabled]); read again on every resume. */
val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
fun VolumTheme(content: @Composable () -> Unit) {
    val colors = LightColors
    val fonts = rememberFonts()
    var reduceMotion by remember { mutableStateOf(isReduceMotionEnabled()) }
    LifecycleResumeEffect(Unit) {
        reduceMotion = isReduceMotionEnabled()
        onPauseOrDispose {}
    }
    CompositionLocalProvider(
        LocalColors provides colors,
        LocalFonts provides fonts,
        LocalReduceMotion provides reduceMotion,
        LocalTextSelectionColors provides TextSelectionColors(colors.accent, colors.accent.copy(alpha = 0.3f)),
        content = content,
    )
}

// ---- Type -----------------------------------------------------------------------------------

/** The type ramp. Every text size in the app is one of these steps. */
object T {
    /** The onboarding monogram and unit tiles. */
    val Monogram = 54.sp
    val Display = 52.sp
    /** Onboarding questions. */
    val Hero = 36.sp
    /** Screen titles. */
    val Title = 30.sp
    /** Big stat figures. */
    val Figure = 28.sp
    /** Set weights and reps being edited, the profile name. */
    val Entry = 26.sp
    val Headline = 24.sp
    val Heading = 22.sp
    /** Sheet titles. */
    val SheetTitle = 20.sp
    val Big = 18.sp
    /** Section titles. */
    val Subhead = 17.sp
    val BodyLarge = 16.sp
    val Body = 15.sp
    val Label = 14.sp
    val Small = 13.sp
    val Caption = 12.sp
    val Micro = 11.sp
    /** Tab labels, chart axes. */
    val Tiny = 10.sp
    val Minute = 9.sp
    /** Screen titles' tight tracking. */
    val TitleTracking = (-0.03).em
}

@Composable
fun textStyle(
    size: TextUnit = T.Body,
    weight: FontWeight = FontWeight.Normal,
    color: Color = Volum.colors.ink,
    tracking: TextUnit = 0.em,
    align: TextAlign = TextAlign.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    tabular: Boolean = false,
): TextStyle = TextStyle(
    fontFamily = Volum.fonts.sans,
    fontSize = size,
    fontWeight = weight,
    color = color,
    letterSpacing = tracking,
    textAlign = align,
    lineHeight = lineHeight,
    fontFeatureSettings = if (tabular) "tnum" else null,
)

val Semibold = FontWeight.SemiBold
val Bold = FontWeight.Bold
val ExtraBold = FontWeight.ExtraBold
val Medium = FontWeight.Medium

// ---- Interaction ----------------------------------------------------------------------------

/**
 * A click without ripple, with a squishy press (sinks while held, springs back with a little overshoot),
 * and an optional haptic tick. The sink is about the same few dp whatever the size, so big cards dip
 * gently and small buttons squish more. Not composed: the press lives in a [SquishIndication] node.
 *
 * [debounce] drops a second click that lands within [ClickDebounceMillis] of the last one on the same
 * element, so a double tap can't save, push or append twice while the screen or sheet is still on its
 * way out. Pass false for controls meant to be tapped quickly in a row (steppers, tabs, toggles).
 * Long presses are never dropped.
 */
fun Modifier.tappable(
    enabled: Boolean = true,
    role: Role? = Role.Button,
    highlight: Boolean = true,
    haptic: Boolean = false,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    debounce: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val click = if (haptic) { { hapticTick(); onClick() } } else onClick
    val gate = if (debounce) ClickGate() else null
    return this
        .then(if (enabled) Modifier else Modifier.alpha(0.45f))
        .then(if (gate != null) ClickGateElement(gate) else Modifier)
        .combinedClickable(
            interactionSource = null,
            indication = if (highlight && enabled) SquishIndication else null,
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onLongClick = onLongClick,
            hapticFeedbackEnabled = false,
            onClick = if (gate != null) { { if (gate.pass()) click() } } else click,
        )
}

/** Outlasts a page transition ([Durations.Page]), the longest a tapped screen stays tappable on its way out. */
private const val ClickDebounceMillis = 350L

/**
 * The click lambda is rebuilt on every recomposition, and the first click usually causes one, so the
 * time of the last click lives in a node, which outlives recompositions; each new lambda's gate is
 * pointed at it when the modifier is updated.
 */
private class ClickGate {
    var node: ClickGateNode? = null

    fun pass(): Boolean {
        val node = node ?: return true
        val last = node.last
        if (last != null && last.elapsedNow().inWholeMilliseconds < ClickDebounceMillis) return false
        node.last = TimeSource.Monotonic.markNow()
        return true
    }
}

private class ClickGateNode : Modifier.Node() {
    var last: TimeSource.Monotonic.ValueTimeMark? = null
}

private class ClickGateElement(private val gate: ClickGate) : ModifierNodeElement<ClickGateNode>() {
    override fun create(): ClickGateNode = ClickGateNode().also { gate.node = it }
    override fun update(node: ClickGateNode) {
        gate.node = node
    }
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = gate.hashCode()
}

/** The press squish as an indication: one node per clickable, driven by its own interactions. */
private object SquishIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = SquishNode(interactionSource)
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = 0x5157
}

private class SquishNode(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private val press = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collectLatest { interaction ->
                // With reduced motion the press shows as a still sink, without the spring or overshoot.
                val reduce = currentValueOf(LocalReduceMotion)
                when (interaction) {
                    is PressInteraction.Press -> {
                        if (reduce) {
                            press.snapTo(1f)
                            invalidateDraw()
                        } else {
                            press.animateTo(1f, Springs.press()) { invalidateDraw() }
                        }
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        if (reduce) {
                            press.snapTo(0f)
                            invalidateDraw()
                        } else {
                            // A quick tap may release before the sink shows: dip first so every tap reads.
                            if (press.value < 0.7f) press.animateTo(1f, tween(Durations.Instant)) { invalidateDraw() }
                            press.animateTo(0f, Springs.release()) { invalidateDraw() }
                        }
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val p = press.value
        if (p == 0f) {
            drawContent()
            return
        }
        val depth = (12.dp.toPx() / size.width.coerceAtLeast(1f)).coerceIn(0.025f, 0.09f)
        scale(1f - p * depth, 1f - p * depth * 1.12f) { this@draw.drawContent() }
    }
}
