package dev.southstar21.volum.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import dev.southstar21.volum.model.AuthUser
import dev.southstar21.volum.model.PartnerUser
import dev.southstar21.volum.ui.LocalApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

/** Where this user's profile photo is served, or null when they have none. */
@Composable
fun AuthUser.photoUrl(): String? = LocalApp.current.avatarUrl(id, avatar)

/** Where this partner's profile photo is served, or null when they have none. */
@Composable
fun PartnerUser.photoUrl(): String? = LocalApp.current.avatarUrl(id, avatar)

/**
 * The photo at [url], decoded. Null while it loads, and when there is no [url] or it can't be had
 * (offline and never fetched): the avatar shows its letter meanwhile.
 */
@Composable
internal fun rememberPhoto(url: String?): ImageBitmap? {
    if (url == null) return null
    val app = LocalApp.current
    val photo by produceState(DecodedPhotos.get(url), url) {
        if (value != null) return@produceState
        value = try {
            app.images.load(url)?.let { bytes -> withContext(Dispatchers.Default) { decode(bytes) } }
                ?.also { DecodedPhotos.put(url, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
    return photo
}

@OptIn(ExperimentalResourceApi::class)
private fun decode(bytes: ByteArray): ImageBitmap? = runCatching { bytes.decodeToImageBitmap() }.getOrNull()

/** Decoded photos recently shown, so returning to a screen doesn't flash the letter first. */
private object DecodedPhotos {
    private const val MAX = 16
    private val entries = LinkedHashMap<String, ImageBitmap>()

    fun get(url: String): ImageBitmap? = entries.remove(url)?.also { entries[url] = it }

    fun put(url: String, photo: ImageBitmap) {
        entries.remove(url)
        entries[url] = photo
        while (entries.size > MAX) entries.remove(entries.keys.first())
    }
}
