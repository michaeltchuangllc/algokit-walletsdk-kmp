package com.michaeltchuang.walletsdk.core.railmpp.spec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * JSON Canonicalization Scheme (RFC 8785) for the charge wire format.
 *
 * Object keys are sorted recursively and emitted as compact JSON, matching the
 * canonical bytes used for the challenge HMAC input and `request`/credential
 * serialization so Android and iOS stay byte-compatible with TS consumers.
 */
internal object JcsJson {
    private fun sortKeys(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject ->
                JsonObject(
                    element.entries
                        .sortedBy { it.key }
                        .associate { it.key to sortKeys(it.value) },
                )
            is JsonArray -> JsonArray(element.map { sortKeys(it) })
            else -> element
        }

    fun canonicalize(element: JsonElement): String = Json.encodeToString(JsonElement.serializer(), sortKeys(element))
}
