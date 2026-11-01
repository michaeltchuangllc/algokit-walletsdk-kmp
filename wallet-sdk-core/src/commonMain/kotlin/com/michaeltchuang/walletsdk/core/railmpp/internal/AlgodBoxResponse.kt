@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package com.michaeltchuang.walletsdk.core.railmpp.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64

internal fun decodeAlgodBoxResponse(
    status: Int,
    body: String,
): ByteArray? {
    if (status == 404) return null
    check(status in 200..299) { "Box lookup failed: HTTP $status" }
    val json = Json.parseToJsonElement(body).jsonObject
    val value = json.getValue("value").jsonPrimitive
    require(value.isString) { "Box value must be a base64 string" }
    return Base64.decode(value.content)
}
