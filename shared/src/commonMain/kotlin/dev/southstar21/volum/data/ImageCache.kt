package dev.southstar21.volum.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * Downloaded images (profile photos), kept on disk so they still show offline. A stored file's name
 * changes whenever it is replaced, so a cached copy never goes stale: it is reused as is.
 */
class ImageCache(
    private val dir: Path,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val download: suspend (url: String) -> ByteArray?,
) {
    private val lock = Mutex()
    private val inFlight = mutableMapOf<String, Deferred<ByteArray?>>()

    /** The image at [url]: from disk if it was fetched before, else downloaded and saved. Null if neither works. */
    suspend fun load(url: String): ByteArray? {
        val job = lock.withLock {
            inFlight.getOrPut(url) {
                scope.async {
                    try {
                        fetch(url)
                    } finally {
                        lock.withLock { inFlight.remove(url) }
                    }
                }
            }
        }
        return job.await()
    }

    /** Saves [bytes] as [url]'s image, so a photo just uploaded shows without downloading it again. */
    suspend fun put(url: String, bytes: ByteArray) {
        withContext(io) { write(fileOf(url), bytes) }
    }

    private suspend fun fetch(url: String): ByteArray? {
        val file = fileOf(url)
        withContext(io) { read(file) }?.let { return it }
        val bytes = download(url) ?: return null
        withContext(io) { write(file, bytes) }
        return bytes
    }

    private fun fileOf(url: String) = Path(dir, keyOf(url))

    private fun read(path: Path): ByteArray? = try {
        if (SystemFileSystem.exists(path)) SystemFileSystem.source(path).buffered().use { it.readByteArray() }.takeIf { it.isNotEmpty() } else null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun write(path: Path, bytes: ByteArray) {
        try {
            SystemFileSystem.createDirectories(dir)
            val tmp = Path("$path.tmp")
            SystemFileSystem.sink(tmp).buffered().use { it.write(bytes) }
            SystemFileSystem.atomicMove(tmp, path)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Disk full or similar: the image still shows this time and is downloaded again next time.
        }
    }

    internal companion object {
        /** A file name for [url]: its path and query with anything but letters, digits, '.', '-' and '_' replaced. */
        fun keyOf(url: String): String {
            val tail = url.substringAfter("://").substringAfter('/')
            return tail.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }.joinToString("").takeLast(160)
        }
    }
}
