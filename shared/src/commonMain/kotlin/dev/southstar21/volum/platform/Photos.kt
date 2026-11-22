package dev.southstar21.volum.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

/** The long side a picked photo is scaled down to: plenty to crop a profile photo from. */
const val PICKED_PHOTO_MAX_PX = 2048

/**
 * The system photo picker (Android's photo picker, iOS's PHPicker): only the photo the user picks is
 * shared with the app, so there is no photos permission to ask for. Call the returned function to open
 * it. [onPicked] gets the photo as a JPEG, upright, at most [PICKED_PHOTO_MAX_PX] on its long side;
 * [onError] runs when a photo was picked but couldn't be read. Cancelling calls neither.
 */
@Composable
expect fun rememberPhotoPicker(onPicked: (ByteArray) -> Unit, onError: () -> Unit): () -> Unit

/** [bitmap] as JPEG at [quality] (0 to 100). */
expect fun encodeJpeg(bitmap: ImageBitmap, quality: Int): ByteArray
