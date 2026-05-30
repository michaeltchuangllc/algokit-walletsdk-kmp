package com.michaeltchuang.walletsdk.core.railmpp.internal

import com.michaeltchuang.walletsdk.core.railmpp.MppWalletSigner

internal actual fun getSessionBoxBytesInternal(
    appId: Long,
    channelId: ByteArray,
    algodUrl: String,
): ByteArray = TODO("iOS: getSessionBoxBytesInternal not yet implemented")

internal actual suspend fun submitAppCallInternal(
    signer: MppWalletSigner,
    appId: Long,
    usdcAssetId: Long,
    defaultSalt: ByteArray,
    algodUrl: String,
    args: List<ByteArray>,
    boxKeys: List<Pair<Long, ByteArray>>,
    foreignAssets: List<Long>,
): String = TODO("iOS: submitAppCallInternal not yet implemented")

internal actual suspend fun submitAssetTransferAndAppCallInternal(
    signer: MppWalletSigner,
    appId: Long,
    usdcAssetId: Long,
    algodUrl: String,
    appCallArgs: List<ByteArray>,
    boxKeys: List<Pair<Long, ByteArray>>,
    appCallForeignAssets: List<Long>,
    depositAmountMicroUsdc: Long,
): String = TODO("iOS: submitAssetTransferAndAppCallInternal not yet implemented")

internal actual fun decodeMsgPackAny(bytes: ByteArray): Any? = null

internal actual fun awaitConfirmationDetailsInternal(txId: String, algodUrl: String, maxRounds: Int): Pair<Long, Int> =
    TODO("iOS: awaitConfirmationDetailsInternal not yet implemented")

internal actual fun awaitConfirmationInternal(txId: String, algodUrl: String, maxRounds: Int): Boolean =
    TODO("iOS: awaitConfirmationInternal not yet implemented")
