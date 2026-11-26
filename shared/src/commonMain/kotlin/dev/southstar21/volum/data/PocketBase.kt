package dev.southstar21.volum.data

import dev.southstar21.volum.model.VolumJson
import io.ktor.client.HttpClient
import kotlin.concurrent.Volatile
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import io.ktor.http.fromHttpToGmtDate
import dev.southstar21.volum.util.nowMillis
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** A PocketBase error response (or a network failure with [status] 0). */
class PbException(val status: Int, override val message: String, val data: JsonObject? = null) : Exception(message) {
    /** The first field's validation message (PocketBase sends `data.<field>.message`), already written for people. */
    val fieldMessage: String?
        get() = data?.values?.firstNotNullOfOrNull { ((it as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
}

data class PbList(val page: Int, val totalPages: Int, val items: List<JsonObject>)

/**
 * The slice of the PocketBase REST API Volum uses: password auth, record CRUD, the partner
 * routes in backend/pb_hooks/partner.pb.js and realtime record events.
 */
class PocketBase(
    private val baseUrl: String,
    private val http: HttpClient,
    /** Sent with every request so the server can turn away builds it no longer supports (backend/pb_hooks/version_gate.pb.js). */
    private val clientVersion: String? = null,
    private val clientPlatform: String? = null,
    /** Where request bodies are encoded and responses parsed; tests pass a test dispatcher. */
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    @Volatile var token: String? = null

    /** Called when the server refuses this build as too old (426), with the version it asks for if it said. */
    @Volatile var onUpdateRequired: ((minVersion: String?) -> Unit)? = null

    private fun HttpRequestBuilder.identify() {
        clientVersion?.let { header(APP_VERSION_HEADER, it) }
        clientPlatform?.let { header(APP_PLATFORM_HEADER, it) }
    }

    /**
     * How far the server's clock is ahead of this device's (negative: behind), in ms, from the `Date`
     * header of the latest reply. 0 until a reply says, and when the difference is too small to tell
     * from the header's one-second resolution.
     */
    @Volatile var clockOffsetMs: Long = 0
        private set

    /** The server's time as best known now (epoch ms): the device clock corrected by [clockOffsetMs]. */
    fun serverNowMillis(): Long = nowMillis() + clockOffsetMs

    private fun noteServerDate(header: String?) {
        clockOffset(header, nowMillis())?.let { clockOffsetMs = it }
    }

    private fun url(path: String) = baseUrl.trimEnd('/') + path

    /** Releases the HTTP client's connections and threads; the instance can't be used afterwards. */
    fun close() = http.close()

    private suspend fun call(
        method: HttpMethod,
        path: String,
        body: JsonElement? = null,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): JsonElement {
        // Encoding a big payload (a workout with many sets) is kept off the calling (main) thread.
        val encoded = body?.let { withContext(defaultDispatcher) { VolumJson.encodeToString(JsonElement.serializer(), it) } }
        // The body is read inside the try too: a connection dropped after the headers is a network
        // failure like any other, not an exception that escapes to the caller's scope.
        val (status, text) = try {
            val response = http.request(url(path)) {
                this.method = method
                identify()
                token?.let { header("Authorization", it) }
                if (encoded != null) {
                    contentType(ContentType.Application.Json)
                    setBody(encoded)
                }
                configure()
            }
            noteServerDate(response.headers[HttpHeaders.Date])
            response.status.value to response.bodyAsText()
        } catch (e: CancellationException) {
            // A timed-out call can surface as a cancellation of the request rather than of this
            // coroutine; only a real cancellation of the caller propagates as one.
            currentCoroutineContext().ensureActive()
            throw PbException(0, e.message ?: "Request timed out")
        } catch (e: Throwable) {
            throw PbException(0, e.message ?: "Network error")
        }
        // A page of records can be megabytes: parsed off the main thread, small replies inline.
        val json = if (text.length > INLINE_JSON_LIMIT) {
            withContext(defaultDispatcher) { runCatching { VolumJson.parseToJsonElement(text) }.getOrNull() }
        } else {
            runCatching { VolumJson.parseToJsonElement(text) }.getOrNull()
        }
        if (status !in 200..299) {
            val obj = json as? JsonObject
            val message = obj?.get("message")?.jsonPrimitive?.contentOrNull ?: "Request failed ($status)"
            if (status == UPDATE_REQUIRED) reportUpdateRequired(obj)
            throw PbException(status, message, obj?.get("data") as? JsonObject)
        }
        // A 2xx whose body isn't JSON (a captive portal's login page, a truncated proxy reply) is
        // a failed exchange with the server; an empty body (204) is a success with nothing to say.
        if (json == null && text.isNotBlank()) throw PbException(0, UNEXPECTED_RESPONSE)
        return json ?: JsonNull
    }

    private fun reportUpdateRequired(error: JsonObject?) {
        val minVersion = ((error?.get("data") as? JsonObject)?.get("minVersion") as? JsonPrimitive)?.contentOrNull
        onUpdateRequired?.invoke(minVersion?.takeIf(String::isNotBlank))
    }

    /** Reads a successful response's shape; a reply that doesn't have it is a [PbException] like any other failure. */
    private inline fun <T> decode(block: () -> T): T = try {
        block()
    } catch (_: IllegalArgumentException) {
        throw PbException(0, UNEXPECTED_RESPONSE)
    } catch (_: NoSuchElementException) {
        throw PbException(0, UNEXPECTED_RESPONSE)
    }

    private fun JsonElement.record(): JsonObject = decode { jsonObject }

    private fun JsonElement.authResult(): Pair<String, JsonObject> = decode {
        val result = jsonObject
        result.getValue("token").jsonPrimitive.content to result.getValue("record").jsonObject
    }

    // ---- Auth -------------------------------------------------------------------------------

    suspend fun authWithPassword(email: String, password: String): Pair<String, JsonObject> = call(
        HttpMethod.Post,
        "/api/collections/users/auth-with-password",
        buildJsonObject { put("identity", email); put("password", password) },
    ).authResult()

    suspend fun authRefresh(): Pair<String, JsonObject> =
        call(HttpMethod.Post, "/api/collections/users/auth-refresh").authResult()

    suspend fun createUser(email: String, password: String, name: String): JsonObject = call(
        HttpMethod.Post,
        "/api/collections/users/records",
        buildJsonObject {
            put("email", email)
            put("password", password)
            put("passwordConfirm", password)
            put("name", name)
        },
    ).record()

    // ---- Records ----------------------------------------------------------------------------

    suspend fun getList(collection: String, page: Int, perPage: Int, filter: String? = null, sort: String? = null): PbList {
        val result = call(HttpMethod.Get, "/api/collections/$collection/records") {
            parameter("page", page)
            parameter("perPage", perPage)
            // The total isn't used (pages follow a cursor), so the server can skip counting it.
            parameter("skipTotal", 1)
            filter?.let { parameter("filter", it) }
            sort?.let { parameter("sort", it) }
        }
        return decode {
            val list = result.jsonObject
            PbList(
                page = list["page"]?.jsonPrimitive?.int ?: page,
                totalPages = list["totalPages"]?.jsonPrimitive?.int ?: 1,
                items = list["items"]?.jsonArray?.map { it.jsonObject } ?: emptyList(),
            )
        }
    }

    suspend fun getOne(collection: String, id: String): JsonObject =
        call(HttpMethod.Get, "/api/collections/$collection/records/${id.encodeURLPathPart()}").record()

    suspend fun create(collection: String, body: JsonObject): JsonObject =
        call(HttpMethod.Post, "/api/collections/$collection/records", body).record()

    suspend fun update(collection: String, id: String, body: JsonObject): JsonObject =
        call(HttpMethod.Patch, "/api/collections/$collection/records/${id.encodeURLPathPart()}", body).record()

    // ---- Files ------------------------------------------------------------------------------

    /** Replaces the file in [field] of a record with [bytes], as a multipart PATCH. */
    suspend fun uploadFile(collection: String, id: String, field: String, bytes: ByteArray, fileName: String, mime: String): JsonObject =
        call(HttpMethod.Patch, "/api/collections/$collection/records/${id.encodeURLPathPart()}") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            field,
                            bytes,
                            Headers.build {
                                append(HttpHeaders.ContentType, mime)
                                append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                            },
                        )
                    },
                ),
            )
        }.record()

    /** Empties the file field [field] of a record. */
    suspend fun clearFile(collection: String, id: String, field: String): JsonObject =
        update(collection, id, buildJsonObject { put(field, "") })

    /** Where a stored file is served; [thumb] is one of the field's thumb sizes ("256x256"). */
    fun fileUrl(collection: String, recordId: String, fileName: String, thumb: String? = null): String =
        url("/api/files/$collection/${recordId.encodeURLPathPart()}/${fileName.encodeURLPathPart()}") +
            (thumb?.let { "?thumb=${it.encodeURLParameter()}" } ?: "")

    /** A file's bytes from [url] (one of [fileUrl]'s), or null when it couldn't be fetched. */
    suspend fun download(url: String): ByteArray? = try {
        val response = http.get(url) { identify() }
        if (response.status.value in 200..299) response.bodyAsBytes() else null
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        null
    } catch (_: Throwable) {
        null
    }

    /** A custom route (pb_hooks), like the JS SDK's pb.send. */
    suspend fun send(path: String, method: HttpMethod = HttpMethod.Get, body: JsonElement? = null): JsonElement =
        call(method, path, body)

    // ---- Realtime ---------------------------------------------------------------------------

    /**
     * Subscribes to record events on [topics] (a collection wildcard or "workouts/<id>") over the
     * realtime SSE stream, reconnecting until [scope] ends. [onEvent] gets the topic and the record.
     */
    fun realtime(scope: CoroutineScope, topics: List<String>, onEvent: (topic: String, record: JsonObject?) -> Unit): Job =
        scope.launch {
            var failures = 0
            while (isActive) {
                var connected = false
                try {
                    http.prepareGet(url("/api/realtime")) {
                        identify()
                        header("Accept", "text/event-stream")
                        // The stream stays open and idle between events; only this request is unbounded.
                        timeout {
                            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                            socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                        }
                    }.execute { response ->
                        val status = response.status.value
                        if (status !in 200..299) {
                            // Not an event stream (426, 401, a proxy's 502): nothing to read, retried with backoff.
                            if (status == UPDATE_REQUIRED) {
                                val error = runCatching { VolumJson.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
                                reportUpdateRequired(error)
                            }
                            return@execute
                        }
                        connected = true
                        val channel = response.bodyAsChannel()
                        var event = ""
                        val data = StringBuilder()
                        while (isActive) {
                            val line = channel.readLine() ?: break
                            when {
                                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                                line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                                line.isEmpty() -> {
                                    val payload = runCatching { VolumJson.parseToJsonElement(data.toString()).jsonObject }.getOrNull()
                                    if (event == "PB_CONNECT") {
                                        val clientId = payload?.get("clientId")?.jsonPrimitive?.contentOrNull
                                        if (clientId != null) {
                                            call(
                                                HttpMethod.Post,
                                                "/api/realtime",
                                                buildJsonObject {
                                                    put("clientId", clientId)
                                                    putJsonArray("subscriptions") { topics.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                                                },
                                            )
                                        }
                                    } else if (event.isNotEmpty()) {
                                        onEvent(event, payload?.get("record") as? JsonObject)
                                    }
                                    event = ""
                                    data.clear()
                                }
                            }
                        }
                    }
                } catch (_: CancellationException) {
                    // Only the scope ending stops the loop; a cancelled stream reconnects like a dropped one.
                    ensureActive()
                } catch (_: Throwable) {
                    // Fall through to a short pause and reconnect; callers also poll.
                }
                // A stream that was up and dropped reconnects quickly; one that never opened backs off.
                failures = if (connected) 0 else failures + 1
                val base = (REALTIME_RETRY_MS shl (failures - 1).coerceIn(0, 5)).coerceAtMost(REALTIME_MAX_RETRY_MS)
                delay(base + Random.nextLong(base / 5 + 1))
            }
        }

    companion object {
        private const val INLINE_JSON_LIMIT = 16_384
        private const val REALTIME_RETRY_MS = 5_000L
        private const val REALTIME_MAX_RETRY_MS = 120_000L
        private const val UNEXPECTED_RESPONSE = "Unexpected response from server"
        const val APP_VERSION_HEADER = "X-App-Version"
        const val APP_PLATFORM_HEADER = "X-App-Platform"

        /** 426 Upgrade Required: the server's answer to a build older than its minimum. */
        const val UPDATE_REQUIRED = 426

        /** A difference beyond this is a broken `Date` (a stale cache, a bad proxy), not a skewed clock. */
        private const val MAX_CLOCK_OFFSET_MS = 24 * 60 * 60 * 1000L

        /** `Date` is whole seconds, so a smaller difference than this is rounding, not skew. */
        private const val CLOCK_OFFSET_DEADBAND_MS = 2_000L

        /**
         * The server clock's offset from the device's, from a `Date` response header [dateHeader] that
         * arrived at [receivedAtMillis] (device clock); null when it is missing, unreadable or absurd.
         */
        fun clockOffset(dateHeader: String?, receivedAtMillis: Long): Long? {
            if (dateHeader.isNullOrBlank()) return null
            val server = try {
                dateHeader.fromHttpToGmtDate().timestamp
            } catch (_: Exception) {
                return null
            }
            val offset = server - receivedAtMillis
            if (kotlin.math.abs(offset) > MAX_CLOCK_OFFSET_MS) return null
            return if (kotlin.math.abs(offset) < CLOCK_OFFSET_DEADBAND_MS) 0 else offset
        }

        private val PLACEHOLDER = Regex("\\{:(\\w+)\\}")

        /** pb.filter: binds {:name} placeholders as quoted strings. */
        fun filter(expr: String, params: Map<String, String>): String =
            // One pass over the template: a value that itself contains "{:other}" is never substituted again.
            PLACEHOLDER.replace(expr) { m ->
                val value = params[m.groupValues[1]] ?: return@replace m.value
                "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
            }
    }
}
