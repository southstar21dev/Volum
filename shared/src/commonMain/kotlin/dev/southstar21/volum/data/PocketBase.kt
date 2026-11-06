package dev.southstar21.volum.data

import dev.southstar21.volum.model.VolumJson
import io.ktor.client.HttpClient
import kotlin.concurrent.Volatile
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** A PocketBase error response (or a network failure with [status] 0). */
class PbException(val status: Int, override val message: String, val data: JsonObject? = null) : Exception(message)

data class PbList(val page: Int, val totalPages: Int, val items: List<JsonObject>)

/**
 * The slice of the PocketBase REST API Volum uses: password auth, record CRUD, the partner
 * routes in pb_hooks/partner.pb.js and realtime record events.
 */
class PocketBase(private val baseUrl: String, private val http: HttpClient) {
    @Volatile var token: String? = null

    private fun url(path: String) = baseUrl.trimEnd('/') + path

    private suspend fun call(
        method: HttpMethod,
        path: String,
        body: JsonElement? = null,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): JsonElement {
        val response = try {
            http.request(url(path)) {
                this.method = method
                token?.let { header("Authorization", it) }
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(VolumJson.encodeToString(JsonElement.serializer(), body))
                }
                configure()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw PbException(0, e.message ?: "Network error")
        }
        val text = response.bodyAsText()
        val json = runCatching { VolumJson.parseToJsonElement(text) }.getOrNull()
        if (response.status.value !in 200..299) {
            val obj = json as? JsonObject
            val message = obj?.get("message")?.jsonPrimitive?.contentOrNull ?: "Request failed (${response.status.value})"
            throw PbException(response.status.value, message, obj?.get("data") as? JsonObject)
        }
        return json ?: JsonNull
    }

    // ---- Auth -------------------------------------------------------------------------------

    suspend fun authWithPassword(email: String, password: String): Pair<String, JsonObject> {
        val result = call(
            HttpMethod.Post,
            "/api/collections/users/auth-with-password",
            buildJsonObject { put("identity", email); put("password", password) },
        ).jsonObject
        return result.getValue("token").jsonPrimitive.content to result.getValue("record").jsonObject
    }

    suspend fun authRefresh(): Pair<String, JsonObject> {
        val result = call(HttpMethod.Post, "/api/collections/users/auth-refresh").jsonObject
        return result.getValue("token").jsonPrimitive.content to result.getValue("record").jsonObject
    }

    suspend fun createUser(email: String, password: String, name: String): JsonObject = call(
        HttpMethod.Post,
        "/api/collections/users/records",
        buildJsonObject {
            put("email", email)
            put("password", password)
            put("passwordConfirm", password)
            put("name", name)
        },
    ).jsonObject

    // ---- Records ----------------------------------------------------------------------------

    suspend fun getList(collection: String, page: Int, perPage: Int, filter: String? = null, sort: String? = null): PbList {
        val result = call(HttpMethod.Get, "/api/collections/$collection/records") {
            parameter("page", page)
            parameter("perPage", perPage)
            parameter("skipTotal", 0)
            filter?.let { parameter("filter", it) }
            sort?.let { parameter("sort", it) }
        }.jsonObject
        return PbList(
            page = result["page"]?.jsonPrimitive?.int ?: page,
            totalPages = result["totalPages"]?.jsonPrimitive?.int ?: 1,
            items = result["items"]?.jsonArray?.map { it.jsonObject } ?: emptyList(),
        )
    }

    suspend fun getOne(collection: String, id: String): JsonObject =
        call(HttpMethod.Get, "/api/collections/$collection/records/${id.encodeURLPathPart()}").jsonObject

    suspend fun create(collection: String, body: JsonObject): JsonObject =
        call(HttpMethod.Post, "/api/collections/$collection/records", body).jsonObject

    suspend fun update(collection: String, id: String, body: JsonObject): JsonObject =
        call(HttpMethod.Patch, "/api/collections/$collection/records/${id.encodeURLPathPart()}", body).jsonObject

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
            while (isActive) {
                try {
                    http.prepareGet(url("/api/realtime")) {
                        header("Accept", "text/event-stream")
                    }.execute { response ->
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
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Fall through to a short pause and reconnect; callers also poll.
                }
                delay(5_000)
            }
        }

    companion object {
        /** pb.filter: binds {:name} placeholders as quoted strings. */
        fun filter(expr: String, params: Map<String, String>): String {
            var out = expr
            for ((key, value) in params) {
                out = out.replace("{:$key}", "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'")
            }
            return out
        }
    }
}
