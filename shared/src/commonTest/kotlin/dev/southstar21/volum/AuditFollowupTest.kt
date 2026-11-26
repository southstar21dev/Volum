@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.southstar21.volum

import dev.southstar21.volum.data.GENERIC_ERROR
import dev.southstar21.volum.data.LocalStore
import dev.southstar21.volum.data.PbException
import dev.southstar21.volum.data.PocketBase
import dev.southstar21.volum.data.Repository
import dev.southstar21.volum.data.actionErrorMessage
import dev.southstar21.volum.data.authErrorMessage
import dev.southstar21.volum.data.partnerErrorMessage
import dev.southstar21.volum.model.SetEntry
import dev.southstar21.volum.model.WorkoutEntry
import dev.southstar21.volum.util.isoOf
import dev.southstar21.volum.util.newId
import dev.southstar21.volum.util.parseTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuditFollowupTest {
    // ---- Clock offset from the Date header -----------------------------------------------------

    private val received = parseTime("2023-11-14T22:13:20.000Z")

    @Test fun clockOffsetReadsTheDateHeader() {
        // The server says 10 s later than the device; the header's date is in the server's own terms.
        assertEquals(10_000L, PocketBase.clockOffset("Tue, 14 Nov 2023 22:13:30 GMT", received))
        assertEquals(-30_000L, PocketBase.clockOffset("Tue, 14 Nov 2023 22:12:50 GMT", received))
    }

    @Test fun clockOffsetIgnoresRoundingMissingAndAbsurdDates() {
        assertEquals(0L, PocketBase.clockOffset("Tue, 14 Nov 2023 22:13:21 GMT", received), "a second off is rounding")
        assertNull(PocketBase.clockOffset(null, received))
        assertNull(PocketBase.clockOffset("not a date", received))
        assertNull(PocketBase.clockOffset("Tue, 14 Nov 2022 22:13:20 GMT", received), "a year off is a broken header")
    }

    // ---- Stamps use the server clock -------------------------------------------------------------

    @Test fun setEditsAreStampedWithTheServerAdjustedClock() = runTest {
        val store = LocalStore(
            Path(SystemTemporaryDirectory, "volum-stamp-${newId()}"),
            CoroutineScope(SupervisorJob() + StandardTestDispatcher(TestCoroutineScheduler())),
        )
        val serverNow = 1_800_000_000_000L
        val repo = Repository(store, "me") { serverNow }
        val entry = WorkoutEntry(id = "e1", exerciseId = "x1", name = "Bench", sets = listOf(SetEntry(id = "s1", weight = 50.0, reps = 5)))
        val saved = repo.saveWorkout(routineName = "Push", entries = listOf(entry))
        assertEquals(isoOf(serverNow), saved.entries.single().sets.single().updatedAt)
    }

    // ---- Error wording ---------------------------------------------------------------------------

    @Test fun actionErrorsPickOfflineServerTextOrTheFallback() {
        val offline = PbException(0, "x")
        val field = PbException(400, "Failed.", kotlinx.serialization.json.buildJsonObject {
            put("name", kotlinx.serialization.json.buildJsonObject { put("message", kotlinx.serialization.json.JsonPrimitive("Too long.")) })
        })
        assertEquals("off", actionErrorMessage(offline, "off", "fail"))
        assertEquals("Too long.", actionErrorMessage(field, "off", "fail"))
        assertEquals("fail", actionErrorMessage(field, "off", "fail", serverText = false))
        assertEquals("fail", actionErrorMessage(IllegalStateException("boom"), "off", "fail"))
    }

    @Test fun authAndPartnerErrorsKeepTheirWording() {
        assertEquals("Wrong email or password.", authErrorMessage(PbException(400, "x"), register = false))
        assertEquals("Too many attempts. Wait a minute and try again.", authErrorMessage(PbException(429, "x"), register = true))
        assertEquals("Something went wrong. Try again.", authErrorMessage(IllegalStateException(), register = false))
        assertEquals("Session ended", partnerErrorMessage(PbException(404, "x")))
        assertEquals("You're offline", partnerErrorMessage(PbException(0, "x")))
        assertEquals(GENERIC_ERROR, partnerErrorMessage(IllegalStateException()))
        assertTrue(partnerErrorMessage(PbException(500, "x")) == GENERIC_ERROR)
    }
}
