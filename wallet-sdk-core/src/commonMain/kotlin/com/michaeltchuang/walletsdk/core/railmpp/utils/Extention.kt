package com.michaeltchuang.walletsdk.core.railmpp.utils

import kotlinx.serialization.json.Json

inline fun <reified T> T.toJson(
    json: Json = Json {
        encodeDefaults = true
        explicitNulls = false
    }
): String {
    return json.encodeToString(this)
}

inline fun <reified T> String.fromJson(
    json: Json = Json {
        ignoreUnknownKeys = true
    }
): T {
    return json.decodeFromString(this)
}