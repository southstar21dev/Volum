package dev.southstar21.volum.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import dev.southstar21.volum.platform.logWarn
import dev.southstar21.volum.util.newId
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * Downloaded images (profile photos), kept on disk so they still show offline. A stored file's name
 * changes whenever it is replaced, so a cached copy never goes stale: it is reused as is. The folder
 * is capped at [MAX_FILES] files: past that, the ones not used since the app started go first.
 */
class ImageCache(
    private val dir: Path,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val download: suspend (url: String) -> ByteArray?,
) {
    private val lock = Mutex()
    private val inFlight = mutableMapOf<String, Deferred<ByteArray?>>()

    /** Held while files are written or pruned, so a [put] and a download of the same image can't interleave. */
    private val fileLock = Mutex()

    /** File names used or written in this run, oldest use first; only touched under [fileLock]. */
    private val recent = LinkedHashSet<String>()

    /** The image at [url]: from disk if it was fetched before, else downloaded and saved. Null if neither works. */
    suspend fun load(url: String): ByteArray? {
        val job = lock.withLock {
            inFlight.getOrPut(url) {
                scope.async {
                    try {
                        fetch(url)
                    } finally {
                        // Also when cancelled, or the dead Deferred would stay in the map for good.
                        withContext(NonCancellable) { lock.withLock { inFlight.remove(url) } }
                    }
                }
            }
        }
        return job.await()
    }

    /** Saves [bytes] as [url]'s image, so a photo just uploaded shows without downloading it again. */
    suspend fun put(url: String, bytes: ByteArray) {
        store(fileOf(url), bytes)
    }

    private suspend fun store(file: Path, bytes: ByteArray) {
        fileLock.withLock { withContext(io) { write(file, bytes); prune(file) } }
    }

    private suspend fun fetch(url: String): ByteArray? {
        val file = fileOf(url)
        fileLock.withLock { withContext(io) { read(file)?.also { recent.remove(file.name); recent.add(file.name) } } }?.let { return it }
        val bytes = download(url) ?: return null
        store(file, bytes)
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
            ensureDirectory(dir)
            // A temp name of its own, so two writers of one image never share a half-written file.
            val tmp = Path("$path.${newId()}.tmp")
            try {
                SystemFileSystem.sink(tmp).buffered().use { it.write(bytes) }
                SystemFileSystem.atomicMove(tmp, path)
            } catch (e: Exception) {
                runCatching { SystemFileSystem.delete(tmp, mustExist = false) }
                throw e
            }
            recent.remove(path.name)
            recent.add(path.name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Disk full or similar: the image still shows this time and is downloaded again next time.
            logWarn("ImageCache", "Couldn't save an image", e)
        }
    }

    /** Keeps the folder to [MAX_FILES]: files not used this run go first, [keep] (just written) never. */
    private fun prune(keep: Path) {
        try {
            val files = SystemFileSystem.list(dir).filter { !it.name.endsWith(".tmp") && it.name != keep.name }
            var excess = files.size + 1 - MAX_FILES
            if (excess <= 0) return
            val (used, unused) = files.partition { it.name in recent }
            val order = unused + used.sortedBy { f -> recent.indexOf(f.name) }
            for (f in order) {
                if (excess-- <= 0) break
                SystemFileSystem.delete(f, mustExist = false)
                recent.remove(f.name)
            }
        } catch (e: Exception) {
            logWarn("ImageCache", "Couldn't trim the image cache", e)
        }
    }

    internal companion object {
        const val MAX_FILES = 64

        /**
         * A file name for [url]: 32 hex digits from two 64-bit FNV-1a hashes of the whole URL. Nothing of
         * the URL is kept, so no URL can name a path outside the folder, and a long one can't collide
         * with another by being cut short.
         */
        fun keyOf(url: String): String {
            var a = -3750763034362895579L // FNV offset basis
            var b = 7809847782465536322L
            for (byte in url.encodeToByteArray()) {
                a = (a xor (byte.toLong() and 0xFF)) * 1099511628211L
                b = (b xor ((byte.toLong() and 0xFF) + 0x9E)) * 1099511628211L
            }
            return a.toULong().toString(16).padStart(16, '0') + b.toULong().toString(16).padStart(16, '0')
        }
    }
}
