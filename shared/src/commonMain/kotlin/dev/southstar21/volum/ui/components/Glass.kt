package dev.southstar21.volum.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.modifier.modifierLocalConsumer
import androidx.compose.ui.modifier.modifierLocalOf
import androidx.compose.ui.modifier.modifierLocalProvider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.Stable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.draw.drawWithCache
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.isRenderEffectSupported
import com.kyant.backdrop.shadow.Shadow as BackdropShadow
import androidx.compose.ui.graphics.shadow.Shadow
import dev.southstar21.volum.ui.theme.Volum
import dev.southstar21.volum.ui.theme.riseIn
import dev.southstar21.volum.ui.theme.VolumColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** What glass refracts: the colour field behind every screen. */
val LocalBackdrop = staticCompositionLocalOf<Backdrop> { emptyBackdrop() }

/**
 * The colour field every screen sits on (sage gradient, a soft accent glow top-right and bottom-left,
 * white and sage light pools), recorded as the backdrop that glass cards and controls bend.
 */
@Composable
fun GlassRoot(solid: Boolean, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val colors = Volum.colors
    val backdrop = rememberLayerBackdrop()
    val field = remember { FieldInfo() }
    SideEffect { field.solid = solid }
    val fieldBackdrop = remember(field, colors) { FieldBackdrop(field, colors) }
    CompositionLocalProvider(LocalBackdrop provides backdrop, LocalField provides field, LocalFieldBackdrop provides fieldBackdrop) {
        Box(modifier.fillMaxSize().onSizeChanged { field.size = it }.onGloballyPositioned { field.coordinates = it }) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop).drawBehind { drawField(field, colors, solid, size.width, size.height) })
            content()
        }
    }
}

/** The field's size and style, so a strip elsewhere can repaint its own slice of it. */
@Stable
class FieldInfo {
    var size by mutableStateOf(IntSize.Zero)
    var solid by mutableStateOf(false)
    /** Where the field sits, for glass that draws its own slice of it (not state: read only while drawing). */
    var coordinates: androidx.compose.ui.layout.LayoutCoordinates? = null

    /**
     * The field's gradients, kept for the last size and colours they were made for: the field is drawn
     * on every frame by each strip and card that repaints its slice of it, and they only change with
     * those. Not state: read only while drawing.
     */
    private var cached: FieldBrushes? = null

    internal fun brushes(c: VolumColors, w: Float, h: Float): FieldBrushes {
        cached?.let { if (it.colors == c && it.size.width == w && it.size.height == h) return it }
        return FieldBrushes(c, w, h).also { cached = it }
    }
}

/** The non-solid field's linear gradient and light pools at one size. */
internal class FieldBrushes(val colors: VolumColors, w: Float, h: Float) {
    val size = Size(w, h)
    val linear: Brush
    val glows: List<FieldGlow>

    init {
        // CSS linear-gradient(165deg, top 0%, mid 48%, bottom 100%).
        val angle = 165.0 * PI / 180
        val dx = sin(angle).toFloat()
        val dy = -cos(angle).toFloat()
        val half = (kotlin.math.abs(w * dx) + kotlin.math.abs(h * dy)) / 2
        val center = Offset(w / 2, h / 2)
        linear = Brush.linearGradient(
            0f to colors.baseTop, 0.48f to colors.baseMid, 1f to colors.baseBottom,
            start = center - Offset(dx * half, dy * half),
            end = center + Offset(dx * half, dy * half),
        )
        val p3 = Offset(0.18f * w, 0.16f * h)
        val p4 = Offset(0.78f * w, 0.58f * h)
        glows = listOfNotNull(
            fieldGlow(colors.glow1, Offset(0.96f * w, 0f), 1.05f * w, 0.32f * h, 1f),
            fieldGlow(colors.glow2, Offset(0f, 0.80f * h), 0.90f * w, 0.28f * h, 1f),
            fieldGlow(colors.glow3, p3, farthest(p3, w, h), farthest(p3, w, h), 0.40f),
            fieldGlow(colors.glow4, p4, farthest(p4, w, h), farthest(p4, w, h), 0.44f),
        )
    }
}

/** A radial glow drawn as a circle of [radius] squashed vertically by [squash] about its centre. */
internal class FieldGlow(val brush: Brush, val center: Offset, val radius: Float, val squash: Float)

/** [color] at the centre fading to clear at [stop] of the ellipse's radii; none for an empty ellipse. */
private fun fieldGlow(color: Color, center: Offset, rx: Float, ry: Float, stop: Float): FieldGlow? {
    if (rx <= 0f || ry <= 0f) return null
    val brush = Brush.radialGradient(0f to color, stop to color.copy(alpha = 0f), 1f to Color.Transparent, center = center, radius = rx)
    return FieldGlow(brush, center, rx, ry / rx)
}

val LocalField = staticCompositionLocalOf { FieldInfo() }

/** A node's latest placement, read while drawing (not state, so reading it never schedules a frame). */
private class Placed {
    var coordinates: androidx.compose.ui.layout.LayoutCoordinates? = null
}

/**
 * A frosted strip along the top or bottom edge: the field with [content] blurred over it, fading out
 * toward the middle of the screen, so scrolled content doesn't run sharp under the status bar or dock.
 * [solid] is how much of the strip (from its outer edge) stays fully frosted before the fade starts.
 * Without blur support (Android 11 and older) the field alone fades the content out.
 */
@Composable
fun EdgeBlur(content: Backdrop, top: Boolean, height: Dp, solid: Dp, modifier: Modifier = Modifier) {
    val c = Volum.colors
    val field = LocalField.current
    // Where the strip sits is read live while drawing. As state alone it arrived a frame late: the
    // frame after the strip resized painted the field at the old offset, a band that flashed at the
    // edge on every page push and pop. The state copy stays so a move on its own still redraws.
    var y by remember { mutableFloatStateOf(0f) }
    val placed = remember { Placed() }
    val blur = glassEffects()
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .onGloballyPositioned { placed.coordinates = it; y = it.positionInRoot().y }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val keep = if (size.height > 0f) (solid.toPx() / size.height).coerceIn(0f, 1f) else 1f
                val mask = if (top) {
                    Brush.verticalGradient(0f to Color.Black, keep to Color.Black, 1f to Color.Transparent)
                } else {
                    Brush.verticalGradient(0f to Color.Transparent, (1f - keep) to Color.Black, 1f to Color.Black)
                }
                onDrawWithContent {
                    val fs = field.size
                    val at = placed.coordinates?.takeIf { it.isAttached }?.positionInRoot()?.y ?: y
                    if (fs.width > 0) translate(0f, -at) { drawField(field, c, field.solid, fs.width.toFloat(), fs.height.toFloat()) }
                    drawContent()
                    drawRect(mask, blendMode = BlendMode.DstIn)
                }
            }
            .then(
                if (blur) Modifier.drawPlainBackdrop(
                    backdrop = content,
                    shape = { RectangleShape },
                    effects = { blur(14.dp.toPx()) },
                ) else Modifier,
            ),
    )
}

private fun DrawScope.drawField(field: FieldInfo, c: VolumColors, solid: Boolean, w: Float, h: Float, filter: ColorFilter? = null) {
    if (solid) {
        drawRect(c.baseMid, size = Size(w, h), colorFilter = filter)
        return
    }
    val b = field.brushes(c, w, h)
    drawRect(b.linear, size = b.size, colorFilter = filter)
    for (g in b.glows) {
        // An ellipse: a circle squashed vertically about its centre.
        scale(1f, g.squash, pivot = g.center) { drawCircle(g.brush, radius = g.radius, center = g.center, colorFilter = filter) }
    }
}

/** The field backdrop drawn directly, for glass that only needs its colour (see [FieldBackdrop]). */
val LocalFieldBackdrop = staticCompositionLocalOf<Backdrop> { emptyBackdrop() }

/**
 * Backdrop's `vibrancy()`: its colour-controls matrix at saturation 1.5 (brightness 0, contrast 1),
 * as built in com.kyant.backdrop.effects.ColorFilter.
 */
private val Vibrancy: ColorFilter = run {
    val s = 1.5f
    val inv = 1f - s
    val r = 0.213f * inv
    val g = 0.715f * inv
    val b = 0.072f * inv
    ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                r + s, g, b, 0f, 0f,
                r, g + s, b, 0f, 0f,
                r, g, b + s, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
}

/**
 * The colour field as a backdrop that glass draws straight into itself: the same gradients, shifted to
 * the glass's place on screen as LayerBackdrop shifts its recording, with the vibrancy colour matrix
 * applied as they're drawn. The field is a few soft gradients, so blurring and refracting it changes no
 * pixel by more than a few levels; this skips those render effects and with them the offscreen layer
 * every card otherwise renders on every frame it moves.
 */
private class FieldBackdrop(private val field: FieldInfo, private val colors: VolumColors) : Backdrop {
    override val isCoordinatesDependent: Boolean get() = true

    override fun DrawScope.drawBackdrop(
        density: androidx.compose.ui.unit.Density,
        coordinates: androidx.compose.ui.layout.LayoutCoordinates?,
        layerBlock: (androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit)?,
    ) {
        val fs = field.size
        if (fs.width == 0) return
        val origin = field.coordinates
        val offset = if (coordinates != null && origin != null && coordinates.isAttached && origin.isAttached) {
            coordinates.positionInWindow() - origin.positionInWindow()
        } else {
            Offset.Zero
        }
        translate(-offset.x, -offset.y) { drawField(field, colors, field.solid, fs.width.toFloat(), fs.height.toFloat(), Vibrancy) }
    }
}

private fun farthest(p: Offset, w: Float, h: Float): Float =
    listOf(Offset(0f, 0f), Offset(w, 0f), Offset(0f, h), Offset(w, h)).maxOf { sqrt((it.x - p.x) * (it.x - p.x) + (it.y - p.y) * (it.y - p.y)) }

/**
 * Clear glass for cards: refracts the field at its edges, with a bright top rim. Inside a sheet or
 * another card it is a milky glass panel on that surface instead (the backdrop only holds the field).
 */
@Composable
fun Modifier.glassCard(shape: Shape): Modifier {
    // Starts from the composition's answer (sheets and GlassCards say so), so the first frame already
    // draws the right variant there; the modifier local still corrects it under raw glass modifiers.
    val inGlass = LocalInGlass.current
    var nested by remember { mutableStateOf(inGlass) }
    return this
        // Cards rise in, staggered, as their screen enters (see Motion.kt).
        .riseIn()
        .modifierLocalConsumer { nested = ModifierLocalInGlass.current }
        .then(if (nested) Modifier.nestedCard(shape) else Modifier.fieldCard(shape))
}

@Composable
private fun Modifier.nestedCard(shape: Shape): Modifier {
    val c = Volum.colors
    // A milky white panel lifted off the sheet.
    val fill = c.row
    val rim = Brush.verticalGradient(listOf(c.spec, c.rim))
    return this
        .outerShadow(shape, Shadow(radius = 16.dp, color = c.drop, offset = DpOffset(0.dp, 6.dp), spread = (-8).dp))
        .background(fill, shape)
        .border(1.dp, rim, shape)
        .marksGlass()
}

@Composable
private fun Modifier.fieldCard(shape: Shape): Modifier {
    val c = Volum.colors
    val rim = Brush.verticalGradient(listOf(c.spec, c.rim, c.rim))
    if (!glassEffects()) {
        return this
            .outerShadow(shape, Shadow(radius = 24.dp, color = c.drop, offset = DpOffset(0.dp, 12.dp)))
            .background(c.glass.copy(alpha = (c.glass.alpha * 1.6f).coerceAtMost(1f)), shape)
            .border(1.dp, rim, shape)
            .marksGlass()
    }
    // The field, drawn straight in with its vibrancy (see FieldBackdrop): on these soft gradients the
    // blur and lens the cards used to run changed no pixel by more than a few levels, and cost an
    // offscreen render pass per card on every frame it moved.
    return this
        .outerShadow(shape, Shadow(radius = 24.dp, color = c.drop, offset = DpOffset(0.dp, 12.dp), spread = (-8).dp))
        .drawBackdrop(
            backdrop = LocalFieldBackdrop.current,
            shape = { shape },
            effects = {},
            highlight = { Highlight.Default.copy(alpha = 0.6f) },
            onDrawSurface = { drawRect(c.glass) },
        )
        .border(1.dp, rim, shape)
        .marksGlass()
}

/**
 * A drop shadow drawn only outside [shape], as a CSS `box-shadow` is. Compose's `dropShadow` paints the
 * whole blurred shape underneath, which shows through translucent glass as a haze.
 */
@Composable
fun Modifier.outerShadow(shape: Shape, shadow: Shadow, alpha: (() -> Float)? = null): Modifier {
    val context = LocalGraphicsContext.current.shadowContext
    val painter = remember(context, shape, shadow) { context.createDropShadowPainter(shape, shadow) }
    // The clip path is built once per size, not on every frame the card redraws. [alpha] is read
    // only here, in the draw pass, so animating it redraws without recomposing.
    return this.drawWithCache {
        val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
        onDrawBehind {
            val a = alpha?.invoke() ?: 1f
            if (a > 0f) clipPath(path, ClipOp.Difference) { with(painter) { draw(size, alpha = a.coerceAtMost(1f)) } }
        }
    }
}

/** True below a glass card, milky row or sheet: controls there sit on glass, not on the field. */
val ModifierLocalInGlass = modifierLocalOf { false }

/**
 * The same as [ModifierLocalInGlass], known during composition: provided by the containers that are
 * glass (sheets, [GlassCard]), so glass inside them composes nested from its first frame.
 */
val LocalInGlass = staticCompositionLocalOf { false }

private fun Modifier.marksGlass(): Modifier = this.modifierLocalProvider(ModifierLocalInGlass) { true }

/**
 * Thicker liquid glass for floating controls: buttons, chips, the dock and toolbar items. On the field
 * it refracts the backdrop; inside a card, row or sheet it is milky white glass on that surface, as a
 * CSS backdrop-filter over the card would look (the backdrop only holds the field).
 */
@Composable
fun Modifier.liquidGlass(shape: Shape, tint: Color? = null): Modifier {
    val inGlass = LocalInGlass.current
    var nested by remember { mutableStateOf(inGlass) }
    return this
        .modifierLocalConsumer { nested = ModifierLocalInGlass.current }
        .then(if (nested) Modifier.nestedLiquid(shape, tint) else Modifier.fieldLiquid(shape, tint))
        .marksGlass()
}

@Composable
private fun Modifier.nestedLiquid(shape: Shape, tint: Color?): Modifier {
    val c = Volum.colors
    val fill = Brush.verticalGradient(listOf(c.milkTop, c.milkBottom))
    return this
        // `0 12px 26px -8px var(--drop)`
        .outerShadow(shape, Shadow(radius = 26.dp, color = c.drop, offset = DpOffset(0.dp, 12.dp), spread = (-8).dp))
        .background(fill, shape)
        .then(if (tint != null) Modifier.background(tint, shape) else Modifier)
        .lgInset(shape, c)
        .border(1.dp, c.liquidRim, shape)
}

/**
 * The mockups' `.lg` inner light, as CSS inset shadows: a soft bright top edge
 * (`inset 0 1.5px 1px spec`) and a white glow welling up from the bottom (`inset 0 -3px 8px rgba(255,255,255,.18)`).
 */
fun Modifier.lgInset(shape: Shape, c: VolumColors): Modifier = this
    .innerShadow(shape, Shadow(radius = 1.dp, color = c.spec, offset = DpOffset(0.dp, 1.5.dp)))
    .innerShadow(shape, Shadow(radius = 8.dp, color = Color.White.copy(alpha = 0.18f), offset = DpOffset(0.dp, (-3).dp)))

/**
 * The mockups' `.lg` sheen inside a liquid surface: a bright inner top edge (`inset 0 1.5px 1px spec`)
 * and a soft white glow rising from the bottom edge (`inset 0 -3px 8px rgba(255,255,255,.18)`).
 */
fun DrawScope.liquidSheen(shape: Shape, c: VolumColors) {
    val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@liquidSheen)) }
    clipPath(path) {
        val glow = 10.dp.toPx().coerceAtMost(size.height / 2)
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, 0.55f to Color.White.copy(alpha = 0.10f), 1f to Color.White.copy(alpha = 0.20f), startY = size.height - glow, endY = size.height),
            topLeft = Offset(0f, size.height - glow),
            size = Size(size.width, glow),
        )
        val spec = 2.5.dp.toPx()
        drawRect(
            Brush.verticalGradient(0f to c.spec, 1f to Color.Transparent, startY = 0f, endY = spec),
            size = Size(size.width, spec),
        )
    }
}

/** The `.lg` rim drawn inside a surface that has no border of its own (the dock's pane). */
fun DrawScope.liquidRim(shape: Shape, c: VolumColors) {
    drawOutline(
        shape.createOutline(size, layoutDirection, this),
        Brush.verticalGradient(listOf(c.spec, c.liquidRim)),
        style = Stroke(width = 2.dp.toPx()),
    )
}

/**
 * A liquid pane's paint for [dev.southstar21.volum.ui.liquid.LiquidTabs]' `surface`: the liquid fill,
 * then [liquidSheen] and [liquidRim]. The surface is drawn on every frame the tabs move or swell, so its
 * path, outline and brushes are kept and only rebuilt when the size, shape or density changes.
 */
@Composable
fun rememberLiquidPane(): DrawScope.(Shape) -> Unit {
    val c = Volum.colors
    return remember(c) {
        val pane = LiquidPane(c)
        val draw: DrawScope.(Shape) -> Unit = { shape -> pane.draw(this, shape) }
        draw
    }
}

private class LiquidPane(private val c: VolumColors) {
    private val fill = Brush.verticalGradient(listOf(c.liquidTop, c.liquidBottom))
    private val rimBrush = Brush.verticalGradient(listOf(c.spec, c.liquidRim))
    private var shape: Shape? = null
    private var size = Size.Unspecified
    private var layoutDirection: LayoutDirection? = null
    private var density = 0f
    private val clip = Path()
    private var outline: Outline? = null
    private var glow: Brush = fill
    private var glowHeight = 0f
    private var spec: Brush = fill
    private var specHeight = 0f
    private var rim = Stroke()

    fun draw(scope: DrawScope, shape: Shape) = with(scope) {
        if (shape != this@LiquidPane.shape || size != this@LiquidPane.size || layoutDirection != this@LiquidPane.layoutDirection || density != this@LiquidPane.density) {
            build(this, shape)
        }
        val outline = this@LiquidPane.outline ?: return@with
        drawRect(fill)
        clipPath(clip) {
            drawRect(glow, topLeft = Offset(0f, size.height - glowHeight), size = Size(size.width, glowHeight))
            drawRect(spec, size = Size(size.width, specHeight))
        }
        drawOutline(outline, rimBrush, style = rim)
    }

    /** As [liquidSheen] and [liquidRim] draw it. */
    private fun build(scope: DrawScope, shape: Shape) = with(scope) {
        this@LiquidPane.shape = shape
        this@LiquidPane.size = size
        this@LiquidPane.layoutDirection = layoutDirection
        this@LiquidPane.density = density
        val outline = shape.createOutline(size, layoutDirection, this)
        this@LiquidPane.outline = outline
        clip.reset()
        clip.addOutline(outline)
        glowHeight = 10.dp.toPx().coerceAtMost(size.height / 2)
        glow = Brush.verticalGradient(0f to Color.Transparent, 0.55f to Color.White.copy(alpha = 0.10f), 1f to Color.White.copy(alpha = 0.20f), startY = size.height - glowHeight, endY = size.height)
        specHeight = 2.5.dp.toPx()
        spec = Brush.verticalGradient(0f to c.spec, 1f to Color.Transparent, startY = 0f, endY = specHeight)
        rim = Stroke(width = 2.dp.toPx())
    }
}

@Composable
private fun Modifier.fieldLiquid(shape: Shape, tint: Color?): Modifier {
    val c = Volum.colors
    val backdrop = LocalBackdrop.current
    val fill = Brush.verticalGradient(listOf(c.liquidTop, c.liquidBottom))
    val rim = Brush.verticalGradient(listOf(c.spec, c.liquidRim))
    if (!glassEffects()) {
        return this
            .outerShadow(shape, Shadow(radius = 18.dp, color = c.drop, offset = DpOffset(0.dp, 8.dp), spread = (-6).dp))
            .background(c.sheet, shape)
            .background(fill, shape)
            .then(if (tint != null) Modifier.background(tint, shape) else Modifier)
            .lgInset(shape, c)
            .border(1.dp, c.liquidRim, shape)
    }
    return this
        .drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(6.dp.toPx())
                lens(12.dp.toPx(), 20.dp.toPx())
            },
            highlight = { Highlight.Default.copy(alpha = 0.8f) },
            shadow = { BackdropShadow(radius = 18.dp, color = c.drop) },
            onDrawSurface = {
                drawRect(fill)
                if (tint != null) drawRect(tint)
            },
        )
        .lgInset(shape, c)
        .border(1.dp, c.liquidRim, shape)
}

/** Pull-up sheets: the sheet tint over a heavily blurred, saturated backdrop (no lens, no white wash). */
@Composable
fun Modifier.sheetGlass(shape: Shape): Modifier {
    val c = Volum.colors
    val backdrop = LocalBackdrop.current
    val rim = Brush.verticalGradient(listOf(c.spec, c.liquidRim))
    if (!glassEffects()) {
        return this
            .outerShadow(shape, Shadow(radius = 30.dp, color = c.drop, offset = DpOffset(0.dp, (-6).dp), spread = (-8).dp))
            .background(c.sheet.copy(alpha = 0.96f), shape)
            .border(1.dp, rim, shape)
            .marksGlass()
    }
    return this
        .drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(30.dp.toPx())
            },
            highlight = { Highlight.Default.copy(alpha = 0.7f) },
            shadow = { BackdropShadow(radius = 30.dp, color = c.drop) },
            onDrawSurface = { drawRect(c.sheet) },
        )
        .border(1.dp, rim, shape)
        .marksGlass()
}

/** The forest gel used for the main action, done ticks and the selected chip. */
@Composable
fun Modifier.accentGel(shape: Shape): Modifier {
    val c = Volum.colors
    return this
        // `.tl`: 0 10px 22px -8px rgba(31,77,51,.5) .
        .outerShadow(shape, Shadow(radius = 22.dp, color = c.accentShadow, offset = DpOffset(0.dp, 10.dp), spread = (-8).dp))
        .background(Brush.verticalGradient(listOf(c.accentTop, c.accentBottom)), shape)
        .innerShadow(shape, Shadow(radius = 2.dp, color = Color.White.copy(alpha = 0.45f), offset = DpOffset(0.dp, 1.5.dp)))
        .innerShadow(shape, Shadow(radius = 10.dp, color = Color.Black.copy(alpha = 0.16f), offset = DpOffset(0.dp, (-4).dp)))
        .border(1.dp, Color.White.copy(alpha = 0.3f), shape)
}

/** A milky insert inside a glass card (set rows, field groups). */
@Composable
fun Modifier.milky(shape: Shape): Modifier {
    val c = Volum.colors
    return this
        .background(c.row, shape)
        .innerShadow(shape, Shadow(radius = 0.5.dp, color = c.spec, offset = DpOffset(0.dp, 1.dp)))
        .marksGlass()
}

/** Soft neumorphism, pressed into the surface (fields, chosen tiles). */
@Composable
fun Modifier.neuIn(shape: Shape): Modifier {
    val c = Volum.colors
    return this
        .background(c.neuBg, shape)
        .innerShadow(shape, Shadow(radius = 12.dp, color = c.neuDark, offset = DpOffset(5.dp, 5.dp)))
        .innerShadow(shape, Shadow(radius = 12.dp, color = c.neuLight, offset = DpOffset((-5).dp, (-5).dp)))
}

/**
 * Neumorphism that can move between raised and pressed: [depth] 1 is [neuOut], -1 is [neuIn], and
 * the shadows cross through flat at 0. Read in the draw pass only, so it can be animated per frame.
 */
@Composable
fun Modifier.neu(shape: Shape, depth: () -> Float): Modifier {
    val c = Volum.colors
    val raised = { depth().coerceIn(0f, 1f) }
    val pressed = { (-depth()).coerceIn(0f, 1f) }
    return this
        .outerShadow(shape, Shadow(radius = 20.dp, color = c.neuDark, offset = DpOffset(9.dp, 9.dp)), raised)
        .outerShadow(shape, Shadow(radius = 20.dp, color = c.neuLight, offset = DpOffset((-9).dp, (-9).dp)), raised)
        .background(c.neuBg, shape)
        .innerShadow(shape) { radius = 12.dp.toPx(); color = c.neuDark; offset = Offset(5.dp.toPx(), 5.dp.toPx()); alpha = pressed() }
        .innerShadow(shape) { radius = 12.dp.toPx(); color = c.neuLight; offset = Offset(-5.dp.toPx(), -5.dp.toPx()); alpha = pressed() }
}

/** [neu] that sinks in when [pressed] turns true and rises back out when it turns false. */
@Composable
fun Modifier.neuToggle(shape: Shape, pressed: Boolean): Modifier {
    val depth = androidx.compose.animation.core.animateFloatAsState(
        if (pressed) -1f else 1f,
        androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 380f),
        label = "neu",
    )
    return neu(shape) { depth.value }
}

/** Soft neumorphism, raised out of the surface. */
@Composable
fun Modifier.neuOut(shape: Shape): Modifier {
    val c = Volum.colors
    return this
        .outerShadow(shape, Shadow(radius = 20.dp, color = c.neuDark, offset = DpOffset(9.dp, 9.dp)))
        .outerShadow(shape, Shadow(radius = 20.dp, color = c.neuLight, offset = DpOffset((-9).dp, (-9).dp)))
        .background(c.neuBg, shape)
}

/**
 * Whether glass refracts and blurs what's behind it (the backdrop library's render effects) or takes
 * the frosted look: translucent fills with drawn rims and shadows. Frosted on every platform: it reads
 * cleaner, and on iOS the live effects began dropping whole frames after a while (a page left half
 * drawn for a frame, seen as flashing).
 */
fun glassEffects(): Boolean = LiveGlass && isRenderEffectSupported()

private const val LiveGlass = false
