package dev.southstar21.volum.ui.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.southstar21.volum.ui.components.BackHandler
import dev.southstar21.volum.ui.components.CircleButton
import dev.southstar21.volum.ui.components.Overlay
import dev.southstar21.volum.ui.components.PrimaryButton
import dev.southstar21.volum.ui.components.TextButton
import dev.southstar21.volum.ui.components.Toolbar
import dev.southstar21.volum.ui.components.TopInset
import dev.southstar21.volum.ui.components.Txt
import dev.southstar21.volum.ui.theme.Bold
import dev.southstar21.volum.ui.theme.ExtraBold
import dev.southstar21.volum.ui.theme.Icons
import dev.southstar21.volum.ui.theme.Semibold
import dev.southstar21.volum.ui.theme.T
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.math.min
import kotlin.math.roundToInt

/** The side of the square profile photo that is uploaded. */
internal const val AVATAR_PX = 512

/** How far past "the photo just covers the circle" it can be zoomed in. */
internal const val CROP_MAX_ZOOM = 5f

/**
 * Where the photo sits under the crop circle: [scale] screen pixels per photo pixel, and [offset] of
 * the photo's top-left corner from the circle's bounding square's top-left (zero or negative).
 */
internal data class CropFrame(val scale: Float, val offset: Offset)

/** The scale at which the photo's short side just spans a circle [circle] pixels across. */
internal fun minCropScale(width: Int, height: Int, circle: Float): Float = circle / min(width, height)

/** The photo as it first appears: as small as it can be while covering the circle, centred. */
internal fun initialCropFrame(width: Int, height: Int, circle: Float): CropFrame {
    val scale = minCropScale(width, height, circle)
    return CropFrame(scale, Offset((circle - width * scale) / 2f, (circle - height * scale) / 2f))
}

/** [frame] held within the zoom range, and moved so the photo still covers the whole circle. */
internal fun clampCropFrame(frame: CropFrame, width: Int, height: Int, circle: Float): CropFrame {
    val min = minCropScale(width, height, circle)
    val scale = frame.scale.coerceIn(min, min * CROP_MAX_ZOOM)
    return CropFrame(
        scale,
        Offset(frame.offset.x.coerceIn(circle - width * scale, 0f), frame.offset.y.coerceIn(circle - height * scale, 0f)),
    )
}

/** [frame] after a gesture: zoomed by [zoom] about [centroid] (in the circle's square) and moved by [pan]. */
internal fun transformCropFrame(frame: CropFrame, width: Int, height: Int, circle: Float, centroid: Offset, pan: Offset, zoom: Float): CropFrame {
    val min = minCropScale(width, height, circle)
    val scale = (frame.scale * zoom).coerceIn(min, min * CROP_MAX_ZOOM)
    // The photo point under the fingers stays under them while zooming.
    val offset = centroid - (centroid - frame.offset) * (scale / frame.scale) + pan
    return clampCropFrame(CropFrame(scale, offset), width, height, circle)
}

/** The square of the photo, in photo pixels, that the circle covers. */
internal fun cropRect(frame: CropFrame, width: Int, height: Int, circle: Float): IntRect {
    val size = (circle / frame.scale).roundToInt().coerceIn(1, min(width, height))
    val left = (-frame.offset.x / frame.scale).roundToInt().coerceIn(0, width - size)
    val top = (-frame.offset.y / frame.scale).roundToInt().coerceIn(0, height - size)
    return IntRect(left, top, left + size, top + size)
}

/** [rect] of [image], scaled to the [AVATAR_PX] square that is uploaded. */
internal fun renderCrop(image: ImageBitmap, rect: IntRect): ImageBitmap {
    val out = ImageBitmap(AVATAR_PX, AVATAR_PX)
    Canvas(out).drawImageRect(
        image,
        IntOffset(rect.left, rect.top),
        IntSize(rect.width, rect.height),
        IntOffset.Zero,
        IntSize(AVATAR_PX, AVATAR_PX),
        Paint().apply { filterQuality = FilterQuality.High },
    )
    return out
}

private val CropBackground = Color(0xFF101A14)
private val CropDim = Color(0xA8101A14)
private val OnDark = Color.White
private val OnDarkMute = Color(0xC7FFFFFF)
private val OnDarkError = Color(0xFFFFB4AB)

/**
 * Full screen, over the tab bar: the picked [photo] under a circle the user drags and pinches it in.
 * [onSave] gets the circle's square at [AVATAR_PX]; [error] is the last save's failure, shown above Save.
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
internal fun AvatarCropScreen(
    photo: ByteArray,
    busy: Boolean,
    error: String?,
    onCancel: () -> Unit,
    onChooseAnother: () -> Unit,
    onSave: (ImageBitmap) -> Unit,
) {
    // Null while decoding; a photo that can't be decoded is a Failed load, not a crash.
    val image by produceState<ImageLoad>(ImageLoad.Loading, photo) {
        value = withContext(Dispatchers.Default) { runCatching { photo.decodeToImageBitmap() }.getOrNull() }
            ?.let { ImageLoad.Ready(it) } ?: ImageLoad.Failed
    }
    // The crop as laid out now; Save reads it. Replaced when the photo or the circle's size changes.
    var view by remember(photo) { mutableStateOf<CropView?>(null) }
    // Scaling a big photo takes a moment: done off the main thread, one render at a time.
    val scope = rememberCoroutineScope()
    var rendering by remember { mutableStateOf(false) }

    Overlay {
        BackHandler { if (!busy) onCancel() }
        Column(Modifier.fillMaxSize().background(CropBackground).navigationBarsPadding()) {
            TopInset(10.dp)
            Toolbar(
                leading = { CircleButton(Icons.X, "Cancel", tint = OnDark, enabled = !busy) { onCancel() } },
                center = { Txt("Move and scale", size = T.Body, weight = ExtraBold, color = OnDark) },
            )
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val density = LocalDensity.current
                val circle = with(density) { (min(maxWidth.toPx(), maxHeight.toPx()) - 48.dp.toPx()).coerceAtLeast(1f) }
                val ready = image as? ImageLoad.Ready
                if (ready != null) {
                    val bitmap = ready.bitmap
                    val crop = remember(bitmap, circle) { CropView(bitmap, circle, initialCropFrame(bitmap.width, bitmap.height, circle)) }
                    SideEffect { view = crop }
                    fun zoomBy(factor: Float): Boolean {
                        crop.frame = transformCropFrame(crop.frame, bitmap.width, bitmap.height, circle, Offset(circle / 2f, circle / 2f), Offset.Zero, factor)
                        return true
                    }
                    Canvas(
                        Modifier.fillMaxSize()
                            .pointerInput(crop) {
                                detectTransformGestures { centroid, pan, zoom, _ ->
                                    val origin = Offset((size.width - circle) / 2f, (size.height - circle) / 2f)
                                    crop.frame = transformCropFrame(crop.frame, bitmap.width, bitmap.height, circle, centroid - origin, pan, zoom)
                                }
                            }
                            .semantics {
                                contentDescription = "Photo. Drag to move it, pinch to zoom."
                                customActions = listOf(
                                    CustomAccessibilityAction("Zoom in") { zoomBy(1.25f) },
                                    CustomAccessibilityAction("Zoom out") { zoomBy(0.8f) },
                                )
                            },
                    ) {
                        val current = crop.frame
                        val origin = Offset((size.width - circle) / 2f, (size.height - circle) / 2f)
                        drawImage(
                            bitmap,
                            dstOffset = IntOffset((origin.x + current.offset.x).roundToInt(), (origin.y + current.offset.y).roundToInt()),
                            dstSize = IntSize((bitmap.width * current.scale).roundToInt(), (bitmap.height * current.scale).roundToInt()),
                            filterQuality = FilterQuality.Medium,
                        )
                        val hole = Path().apply {
                            fillType = PathFillType.EvenOdd
                            addRect(Rect(Offset.Zero, size))
                            addOval(Rect(origin, Size(circle, circle)))
                        }
                        drawPath(hole, CropDim)
                        drawCircle(OnDark.copy(alpha = 0.9f), radius = circle / 2f, center = origin + Offset(circle / 2f, circle / 2f), style = Stroke(2.dp.toPx()))
                    }
                } else if (image == ImageLoad.Failed) {
                    Txt("Couldn't open that photo. Choose another one.", Modifier.padding(horizontal = 32.dp), size = T.Body, weight = Semibold, color = OnDarkError, align = TextAlign.Center)
                }
            }
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt("Drag to move, pinch to zoom", size = T.Label, color = OnDarkMute)
                error?.let { Txt(it, size = T.Label, weight = Semibold, color = OnDarkError, align = TextAlign.Center) }
                Spacer(Modifier.height(2.dp))
                PrimaryButton("Save photo", enabled = image is ImageLoad.Ready, busy = busy || rendering) {
                    val crop = view ?: return@PrimaryButton
                    if (rendering) return@PrimaryButton
                    rendering = true
                    val rect = cropRect(crop.frame, crop.bitmap.width, crop.bitmap.height, crop.circle)
                    scope.launch {
                        try {
                            val rendered = withContext(Dispatchers.Default) { renderCrop(crop.bitmap, rect) }
                            onSave(rendered)
                        } finally {
                            rendering = false
                        }
                    }
                }
                TextButton("Choose another", color = OnDarkMute) { if (!busy) onChooseAnother() }
            }
        }
    }
}

@Stable
private class CropView(val bitmap: ImageBitmap, val circle: Float, initial: CropFrame) {
    var frame by mutableStateOf(initial)
}

private sealed interface ImageLoad {
    data object Loading : ImageLoad
    data object Failed : ImageLoad
    class Ready(val bitmap: ImageBitmap) : ImageLoad
}
