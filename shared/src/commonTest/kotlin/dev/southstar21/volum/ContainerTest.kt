@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.southstar21.volum

import dev.southstar21.volum.data.PartnerRepository
import dev.southstar21.volum.data.PocketBase
import dev.southstar21.volum.model.IncomingPairRequest
import dev.southstar21.volum.model.PartnerSnapshot
import dev.southstar21.volum.model.PartnerUser
import dev.southstar21.volum.model.VolumJson
import dev.southstar21.volum.util.newId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PARTNER_ROUTE = "/api/volum/partner"

/** The partner route (backend/pb_hooks/partner.pb.js) behind Ktor's MockEngine, on the test's virtual clock. */
private class PartnerServer(scope: TestScope) {
    sealed interface Reply {
        class Json(val status: Int, val body: String) : Reply

        /** 200 headers, then the body stream fails with [cause] after [prefix]. */
        class Broken(val prefix: String, val cause: Throwable) : Reply
    }

    var reply: suspend () -> Reply = { Reply.Json(200, snapshotJson(null)) }
    var calls = 0
        private set

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    val client = HttpClient(
        MockEngine(
            MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(scope.testScheduler)
                reuseHandlers = true
                addHandler { data ->
                    if (data.url.encodedPath != PARTNER_ROUTE) {
                        // The realtime stream and anything else: nothing to say.
                        respond("", HttpStatusCode.NotFound, jsonHeaders)
                    } else {
                        calls++
                        when (val r = reply()) {
                            is Reply.Json -> respond(r.body, HttpStatusCode.fromValue(r.status), jsonHeaders)
                            is Reply.Broken -> {
                                val channel = ByteChannel()
                                channel.writeStringUtf8(r.prefix)
                                channel.flush()
                                channel.cancel(r.cause)
                                respond(channel, HttpStatusCode.OK, jsonHeaders)
                            }
                        }
                    }
                }
            },
        ),
    )

    val pb = PocketBase("https://pb.test", client)
}

private fun snapshotJson(partner: PartnerUser?, incoming: List<IncomingPairRequest> = emptyList()) =
    VolumJson.encodeToString(PartnerSnapshot.serializer(), PartnerSnapshot(partner = partner, incoming = incoming))

class ContainerTest {
    private fun tempDir() = Path(SystemTemporaryDirectory, "volum-container-${newId()}")

    private val pat = PartnerUser(id = "p1", name = "Pat", email = "pat@example.com")
    private val robin = PartnerUser(id = "p2", name = "Robin", email = "robin@example.com")

    // ---- Partner refresh failures -----------------------------------------------------------

    @Test fun partnerRefreshSurvivesASuccessWhoseBodyIsNotASnapshot() = runTest {
        val server = PartnerServer(this)
        val partner = PartnerRepository(server.pb, tempDir(), backgroundScope)

        server.reply = { PartnerServer.Reply.Json(200, "null") }
        partner.refresh()
        assertEquals("Something went wrong", partner.error.value)
        assertEquals(PartnerSnapshot(), partner.snapshot.value)

        server.reply = { PartnerServer.Reply.Json(200, """{"partner":"nobody"}""") }
        partner.refresh()
        assertEquals("Something went wrong", partner.error.value)

        server.reply = { PartnerServer.Reply.Json(200, "<html>Sign in to the Wi-Fi</html>") }
        partner.refresh()
        assertEquals("You're offline", partner.error.value)

        server.reply = { PartnerServer.Reply.Json(200, snapshotJson(pat)) }
        partner.refresh()
        assertNull(partner.error.value)
        assertEquals(pat, partner.snapshot.value.partner)
    }

    @Test fun partnerRefreshSurvivesAConnectionDroppedMidBody() = runTest {
        val server = PartnerServer(this)
        val partner = PartnerRepository(server.pb, tempDir(), backgroundScope)

        server.reply = { PartnerServer.Reply.Broken("""{"partner":{"id":""", IOException("Connection reset by peer")) }
        partner.refresh()
        assertEquals("You're offline", partner.error.value)

        server.reply = { PartnerServer.Reply.Broken("""{"partner":{"id":""", CancellationException("Stream reset")) }
        partner.refresh()
        assertEquals("You're offline", partner.error.value)
        assertEquals(PartnerSnapshot(), partner.snapshot.value)
    }

    @Test fun partnerPollKeepsRunningAfterARefreshThatFailed() = runTest {
        val server = PartnerServer(this)
        val partner = PartnerRepository(server.pb, tempDir(), backgroundScope)
        server.reply = {
            if (server.calls == 1) PartnerServer.Reply.Json(200, "null") else PartnerServer.Reply.Json(200, snapshotJson(pat))
        }

        partner.start()
        partner.snapshot.first { it.partner != null }

        assertEquals(pat, partner.snapshot.value.partner)
        assertNull(partner.error.value)
        assertTrue(server.calls >= 2)
        partner.stop()
    }

    // ---- Partner changes --------------------------------------------------------------------

    @Test fun partnerChangeIsReportedWhenGainedReplacedOrLostAndNotForOtherChanges() = runTest {
        val server = PartnerServer(this)
        val dir = tempDir()
        val changes = mutableListOf<Pair<String?, String?>>()
        val partner = PartnerRepository(server.pb, dir, backgroundScope) { previous, current -> changes += previous?.id to current?.id }

        suspend fun serve(body: String) {
            server.reply = { PartnerServer.Reply.Json(200, body) }
            partner.refresh()
            assertNull(partner.error.value)
        }

        serve(snapshotJson(pat))
        assertEquals(listOf<Pair<String?, String?>>(null to "p1"), changes)

        // Same partner: a renamed profile, a pair request arriving.
        serve(snapshotJson(pat.copy(name = "Pat B")))
        serve(snapshotJson(pat.copy(name = "Pat B"), incoming = listOf(IncomingPairRequest("q1", PartnerUser("u3", "Sam")))))
        assertEquals(1, changes.size)

        serve(snapshotJson(robin))
        val reloaded = PartnerRepository(server.pb, dir, backgroundScope)
        reloaded.load()
        assertEquals(robin, reloaded.snapshot.value.partner)

        serve(snapshotJson(null))
        assertEquals(listOf<Pair<String?, String?>>(null to "p1", "p1" to "p2", "p2" to null), changes)
    }

    @Test fun partnerCleanupThatFailsRunsAgainOnTheNextStart() = runTest {
        val server = PartnerServer(this)
        val dir = tempDir()
        server.reply = { PartnerServer.Reply.Json(200, snapshotJson(pat)) }
        var failCleanup = true
        val changes = mutableListOf<Pair<String?, String?>>()
        val onChange: suspend (PartnerUser?, PartnerUser?) -> Unit = { previous, current ->
            changes += previous?.id to current?.id
            if (failCleanup) throw IOException("No space left on device")
        }

        val first = PartnerRepository(server.pb, dir, backgroundScope, onChange)
        first.load()
        first.refresh()
        assertEquals("Something went wrong", first.error.value)
        assertFalse(SystemFileSystem.exists(Path(dir, "partner.json")), "the snapshot isn't saved before the cleanup succeeds")

        failCleanup = false
        val next = PartnerRepository(server.pb, dir, backgroundScope, onChange)
        next.load()
        next.refresh()
        assertNull(next.error.value)
        assertEquals(listOf<Pair<String?, String?>>(null to "p1", null to "p1"), changes)
        assertTrue(SystemFileSystem.exists(Path(dir, "partner.json")))
    }
}
