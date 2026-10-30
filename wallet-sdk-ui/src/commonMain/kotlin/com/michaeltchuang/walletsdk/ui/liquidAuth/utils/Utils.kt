package com.michaeltchuang.walletsdk.ui.liquidAuth.utils

import com.michaeltchuang.walletsdk.core.account.domain.model.core.AccountRegistrationType
import com.michaeltchuang.walletsdk.core.account.domain.model.custom.AccountLite
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.foundation.utils.AppId
import com.michaeltchuang.walletsdk.core.foundation.utils.Chain
import com.michaeltchuang.walletsdk.core.network.model.AlgorandNetwork
import com.michaeltchuang.walletsdk.core.railmpp.MppNetworks
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.AuthMessage
import com.michaeltchuang.walletsdk.ui.settings.utils.isSeedVaultTransactionSigningSupported
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

    // Parse chainId if present
    val chainId =
        uri.findParameterValue("chainId")
            ?: uri.findParameterValue("chain_id")

    Napier.d("   ChainId found: '$chainId'")

    return AuthMessage(origin, requestId, appId, chainId)
}

fun getSupportedAccountsByAppId(
    appId: String,
    accountLite: List<AccountLite>,
    seedVaultSigningSupported: Boolean = isSeedVaultTransactionSigningSupported,
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
                ) ||
                (seedVaultSigningSupported && it.registrationType is AccountRegistrationType.SeedVault && it.isSolana)
        } ?: accountLite

fun getSupportedLocalAccountsByAppId(
    appId: String,
    localAccount: List<LocalAccount>,
    seedVaultSigningSupported: Boolean = isSeedVaultTransactionSigningSupported,
): List<LocalAccount> =
    localAccount
        .takeIf {
            appId == AppId.LIQUID_AUTH_STREAM.name
        }?.filter {
            it is LocalAccount.HdKey ||
                it is LocalAccount.Algo25 ||
                it is LocalAccount.Falcon24 ||
                it is LocalAccount.Falcon25 ||
                (seedVaultSigningSupported && it is LocalAccount.SeedVault && it.chainId == Chain.SOLANA.id)
        } ?: localAccount

fun resolveLiquidStreamAccountNetwork(
    account: LocalAccount?,
    network: AlgorandNetwork,
    paymentNetworkOverride: String? = null,
    seedVaultSigningSupported: Boolean = isSeedVaultTransactionSigningSupported,
): String {
    requireNotNull(account) { "The streaming account is not available locally." }
    require(
        getSupportedLocalAccountsByAppId(
            AppId.LIQUID_AUTH_STREAM.name,
            listOf(account),
            seedVaultSigningSupported,
        ).isNotEmpty(),
    ) { "This account cannot sign LiquidStream transactions on this platform." }
    val resolved =
        if (account is LocalAccount.SeedVault) {
            // Seed Vault accounts follow the app-wide toggle: MainNet -> Solana mainnet, else devnet.
            if (network == AlgorandNetwork.MAINNET) MppNetworks.SOLANA_MAINNET else MppNetworks.SOLANA_DEVNET
        } else {
            when (network) {
                AlgorandNetwork.MAINNET -> MppNetworks.ALGORAND_MAINNET
                AlgorandNetwork.TESTNET -> MppNetworks.ALGORAND_TESTNET
                AlgorandNetwork.FUTURENET -> MppNetworks.ALGORAND_FUTURENET
            }
        }
    if (paymentNetworkOverride != null) {
        require(normalizeLiquidStreamNetwork(paymentNetworkOverride) == resolved) {
            "The stream network does not match the selected account and network."
        }
    }
    return resolved
}

fun normalizeLiquidStreamNetwork(network: String): String =
    when (network.trim().lowercase()) {
        MppNetworks.ALGORAND_MAINNET.lowercase(), "mainnet", "algorand:mainnet" -> MppNetworks.ALGORAND_MAINNET
        MppNetworks.ALGORAND_TESTNET.lowercase(), "testnet", "algorand:testnet" -> MppNetworks.ALGORAND_TESTNET
        MppNetworks.ALGORAND_FUTURENET.lowercase(), "futurenet", "algorand:futurenet" -> MppNetworks.ALGORAND_FUTURENET
        "solana:devnet" -> MppNetworks.SOLANA_DEVNET
        "solana:mainnet", "solana:mainnet-beta" -> MppNetworks.SOLANA_MAINNET
        "solana:testnet" -> MppNetworks.SOLANA_TESTNET
        else -> throw IllegalArgumentException("Unsupported stream network: $network")
    }

fun parseLiquidStreamDepositAmount(enteredAmount: String): Long {
    val value = enteredAmount.trim()
    require(Regex("[0-9]+(?:\\.[0-9]{1,6})?").matches(value)) {
        "Enter a positive amount with at most six decimal places."
    }
    val parts = value.split('.', limit = 2)
    val units = (parts[0] + parts.getOrElse(1) { "" }.padEnd(6, '0')).toLongOrNull()
    require(units != null && units > 0L) { "The deposit amount is zero or too large." }
    return units
}

fun validateLiquidStreamMetadataNetwork(
    account: LocalAccount?,
    network: AlgorandNetwork,
    advertisedNetwork: String?,
    seedVaultSigningSupported: Boolean = isSeedVaultTransactionSigningSupported,
): String {
    require(account !is LocalAccount.SeedVault || !advertisedNetwork.isNullOrBlank()) {
        "Solana streams must advertise their payment network."
    }
    return resolveLiquidStreamAccountNetwork(account, network, advertisedNetwork, seedVaultSigningSupported)
}
