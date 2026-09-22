package com.michaeltchuang.walletsdk.ui.liquidAuth.utils

import com.michaeltchuang.walletsdk.core.account.domain.model.core.AccountRegistrationType
import com.michaeltchuang.walletsdk.core.account.domain.model.custom.AccountLite
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.foundation.utils.AppId
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.AuthMessage
import io.github.aakira.napier.Napier

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
    Napier.d("   🔍 findParameterValue('$parameterName'):")
    Napier.d("      queryStart index: $queryStart")

    val query = if (queryStart != -1) this.substring(queryStart + 1) else null
    Napier.d("      query string: '$query'")

    val pairs =
        query
            ?.split('&')
            ?.map {
                val parts = it.split('=')
                val name = parts.firstOrNull() ?: ""
                val rawValue = parts.drop(1).joinToString("=") // Join back in case value had '='
                val value = rawValue.urlDecode() // URL-decode the parameter value
                Napier.d("      found param: '$name' = '$value'")
                Pair(name, value)
            }

    val result = pairs?.firstOrNull { it.first == parameterName }?.second
    Napier.d("      result for '$parameterName': '$result'")
    return result
}

fun fromUri(uri: String): AuthMessage {
    Napier.d("🔍 Parsing Liquid Auth URI:")
    Napier.d("   Full URI: $uri")

    // Extract host from URI
    val host = uri.removePrefix("liquid://").substringBefore('?').substringBefore('/')
    val origin = "https://$host"
    Napier.d("   Host: $host")
    Napier.d("   Origin: $origin")

    // Try multiple parameter names for requestId
    val requestId =
        uri.findParameterValue("requestId")
            ?: uri.findParameterValue("request_id")
            ?: uri.findParameterValue("rid")
            ?: "" // Default to empty string if not found

    Napier.d("   RequestId found: '$requestId'")

    if (requestId.isEmpty()) {
        Napier.w("   ⚠️ WARNING: RequestId is empty! Check URL format.")
        Napier.d("   Expected format: liquid://host/?requestId=...")
    }

    // Parse appId if present
    val appId =
        uri.findParameterValue("appId")
            ?: AppId.NONE.name

    Napier.d("   AppId found: '$appId'")

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
                    AccountRegistrationType.Falcon24,
                    AccountRegistrationType.Falcon25,
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
            it is LocalAccount.HdKey ||
                it is LocalAccount.Algo25 ||
                it is LocalAccount.Falcon24 ||
                it is LocalAccount.Falcon25
        } ?: localAccount
