package com.michaeltchuang.walletsdk.core.railmpp.smartcontract

import android.util.Log
import com.algorand.algosdk.crypto.Address
import com.algorand.algosdk.sdk.Sdk
import com.algorand.algosdk.transaction.AppBoxReference
import com.algorand.algosdk.transaction.Transaction
import com.algorand.algosdk.transaction.TxGroup
import com.algorand.algosdk.v2.client.common.AlgodClient
import com.algorand.algosdk.v2.client.common.Response
import com.algorand.algosdk.v2.client.model.PostTransactionsResponse
import com.michaeltchuang.walletsdk.core.deeplink.utils.AssetConstants
import com.michaeltchuang.walletsdk.core.railmpp.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.utils.RailMppConstants
import org.bouncycastle.crypto.digests.SHA512tDigest
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.Security

/**
 * Kotlin client for EscrowSessionVaultManager ARC-56 contract.
 */
class EscrowSessionVaultManagerClient(
    private val appId: Long = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
    private val usdcAssetId: Long = AssetConstants.USDC_TESTNET_ID,
    private val defaultSalt: ByteArray = DEFAULT_CHANNEL_SALT,
    private val defaultAlgodUrl: String = DEFAULT_ALGOD_URL,
) {
    private val tag = "EscrowSessionVaultClient"

    companion object {
        // Retained for backwards compatibility with existing callers.
        const val SIGNER_TYPE_ED25519 = 0L
        const val SIGNER_TYPE_FALCON_TXN_AUTH = 1L

        private const val APP_CALL_FEE = 12_000L
        private const val DUMMIES_PER_REAL_TXN = 3
        private const val MIN_TXN_FEE_MICROALGOS = 1_000L
        private const val DEFAULT_ALGOD_URL = "https://testnet-api.algonode.cloud"
        private val DEFAULT_CHANNEL_SALT = "walletsdk-session-v1".toByteArray(StandardCharsets.UTF_8)
        private val AUTHORIZED_SIGNER_PUBLIC_KEY_BOX_PREFIX = "p".toByteArray(StandardCharsets.UTF_8)
        private const val ALGORAND_ADDRESS_PUBLIC_KEY_LENGTH = 32
        private const val ALGORAND_ADDRESS_CHECKSUM_LENGTH = 4

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
    }

    suspend fun openAndDeposit(
        signer: MppWalletSigner,
        payeeAddress: String,
        depositMicroUsdc: Long,
        authorizedSignerPublicKey: ByteArray = signer.authorizedSignerPublicKey,
        signerType: Long = signer.signerType,
        algodUrl: String = defaultAlgodUrl,
    ): Result<String> =
        runCatching {
            ensureBouncyCastleProvider()
            // Kept for API compatibility with previous call sites.
            normalizeSignerType(signerType)

            val channelId =
                deriveChannelId(
                    payerAddress = signer.address,
                    payeeAddress = payeeAddress,
                    authorizedSignerPublicKey = authorizedSignerPublicKey,
                    salt = defaultSalt,
                )

            submitAssetTransferAndAppCall(
                signer = signer,
                algodUrl = algodUrl,
                appCallArgs =
                    listOf(
                        ABI_OPEN,
                        Address(payeeAddress).getBytes(),
                        encodeArc4DynamicBytes(defaultSalt),
                        encodeArc4DynamicBytes(computeSignerPubkeyHash(authorizedSignerPublicKey)),
                        encodeArc4DynamicBytes(authorizedSignerPublicKey),
                    ),
                appCallBoxReferences =
                    listOf(
                        AppBoxReference(appId, channelId),
                        AppBoxReference(appId, AUTHORIZED_SIGNER_PUBLIC_KEY_BOX_PREFIX + channelId),
                    ),
                appCallForeignAssets = listOf(usdcAssetId),
                depositAmountMicroUsdc = depositMicroUsdc,
            )
        }

    suspend fun topUp(
        signer: MppWalletSigner,
        channelId: ByteArray,
        additionalDepositMicroUsdc: Long,
        algodUrl: String = defaultAlgodUrl,
    ): Result<String> =
        runCatching {
            submitAssetTransferAndAppCall(
                signer = signer,
                algodUrl = algodUrl,
                appCallArgs =
                    listOf(
                        ABI_TOP_UP,
                        encodeArc4DynamicBytes(channelId),
                    ),
                appCallBoxReferences = listOf(AppBoxReference(appId, channelId)),
                appCallForeignAssets = emptyList(),
                depositAmountMicroUsdc = additionalDepositMicroUsdc,
            )
        }

    suspend fun setAuthorizedSignerPublicKey(
        signer: MppWalletSigner,
        channelId: ByteArray,
        authorizedSignerPublicKey: ByteArray,
        algodUrl: String = defaultAlgodUrl,
    ): Result<String> =
        runCatching {
            submitAppCall(
                signer = signer,
                algodUrl = algodUrl,
                args =
                    listOf(
                        ABI_SET_AUTHORIZED_SIGNER_PUBLIC_KEY,
                        encodeArc4DynamicBytes(channelId),
                        encodeArc4DynamicBytes(authorizedSignerPublicKey),
                    ),
                boxReferences =
                    listOf(
                        AppBoxReference(appId, channelId),
                        AppBoxReference(appId, AUTHORIZED_SIGNER_PUBLIC_KEY_BOX_PREFIX + channelId),
                    ),
            )
        }

    suspend fun updateVoucher(
        signer: MppWalletSigner,
        channelId: ByteArray,
        cumulativeAmountMicroUsdc: Long,
        signature: ByteArray,
        algodUrl: String = defaultAlgodUrl,
    ): Result<String> =
        runCatching {
            submitAppCall(
                signer = signer,
                algodUrl = algodUrl,
                args =
                    listOf(
                        ABI_UPDATE_VOUCHER,
                        encodeArc4DynamicBytes(channelId),
                        encodeUint64(cumulativeAmountMicroUsdc),
                        encodeArc4DynamicBytes(signature),
                    ),
                boxReferences =
                    listOf(
                        AppBoxReference(appId, channelId),
                        AppBoxReference(appId, AUTHORIZED_SIGNER_PUBLIC_KEY_BOX_PREFIX + channelId),
                    ),
            )
        }

    suspend fun settle(
        signer: MppWalletSigner,
        channelId: ByteArray,
        cumulativeAmountMicroUsdc: Long,
        signature: ByteArray,
        algodUrl: String = defaultAlgodUrl,
    ): Result<String> =
        runCatching {
            submitAppCall(
                signer = signer,
                algodUrl = algodUrl,
                args =
                    listOf(
                        ABI_SETTLE,
                        encodeArc4DynamicBytes(channelId),
                        encodeUint64(cumulativeAmountMicroUsdc),
                        encodeArc4DynamicBytes(signature),
                    ),
                boxReferences =
                    listOf(
                        AppBoxReference(appId, channelId),
                        AppBoxReference(appId, AUTHORIZED_SIGNER_PUBLIC_KEY_BOX_PREFIX + channelId),
                    ),
                foreignAssets = listOf(usdcAssetId),
            )
        }

    suspend fun settleLatest(
        signer: MppWalletSigner,
        channelId: ByteArray,
        algodUrl: String = defaultAlgodUrl,
    ): Result<String> =
        runCatching {
            submitAppCall(
                signer = signer,
                algodUrl = algodUrl,
                args =
                    listOf(
                        ABI_SETTLE_LATEST,
                        encodeArc4DynamicBytes(channelId),
                    ),
                boxReferences = listOf(AppBoxReference(appId, channelId)),
                foreignAssets = listOf(usdcAssetId),
            )
        }

    suspend fun close(
        signer: MppWalletSigner,
        channelId: ByteArray,
        algodUrl: String = defaultAlgodUrl,
    ): Result<String> =
        runCatching {
            submitAppCall(
                signer = signer,
                algodUrl = algodUrl,
                args =
                    listOf(
                        ABI_CLOSE,
                        encodeArc4DynamicBytes(channelId),
                    ),
                boxReferences = listOf(AppBoxReference(appId, channelId)),
                foreignAssets = listOf(usdcAssetId),
            )
        }

    suspend fun requestClose(
        signer: MppWalletSigner,
        channelId: ByteArray,
        algodUrl: String = defaultAlgodUrl,
    ): Result<String> =
        runCatching {
            submitAppCall(
                signer = signer,
                algodUrl = algodUrl,
                args =
                    listOf(
                        ABI_REQUEST_CLOSE,
                        encodeArc4DynamicBytes(channelId),
                    ),
                boxReferences = listOf(AppBoxReference(appId, channelId)),
            )
        }

    suspend fun withdraw(
        signer: MppWalletSigner,
        channelId: ByteArray,
        algodUrl: String = defaultAlgodUrl,
    ): Result<String> =
        runCatching {
            submitAppCall(
                signer = signer,
                algodUrl = algodUrl,
                args =
                    listOf(
                        ABI_WITHDRAW,
                        encodeArc4DynamicBytes(channelId),
                    ),
                boxReferences = listOf(AppBoxReference(appId, channelId)),
                foreignAssets = listOf(usdcAssetId),
            )
        }

    suspend fun fundMbrPool(
        signer: MppWalletSigner,
        receiverAddress: String,
        algodUrl: String = defaultAlgodUrl,
    ): Result<String> =
        runCatching {
            submitAppCall(
                signer = signer,
                algodUrl = algodUrl,
                args =
                    listOf(
                        ABI_FUND_MBR_POOL,
                        Address(receiverAddress).getBytes(),
                    ),
            )
        }

    suspend fun optInUsdc(
        signer: MppWalletSigner,
        algodUrl: String = defaultAlgodUrl,
    ): Result<String> =
        runCatching {
            submitAppCall(
                signer = signer,
                algodUrl = algodUrl,
                args = listOf(ABI_OPT_IN_USDC),
                foreignAssets = listOf(usdcAssetId),
            )
        }

    suspend fun verifySettleSignatureOnChain(
        signer: MppWalletSigner,
        channelId: ByteArray,
        cumulativeAmountMicroUsdc: Long,
        signature: ByteArray,
        algodUrl: String = defaultAlgodUrl,
    ): Result<String> =
        runCatching {
            submitAppCall(
                signer = signer,
                algodUrl = algodUrl,
                args =
                    listOf(
                        ABI_VERIFY_SETTLE_SIGNATURE,
                        encodeArc4DynamicBytes(channelId),
                        encodeUint64(cumulativeAmountMicroUsdc),
                        encodeArc4DynamicBytes(signature),
                    ),
                boxReferences =
                    listOf(
                        AppBoxReference(appId, channelId),
                        AppBoxReference(appId, AUTHORIZED_SIGNER_PUBLIC_KEY_BOX_PREFIX + channelId),
                    ),
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

    private data class SessionInfoOffsets(
        val totalDepositOffset: Int,
        val lastSettledOffset: Int,
        val latestVoucherAmountOffset: Int,
    )

    fun getSessionStaticData(
        channelId: ByteArray,
        algodUrl: String = defaultAlgodUrl,
    ): Result<SessionStaticData> =
        runCatching {
            val sessionBytes = getSessionBoxBytes(channelId, algodUrl)
            val startRound = decodeUint64BigEndian(sessionBytes, 90)
            val startTimestamp = decodeUint64BigEndian(sessionBytes, 98)
            SessionStaticData(
                startRound = startRound,
                startTimestamp = startTimestamp,
            )
        }

    fun getSessionDynamicData(
        channelId: ByteArray,
        algodUrl: String = defaultAlgodUrl,
    ): Result<SessionDynamicData> =
        runCatching {
            val sessionBytes = getSessionBoxBytes(channelId, algodUrl)
            val offsets = decodeSessionInfoOffsets(sessionBytes)
            SessionDynamicData(
                totalDeposit = decodeUint64BigEndian(sessionBytes, offsets.totalDepositOffset),
                lastSettled = decodeUint64BigEndian(sessionBytes, offsets.lastSettledOffset),
                latestVoucherAmount = decodeUint64BigEndian(sessionBytes, offsets.latestVoucherAmountOffset),
            )
        }

    fun computeChannelId(
        payerAddress: String,
        payeeAddress: String,
        authorizedSigner: ByteArray,
        salt: ByteArray = defaultSalt,
    ): ByteArray {
        ensureBouncyCastleProvider()
        val payer = decodeAlgorandAddressPublicKey(payerAddress)
        val payee = decodeAlgorandAddressPublicKey(payeeAddress)
        val material = payer + payee + encodeUint64(usdcAssetId) + salt + authorizedSigner
        return MessageDigest.getInstance("SHA-256").digest(material)
    }

    private fun decodeAlgorandAddressPublicKey(address: String): ByteArray {
        val decoded = decodeBase32(address)
        require(decoded.size >= ALGORAND_ADDRESS_PUBLIC_KEY_LENGTH + ALGORAND_ADDRESS_CHECKSUM_LENGTH) {
            "Invalid Algorand address length"
        }
        return decoded.copyOfRange(0, ALGORAND_ADDRESS_PUBLIC_KEY_LENGTH)
    }

    private fun decodeBase32(value: String): ByteArray {
        var buffer = 0
        var bitsLeft = 0
        val bytes = mutableListOf<Byte>()

        value
            .trim()
            .trimEnd('=')
            .uppercase()
            .forEach { char ->
                val charValue =
                    when (char) {
                        in 'A'..'Z' -> char - 'A'
                        in '2'..'7' -> char - '2' + 26
                        else -> error("Invalid base32 character: $char")
                    }

                buffer = (buffer shl 5) or charValue
                bitsLeft += 5

                if (bitsLeft >= 8) {
                    bitsLeft -= 8
                    bytes.add(((buffer shr bitsLeft) and 0xFF).toByte())
                }
            }

        return bytes.toByteArray()
    }

    private fun sha512256(bytes: ByteArray): ByteArray {
        val digest = SHA512tDigest(256)
        val output = ByteArray(32)
        digest.update(bytes, 0, bytes.size)
        digest.doFinal(output, 0)
        return output
    }

    fun deriveChannelId(
        payerAddress: String,
        payeeAddress: String,
        authorizedSignerPublicKey: ByteArray,
        salt: ByteArray = defaultSalt,
    ): ByteArray =
        computeChannelId(
            payerAddress = payerAddress,
            payeeAddress = payeeAddress,
            authorizedSigner = computeSignerPubkeyHash(authorizedSignerPublicKey),
            salt = salt,
        )

    fun computeSignerPubkeyHash(authorizedSigner: ByteArray): ByteArray {
        ensureBouncyCastleProvider()
        return sha512256(authorizedSigner)
    }

    fun settleMessage(
        channelId: ByteArray,
        cumulativeAmountMicroUsdc: Long,
    ): ByteArray =
        buildSettleMessage(
            channelId = channelId,
            cumulativeAmountMicroUsdc = cumulativeAmountMicroUsdc,
        )

    suspend fun verifySettleSignature(
        signer: MppWalletSigner,
        channelId: ByteArray,
        cumulativeAmountMicroUsdc: Long,
        signature: ByteArray,
        algodUrl: String = defaultAlgodUrl,
    ): Result<String> =
        verifySettleSignatureOnChain(
            signer = signer,
            channelId = channelId,
            cumulativeAmountMicroUsdc = cumulativeAmountMicroUsdc,
            signature = signature,
            algodUrl = algodUrl,
        )

    fun buildSettleMessage(
        channelId: ByteArray,
        cumulativeAmountMicroUsdc: Long,
    ): ByteArray = encodeUint64(appId) + channelId + encodeUint64(cumulativeAmountMicroUsdc) + "settle".toByteArray(StandardCharsets.UTF_8)

    private suspend fun submitAppCall(
        signer: MppWalletSigner,
        algodUrl: String,
        args: List<ByteArray>,
        boxReferences: List<AppBoxReference> = emptyList(),
        foreignAssets: List<Long> = emptyList(),
    ): String {
        val client = algodClient(algodUrl)
        val params = client.TransactionParams().execute().body()
        val appCallTxn =
            buildAppCallTxn(
                signer = signer,
                params = params,
                args = args,
                boxReferences = boxReferences,
                foreignAssets = foreignAssets,
            )

        val useFalconBundleSigner = signer.signerType == SIGNER_TYPE_FALCON_TXN_AUTH
        val dummyTxns =
            if (useFalconBundleSigner) {
                List(DUMMIES_PER_REAL_TXN) {
                    buildFalconBudgetDummyTxn(signer, params, it)
                }
            } else {
                emptyList()
            }

        if (dummyTxns.isNotEmpty()) {
            val extraPooledFee = MIN_TXN_FEE_MICROALGOS * dummyTxns.size
            val baseFee = appCallTxn.fee?.toLong() ?: MIN_TXN_FEE_MICROALGOS
            appCallTxn.fee = BigInteger.valueOf(baseFee + extraPooledFee)
        }

        val txnsToSign = dummyTxns + appCallTxn

        TxGroup.assignGroupID(*txnsToSign.toTypedArray())

        val preSignFirstGroup = txnsToSign.firstOrNull()?.group?.toString()
        Log.e(
            tag,
            "[SESSION_VAULT_APP_CALL_GROUP_PRE_SIGN] sender=${signer.address} appId=$appId useFalconBundleSigner=$useFalconBundleSigner txCount=${txnsToSign.size} firstGroup=$preSignFirstGroup falconDummyCount=${dummyTxns.size}",
        )

        val signedGroup = signer.signTransactions(txnsToSign)
        val minExpectedSignedSize = txnsToSign.size
        if (useFalconBundleSigner) {
            require(signedGroup.size >= minExpectedSignedSize) {
                "Expected >=$minExpectedSignedSize signed txns for Falcon bundle, got ${signedGroup.size}"
            }
        } else {
            require(signedGroup.size == minExpectedSignedSize) {
                "Expected $minExpectedSignedSize signed txns, got ${signedGroup.size}"
            }
        }

        Log.e(
            tag,
            "[SESSION_VAULT_APP_CALL_GROUP_SIGNED] sender=${signer.address} appId=$appId signedGroupSize=${signedGroup.size} expectedGroupSize=$minExpectedSignedSize falconBundleMode=$useFalconBundleSigner falconDummyCount=${dummyTxns.size} appCallSignedLen=${signedGroup.last().size}",
        )

        return broadcast(client, signedGroup) ?: appCallTxn.txID()
    }

    private suspend fun submitAssetTransferAndAppCall(
        signer: MppWalletSigner,
        algodUrl: String,
        appCallArgs: List<ByteArray>,
        appCallBoxReferences: List<AppBoxReference>,
        appCallForeignAssets: List<Long>,
        depositAmountMicroUsdc: Long,
    ): String {
        require(depositAmountMicroUsdc > 0L) { "depositAmountMicroUsdc must be > 0" }

        val client = algodClient(algodUrl)
        val params = client.TransactionParams().execute().body()

        val assetTransferTxn =
            Transaction
                .AssetTransferTransactionBuilder()
                .sender(Address(signer.address))
                .assetReceiver(applicationAddress())
                .assetAmount(depositAmountMicroUsdc)
                .assetIndex(usdcAssetId)
                .suggestedParams(params)
                .build()

        val appCallTxn =
            buildAppCallTxn(
                signer = signer,
                params = params,
                args = appCallArgs,
                boxReferences = appCallBoxReferences,
                foreignAssets = appCallForeignAssets,
            )

        val useFalconBundleSigner = signer.signerType == SIGNER_TYPE_FALCON_TXN_AUTH
        val dummyTxns =
            if (useFalconBundleSigner) {
                List(2 * DUMMIES_PER_REAL_TXN) {
                    buildFalconBudgetDummyTxn(signer, params, it)
                }
            } else {
                emptyList()
            }

        if (dummyTxns.isNotEmpty()) {
            val extraPooledFee = MIN_TXN_FEE_MICROALGOS * dummyTxns.size
            val baseFee = assetTransferTxn.fee?.toLong() ?: MIN_TXN_FEE_MICROALGOS
            assetTransferTxn.fee = BigInteger.valueOf(baseFee + extraPooledFee)
        }

        val txnsToSign = dummyTxns + assetTransferTxn + appCallTxn

        TxGroup.assignGroupID(*txnsToSign.toTypedArray())

        val preSignFirstGroup = txnsToSign.firstOrNull()?.group?.toString()
        Log.e(
            tag,
            "[SESSION_VAULT_OPEN_TOPUP_GROUP_PRE_SIGN] sender=${signer.address} appId=$appId useFalconBundleSigner=$useFalconBundleSigner txCount=${txnsToSign.size} firstGroup=$preSignFirstGroup falconDummyCount=${dummyTxns.size}",
        )

        val signedGroup = signer.signTransactions(txnsToSign)
        val minExpectedSignedSize = txnsToSign.size
        if (useFalconBundleSigner) {
            require(signedGroup.size >= minExpectedSignedSize) {
                "Expected >=$minExpectedSignedSize signed txns for Falcon bundle, got ${signedGroup.size}"
            }
        } else {
            require(signedGroup.size == minExpectedSignedSize) {
                "Expected $minExpectedSignedSize signed txns, got ${signedGroup.size}"
            }
        }

        Log.e(
            tag,
            "[SESSION_VAULT_OPEN_TOPUP_GROUP_SIGNED] sender=${signer.address} appId=$appId usdcAssetId=$usdcAssetId signedGroupSize=${signedGroup.size} expectedGroupSize=$minExpectedSignedSize falconBundleMode=$useFalconBundleSigner falconDummyCount=${dummyTxns.size} axferSignedLen=${signedGroup.first().size} appCallSignedLen=${signedGroup.last().size}",
        )

        return broadcast(client, signedGroup) ?: appCallTxn.txID()
    }

    private fun buildAppCallTxn(
        signer: MppWalletSigner,
        params: com.algorand.algosdk.v2.client.model.TransactionParametersResponse,
        args: List<ByteArray>,
        boxReferences: List<AppBoxReference>,
        foreignAssets: List<Long>,
    ): Transaction {
        val builder =
            Transaction
                .ApplicationCallTransactionBuilder()
                .sender(signer.address)
                .suggestedParams(params)
                .applicationId(appId)
                .args(args)

        if (boxReferences.isNotEmpty()) {
            builder.boxReferences(boxReferences)
        }
        if (foreignAssets.isNotEmpty()) {
            builder.foreignAssets(foreignAssets)
        }

        return builder.build().also { it.fee = BigInteger.valueOf(APP_CALL_FEE) }
    }

    private fun buildFalconBudgetDummyTxn(
        signer: MppWalletSigner,
        params: com.algorand.algosdk.v2.client.model.TransactionParametersResponse,
        index: Int,
    ): Transaction {
        val falconLsigAddress = Address(Sdk.getFalconLsigAddress())

        val txn =
            Transaction
                .PaymentTransactionBuilder()
                .sender(falconLsigAddress)
                .receiver(falconLsigAddress)
                .amount(0)
                .suggestedParams(params)
                .note(byteArrayOf(index.toByte()))
                .build()
        txn.fee = BigInteger.ZERO
        return txn
    }

    private fun applicationAddress(): Address = Address.forApplication(appId)

    private fun decodeSessionInfoOffsets(bytes: ByteArray): SessionInfoOffsets {
        if (bytes.size < 98) error("Invalid session box payload size=${bytes.size}")

        val markerAt64 = ((bytes[64].toInt() and 0xFF) shl 8) or (bytes[65].toInt() and 0xFF)
        val arc4Offsets =
            SessionInfoOffsets(
                totalDepositOffset = 66,
                lastSettledOffset = 74,
                latestVoucherAmountOffset = 82,
            )

        if (isPlausibleSessionLayout(bytes, arc4Offsets)) {
            return arc4Offsets
        }

        val signerLen = markerAt64
        val totalsOffset = 66 + signerLen
        val legacyFits = bytes.size >= totalsOffset + (8 * 3)
        if (legacyFits) {
            val legacyOffsets =
                SessionInfoOffsets(
                    totalDepositOffset = totalsOffset,
                    lastSettledOffset = totalsOffset + 8,
                    latestVoucherAmountOffset = totalsOffset + 16,
                )
            if (isPlausibleSessionLayout(bytes, legacyOffsets)) {
                return legacyOffsets
            }
            return legacyOffsets
        }

        if (markerAt64 >= 98 && markerAt64 <= bytes.size) {
            return arc4Offsets
        }

        error("Invalid session box payload (markerAt64=$markerAt64 size=${bytes.size})")
    }

    private fun isPlausibleSessionLayout(
        bytes: ByteArray,
        offsets: SessionInfoOffsets,
    ): Boolean {
        val latestEnd = offsets.latestVoucherAmountOffset + 8
        if (latestEnd > bytes.size) return false

        return runCatching {
            val totalDeposit = decodeUint64BigEndian(bytes, offsets.totalDepositOffset)
            val lastSettled = decodeUint64BigEndian(bytes, offsets.lastSettledOffset)
            val latestVoucherAmount = decodeUint64BigEndian(bytes, offsets.latestVoucherAmountOffset)
            totalDeposit >= lastSettled && totalDeposit >= latestVoucherAmount && latestVoucherAmount >= lastSettled
        }.getOrDefault(false)
    }

    private fun decodeUint64BigEndian(
        bytes: ByteArray,
        offset: Int,
    ): Long {
        var out = 0L
        for (i in 0 until 8) {
            out = (out shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return out
    }

    private fun getSessionBoxBytes(
        channelId: ByteArray,
        algodUrl: String,
    ): ByteArray {
        val client = algodClient(algodUrl)
        val boxNameB64 =
            com.algorand.algosdk.util.Encoder
                .encodeToBase64(channelId)
        val response =
            client
                .GetApplicationBoxByName(appId)
                .name("b64:$boxNameB64")
                .execute()

        if (!response.isSuccessful) {
            val err = response.message() ?: "box fetch failed"
            error("EscrowSessionVaultManager box fetch failed: $err")
        }

        return response.body()?.value ?: error("EscrowSessionVaultManager empty box value")
    }

    private fun encodeUint64(value: Long): ByteArray =
        ByteBuffer
            .allocate(8)
            .putLong(value)
            .array()

    private fun encodeArc4DynamicBytes(bytes: ByteArray): ByteArray {
        require(bytes.size <= 0xFFFF) { "byte[] too long for ARC4 dynamic bytes" }
        return byteArrayOf(
            ((bytes.size ushr 8) and 0xFF).toByte(),
            (bytes.size and 0xFF).toByte(),
        ) + bytes
    }

    private fun normalizeSignerType(rawSignerType: Long): Long =
        when (rawSignerType) {
            SIGNER_TYPE_FALCON_TXN_AUTH -> SIGNER_TYPE_FALCON_TXN_AUTH
            else -> SIGNER_TYPE_ED25519
        }

    private fun algodClient(url: String): AlgodClient {
        val clean = url.removeSuffix("/")
        val uri = java.net.URI(clean)
        val host = uri.host ?: error("Invalid algod host: $url")
        val scheme = uri.scheme ?: "https"
        val port =
            if (uri.port == -1) {
                if (scheme == "https") 443 else 80
            } else {
                uri.port
            }
        return AlgodClient("$scheme://$host", port, "")
    }

    private fun broadcast(
        client: AlgodClient,
        signedBlobs: List<ByteArray>,
    ): String? {
        val concatenated = signedBlobs.fold(ByteArray(0)) { acc, b -> acc + b }
        val response: Response<PostTransactionsResponse> = client.RawTransaction().rawtxn(concatenated).execute()
        if (!response.isSuccessful) {
            val err = response.message() ?: "algod rejected transaction"
            error("EscrowSessionVaultManager broadcast failed: $err")
        }
        return response.body()?.txId
    }

    @Synchronized
    private fun ensureBouncyCastleProvider() {
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }
}
