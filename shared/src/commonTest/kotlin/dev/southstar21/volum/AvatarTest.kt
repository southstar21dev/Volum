package dev.southstar21.volum

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import dev.southstar21.volum.data.ImageCache
import dev.southstar21.volum.model.PartnerSnapshot
import dev.southstar21.volum.model.VolumJson
import dev.southstar21.volum.ui.profile.CROP_MAX_ZOOM
import dev.southstar21.volum.ui.profile.CropFrame
import dev.southstar21.volum.ui.profile.clampCropFrame
import dev.southstar21.volum.ui.profile.cropRect
import dev.southstar21.volum.ui.profile.initialCropFrame
import dev.southstar21.volum.ui.profile.minCropScale
import dev.southstar21.volum.ui.profile.transformCropFrame
import dev.southstar21.volum.util.newId
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AvatarTest {
    // ---- Crop -------------------------------------------------------------------------------

    private val circle = 300f

    @Test fun aLandscapePhotoStartsCentredWithItsHeightFillingTheCircle() {
        val frame = initialCropFrame(2000, 1000, circle)
        assertEquals(0.3f, frame.scale, 0.0001f)
        assertEquals(-150f, frame.offset.x, 0.01f)
        assertEquals(0f, frame.offset.y, 0.01f)
        assertEquals(IntRect(500, 0, 1500, 1000), cropRect(frame, 2000, 1000, circle))
    }

    @Test fun aPortraitPhotoStartsCentredWithItsWidthFillingTheCircle() {
        val frame = initialCropFrame(1000, 2000, circle)
        assertEquals(IntRect(0, 500, 1000, 1500), cropRect(frame, 1000, 2000, circle))
    }

    @Test fun thePhotoCantBeDraggedOffTheCircle() {
        val frame = initialCropFrame(2000, 1000, circle)
        val dragged = transformCropFrame(frame, 2000, 1000, circle, Offset(150f, 150f), Offset(10_000f, 10_000f), 1f)
        assertEquals(Offset(0f, 0f), dragged.offset, "stops at the photo's top-left corner")
        assertEquals(IntRect(0, 0, 1000, 1000), cropRect(dragged, 2000, 1000, circle))

        val other = transformCropFrame(frame, 2000, 1000, circle, Offset(150f, 150f), Offset(-10_000f, -10_000f), 1f)
        assertEquals(IntRect(1000, 0, 2000, 1000), cropRect(other, 2000, 1000, circle))
    }

    @Test fun zoomStaysBetweenCoveringTheCircleAndTheMaximum() {
        val frame = initialCropFrame(1000, 1000, circle)
        val min = minCropScale(1000, 1000, circle)
        assertEquals(min, transformCropFrame(frame, 1000, 1000, circle, Offset(150f, 150f), Offset.Zero, 0.1f).scale)
        assertEquals(min * CROP_MAX_ZOOM, transformCropFrame(frame, 1000, 1000, circle, Offset(150f, 150f), Offset.Zero, 100f).scale)
    }

    @Test fun zoomingKeepsThePointUnderTheFingersInPlace() {
        val frame = initialCropFrame(1000, 1000, circle)
        val zoomed = transformCropFrame(frame, 1000, 1000, circle, Offset(150f, 150f), Offset.Zero, 2f)
        // Twice as close on the centre: the middle half of the photo.
        assertEquals(IntRect(250, 250, 750, 750), cropRect(zoomed, 1000, 1000, circle))

        val corner = transformCropFrame(frame, 1000, 1000, circle, Offset(0f, 0f), Offset.Zero, 2f)
        assertEquals(IntRect(0, 0, 500, 500), cropRect(corner, 1000, 1000, circle))
    }

    @Test fun aFrameOutOfRangeIsPulledBackIn() {
        val clamped = clampCropFrame(CropFrame(scale = 0.01f, offset = Offset(50f, -5000f)), 1000, 1000, circle)
        assertEquals(0.3f, clamped.scale, 0.0001f)
        assertTrue(clamped.offset.x <= 0f && clamped.offset.x >= circle - 1000 * clamped.scale)
        assertTrue(abs(clamped.offset.y - (circle - 1000 * clamped.scale)) < 0.001f)
    }

    // ---- Image cache ------------------------------------------------------------------------

    @Test fun anImageIsDownloadedOnceThenReadFromDisk() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dir = Path(SystemTemporaryDirectory, "volum-images-${newId()}")
        val downloads = mutableListOf<String>()
        fun cache(online: Boolean) = ImageCache(dir, backgroundScope, dispatcher) { url ->
            downloads += url
            if (online) byteArrayOf(7, 8, 9) else null
        }
        val url = "https://pb.test/api/files/users/ua/a_1.jpg?thumb=256x256"
        try {
            assertTrue(byteArrayOf(7, 8, 9).contentEquals(cache(online = true).load(url)))
            // A new process, offline: still there.
            assertTrue(byteArrayOf(7, 8, 9).contentEquals(cache(online = false).load(url)))
            assertEquals(1, downloads.size)

            assertNull(cache(online = false).load("https://pb.test/api/files/users/ub/b_2.jpg"), "offline and never fetched")
        } finally {
            if (SystemFileSystem.exists(dir)) {
                SystemFileSystem.list(dir).forEach { SystemFileSystem.delete(it, mustExist = false) }
                SystemFileSystem.delete(dir, mustExist = false)
            }
        }
    }

    @Test fun cacheKeysAreSafeFileNames() {
        val key = ImageCache.keyOf("https://pb.test/api/files/users/ua/a_1.jpg?thumb=256x256")
        assertEquals("api_files_users_ua_a_1.jpg_thumb_256x256", key)
    }

    // ---- Partner payloads -------------------------------------------------------------------

    @Test fun partnerPayloadsWithAndWithoutAPhotoDecode() {
        val withPhoto = VolumJson.decodeFromString(
            PartnerSnapshot.serializer(),
            """{"partner":{"id":"up","name":"Pat","email":"pat@example.com","avatar":"p_1.jpg"},"activeWorkout":null,"incoming":[],"outgoing":[]}""",
        )
        assertEquals("p_1.jpg", withPhoto.partner?.avatar)
        val older = VolumJson.decodeFromString(
            PartnerSnapshot.serializer(),
            """{"partner":{"id":"up","name":"Pat","email":"pat@example.com"},"activeWorkout":null,"incoming":[],"outgoing":[]}""",
        )
        assertEquals("", older.partner?.avatar)
    }
}
