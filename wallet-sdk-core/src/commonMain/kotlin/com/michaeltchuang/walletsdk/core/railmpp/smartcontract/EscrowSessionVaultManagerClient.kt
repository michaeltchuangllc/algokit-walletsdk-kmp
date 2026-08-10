package com.michaeltchuang.walletsdk.core.railmpp.smartcontract

import com.michaeltchuang.walletsdk.core.deeplink.utils.AssetConstants
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.NODE_FUTURENET_BASE_URL
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.NODE_MAINNET_BASE_URL
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.NODE_TESTNET_BASE_URL
import com.michaeltchuang.walletsdk.core.network.domain.provideNodePreferenceRepository
import com.michaeltchuang.walletsdk.core.network.model.AlgorandNetwork
import com.michaeltchuang.walletsdk.core.railmpp.data.repository.RailMppDataRepositoryImpl
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.internal.decodeAlgorandAddressPublicKey
import com.michaeltchuang.walletsdk.core.railmpp.internal.encodeAlgorandAddress
import com.michaeltchuang.walletsdk.core.railmpp.internal.encodeUint64
import com.michaeltchuang.walletsdk.core.railmpp.internal.getSessionBoxBytesInternal
import com.michaeltchuang.walletsdk.core.railmpp.internal.sha256
import com.michaeltchuang.walletsdk.core.railmpp.internal.sha512_256
import com.michaeltchuang.walletsdk.core.railmpp.internal.submitAppCallInternal
import com.michaeltchuang.walletsdk.core.railmpp.internal.submitAssetTransferAndAppCallInternal
import com.michaeltchuang.walletsdk.core.railmpp.utils.RailMppConstants
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Kotlin client for the EscrowSessionVaultManager ARC-56 contract. */
object EscrowSessionVaultManagerClient {
    private const val DEFAULT_ALGOD_URL = "https://testnet-api.algonode.cloud"
    private val AUTHORIZED_SIGNER_PUBLIC_KEY_BOX_PREFIX = "p".encodeToByteArray()

    private val ABI_OPEN = byteArrayOf(0x48, 0xd5.toByte(), 0x3e, 0x32)
    private val ABI_TOP_UP = byteArrayOf(0xbd.toByte(), 0xcf.toByte(), 0xac.toByte(), 0x58)
    private val ABI_SET_AUTHORIZED_SIGNER_PUBLIC_KEY = byteArrayOf(0x4b, 0x1d, 0xbb.toByte(), 0x67)
    private val ABI_UPDATE_VOUCHER = byteArrayOf(0xa9.toByte(), 0x8d.toByte(), 0x82.toByte(), 0xda.toByte())
    private val ABI_SETTLE = byteArrayOf(0xf7.toByte(), 0xdf.toByte(), 0x8d.toByte(), 0xe2.toByte())
    private val ABI_SETTLE_LATEST = byteArrayOf(0x6e, 0x87.toByte(), 0x27, 0x89.toByte())
    private val ABI_CLOSE = byteArrayOf(0xe8.toByte(), 0x6a, 0xe9.toByte(), 0xe9.toByte())
    private val ABI_REQUEST_CLOSE = byteArrayOf(0x34, 0x68, 0x50, 0x50)
    private val ABI_WITHDRAW = byteArrayOf(0x59, 0x05, 0xd4.toByte(), 0xf4.toByte())
    private val ABI_FUND_MBR_POOL = byteArrayOf(0xaa.toByte(), 0x14, 0xc4.toByte(), 0xf9.toByte())
    private val ABI_OPT_IN_USDC = byteArrayOf(0x7e, 0x3f, 0x4a, 0x68)
    private val ABI_VERIFY_SETTLE_SIGNATURE = byteArrayOf(0x27, 0x04, 0x92.toByte(), 0x89.toByte())

    var appId: Long = RailMppConstants.MPP_SESSION_VAULT_APP_ID
    var usdcAssetId: Long = AssetConstants.USDC_TESTNET_ID
    var algodUrl: String = DEFAULT_ALGOD_URL
    var defaultSalt: ByteArray? = null
    var channelId: ByteArray? = null
    var salt: ByteArray? = null
    var hostAddress: String? = null

    init {
        runCatching {
            val network =
                runBlocking {
                    provideNodePreferenceRepository().getSavedNodePreferenceFlow().first()
                }
            applyNetworkDefaults(network)
        }
        runCatching {
            defaultSalt =
                runBlocking {
                    RailMppDataRepositoryImpl().getOrCreateChannelSalt()
                }
        }
    }

    fun deriveChannelId(
        payerAddress: String,
        payeeAddress: String,
        authorizedSignerPublicKey: ByteArray,
    ): ByteArray {
        val defaultSalt = defaultSalt ?: error("defaultSalt is not configured")
        val payer = decodeAlgorandAddressPublicKey(payerAddress)
        val payee = decodeAlgorandAddressPublicKey(payeeAddress)
        return sha256(
            payer + payee + encodeUint64(usdcAssetId) +
                defaultSalt +
                computeSignerPubkeyHash(authorizedSignerPublicKey),
        )
    }

    fun initializeChannelId(
        payerAddress: String,
        payeeAddress: String,
        authorizedSignerPublicKey: ByteArray,
    ): ByteArray =
        deriveChannelId(payerAddress, payeeAddress, authorizedSignerPublicKey).also { derivedChannelId ->
            channelId = derivedChannelId
        }

    suspend fun openAndDeposit(
        signer: MppWalletSigner,
        payerAddress: String = signer.address,
        depositMicroUsdc: Long,
        channelId: ByteArray? = this.channelId,
    ): Result<String> =
        runCatching {
            require(payerAddress == signer.address) {
                "payerAddress must match signer.address for session vault deposit"
            }
            require(hostAddress != null) {
                "hostAddress is null. hostAddress is required for session vault deposit"
            }
            val resolvedChannelId = channelId ?: error("channelId is null")
            val salt = salt ?: defaultSalt ?: error("salt is null")

            submitAssetTransferAndAppCallInternal(
                signer = signer,
                appId = appId,
                usdcAssetId = usdcAssetId,
                algodUrl = algodUrl,
                appCallArgs =
                    listOf(
                        ABI_OPEN,
                        decodeAlgorandAddressPublicKey(hostAddress!!),
                        encodeArc4DynamicBytes(salt),
                        encodeArc4DynamicBytes(computeSignerPubkeyHash(signer.authorizedSignerPublicKey)),
                        encodeArc4DynamicBytes(signer.authorizedSignerPublicKey),
                    ),
                boxKeys =
                    listOf(
                        Pair(appId, resolvedChannelId),
                        Pair(appId, AUTHORIZED_SIGNER_PUBLIC_KEY_BOX_PREFIX + resolvedChannelId),
                    ),
                appCallForeignAssets = listOf(usdcAssetId),
                depositAmountMicroUsdc = depositMicroUsdc,
            )
        }

    suspend fun topUp(
        signer: MppWalletSigner,
        channelId: ByteArray,
        additionalDepositMicroUsdc: Long,
    ): Result<String> =
        runCatching {
            submitAssetTransferAndAppCallInternal(
                signer = signer,
                appId = appId,
                usdcAssetId = usdcAssetId,
                algodUrl = algodUrl,
                appCallArgs = listOf(ABI_TOP_UP, encodeArc4DynamicBytes(channelId)),
                boxKeys = listOf(Pair(appId, channelId)),
                appCallForeignAssets = emptyList(),
                depositAmountMicroUsdc = additionalDepositMicroUsdc,
            )
        }

    suspend fun setAuthorizedSignerPublicKey(
        signer: MppWalletSigner,
        channelId: ByteArray,
        authorizedSignerPublicKey: ByteArray,
    ): Result<String> =
        runCatching {
            submitAppCallInternal(
                signer = signer,
                appId = appId,
                usdcAssetId = usdcAssetId,
                algodUrl = algodUrl,
                args =
                    listOf(
                        ABI_SET_AUTHORIZED_SIGNER_PUBLIC_KEY,
                        encodeArc4DynamicBytes(channelId),
                        encodeArc4DynamicBytes(authorizedSignerPublicKey),
                    ),
                boxKeys =
                    listOf(
                        Pair(appId, channelId),
                        Pair(appId, AUTHORIZED_SIGNER_PUBLIC_KEY_BOX_PREFIX + channelId),
                    ),
                foreignAssets = emptyList(),
            )
        }

    suspend fun updateVoucher(
        signer: MppWalletSigner,
        channelId: ByteArray,
        cumulativeAmountMicroUsdc: Long,
        signature: ByteArray,
    ): Result<String> =
        runCatching {
            submitAppCallInternal(
                signer = signer,
                appId = appId,
                usdcAssetId = usdcAssetId,
                algodUrl = algodUrl,
                args =
                    listOf(
                        ABI_UPDATE_VOUCHER,
                        encodeArc4DynamicBytes(channelId),
                        encodeUint64(cumulativeAmountMicroUsdc),
                        encodeArc4DynamicBytes(signature),
                    ),
                boxKeys =
                    listOf(
                        Pair(appId, channelId),
                        Pair(appId, AUTHORIZED_SIGNER_PUBLIC_KEY_BOX_PREFIX + channelId),
                    ),
                foreignAssets = emptyList(),
            )
        }

    suspend fun settle(
        signer: MppWalletSigner,
        channelId: ByteArray,
        cumulativeAmountMicroUsdc: Long,
        signature: ByteArray,
    ): Result<String> =
        runCatching {
            submitAppCallInternal(
                signer = signer,
                appId = appId,
                usdcAssetId = usdcAssetId,
                algodUrl = algodUrl,
                args =
                    listOf(
                        ABI_SETTLE,
                        encodeArc4DynamicBytes(channelId),
                        encodeUint64(cumulativeAmountMicroUsdc),
                        encodeArc4DynamicBytes(signature),
                    ),
                boxKeys =
                    listOf(
                        Pair(appId, channelId),
                        Pair(appId, AUTHORIZED_SIGNER_PUBLIC_KEY_BOX_PREFIX + channelId),
                    ),
                foreignAssets = listOf(usdcAssetId),
            )
        }

    suspend fun settleLatest(
        signer: MppWalletSigner,
        channelId: ByteArray,
    ): Result<String> =
        runCatching {
            submitAppCallInternal(
                signer = signer,
                appId = appId,
                usdcAssetId = usdcAssetId,
                algodUrl = algodUrl,
                args = listOf(ABI_SETTLE_LATEST, encodeArc4DynamicBytes(channelId)),
                boxKeys = listOf(Pair(appId, channelId)),
                foreignAssets = listOf(usdcAssetId),
            )
        }

    // payee/creator side
    suspend fun close(
        signer: MppWalletSigner,
        channelId: ByteArray,
    ): Result<String> =
        runCatching {
            submitAppCallInternal(
                signer = signer,
                appId = appId,
                usdcAssetId = usdcAssetId,
                algodUrl = algodUrl,
                args = listOf(ABI_CLOSE, encodeArc4DynamicBytes(channelId)),
                boxKeys = listOf(Pair(appId, channelId)),
                foreignAssets = listOf(usdcAssetId),
                foreignAccounts = getChannelParticipants(channelId),
            )
        }

    // viewer side
    suspend fun requestClose(
        signer: MppWalletSigner,
        channelId: ByteArray,
    ): Result<String> =
        runCatching {
            submitAppCallInternal(
                signer = signer,
                appId = appId,
                usdcAssetId = usdcAssetId,
                algodUrl = algodUrl,
                args = listOf(ABI_REQUEST_CLOSE, encodeArc4DynamicBytes(channelId)),
                boxKeys = listOf(Pair(appId, channelId)),
                foreignAssets = emptyList(),
            )
        }

    suspend fun withdraw(
        signer: MppWalletSigner,
        channelId: ByteArray,
    ): Result<String> =
        runCatching {
            submitAppCallInternal(
                signer = signer,
                appId = appId,
                usdcAssetId = usdcAssetId,
                algodUrl = algodUrl,
                args = listOf(ABI_WITHDRAW, encodeArc4DynamicBytes(channelId)),
                boxKeys = listOf(Pair(appId, channelId)),
                foreignAssets = listOf(usdcAssetId),
                foreignAccounts = getChannelParticipants(channelId),
            )
        }

    suspend fun fundMbrPool(
        signer: MppWalletSigner,
        receiverAddress: String,
    ): Result<String> =
        runCatching {
            submitAppCallInternal(
                signer = signer,
                appId = appId,
                usdcAssetId = usdcAssetId,
                algodUrl = algodUrl,
                args = listOf(ABI_FUND_MBR_POOL, decodeAlgorandAddressPublicKey(receiverAddress)),
                boxKeys = emptyList(),
                foreignAssets = emptyList(),
            )
        }

    suspend fun optInUsdc(signer: MppWalletSigner): Result<String> =
        runCatching {
            submitAppCallInternal(
                signer = signer,
                appId = appId,
                usdcAssetId = usdcAssetId,
                algodUrl = algodUrl,
                args = listOf(ABI_OPT_IN_USDC),
                boxKeys = emptyList(),
                foreignAssets = listOf(usdcAssetId),
            )
        }

    suspend fun verifySettleSignatureOnChain(
        signer: MppWalletSigner,
        channelId: ByteArray,
        cumulativeAmountMicroUsdc: Long,
        signature: ByteArray,
    ): Result<String> =
        runCatching {
            submitAppCallInternal(
                signer = signer,
                appId = appId,
                usdcAssetId = usdcAssetId,
                algodUrl = algodUrl,
                args =
                    listOf(
                        ABI_VERIFY_SETTLE_SIGNATURE,
                        encodeArc4DynamicBytes(channelId),
                        encodeUint64(cumulativeAmountMicroUsdc),
                        encodeArc4DynamicBytes(signature),
                    ),
                boxKeys =
                    listOf(
                        Pair(appId, channelId),
                        Pair(appId, AUTHORIZED_SIGNER_PUBLIC_KEY_BOX_PREFIX + channelId),
                    ),
                foreignAssets = emptyList(),
            )
        }

    suspend fun verifySettleSignature(
        signer: MppWalletSigner,
        channelId: ByteArray,
        cumulativeAmountMicroUsdc: Long,
        signature: ByteArray,
    ): Result<String> = verifySettleSignatureOnChain(signer, channelId, cumulativeAmountMicroUsdc, signature)

    data class SessionStaticData(
        val startRound: Long,
        val startTimestamp: Long,
    )

    data class SessionDynamicData(
        val totalDeposit: Long,
        val lastSettled: Long,
        val latestVoucherAmount: Long,
    )

    private data class SessionInfoOffsets(
        val totalDepositOffset: Int,
        val lastSettledOffset: Int,
        val latestVoucherAmountOffset: Int,
    )

    fun getSessionStaticData(channelId: ByteArray): Result<SessionStaticData> =
        runCatching {
            val bytes = getSessionBoxBytesInternal(appId, channelId, algodUrl)
            SessionStaticData(
                startRound = decodeUint64BigEndian(bytes, 90),
                startTimestamp = decodeUint64BigEndian(bytes, 98),
            )
        }

    fun getSessionDynamicData(channelId: ByteArray): Result<SessionDynamicData> =
        runCatching {
            val bytes = getSessionBoxBytesInternal(appId, channelId, algodUrl)
            val offsets = decodeSessionInfoOffsets(bytes)
            SessionDynamicData(
                totalDeposit = decodeUint64BigEndian(bytes, offsets.totalDepositOffset),
                lastSettled = decodeUint64BigEndian(bytes, offsets.lastSettledOffset),
                latestVoucherAmount =
                    decodeUint64BigEndian(
                        bytes,
                        offsets.latestVoucherAmountOffset,
                    ),
            )
        }

    fun computeSignerPubkeyHash(authorizedSigner: ByteArray): ByteArray = sha512_256(authorizedSigner)

    fun settleMessage(
        channelId: ByteArray,
        cumulativeAmountMicroUsdc: Long,
    ): ByteArray = buildSettleMessage(channelId, cumulativeAmountMicroUsdc)

    fun buildSettleMessage(
        channelId: ByteArray,
        cumulativeAmountMicroUsdc: Long,
    ): ByteArray = encodeUint64(appId) + channelId + encodeUint64(cumulativeAmountMicroUsdc) + "settle".encodeToByteArray()

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun getChannelParticipants(channelId: ByteArray): List<String> {
        val bytes = getSessionBoxBytesInternal(appId, channelId, algodUrl)
        if (bytes.size < 64) error("Invalid session box payload size=${bytes.size}")
        val payer = encodeAlgorandAddress(bytes.copyOfRange(0, 32))
        val payee = encodeAlgorandAddress(bytes.copyOfRange(32, 64))
        return listOf(payer, payee)
    }

    private fun decodeSessionInfoOffsets(bytes: ByteArray): SessionInfoOffsets {
        if (bytes.size < 98) error("Invalid session box payload size=${bytes.size}")
        val markerAt64 = ((bytes[64].toInt() and 0xFF) shl 8) or (bytes[65].toInt() and 0xFF)
        val arc4Offsets = SessionInfoOffsets(66, 74, 82)
        if (isPlausibleSessionLayout(bytes, arc4Offsets)) return arc4Offsets
        val signerLen = markerAt64
        val totalsOffset = 66 + signerLen
        if (bytes.size >= totalsOffset + 24) {
            val legacyOffsets =
                SessionInfoOffsets(totalsOffset, totalsOffset + 8, totalsOffset + 16)
            if (isPlausibleSessionLayout(bytes, legacyOffsets)) return legacyOffsets
            return legacyOffsets
        }
        if (markerAt64 in 98..bytes.size) return arc4Offsets
        error("Invalid session box payload (markerAt64=$markerAt64 size=${bytes.size})")
    }

    private fun isPlausibleSessionLayout(
        bytes: ByteArray,
        offsets: SessionInfoOffsets,
    ): Boolean {
        if (offsets.latestVoucherAmountOffset + 8 > bytes.size) return false
        return runCatching {
            val total = decodeUint64BigEndian(bytes, offsets.totalDepositOffset)
            val settled = decodeUint64BigEndian(bytes, offsets.lastSettledOffset)
            val voucher = decodeUint64BigEndian(bytes, offsets.latestVoucherAmountOffset)
            total >= settled && total >= voucher && voucher >= settled
        }.getOrDefault(false)
    }

    private fun decodeUint64BigEndian(
        bytes: ByteArray,
        offset: Int,
    ): Long {
        var out = 0L
        for (i in 0 until 8) out = (out shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return out
    }

    private fun encodeArc4DynamicBytes(bytes: ByteArray): ByteArray {
        require(bytes.size <= 0xFFFF) { "byte[] too long for ARC4 dynamic bytes" }
        return byteArrayOf(
            ((bytes.size ushr 8) and 0xFF).toByte(),
            (bytes.size and 0xFF).toByte(),
        ) + bytes
    }

    fun configureForNetwork(network: AlgorandNetwork) {
        applyNetworkDefaults(network)
    }

    private fun applyNetworkDefaults(network: AlgorandNetwork) {
        when (network) {
            AlgorandNetwork.MAINNET -> {
                appId = RailMppConstants.MAINNET_MPP_SESSION_VAULT_APP_ID
                usdcAssetId = AssetConstants.USDC_MAINNET_ID
                algodUrl = NODE_MAINNET_BASE_URL
            }

            AlgorandNetwork.TESTNET -> {
                appId = RailMppConstants.TESTNET_MPP_SESSION_VAULT_APP_ID
                usdcAssetId = AssetConstants.USDC_TESTNET_ID
                algodUrl = NODE_TESTNET_BASE_URL
            }

            AlgorandNetwork.FUTURENET -> {
                appId = RailMppConstants.FUTURENET_MPP_SESSION_VAULT_APP_ID
                usdcAssetId = AssetConstants.USDC_FUTURENET_ID
                algodUrl = NODE_FUTURENET_BASE_URL
            }
        }
    }
}
