package com.michaeltchuang.walletsdk.core.railmpp.smartcontract

import com.michaeltchuang.walletsdk.core.deeplink.utils.AssetConstants
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.NODE_FUTURENET_BASE_URL
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.NODE_MAINNET_BASE_URL
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.NODE_TESTNET_BASE_URL
import com.michaeltchuang.walletsdk.core.network.domain.provideNodePreferenceRepository
import com.michaeltchuang.walletsdk.core.network.model.AlgorandNetwork
import com.michaeltchuang.walletsdk.core.railmpp.data.repository.RailMppDataRepositoryImpl
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.internal.compileSettlementLogicSigAddressInternal
import com.michaeltchuang.walletsdk.core.railmpp.internal.decodeAlgorandAddressPublicKey
import com.michaeltchuang.walletsdk.core.railmpp.internal.encodeAlgorandAddress
import com.michaeltchuang.walletsdk.core.railmpp.internal.encodeArc4DynamicBytes
import com.michaeltchuang.walletsdk.core.railmpp.internal.encodeUint64
import com.michaeltchuang.walletsdk.core.railmpp.internal.getSessionBoxBytesInternal
import com.michaeltchuang.walletsdk.core.railmpp.internal.sha256
import com.michaeltchuang.walletsdk.core.railmpp.internal.sha512_256
import com.michaeltchuang.walletsdk.core.railmpp.internal.simulateReadonlyMethodInternal
import com.michaeltchuang.walletsdk.core.railmpp.internal.submitAppCallInternal
import com.michaeltchuang.walletsdk.core.railmpp.internal.submitAssetTransferAndAppCallInternal
import com.michaeltchuang.walletsdk.core.railmpp.internal.submitLogicSigSettlementInternal
import com.michaeltchuang.walletsdk.core.railmpp.utils.RailMppConstants
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Kotlin client for the EscrowSessionVaultHybridManager ARC-56 contract. */
object EscrowSessionVaultHybridManagerClient {
    private const val DEFAULT_ALGOD_URL = "https://testnet-api.algonode.cloud"
    private val AUTHORIZED_SIGNER_PUBLIC_KEY_BOX_PREFIX = "p".encodeToByteArray()
    private val SETTLEMENT_LOGIC_SIG_BOX_PREFIX = "l".encodeToByteArray()

    private val ABI_OPEN = byteArrayOf(0x48, 0xd5.toByte(), 0x3e, 0x32)
    private val ABI_TOP_UP = byteArrayOf(0xbd.toByte(), 0xcf.toByte(), 0xac.toByte(), 0x58)
    private val ABI_SET_AUTHORIZED_SIGNER_PUBLIC_KEY = byteArrayOf(0x4b, 0x1d, 0xbb.toByte(), 0x67)
    private val ABI_SET_SETTLEMENT_LOGIC_SIG = byteArrayOf(0x42, 0xd9.toByte(), 0x75, 0xa6.toByte())
    private val ABI_REVOKE_SETTLEMENT_LOGIC_SIG = byteArrayOf(0x20, 0xb9.toByte(), 0xbe.toByte(), 0x9b.toByte())
    private val ABI_CLOSE = byteArrayOf(0xe8.toByte(), 0x6a, 0xe9.toByte(), 0xe9.toByte())
    private val ABI_REQUEST_CLOSE = byteArrayOf(0x34, 0x68, 0x50, 0x50)
    private val ABI_WITHDRAW = byteArrayOf(0x59, 0x05, 0xd4.toByte(), 0xf4.toByte())
    private val ABI_FUND_MBR_POOL = byteArrayOf(0xaa.toByte(), 0x14, 0xc4.toByte(), 0xf9.toByte())
    private val ABI_OPT_IN_USDC = byteArrayOf(0x7e, 0x3f, 0x4a, 0x68)
    private val ABI_GET_SESSION_STATIC_DATA = byteArrayOf(0xa8.toByte(), 0x70, 0x49, 0x03)
    private val ABI_GET_SESSION_DYNAMIC_DATA = byteArrayOf(0xcc.toByte(), 0xde.toByte(), 0x9f.toByte(), 0xb6.toByte())

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

    /**
     * Matches the hybrid contract's channel ID derivation, which commits to the signer key hash.
     */
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

    /**
     * Computes the address of the channel's settlement LogicSig, compiled with [signer]'s own
     * ephemeral session key as `TMPL_AUTHORIZED_PUBLIC_KEY`, then registers it on-chain via
     * [setSettlementLogicSig]. Must be called by the channel's payer — the contract asserts
     * `Txn.sender === payer` — typically once, right after opening/topping-up the channel
     * (mirrors `setAuthorizedSignerForSession`). The payee later settles vouchers signed by this
     * same session key without ever needing the payer's real wallet key again.
     */
    suspend fun registerSettlementLogicSig(
        signer: MppWalletSigner,
        channelId: ByteArray,
        payeeAddress: String,
    ): Result<String> =
        runCatching {
            val logicSigAddress =
                compileSettlementLogicSigAddressInternal(
                    appId = appId,
                    algodUrl = algodUrl,
                    channelId = channelId,
                    authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                    payeeAddress = payeeAddress,
                )
            setSettlementLogicSig(signer, channelId, logicSigAddress).getOrThrow()
        }

    /** Registers the channel-specific settlement LogicSig address. */
    suspend fun setSettlementLogicSig(
        signer: MppWalletSigner,
        channelId: ByteArray,
        logicSigAddress: String,
    ): Result<String> =
        runCatching {
            submitAppCallInternal(
                signer = signer,
                appId = appId,
                usdcAssetId = usdcAssetId,
                algodUrl = algodUrl,
                args =
                    listOf(
                        ABI_SET_SETTLEMENT_LOGIC_SIG,
                        encodeArc4DynamicBytes(channelId),
                        decodeAlgorandAddressPublicKey(logicSigAddress),
                    ),
                boxKeys =
                    listOf(
                        Pair(appId, channelId),
                        Pair(appId, SETTLEMENT_LOGIC_SIG_BOX_PREFIX + channelId),
                    ),
                foreignAssets = emptyList(),
            )
        }

    /**
     * Emergency stop: payer immediately revokes the registered settlement LogicSig for a
     * channel (e.g. if the ephemeral Falcon session key is suspected compromised), without
     * closing the channel or losing the deposit. [settleFromLogicSig] will fail until the payer
     * registers a fresh LogicSig via [setSettlementLogicSig].
     */
    suspend fun revokeSettlementLogicSig(
        signer: MppWalletSigner,
        channelId: ByteArray,
    ): Result<String> =
        runCatching {
            submitAppCallInternal(
                signer = signer,
                appId = appId,
                usdcAssetId = usdcAssetId,
                algodUrl = algodUrl,
                args = listOf(ABI_REVOKE_SETTLEMENT_LOGIC_SIG, encodeArc4DynamicBytes(channelId)),
                boxKeys =
                    listOf(
                        Pair(appId, channelId),
                        Pair(appId, SETTLEMENT_LOGIC_SIG_BOX_PREFIX + channelId),
                    ),
                foreignAssets = emptyList(),
            )
        }

    /**
     * Submits the hybrid contract's required two-LogicSig settlement group. The viewer's
     * [voucherSignature] becomes a settlement-program argument and is verified by AVM Falcon
     * logic; [payeeAddress] is part of the signed voucher domain.
     *
     * The settlement group is authorized entirely by the two LogicSigs (settlement + padding) —
     * neither the payer's nor the payee's wallet key ever signs it. [funderSigner] is only used
     * to (a) fund both LogicSig accounts if their balance is low and (b) sanity-check that the
     * settlement LogicSig is already registered on-chain; in production this is typically the
     * *payee*, who is settling the viewer's voucher and paying the group fee. It does **not**
     * need to be the channel's payer. [authorizedSignerPublicKey] must be the *payer's* ephemeral
     * session key (whatever was passed to [registerSettlementLogicSig]/[setSettlementLogicSig]),
     * not the funder's — passing the wrong key compiles a different LogicSig address and this
     * call fails with a clear "not registered" error.
     */
    suspend fun settleFromLogicSig(
        funderSigner: MppWalletSigner,
        channelId: ByteArray,
        cumulativeAmountMicroUsdc: Long,
        voucherSignature: ByteArray,
        authorizedSignerPublicKey: ByteArray,
        payeeAddress: String,
        note: String = "N/A",
    ): Result<String> =
        runCatching {
            require(channelId.size == 32) { "channelId must be 32 bytes" }
            require(cumulativeAmountMicroUsdc > 0) { "cumulativeAmountMicroUsdc must be positive" }
            require(voucherSignature.isNotEmpty()) { "voucherSignature must not be empty" }
            require(authorizedSignerPublicKey.isNotEmpty()) { "authorizedSignerPublicKey must not be empty" }
            submitLogicSigSettlementInternal(
                payerSigner = funderSigner,
                appId = appId,
                usdcAssetId = usdcAssetId,
                algodUrl = algodUrl,
                channelId = channelId,
                cumulativeAmountMicroUsdc = cumulativeAmountMicroUsdc,
                voucherSignature = voucherSignature,
                authorizedSignerPublicKey = authorizedSignerPublicKey,
                payeeAddress = payeeAddress,
                note = note.encodeToByteArray(),
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
                boxKeys =
                    listOf(
                        Pair(appId, channelId),
                        Pair(appId, AUTHORIZED_SIGNER_PUBLIC_KEY_BOX_PREFIX + channelId),
                        Pair(appId, SETTLEMENT_LOGIC_SIG_BOX_PREFIX + channelId),
                    ),
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
                boxKeys =
                    listOf(
                        Pair(appId, channelId),
                        Pair(appId, AUTHORIZED_SIGNER_PUBLIC_KEY_BOX_PREFIX + channelId),
                        Pair(appId, SETTLEMENT_LOGIC_SIG_BOX_PREFIX + channelId),
                    ),
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

    data class SessionStaticData(
        val startRound: Long,
        val startTimestamp: Long,
    )

    data class SessionDynamicData(
        val totalDeposit: Long,
        val lastSettled: Long,
        val latestVoucherAmount: Long,
    )

    /**
     * Reads the channel's `(startRound, startTimestamp)` tuple via a free readonly ABI simulate
     * call against algod (mirrors the TypeScript reference script's
     * `appClient.send.getSessionStaticData`, which algokit-utils resolves via
     * `/v2/transactions/simulate` since the ARC-56 method is marked `readonly: true` — no fee,
     * no real signature required), so the return value is decoded exactly as the contract
     * computed it. No box-byte fallback: `allow-empty-signatures`/`allow-unnamed-resources`
     * simulate support is universal across current algod versions, and silently guessing at the
     * box's raw byte layout risks returning wrong-but-plausible-looking data instead of a clear
     * error.
     */
    fun getSessionStaticData(channelId: ByteArray): Result<SessionStaticData> =
        runCatching {
            val simulated =
                simulateReadonlyMethodInternal(
                    appId = appId,
                    algodUrl = algodUrl,
                    selector = ABI_GET_SESSION_STATIC_DATA,
                    args = listOf(encodeArc4DynamicBytes(channelId)),
                    boxKeys = listOf(Pair(appId, channelId)),
                ) ?: error("getSessionStaticData: readonly simulate call failed for appId=$appId")
            check(simulated.size >= 16) { "getSessionStaticData: unexpected return size=${simulated.size}" }
            SessionStaticData(
                startRound = decodeUint64BigEndian(simulated, 0),
                startTimestamp = decodeUint64BigEndian(simulated, 8),
            )
        }

    /**
     * Reads the channel's `(totalDeposit, lastSettled, latestVoucherAmount)` tuple via a free
     * readonly ABI simulate call against algod (see [getSessionStaticData] doc for details); the
     * simulated return value is a plain fixed-width ARC-4 tuple `(uint64,uint64,uint64,address)`.
     */
    fun getSessionDynamicData(channelId: ByteArray): Result<SessionDynamicData> =
        runCatching {
            val simulated =
                simulateReadonlyMethodInternal(
                    appId = appId,
                    algodUrl = algodUrl,
                    selector = ABI_GET_SESSION_DYNAMIC_DATA,
                    args = listOf(encodeArc4DynamicBytes(channelId)),
                    boxKeys = listOf(Pair(appId, channelId)),
                ) ?: error("getSessionDynamicData: readonly simulate call failed for appId=$appId")
            check(simulated.size >= 24) { "getSessionDynamicData: unexpected return size=${simulated.size}" }
            SessionDynamicData(
                totalDeposit = decodeUint64BigEndian(simulated, 0),
                lastSettled = decodeUint64BigEndian(simulated, 8),
                latestVoucherAmount = decodeUint64BigEndian(simulated, 16),
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

    private fun decodeUint64BigEndian(
        bytes: ByteArray,
        offset: Int,
    ): Long {
        var out = 0L
        for (i in 0 until 8) out = (out shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return out
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
