package com.michaeltchuang.walletsdk.core.railmpp.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun JsonObject.readMppNetwork(): String? {
    fun read(key: String): String? {
        val value = this[key] ?: return null
        require(value is JsonPrimitive && value.isString) { "$key must be a JSON string" }
        return value.content
    }

    val current = read("mppNetwork")
    val legacy = read("network")
    require(current == null || legacy == null || current == legacy) { "Conflicting mppNetwork and network" }
    return current ?: legacy
}

internal fun JsonObject.normalizeMppNetwork(): JsonObject {
    val network = readMppNetwork() ?: return this
    return buildJsonObject {
        this@normalizeMppNetwork.forEach { (key, value) ->
            if (key != "network" && key != "mppNetwork") put(key, value)
        }
        put("mppNetwork", network)
    }
}
