package com.michaeltchuang.walletsdk.core.railmpp

/**
 * Algorand network CAIP-2 identifiers.
 */
object MppNetworks {
    const val ALGORAND_MAINNET = "algorand:wGHE2Pwdvd7S12BL5FaOP20EGYesN73ktiC1qzkkit8="
    const val ALGORAND_TESTNET = "algorand:SGO1GKSzyE7IEPItTxCByw9x8FmnrCDexi9/cOUJOiI="

    const val ALGORAND_FUTURENET = "algorand:kUt08LxeVAAGHnh4JoAoAMM9ql/hBwSoiFtlnKNeOxA="

    const val SOLANA_MAINNET = "solana:mainnet-beta"
    const val SOLANA_DEVNET = "solana:devnet"
    const val SOLANA_TESTNET = "solana:testnet"
}

/**
 * Sentinel asset value meaning "native ALGO" (no ASA).
 * Aligns with the Algorand convention where asset-id 0 = ALGO.
 */
const val ALGO_ASSET = "0"

/**
 * Default algod URLs keyed by CAIP-2 network id.
 */
internal val DEFAULT_ALGOD_URLS: Map<String, String> =
    mapOf(
        MppNetworks.ALGORAND_MAINNET to "https://mainnet-api.4160.nodely.dev",
        MppNetworks.ALGORAND_TESTNET to "https://testnet-api.4160.nodely.dev",
        MppNetworks.ALGORAND_FUTURENET to "https://fnet-api.4160.nodely.dev",
    )

internal val NETWORK_GENESIS_HASH: Map<String, String> =
    mapOf(
        MppNetworks.ALGORAND_MAINNET to "wGHE2Pwdvd7S12BL5FaOP20EGYesN73ktiC1qzkkit8=",
        MppNetworks.ALGORAND_TESTNET to "SGO1GKSzyE7IEPItTxCByw9x8FmnrCDexi9/cOUJOiI=",
        MppNetworks.ALGORAND_FUTURENET to "kUt08LxeVAAGHnh4JoAoAMM9ql/hBwSoiFtlnKNeOxA=",
    )

internal val NETWORK_GENESIS_ID: Map<String, String> =
    mapOf(
        MppNetworks.ALGORAND_MAINNET to "mainnet-v1.0",
        MppNetworks.ALGORAND_TESTNET to "testnet-v1.0",
        MppNetworks.ALGORAND_FUTURENET to "fnet-v1",
    )
