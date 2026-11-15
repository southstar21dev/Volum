package dev.southstar21.volum

import dev.southstar21.volum.data.PbException
import dev.southstar21.volum.data.PocketBase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class UpdateGateTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun everyRequestNamesTheBuild() = runTest {
        var seen: Headers? = null
        val pb = PocketBase("https://pb.test", HttpClient(MockEngine { seen = it.headers; respond("{}", HttpStatusCode.OK, json) }), "1.4.2", "ios")
        pb.send("/api/health")
        assertEquals("1.4.2", seen!![PocketBase.APP_VERSION_HEADER])
        assertEquals("ios", seen!![PocketBase.APP_PLATFORM_HEADER])
    }

    @Test
    fun aRefusedBuildIsToldWhichVersionItNeeds() = runTest {
        val body = """{"status":426,"message":"Out of date","data":{"minVersion":"1.6.0"}}"""
        val pb = PocketBase("https://pb.test", HttpClient(MockEngine { respond(body, HttpStatusCode.UpgradeRequired, json) }), "1.4.2", "ios")
        val asked = mutableListOf<String?>()
        pb.onUpdateRequired = { asked += it }
        val error = assertFailsWith<PbException> { pb.send("/api/health") }
        assertEquals(426, error.status)
        assertEquals(listOf<String?>("1.6.0"), asked)
    }

    @Test
    fun aRefusalWithoutAVersionStillCounts() = runTest {
        val pb = PocketBase("https://pb.test", HttpClient(MockEngine { respond("""{"message":"Out of date","data":{"minVersion":""}}""", HttpStatusCode.UpgradeRequired, json) }))
        var calls = 0
        var asked: String? = "unset"
        pb.onUpdateRequired = { calls++; asked = it }
        assertFailsWith<PbException> { pb.send("/api/health") }
        assertEquals(1, calls)
        assertNull(asked)
    }
}
