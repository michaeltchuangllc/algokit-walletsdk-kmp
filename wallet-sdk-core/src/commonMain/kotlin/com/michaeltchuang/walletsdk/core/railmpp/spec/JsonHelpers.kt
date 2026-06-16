package com.michaeltchuang.walletsdk.core.railmpp.spec

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

internal fun JsonObject.reqString(key: String): String = this[key]?.jsonPrimitive?.content ?: error("Missing '$key'")

internal fun JsonObject.optString(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull?.ifBlank { null }

internal fun JsonObject.reqLong(key: String): Long = this[key]?.jsonPrimitive?.long ?: error("Missing '$key'")

internal fun JsonObject.optBoolean(
    key: String,
    default: Boolean,
): Boolean = this[key]?.jsonPrimitive?.booleanOrNull ?: default

internal fun JsonObject.optObject(key: String): JsonObject? = (this[key] as? JsonObject)
