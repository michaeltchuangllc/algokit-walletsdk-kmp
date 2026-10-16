package com.michaeltchuang.walletsdk.core.railmpp.utils

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

inline fun <reified T> T.toJson(
    json: Json =
        Json {
            encodeDefaults = true
            explicitNulls = false
        },
): String = json.encodeToString(this)

inline fun <reified T> String.fromJson(
    json: Json =
        Json {
            ignoreUnknownKeys = true
        },
): T = json.decodeFromString(this)
