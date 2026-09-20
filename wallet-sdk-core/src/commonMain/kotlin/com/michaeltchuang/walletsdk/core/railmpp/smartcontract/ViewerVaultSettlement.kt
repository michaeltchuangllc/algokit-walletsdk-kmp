package com.michaeltchuang.walletsdk.core.railmpp.smartcontract

import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.internal.getSessionBoxBytesInternal
import com.michaeltchuang.walletsdk.core.railmpp.internal.simulateReadonlyMethodInternal
import com.michaeltchuang.walletsdk.core.railmpp.internal.submitLogicSigSettlementInternal
import com.michaeltchuang.walletsdk.core.railmpp.internal.validateLogicSigSettlementInternal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class ViewerVaultSettlement internal constructor(
    private val funderSigner: MppWalletSigner,
    private val readBox: (Long, ByteArray, String) -> ByteArray,
    private val simulate: (Long, String, ByteArray, List<ByteArray>, List<Pair<Long, ByteArray>>) -> ByteArray?,
    private val validateSignature: suspend (
        MppWalletSigner, Long, Long, String, ByteArray, Long, ByteArray, ByteArray, String,
    ) -> Unit,
    private val submit: suspend (
        MppWalletSigner,
        Long,
        Long,
        String,
        ByteArray,
        Long,
        ByteArray,
        ByteArray,
        String,
        ByteArray?,
    ) -> String,
) {
    companion object {
        suspend fun validateVoucher(
            viewerAddress: String,
            creatorAddress: String,
            authorizedSignerPublicKey: ByteArray,
            channelId: ByteArray,
            signature: ByteArray,
            cumulativeAmount: Long,
            network: String,
        ): Result<HostViewerVaultReader.Snapshot> =
            validateVoucher(
                viewerAddress, creatorAddress, authorizedSignerPublicKey, channelId,
                signature, cumulativeAmount, network,
                ::getSessionBoxBytesInternal,
                ::simulateReadonlyMethodInternal,
                ::validateLogicSigSettlementInternal,
            )

        internal suspend fun validateVoucher(
            viewerAddress: String,
            creatorAddress: String,
            authorizedSignerPublicKey: ByteArray,
            channelId: ByteArray,
            signature: ByteArray,
            cumulativeAmount: Long,
            network: String,
            readBox: (Long, ByteArray, String) -> ByteArray,
            simulate: (Long, String, ByteArray, List<ByteArray>, List<Pair<Long, ByteArray>>) -> ByteArray?,
            validateSignature: suspend (
                MppWalletSigner, Long, Long, String, ByteArray, Long, ByteArray, ByteArray, String,
            ) -> Unit,
        ): Result<HostViewerVaultReader.Snapshot> {
            val validationOnlySigner =
                object : MppWalletSigner {
                    override val address = creatorAddress
                    override val authorizedSignerPublicKey: ByteArray
                        get() = error("Voucher validation must not access wallet keys")

                    override suspend fun signTransactionBytes(txnMsgpack: ByteArray): ByteArray =
                        error("Voucher validation must not sign wallet transactions")
                }
            return ViewerVaultSettlement(
                validationOnlySigner,
                readBox,
                simulate,
                validateSignature,
                submit = { _, _, _, _, _, _, _, _, _, _ -> error("Voucher validation must not submit") },
            ).validateVoucher(
                viewerAddress, creatorAddress, authorizedSignerPublicKey, channelId, signature, cumulativeAmount, network,
            )
        }
    }

    constructor(funderSigner: MppWalletSigner) : this(
        funderSigner,
        ::getSessionBoxBytesInternal,
        ::simulateReadonlyMethodInternal,
        ::validateLogicSigSettlementInternal,
        ::submitLogicSigSettlementInternal,
    )

    suspend fun readSnapshot(
        viewerAddress: String,
        creatorAddress: String,
        authorizedSignerPublicKey: ByteArray,
        channelId: ByteArray,
        network: String,
    ): Result<HostViewerVaultReader.Snapshot> =
        HostViewerVaultReader.readChannel(
            channelId = channelId,
            viewerAddress = viewerAddress,
            creatorAddress = creatorAddress,
            authorizedSignerPublicKey = authorizedSignerPublicKey,
            network = network,
            readBox = readBox,
            simulate = simulate,
        )

    suspend fun validateVoucher(
        viewerAddress: String,
        creatorAddress: String,
        authorizedSignerPublicKey: ByteArray,
        channelId: ByteArray,
        signature: ByteArray,
        cumulativeAmount: Long,
        network: String,
    ): Result<HostViewerVaultReader.Snapshot> {
        val channel = channelId.copyOf()
        val signerKey = authorizedSignerPublicKey.copyOf()
        val voucherSignature = signature.copyOf()
        return withContext(Dispatchers.Default) {
            try {
                require(channel.size == 32) { "channelId must be 32 bytes" }
                require(signerKey.isNotEmpty()) { "authorizedSignerPublicKey must not be empty" }
                require(voucherSignature.isNotEmpty()) { "signature must not be empty" }
                require(cumulativeAmount > 0) { "cumulativeAmount must be positive" }
                val config = HostViewerVaultReader.networkConfig(network)
                val snapshot = readSnapshot(viewerAddress, creatorAddress, signerKey, channel, network).getOrThrow()
                require(cumulativeAmount <= snapshot.totalDepositMicroUsdc) { "Voucher exceeds deposit" }
                require(cumulativeAmount > snapshot.lastSettledMicroUsdc) { "Nothing new to settle" }
                currentCoroutineContext().ensureActive()
                validateSignature(
                    funderSigner, config.appId, config.assetId, config.algodUrl, channel,
                    cumulativeAmount, voucherSignature, signerKey, creatorAddress,
                )
                currentCoroutineContext().ensureActive()
                Result.success(snapshot)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun settle(
        viewerAddress: String,
        creatorAddress: String,
        authorizedSignerPublicKey: ByteArray,
        channelId: ByteArray,
        signature: ByteArray,
        cumulativeAmount: Long,
        network: String,
    ): Result<String> {
        val channel = channelId.copyOf()
        val signerKey = authorizedSignerPublicKey.copyOf()
        val voucherSignature = signature.copyOf()
        return withContext(Dispatchers.Default) {
            try {
                val config = HostViewerVaultReader.networkConfig(network)
                validateVoucher(
                    viewerAddress, creatorAddress, signerKey, channel, voucherSignature, cumulativeAmount, network,
                ).getOrThrow()
                currentCoroutineContext().ensureActive()
                Result.success(
                    submit(
                        funderSigner,
                        config.appId,
                        config.assetId,
                        config.algodUrl,
                        channel,
                        cumulativeAmount,
                        voucherSignature,
                        signerKey,
                        creatorAddress,
                        "N/A".encodeToByteArray(),
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
