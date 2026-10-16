package com.michaeltchuang.walletsdk.core.railmpp.core

import com.michaeltchuang.walletsdk.core.railmpp.internal.decodeAlgorandAddressPublicKey
import com.michaeltchuang.walletsdk.core.railmpp.internal.encodeAlgorandAddress
import com.michaeltchuang.walletsdk.core.railmpp.solanasmartcontract.SolanaStreamVault
import com.michaeltchuang.walletsdk.core.solana.utils.Base58

internal fun requireStreamAddress(
    network: String,
    address: String,
) {
    if (SolanaStreamVault.isSolana(network)) {
        SolanaStreamVault.cluster(network)
        val key = Base58.decode(address)
        require(key.size == 32 && Base58.encode(key) == address) { "Expected a Solana address" }
    } else {
        require(network.startsWith("algorand:")) { "Unsupported stream network" }
        require(address.length == 58 && encodeAlgorandAddress(decodeAlgorandAddressPublicKey(address)) == address) {
            "Expected an Algorand address"
        }
    }
}

internal fun requireStreamNetwork(
    expected: String?,
    actual: String?,
    solana: Boolean,
) {
    require(!solana || (expected != null && actual != null)) { "Solana requires a pinned stream network" }
    require(expected == null || actual == null || expected == actual) { "Stream network mismatch" }
}
