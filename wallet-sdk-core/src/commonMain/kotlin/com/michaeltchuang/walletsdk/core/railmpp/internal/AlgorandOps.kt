package com.michaeltchuang.walletsdk.core.railmpp.internal

import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner

/** Fetches raw box bytes for [channelId] from the Algod REST API. */
internal expect fun getSessionBoxBytesInternal(
    appId: Long,
    channelId: ByteArray,
    algodUrl: String,
): ByteArray

/**
 * Builds, signs (via [signer]), and broadcasts an app-call transaction.
 * [boxKeys] is a list of (appId, boxKey) pairs for AVM box references.
 */
internal expect suspend fun submitAppCallInternal(
    signer: MppWalletSigner,
    appId: Long,
    usdcAssetId: Long,
    algodUrl: String,
    args: List<ByteArray>,
    boxKeys: List<Pair<Long, ByteArray>>,
    foreignAssets: List<Long>,
    foreignAccounts: List<String> = emptyList(),
): String

/**
 * Builds, signs (via [signer]), and broadcasts an asset-transfer + app-call group.
 * [boxKeys] is a list of (appId, boxKey) pairs for AVM box references.
 */
internal expect suspend fun submitAssetTransferAndAppCallInternal(
    signer: MppWalletSigner,
    appId: Long,
    usdcAssetId: Long,
    algodUrl: String,
    appCallArgs: List<ByteArray>,
    boxKeys: List<Pair<Long, ByteArray>>,
    appCallForeignAssets: List<Long>,
    depositAmountMicroUsdc: Long,
): String

/** Decodes msgpack bytes to a generic map/object; returns null on failure. */
internal expect fun decodeMsgPackAny(bytes: ByteArray): Any?

/**
 * Polls Algod until [txId] is confirmed or [maxRounds] exhausted.
 * Returns (confirmedRound, logCount).
 */
internal expect fun awaitConfirmationDetailsInternal(
    txId: String,
    algodUrl: String,
    maxRounds: Int = 8,
): Pair<Long, Int>

/** Polls Algod until [txId] is confirmed or [maxRounds] exhausted; returns true if confirmed. */
internal expect fun awaitConfirmationInternal(
    txId: String,
    algodUrl: String,
    maxRounds: Int = 10,
): Boolean
