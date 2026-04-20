package com.michaeltchuang.walletsdk.core.railmpp

/**
 * Algorand network CAIP-2 identifiers.
 */
object MppNetworks {
    const val MAINNET = "algorand:wGHE2Pwdvd7S12BL5FaOP20EGYesN73ktiC1qzkkit8="
    const val TESTNET = "algorand:SGO1GKSzyE7IEPItTxCByw9x8FmnrCDexi9/cOUJOiI="
}

/**
 * Sentinel asset value meaning "native ALGO" (no ASA).
 * Aligns with the Algorand convention where asset-id 0 = ALGO.
 */
const val ALGO_ASSET = "0"

/**
 * Default algod URLs keyed by CAIP-2 network id.
 */
internal val DEFAULT_ALGOD_URLS: Map<String, String> = mapOf(
    MppNetworks.MAINNET to "https://mainnet-api.4160.nodely.dev",
    MppNetworks.TESTNET to "https://testnet-api.4160.nodely.dev",
)

internal val NETWORK_GENESIS_HASH: Map<String, String> = mapOf(
    MppNetworks.MAINNET to "wGHE2Pwdvd7S12BL5FaOP20EGYesN73ktiC1qzkkit8=",
    MppNetworks.TESTNET to "SGO1GKSzyE7IEPItTxCByw9x8FmnrCDexi9/cOUJOiI=",
)

internal val NETWORK_GENESIS_ID: Map<String, String> = mapOf(
    MppNetworks.MAINNET to "mainnet-v1.0",
    MppNetworks.TESTNET to "testnet-v1.0",
)
