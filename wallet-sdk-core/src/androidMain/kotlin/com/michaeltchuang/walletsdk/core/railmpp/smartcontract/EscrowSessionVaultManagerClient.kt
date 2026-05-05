package com.michaeltchuang.walletsdk.core.railmpp.smartcontract

import com.algorand.algosdk.crypto.Address
import com.algorand.algosdk.transaction.AppBoxReference
import com.algorand.algosdk.transaction.Transaction
import com.algorand.algosdk.v2.client.common.AlgodClient
import com.algorand.algosdk.v2.client.common.Response
import com.algorand.algosdk.v2.client.model.PostTransactionsResponse
import com.michaeltchuang.walletsdk.core.deeplink.utils.AssetConstants
import com.michaeltchuang.walletsdk.core.railmpp.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.utils.RailMppConstants
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
    companion object {
        const val SIGNER_TYPE_ED25519 = 0L
        const val SIGNER_TYPE_FALCON_TXN_AUTH = 1L

        private const val APP_CALL_FEE = 12_000L
        private const val DEFAULT_ALGOD_URL = "https://testnet-api.algonode.cloud"
        private val DEFAULT_CHANNEL_SALT = "walletsdk-session-v1".toByteArray(StandardCharsets.UTF_8)

        private val ABI_OPEN = byteArrayOf(0xab.toByte(), 0xb1.toByte(), 0x00, 0xf2.toByte())
        private val ABI_TOP_UP = byteArrayOf(0x1f, 0xd4.toByte(), 0xeb.toByte(), 0xc2.toByte())
        private val ABI_UPDATE_VOUCHER = byteArrayOf(0xa9.toByte(), 0x8d.toByte(), 0x82.toByte(), 0xda.toByte())
        private val ABI_SETTLE = byteArrayOf(0xf7.toByte(), 0xdf.toByte(), 0x8d.toByte(), 0xe2.toByte())
        private val ABI_VERIFY_SETTLE_SIGNATURE = byteArrayOf(0x27, 0x04, 0x92.toByte(), 0x89.toByte())
    }

    suspend fun openAndDeposit(
        signer: MppWalletSigner,
        payeeAddress: String,
        depositMicroUsdc: Long,
        authorizedSignerAddress: String = signer.address,
        signerType: Long = signer.signerType,
        algodUrl: String = defaultAlgodUrl,
    ): Result<String> =
        runCatching {
            ensureBouncyCastleProvider()
            val normalizedSignerType = normalizeSignerType(signerType)
            val channelId =
                deriveChannelId(
                    payerAddress = signer.address,
                    payeeAddress = payeeAddress,
                    authorizedSignerAddress = authorizedSignerAddress,
                    salt = defaultSalt,
                )
            val client = algodClient(algodUrl)
            val params = client.TransactionParams().execute().body()

            val appCallTxn =
                Transaction
                    .ApplicationCallTransactionBuilder()
                    .sender(signer.address)
                    .suggestedParams(params)
                    .applicationId(appId)
                    .args(
                        listOf(
                            ABI_OPEN,
                            Address(payeeAddress).getBytes(),
                            encodeUint64(depositMicroUsdc),
                            encodeArc4DynamicBytes(defaultSalt),
                            Address(authorizedSignerAddress).getBytes(),
                            encodeUint64(normalizedSignerType),
                        ),
                    ).foreignAssets(listOf(usdcAssetId))
                    .boxReferences(listOf(AppBoxReference(appId, channelId)))
                    .build()

            appCallTxn.fee = BigInteger.valueOf(APP_CALL_FEE)
            val signed = signer.signTransaction(appCallTxn)
            broadcast(client, listOf(signed)) ?: appCallTxn.txID()
        }

    suspend fun topUp(
        signer: MppWalletSigner,
        channelId: ByteArray,
        additionalDepositMicroUsdc: Long,
        algodUrl: String = defaultAlgodUrl,
    ): Result<String> =
        runCatching {
            val client = algodClient(algodUrl)
            val params = client.TransactionParams().execute().body()
            val appCallTxn =
                Transaction
                    .ApplicationCallTransactionBuilder()
                    .sender(signer.address)
                    .suggestedParams(params)
                    .applicationId(appId)
                    .args(
                        listOf(
                            ABI_TOP_UP,
                            encodeArc4DynamicBytes(channelId),
                            encodeUint64(additionalDepositMicroUsdc),
                        ),
                    ).boxReferences(listOf(AppBoxReference(appId, channelId)))
                    .build()

            appCallTxn.fee = BigInteger.valueOf(APP_CALL_FEE)
            val signed = signer.signTransaction(appCallTxn)
            broadcast(client, listOf(signed)) ?: appCallTxn.txID()
        }

    suspend fun updateVoucher(
        signer: MppWalletSigner,
        channelId: ByteArray,
        cumulativeAmountMicroUsdc: Long,
        signature: ByteArray,
        algodUrl: String = defaultAlgodUrl,
    ): Result<String> =
        runCatching {
            val client = algodClient(algodUrl)
            val params = client.TransactionParams().execute().body()
            val appCallTxn =
                Transaction
                    .ApplicationCallTransactionBuilder()
                    .sender(signer.address)
                    .suggestedParams(params)
                    .applicationId(appId)
                    .args(
                        listOf(
                            ABI_UPDATE_VOUCHER,
                            encodeArc4DynamicBytes(channelId),
                            encodeUint64(cumulativeAmountMicroUsdc),
                            encodeArc4DynamicBytes(signature),
                        ),
                    ).boxReferences(listOf(AppBoxReference(appId, channelId)))
                    .build()

            appCallTxn.fee = BigInteger.valueOf(APP_CALL_FEE)
            val signed = signer.signTransaction(appCallTxn)
            broadcast(client, listOf(signed)) ?: appCallTxn.txID()
        }

    suspend fun settle(
        signer: MppWalletSigner,
        channelId: ByteArray,
        cumulativeAmountMicroUsdc: Long,
        signature: ByteArray,
        algodUrl: String = defaultAlgodUrl,
    ): Result<String> =
        runCatching {
            val client = algodClient(algodUrl)
            val params = client.TransactionParams().execute().body()
            val appCallTxn =
                Transaction
                    .ApplicationCallTransactionBuilder()
                    .sender(signer.address)
                    .suggestedParams(params)
                    .applicationId(appId)
                    .args(
                        listOf(
                            ABI_SETTLE,
                            encodeArc4DynamicBytes(channelId),
                            encodeUint64(cumulativeAmountMicroUsdc),
                            encodeArc4DynamicBytes(signature),
                        ),
                    ).boxReferences(listOf(AppBoxReference(appId, channelId)))
                    .build()

            appCallTxn.fee = BigInteger.valueOf(APP_CALL_FEE)
            val signed = signer.signTransaction(appCallTxn)
            broadcast(client, listOf(signed)) ?: appCallTxn.txID()
        }

    suspend fun verifySettleSignatureOnChain(
        signer: MppWalletSigner,
        channelId: ByteArray,
        cumulativeAmountMicroUsdc: Long,
        signature: ByteArray,
        algodUrl: String = defaultAlgodUrl,
    ): Result<String> =
        runCatching {
            val client = algodClient(algodUrl)
            val params = client.TransactionParams().execute().body()
            val appCallTxn =
                Transaction
                    .ApplicationCallTransactionBuilder()
                    .sender(signer.address)
                    .suggestedParams(params)
                    .applicationId(appId)
                    .args(
                        listOf(
                            ABI_VERIFY_SETTLE_SIGNATURE,
                            encodeArc4DynamicBytes(channelId),
                            encodeUint64(cumulativeAmountMicroUsdc),
                            encodeArc4DynamicBytes(signature),
                        ),
                    ).boxReferences(listOf(AppBoxReference(appId, channelId)))
                    .build()

            appCallTxn.fee = BigInteger.valueOf(APP_CALL_FEE)
            val signed = signer.signTransaction(appCallTxn)
            broadcast(client, listOf(signed)) ?: appCallTxn.txID()
        }

    fun deriveChannelId(
        payerAddress: String,
        payeeAddress: String,
        authorizedSignerAddress: String,
        salt: ByteArray = defaultSalt,
    ): ByteArray {
        ensureBouncyCastleProvider()
        val payer = Address(payerAddress).getBytes()
        val payee = Address(payeeAddress).getBytes()
        val authorizedSigner = Address(authorizedSignerAddress).getBytes()
        val material = payer + payee + encodeUint64(usdcAssetId) + salt + authorizedSigner
        return MessageDigest.getInstance("SHA-256").digest(material)
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
        if (Security.getProvider("BC") == null) {
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }
}
