package com.michaeltchuang.walletsdk.ui.liquidAuth.utils

import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.AuthMessage

private fun String.findParameterValue(parameterName: String): String? {
    // Extract query string from URI (everything after '?')
    val queryStart = this.indexOf('?')
    println("   🔍 findParameterValue('$parameterName'):")
    println("      queryStart index: $queryStart")
    
    val query = if (queryStart != -1) this.substring(queryStart + 1) else null
    println("      query string: '$query'")
    
    val pairs = query
        ?.split('&')
        ?.map {
            val parts = it.split('=')
            val name = parts.firstOrNull() ?: ""
            val value = parts.drop(1).joinToString("=")  // Join back in case value had '='
            println("      found param: '$name' = '$value'")
            Pair(name, value)
        }
    
    val result = pairs?.firstOrNull { it.first == parameterName }?.second
    println("      result for '$parameterName': '$result'")
    return result
}

fun fromUri(uri: String): AuthMessage {
    println("🔍 Parsing Liquid Auth URI:")
    println("   Full URI: $uri")

    // Extract host from URI
    val host = uri.removePrefix("liquid://").substringBefore('?').substringBefore('/')
    val origin = "https://$host"
    println("   Host: $host")
    println("   Origin: $origin")
    
    // Try multiple parameter names for requestId
    val requestId = uri.findParameterValue("requestId") 
        ?: uri.findParameterValue("request_id")
        ?: uri.findParameterValue("rid")
        ?: ""  // Default to empty string if not found
    
    println("   RequestId found: '$requestId'")
    
    if (requestId.isEmpty()) {
        println("   ⚠️ WARNING: RequestId is empty! Check URL format.")
        println("   Expected format: liquid://host/?requestId=...")
    }

    return AuthMessage(origin, requestId)
}
