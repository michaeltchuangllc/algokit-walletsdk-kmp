package com.michaeltchuang.walletsdk.core.railmpp.smartcontract

import com.michaeltchuang.walletsdk.core.railmpp.internal.compileSettlementLogicSigAddressInternal
import com.michaeltchuang.walletsdk.core.railmpp.internal.decodeAlgorandAddressPublicKey
import com.michaeltchuang.walletsdk.core.railmpp.internal.encodeAlgorandAddress
import com.michaeltchuang.walletsdk.core.railmpp.internal.findSessionBoxBytesInternal
import com.michaeltchuang.walletsdk.core.railmpp.internal.sha512_256
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultHybridManagerClient.SessionAuthorizationStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal object SessionAuthorizationStatusReader {
    internal suspend fun read(
        appId: Long,
        algodUrl: String,
        channelId: ByteArray,
        authorizedSignerPublicKey: ByteArray,
        payeeAddress: String,
        readBox: suspend (Long, ByteArray, String) -> ByteArray? = ::findSessionBoxBytesInternal,
        compile: suspend (Long, String, ByteArray, ByteArray, String) -> String = ::compileSettlementLogicSigAddressInternal,
    ): Result<SessionAuthorizationStatus> {
        val channel = channelId.copyOf()
        val signerKey = authorizedSignerPublicKey.copyOf()
        return withContext(Dispatchers.Default) {
            try {
                require(channel.size == 32) { "channelId must be 32 bytes" }
                require(signerKey.isNotEmpty()) { "authorizedSignerPublicKey must not be empty" }
                val payee = decodeAlgorandAddressPublicKey(payeeAddress)
                require(encodeAlgorandAddress(payee) == payeeAddress) { "Invalid Algorand address" }
                val box = readBox(appId, channel, algodUrl)
                currentCoroutineContext().ensureActive()
                if (box == null) return@withContext Result.success(SessionAuthorizationStatus(channelExists = false))
                HostViewerVaultReader.validateSessionBoxLayout(box)
                require(box.copyOfRange(32, 64).contentEquals(payee)) { "Session payee mismatch" }
                val total = HostViewerVaultReader.decodeAmount(box, 66)
                val settled = HostViewerVaultReader.decodeAmount(box, 74)
                require(settled <= total) { "Session settlement exceeds total deposit" }
                val closeRequestedAt = HostViewerVaultReader.decodeAmount(box, 106)
                val logicSig = decodeOptionalAddress(readBox(appId, "l".encodeToByteArray() + channel, algodUrl))
                // Channel-level override wins over the payee's account-level default (matches the contract).
                val payeeSigner =
                    decodeOptionalAddress(readBox(appId, "e".encodeToByteArray() + channel, algodUrl))
                        ?: decodeOptionalAddress(readBox(appId, "d".encodeToByteArray() + payee, algodUrl))
                val expectedLogicSig =
                    if (logicSig == null) null else compile(appId, algodUrl, channel, signerKey, payeeAddress)
                currentCoroutineContext().ensureActive()
                Result.success(
                    SessionAuthorizationStatus(
                        channelExists = true,
                        // The full key is no longer stored on-chain; only its hash is, in the channel box.
                        viewerSignerAuthorized = box.copyOfRange(116, 148).contentEquals(sha512_256(signerKey)),
                        settlementRegistered = logicSig != null && logicSig == expectedLogicSig,
                        hasSettlementLogicSig = logicSig != null,
                        authorizedPayeeSigner = payeeSigner,
                        remainingBalance = total - settled,
                        closeRequestedAt = closeRequestedAt,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun decodeOptionalAddress(bytes: ByteArray?): String? {
        if (bytes == null) return null
        require(bytes.size == 32) { "Unexpected address box size=${bytes.size}" }
        return if (bytes.all { it == 0.toByte() }) null else encodeAlgorandAddress(bytes)
    }
}
