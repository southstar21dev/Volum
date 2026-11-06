package dev.southstar21.volum.data

import dev.southstar21.volum.model.VolumJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** PocketBase writes empty optional text/date fields as "" and empty JSON as null; drop those. */
fun cleanRecord(record: JsonObject): JsonObject = JsonObject(
    record.filterValues { value ->
        value !is JsonNull && !(value is JsonPrimitive && value.isString && value.content.isEmpty())
    },
)

fun <T> decodeRecord(serializer: KSerializer<T>, record: JsonObject): T? =
    runCatching { VolumJson.decodeFromJsonElement(serializer, cleanRecord(record)) }.getOrNull()
