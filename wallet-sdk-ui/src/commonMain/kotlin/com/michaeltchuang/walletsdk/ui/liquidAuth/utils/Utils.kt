package com.michaeltchuang.walletsdk.ui.liquidAuth.utils

import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.AuthMessage

private fun String.findParameterValue(parameterName: String): String? {
    // Extract query string from URI (everything after '?')
    val queryStart = this.indexOf('?')
    val query = if (queryStart != -1) this.substring(queryStart + 1) else null

    return query
        ?.split('&')
        ?.map {
            val parts = it.split('=')
            val name = parts.firstOrNull() ?: ""
            val value = parts.drop(1).firstOrNull() ?: ""
            Pair(name, value)
        }?.firstOrNull { it.first == parameterName }
        ?.second
}

fun fromUri(uri: String): AuthMessage {
    println("fromUri($uri)")

    // Extract host from URI
    val host = uri.removePrefix("liquid://").substringBefore('?').substringBefore('/')
    val origin = "https://$host"
    val requestId = uri.findParameterValue("requestId").toString()

    return AuthMessage(origin, requestId)
}
