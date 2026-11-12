package dev.southstar21.volum.data

import dev.southstar21.volum.model.VolumJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** PocketBase writes empty optional text/date fields as "" and empty JSON as null; drop those. */
fun cleanRecord(record: JsonObject): JsonObject = JsonObject(
    record.filterValues { value ->
        value !is JsonNull && !(value is JsonPrimitive && value.isString && value.content.isEmpty())
    },
)

/**
 * As [cleanRecord], but an empty required text field stays "": dropping it would make the record
 * fail to decode, and a pulled record that never decodes never reaches the device.
 */
private fun cleanRecord(record: JsonObject, descriptor: SerialDescriptor): JsonObject {
    val required = (0 until descriptor.elementsCount).filterNot { descriptor.isElementOptional(it) }.map { descriptor.getElementName(it) }.toSet()
    return JsonObject(
        record.filter { (key, value) ->
            value !is JsonNull && !(key !in required && value is JsonPrimitive && value.isString && value.content.isEmpty())
        },
    )
}

fun <T> decodeRecord(serializer: KSerializer<T>, record: JsonObject): T? =
    runCatching { VolumJson.decodeFromJsonElement(serializer, cleanRecord(record, serializer.descriptor)) }.getOrNull()
