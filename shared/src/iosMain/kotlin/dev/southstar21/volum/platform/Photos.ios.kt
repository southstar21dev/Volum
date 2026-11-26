package dev.southstar21.volum.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.kCGBitmapByteOrder32Little
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGImageAlphaInfo
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIGraphicsPopContext
import platform.UIKit.UIGraphicsPushContext
import platform.UIKit.UIImage
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

@Composable
actual fun rememberPhotoPicker(onPicked: (ImageBitmap) -> Unit, onError: () -> Unit): () -> Unit {
    val picked by rememberUpdatedState(onPicked)
    val failed by rememberUpdatedState(onError)
    // Held here: the picker keeps only a weak reference to its delegate.
    val delegate = remember { PhotoPickerDelegate({ picked(it) }, { failed() }) }
    // The picker's answer can arrive after the screen is gone (an iCloud photo takes a while to load).
    DisposableEffect(delegate) { onDispose { delegate.dispose() } }
    return remember(delegate) { { delegate.present() } }
}

@OptIn(ExperimentalForeignApi::class)
actual fun encodeJpeg(bitmap: ImageBitmap, quality: Int): ByteArray =
    Image.makeFromBitmap(bitmap.asSkiaBitmap()).encodeToData(EncodedImageFormat.JPEG, quality)?.bytes
        ?: error("JPEG encoding failed")

private class PhotoPickerDelegate(
    private val onPicked: (ImageBitmap) -> Unit,
    private val onError: () -> Unit,
) : NSObject(), PHPickerViewControllerDelegateProtocol {
    private var disposed = false

    fun dispose() {
        disposed = true
    }

    fun present() {
        val config = PHPickerConfiguration()
        config.filter = PHPickerFilter.imagesFilter
        config.selectionLimit = 1
        val picker = PHPickerViewController(configuration = config)
        picker.delegate = this
        topViewController()?.presentViewController(picker, animated = true, completion = null)
    }

    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, completion = null)
        val result = didFinishPicking.firstOrNull() as? PHPickerResult ?: return
        // Data, not a UIImage: UIImage(data:) reads HEIC and JPEG alike and keeps the EXIF orientation.
        result.itemProvider.loadDataRepresentationForTypeIdentifier("public.image") { data, _ ->
            val photo = data?.let { UIImage(data = it) }?.let(::scaledBitmap)
            dispatch_async(dispatch_get_main_queue()) {
                if (disposed) return@dispatch_async
                if (photo != null) onPicked(photo) else onError()
            }
        }
    }
}

private fun topViewController(): UIViewController? {
    // UIApplication.keyWindow is deprecated and wrong with more than one scene: ask the active scene.
    val scenes = UIApplication.sharedApplication.connectedScenes.filterIsInstance<UIWindowScene>()
    val scene = scenes.firstOrNull { it.activationState == UISceneActivationStateForegroundActive } ?: scenes.firstOrNull()
    val windows = scene?.windows?.filterIsInstance<UIWindow>().orEmpty()
    var top = (windows.firstOrNull { it.isKeyWindow() } ?: windows.firstOrNull())?.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    return top
}

/**
 * [image] drawn upright, at most [PICKED_PHOTO_MAX_PX] on its long side, into a bitmap the crop screen
 * can use directly (no JPEG round trip).
 */
@OptIn(ExperimentalForeignApi::class)
private fun scaledBitmap(image: UIImage): ImageBitmap? {
    val (width, height) = image.size.useContents { width to height }
    if (width <= 0.0 || height <= 0.0) return null
    val scale = min(1.0, PICKED_PHOTO_MAX_PX / max(width, height))
    val w = max(1.0, floor(width * scale)).toInt()
    val h = max(1.0, floor(height * scale)).toInt()
    val pixels = ByteArray(w * h * 4)
    val drawn = pixels.usePinned { pinned ->
        val space = CGColorSpaceCreateDeviceRGB()
        // BGRA, premultiplied: the layout Skia's BGRA_8888 expects.
        val context = CGBitmapContextCreate(
            pinned.addressOf(0), w.toULong(), h.toULong(), 8u, (w * 4).toULong(), space,
            CGImageAlphaInfo.kCGImageAlphaPremultipliedFirst.value or kCGBitmapByteOrder32Little,
        )
        CGColorSpaceRelease(space)
        if (context == null) return@usePinned false
        // UIKit draws from the top left, a raw bitmap context from the bottom left: flip it.
        CGContextTranslateCTM(context, 0.0, h.toDouble())
        CGContextScaleCTM(context, 1.0, -1.0)
        UIGraphicsPushContext(context)
        // Drawing applies imageOrientation, so the pixels come out upright.
        image.drawInRect(CGRectMake(0.0, 0.0, w.toDouble(), h.toDouble()))
        UIGraphicsPopContext()
        CGContextRelease(context)
        true
    }
    if (!drawn) return null
    val bitmap = Bitmap()
    if (!bitmap.installPixels(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.PREMUL), pixels, w * 4)) return null
    return Image.makeFromBitmap(bitmap).toComposeImageBitmap()
}
