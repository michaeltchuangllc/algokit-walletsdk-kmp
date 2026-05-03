package com.michaeltchuang.walletsdk.ui.liquidAuth.utils

import com.michaeltchuang.walletsdk.core.account.domain.model.core.AccountRegistrationType
import com.michaeltchuang.walletsdk.core.account.domain.model.custom.AccountLite
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.utils.AppId
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.AuthMessage

/**
 * Simple URL decoder for common percent-encoded characters.
 * Decodes hex sequences like %20, %3D, %26, %3F, %23, %25 etc.
 */
private fun String.urlDecode(): String {
    val hexChars = "0123456789ABCDEF"
    val sb = StringBuilder()
    var i = 0
    while (i < this.length) {
        val c = this[i]
        if (c == '%' && i + 2 < this.length) {
            val high = hexChars.indexOf(this[i + 1].uppercaseChar())
            val low = hexChars.indexOf(this[i + 2].uppercaseChar())
            if (high != -1 && low != -1) {
                sb.append((high * 16 + low).toChar())
                i += 3
                continue
            }
        }
        sb.append(c)
        i++
    }
    return sb.toString()
}

private fun String.findParameterValue(parameterName: String): String? {
    // Extract query string from URI (everything after '?')
    val queryStart = this.indexOf('?')
    println("   🔍 findParameterValue('$parameterName'):")
    println("      queryStart index: $queryStart")

    val query = if (queryStart != -1) this.substring(queryStart + 1) else null
    println("      query string: '$query'")

    val pairs =
        query
            ?.split('&')
            ?.map {
                val parts = it.split('=')
                val name = parts.firstOrNull() ?: ""
                val rawValue = parts.drop(1).joinToString("=") // Join back in case value had '='
                val value = rawValue.urlDecode() // URL-decode the parameter value
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
    val requestId =
        uri.findParameterValue("requestId")
            ?: uri.findParameterValue("request_id")
            ?: uri.findParameterValue("rid")
            ?: "" // Default to empty string if not found

    println("   RequestId found: '$requestId'")

    if (requestId.isEmpty()) {
        println("   ⚠️ WARNING: RequestId is empty! Check URL format.")
        println("   Expected format: liquid://host/?requestId=...")
    }

    // Parse appId if present
    val appId =
        uri.findParameterValue("appId")
            ?: AppId.NONE.name

    println("   AppId found: '$appId'")

    return AuthMessage(origin, requestId, appId)
}

fun getSupportedAccountsByAppId(
    appId: String,
    accountLite: List<AccountLite>,
): List<AccountLite> =
    accountLite
        .takeIf {
            appId == AppId.LIQUID_AUTH_STREAM.name
        }?.filter {
            it.registrationType in
                setOf(
                    AccountRegistrationType.Algo25,
                    AccountRegistrationType.HdKey,
                )
        } ?: accountLite

fun getSupportedLocalAccountsByAppId(
    appId: String,
    localAccount: List<LocalAccount>,
): List<LocalAccount> =
    localAccount
        .takeIf {
            appId == AppId.LIQUID_AUTH_STREAM.name
        }?.filter {
            it is LocalAccount.HdKey || it is LocalAccount.Algo25
        } ?: localAccount
