package com.michaeltchuang.walletsdk.core.algosdk

import AlgorandIosSdk.spmAlgoApiBridge
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.Bip39Entropy
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.Bip39Mnemonic
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.Bip39Seed
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.Falcon24
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.HdKeyAddress
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.HdKeyAddressDerivationType
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.HdKeyAddressIndex
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.HdKeyAddressLite
import com.michaeltchuang.walletsdk.core.algosdk.bip39.sdk.Bip39Wallet
import com.michaeltchuang.walletsdk.core.algosdk.domain.model.Algo25Account
import com.michaeltchuang.walletsdk.core.algosdk.domain.model.Falcon25Account
import com.michaeltchuang.walletsdk.core.foundation.utils.getMinimumFee
import com.michaeltchuang.walletsdk.core.network.model.TransactionParams
import com.michaeltchuang.walletsdk.core.transaction.model.OfflineKeyRegTransactionPayload
import com.michaeltchuang.walletsdk.core.transaction.model.OnlineKeyRegTransactionPayload
import io.github.aakira.napier.Napier
import io.ktor.util.decodeBase64Bytes
import io.ktor.utils.io.core.toByteArray
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create
import platform.posix.memcpy
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

const val ROUND_THRESHOLD = 1000L

@OptIn(ExperimentalForeignApi::class)
internal actual fun deriveBip39Seed(mnemonic: String): ByteArray = bridge.xhdSeedFromMnemonicWithMnemonic(mnemonic).toByteArray()

@OptIn(ExperimentalForeignApi::class)
internal actual fun ByteArray.sha256(): ByteArray =
    bridge
        .sha256WithDataBase64(
            this.toNSData().base64EncodedStringWithOptions(0.toULong()),
        ).fromBase64ToByteArray()

actual fun TransactionParams.toSuggestedParams(addGenesisId: Boolean): SuggestedParams =
    SuggestedParams(
        fee = fee.takeIf { it > 0L } ?: getMinimumFee(),
        genesisID = if (addGenesisId) genesisId else "",
        firstRoundValid = lastRound,
        lastRoundValid = lastRound + ROUND_THRESHOLD,
        genesisHash =
            try {
                genesisHash.decodeBase64Bytes()
            } catch (e: Exception) {
                println("Error decoding genesis hash: ${e.message}")
                ByteArray(0)
            },
        flatFee = fee <= 0L,
    )

@OptIn(ExperimentalForeignApi::class)
private val bridge = spmAlgoApiBridge()

@OptIn(ExperimentalForeignApi::class)
fun ByteArray.toNSData(): NSData {
    if (this.isEmpty()) {
        return NSData()
    }

    // Create NSData with copied bytes
    return this.usePinned { pinned ->
        NSData.create(
            bytes = pinned.addressOf(0),
            length = this.size.toULong(),
        ) ?: NSData()
    }
}

@OptIn(ExperimentalForeignApi::class)
fun NSData.toByteArray(): ByteArray {
    val length = this.length.toInt()
    if (length == 0) {
        return ByteArray(0)
    }

    return ByteArray(length).apply {
        usePinned { pinned ->
            memcpy(pinned.addressOf(0), this@toByteArray.bytes, this@toByteArray.length)
        }
    }
}

private fun String.fromBase64ToByteArray(): ByteArray =
    try {
        this.decodeBase64Bytes()
    } catch (e: Exception) {
        ByteArray(0)
    }

@OptIn(ExperimentalForeignApi::class)
actual fun recoverAlgo25Account(mnemonic: String): Algo25Account? {
    val secretKey =
        bridge.getAlgo25SecretKeyWithMnemonic(
            mnemonic = mnemonic,
        )
    val address =
        bridge.generateAddressFromSKWithSecretKey(
            secretKey = secretKey,
        )
    return Algo25Account(address, secretKey.fromBase64ToByteArray())
}

@OptIn(ExperimentalForeignApi::class)
actual fun createFalcon25Account(): Falcon25Account? =
    try {
        val entropy = createAlgo25Account()?.secretKey?.copyOfRange(0, 32) ?: return null
        val mnemonic = getFalcon25MnemonicFromEntropy(entropy) ?: return null
        deriveFalcon25Account(mnemonic, entropy)
    } catch (_: Exception) {
        null
    }

@OptIn(ExperimentalForeignApi::class)
actual fun recoverFalcon25Account(mnemonic: String): Falcon25Account? =
    try {
        val entropy = getFalcon25EntropyFromMnemonic(mnemonic)
        deriveFalcon25Account(mnemonic, entropy)
    } catch (_: Exception) {
        null
    }

@OptIn(ExperimentalForeignApi::class)
actual fun getFalcon25EntropyFromMnemonic(mnemonic: String): ByteArray =
    bridge.getFalcon25EntropyFromMnemonicWithMnemonic(mnemonic)?.toByteArray()
        ?: throw IllegalArgumentException("Invalid Falcon25 mnemonic")

@OptIn(ExperimentalForeignApi::class)
private fun deriveFalcon25Account(
    mnemonic: String,
    entropy: ByteArray,
): Falcon25Account? =
    try {
        Falcon25Account(
            address = bridge.getFalconAddressFromMnemonicWithMnemonic(mnemonic),
            publicKey = bridge.getFalconPublicKeyFromMnemonicWithMnemonic(mnemonic).fromBase64ToByteArray(),
            privateKey = bridge.getFalconPrivateKeyFromMnemonicWithMnemonic(mnemonic).fromBase64ToByteArray(),
            entropy = entropy,
            seed = getFalcon25SeedFromEntropy(entropy) ?: ByteArray(0),
        )
    } catch (_: Exception) {
        null
    }

@OptIn(ExperimentalForeignApi::class)
actual fun getFalcon25MnemonicFromEntropy(entropy: ByteArray): String? = bridge.getFalconMnemonicFromEntropyWithEntropy(entropy.toNSData())

@OptIn(ExperimentalForeignApi::class)
actual fun getFalcon25SeedFromEntropy(entropy: ByteArray): ByteArray? =
    bridge.getFalconSeedFromEntropyWithEntropy(entropy.toNSData())?.toByteArray()

@OptIn(ExperimentalForeignApi::class)
actual fun createAlgo25Account(): Algo25Account? {
    val secretKey =
        bridge.getAlgo25SecretKeyWithMnemonic(
            mnemonic = null,
        )
    val address =
        bridge.generateAddressFromSKWithSecretKey(
            secretKey = secretKey,
        )
    return Algo25Account(address, secretKey.fromBase64ToByteArray())
}

@OptIn(ExperimentalForeignApi::class)
actual fun isValidAlgorandAddress(accountAddress: String): Boolean = bridge.isValidAlgorandAddressWithAddress(accountAddress)

@OptIn(ExperimentalForeignApi::class)
actual fun getMnemonicFromAlgo25SecretKey(secretKey: ByteArray): String? {
    var mnemonic: String? = null
    try {
        mnemonic =
            bridge.getAlgo25MnemonicFromSecretKeyWithSecretKey(secretKey.toNSData())
    } catch (e: Exception) {
        println("Failed to generate mnemonic: ${e.message}")
    }
    return mnemonic
}

actual fun getBip39Wallet(entropy: ByteArray): Bip39Wallet = getBit39Wallet(entropy)

actual fun createBip39Wallet(): Bip39Wallet =
    getBit39Wallet(
        AlgoKitBip39.getEntropyFromMnemonic(
            AlgoKitBip39.generate24WordMnemonic(),
        ),
    )

actual fun getSeedFromEntropy(entropy: ByteArray): ByteArray? = AlgoKitBip39.getSeedFromEntropy(entropy)

@OptIn(ExperimentalForeignApi::class)
actual fun signHdKeyTransaction(
    transactionByteArray: ByteArray,
    seed: ByteArray,
    account: Int,
    change: Int,
    key: Int,
): ByteArray? {
    return try {
        val seedBase64 = seed.toNSData().base64EncodedStringWithOptions(0.toULong())

        val derivedPublicKey =
            bridge.getHdPublicKeyFromSeedWithSeedBase64(
                seedBase64 = seedBase64,
                account = account.toLong(),
                change = change.toLong(),
                keyIndex = key.toLong(),
            )

        val transactionData = transactionByteArray.toNSData()

        val signedData =
            bridge.signHdKeyTransactionWithTransactionBytes(
                transactionBytes = transactionData,
                seedBase64 = seedBase64,
                account = account.toLong(),
                change = change.toLong(),
                keyIndex = key.toLong(),
            )

        if (signedData == null) {
            println("ERROR: Transaction signing failed")
            return null
        }

        signedData.toByteArray()
    } catch (e: Exception) {
        println("ERROR: Transaction signing failed: ${e.message}")
        null
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun signFalcon24Transaction(
    transactionByteArray: ByteArray,
    publicKey: ByteArray,
    privateKey: ByteArray,
): ByteArray? =
    try {
        bridge
            .signFalcon24TransactionWithTransactionBytes(
                transactionBytes = transactionByteArray.toNSData(),
                publicKeyBase64 = publicKey.toNSData().base64EncodedStringWithOptions(0u),
                privateKeyBase64 = privateKey.toNSData().base64EncodedStringWithOptions(0u),
            )?.toByteArray()
    } catch (e: Exception) {
        println("Falcon24 transaction signing failed: ${e.message}")
        null
    }

@OptIn(ExperimentalForeignApi::class)
actual fun signFalcon25Transaction(
    transactionByteArray: ByteArray,
    seed: ByteArray,
): ByteArray? =
    try {
        bridge
            .signFalcon25TransactionWithTransactionBytes(
                transactionBytes = transactionByteArray.toNSData(),
                seed = seed.toNSData(),
            )?.toByteArray()
    } catch (e: Exception) {
        println("Falcon25 transaction signing failed: ${e.message}")
        null
    }

@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
actual fun signFalcon24GroupBundle(
    txnsByteArrays: List<ByteArray>,
    publicKey: ByteArray,
    privateKey: ByteArray,
): List<ByteArray> =
    try {
        val publicKeyBase64 = publicKey.toNSData().base64EncodedStringWithOptions(0u)
        val privateKeyBase64 = privateKey.toNSData().base64EncodedStringWithOptions(0u)
        val txnsBase64 = txnsByteArrays.map { it.toNSData().base64EncodedStringWithOptions(0u) }

        val signedB64List =
            bridge.signFalconGroupBundleWithTxnsBase64(
                txnsBase64 = txnsBase64,
                publicKeyBase64 = publicKeyBase64,
                privateKeyBase64 = privateKeyBase64,
            )

        signedB64List.mapNotNull { b64Item: Any? ->
            val b64 = b64Item?.toString() ?: return@mapNotNull null
            if (b64.isEmpty()) return@mapNotNull null
            try {
                // Normalise URL-safe base64 → standard base64 with padding before decoding.
                val standard = b64.replace('-', '+').replace('_', '/')
                val pad = (4 - standard.length % 4) % 4
                val padded = if (pad > 0) standard + "=".repeat(pad) else standard
                Base64.decode(padded)
            } catch (ignored: Exception) {
                null
            }
        }
    } catch (e: Exception) {
        println("Falcon24 group bundle signing failed: ${e.message}")
        emptyList<ByteArray>()
    }

@OptIn(ExperimentalEncodingApi::class, ExperimentalForeignApi::class)
actual fun signAlgo25Transaction(
    secretKey: ByteArray,
    transactionByteArray: ByteArray,
): ByteArray =
    try {
        val secretKeyBase64 = Base64.encode(secretKey)
        val transactionBase64 = Base64.encode(transactionByteArray)

        val signedDataBase64 =
            bridge.signAlgo25TransactionWithBase64WithSkBase64(
                skBase64 = secretKeyBase64,
                encodedTxBase64 = transactionBase64,
            )

        val result = Base64.decode(signedDataBase64)
        result
    } catch (e: Exception) {
        Napier.e("Algo25 transaction signing failed: ${e.message}", tag = "Algo25Sign")
        ByteArray(0)
    }

@OptIn(ExperimentalForeignApi::class)
actual fun createTransaction(payload: OfflineKeyRegTransactionPayload): ByteArray {
    val firstRound = payload.txnParams.lastRound
    val lastRound = payload.txnParams.lastRound + ROUND_THRESHOLD

    val fee =
        payload.flatFee
            ?.toString()
            ?.toLong()
            ?.toULong() ?: payload.txnParams.fee.toULong()
    val flatFeeEnabled = payload.flatFee != null

    val encodedTx =
        bridge.createOfflineKeyRegTransactionWithSenderAddress(
            senderAddress = payload.senderAddress,
            noteBase64 = payload.note,
            fee = fee,
            flatFee = flatFeeEnabled,
            firstRound = firstRound.toULong(),
            lastRound = lastRound.toULong(),
            genesisHashBase64 = payload.txnParams.genesisHash,
            genesisID = payload.txnParams.genesisId,
        )

    if (encodedTx.length == 0UL) {
        println("Failed to create offline key registration transaction")
        return ByteArray(0)
    }

    return encodedTx.toByteArray()
}

@OptIn(ExperimentalForeignApi::class)
actual fun createTransaction(payload: OnlineKeyRegTransactionPayload): ByteArray {
    val firstRound = payload.txnParams.lastRound
    val lastRound = payload.txnParams.lastRound + ROUND_THRESHOLD

    val fee =
        payload.flatFee
            ?.toString()
            ?.toLong()
            ?.toULong() ?: payload.txnParams.fee.toULong()
    val flatFeeEnabled = payload.flatFee != null

    val encodedTx =
        bridge.createOnlineKeyRegTransactionWithSenderAddress(
            senderAddress = payload.senderAddress,
            noteBase64 = payload.note,
            fee = fee,
            flatFee = flatFeeEnabled,
            firstRound = firstRound.toULong(),
            lastRound = lastRound.toULong(),
            genesisHashBase64 = payload.txnParams.genesisHash,
            genesisID = payload.txnParams.genesisId,
            voteKeyBase64 = payload.voteKey,
            selectionKeyBase64 = payload.selectionPublicKey,
            stateProofKeyBase64 = payload.stateProofKey,
            voteFirstRound = payload.voteFirstRound.toULong(),
            voteLastRound = payload.voteLastRound.toULong(),
            voteKeyDilution = payload.voteKeyDilution.toULong(),
        )

    if (encodedTx.length == 0UL) {
        println("Failed to create online key registration transaction")
        return ByteArray(0)
    }

    return encodedTx.toByteArray()
}

@OptIn(ExperimentalForeignApi::class)
private fun getBit39Wallet(entropy: ByteArray): Bip39Wallet =
    object : Bip39Wallet {
        private val mnemonic: String by lazy {
            AlgoKitBip39.getMnemonicFromEntropy(entropy)
        }

        override fun getEntropy(): Bip39Entropy = Bip39Entropy(entropy.copyOf())

        override fun getSeed(): Bip39Seed = Bip39Seed(seed.copyOf())

        override fun getMnemonic(): Bip39Mnemonic = Bip39Mnemonic(mnemonic.split(" "))

        override fun generateAddress(index: HdKeyAddressIndex): HdKeyAddress {
            val seedBytes = AlgoKitBip39.getSeedFromEntropy(entropy)

            val seedBase64 = seedBytes.toNSData().base64EncodedStringWithOptions(0.toULong())

            val publicKey =
                bridge.getHdPublicKeyFromSeedWithSeedBase64(
                    seedBase64 = seedBase64,
                    account = index.accountIndex.toLong(),
                    change = index.changeIndex.toLong(),
                    keyIndex = index.keyIndex.toLong(),
                )

            val privateKey =
                bridge.getHdPrivateKeyFromSeedWithSeedBase64(
                    seedBase64 = seedBase64,
                    account = index.accountIndex.toLong(),
                    change = index.changeIndex.toLong(),
                    keyIndex = index.keyIndex.toLong(),
                )

            return HdKeyAddress(
                address = getAddressFromPublicKey(publicKey),
                index = index,
                privateKey = privateKey.toByteArray(),
                publicKey = publicKey.toByteArray(),
                derivationType = HdKeyAddressDerivationType.Peikert,
            )
        }

        override fun generateFalcon24Address(mnemonic: String): Falcon24 {
            val address = bridge.getFalconAddressFromMnemonicWithMnemonic(mnemonic)
            val publicKeyBase64 =
                bridge.getFalconPublicKeyFromMnemonicWithMnemonic(mnemonic)
            val privateKeyBase64 =
                bridge.getFalconPrivateKeyFromMnemonicWithMnemonic(mnemonic)

            return Falcon24(
                address = address,
                publicKey = publicKeyBase64.fromBase64ToByteArray(),
                privateKey = privateKeyBase64.fromBase64ToByteArray(),
            )
        }

        override fun invalidate() {}

        private val seed: ByteArray by lazy {
            val entropy = AlgoKitBip39.getEntropyFromMnemonic(mnemonic)
            AlgoKitBip39.getSeedFromEntropy(entropy)
        }

        override fun generateAddressLite(index: HdKeyAddressIndex): HdKeyAddressLite {
            val publicKey = generatePublicKey(index)
            return HdKeyAddressLite(
                address = getAddressFromPublicKey(publicKey),
                index = index,
            )
        }

        fun generatePublicKey(index: HdKeyAddressIndex): String {
            val seedBase64 = seed.toNSData().base64EncodedStringWithOptions(0.toULong())

            val publicKey =
                bridge.getHdPublicKeyFromSeedWithSeedBase64(
                    seedBase64 = seedBase64,
                    account = index.accountIndex.toLong(),
                    change = index.changeIndex.toLong(),
                    keyIndex = index.keyIndex.toLong(),
                )
            return publicKey
        }

        fun getAddressFromPublicKey(publicKey: String): String =
            bridge.generateAddressFromPublicKeyWithPublicKey(
                publicKey = publicKey,
            )
    }

@OptIn(ExperimentalForeignApi::class)
actual fun makeAssetTransferTxn(
    senderAddress: String,
    receiverAddress: String,
    amount: String,
    assetId: Long,
    noteInByteArray: ByteArray?,
    suggestedParams: SuggestedParams,
    staticFee: Long?,
): ByteArray {
    val noteBase64 = noteInByteArray?.toNSData()?.base64EncodedStringWithOptions(0.toULong())

    val encodedTx =
        bridge.makeAssetTransferTxnWithSenderAddress(
            senderAddress = senderAddress,
            receiverAddress = receiverAddress,
            amount = amount,
            assetId = assetId,
            noteBase64 = noteBase64,
            fee = staticFee ?: suggestedParams.fee,
            flatFee = staticFee != null || suggestedParams.flatFee,
            firstRound = suggestedParams.firstRoundValid,
            lastRound = suggestedParams.lastRoundValid,
            genesisHashBase64 =
                suggestedParams.genesisHash
                    .toNSData()
                    .base64EncodedStringWithOptions(0.toULong()),
            genesisID = suggestedParams.genesisID,
        )

    if (encodedTx.length == 0UL) {
        println("Failed to create asset transfer transaction")
        return ByteArray(0)
    }

    return encodedTx.toByteArray()
}

@OptIn(ExperimentalForeignApi::class)
actual fun makePaymentTxn(
    senderAddress: String,
    receiverAddress: String,
    amount: String,
    isMax: Boolean,
    noteInByteArray: ByteArray?,
    suggestedParams: SuggestedParams,
    staticFee: Long?,
): ByteArray {
    val noteBase64 = noteInByteArray?.toNSData()?.base64EncodedStringWithOptions(0.toULong())

    val encodedTx =
        bridge.makePaymentTxnWithSenderAddress(
            senderAddress = senderAddress,
            receiverAddress = receiverAddress,
            amount = amount,
            isMax = isMax,
            noteBase64 = noteBase64,
            fee = staticFee ?: suggestedParams.fee,
            flatFee = staticFee != null || suggestedParams.flatFee,
            firstRound = suggestedParams.firstRoundValid,
            lastRound = suggestedParams.lastRoundValid,
            genesisHashBase64 =
                suggestedParams.genesisHash
                    .toNSData()
                    .base64EncodedStringWithOptions(0.toULong()),
            genesisID = suggestedParams.genesisID,
        )

    if (encodedTx.length == 0UL) {
        println("Failed to create payment transaction")
        return ByteArray(0)
    }

    return encodedTx.toByteArray()
}

@OptIn(ExperimentalForeignApi::class)
actual fun makeAssetAcceptanceTxn(
    publicKey: String,
    assetId: Long,
    suggestedParams: SuggestedParams,
    staticFee: Long?,
): ByteArray {
    val encodedTx =
        bridge.makeAssetAcceptanceTxnWithPublicKey(
            publicKey = publicKey,
            assetId = assetId,
            fee = staticFee ?: suggestedParams.fee,
            flatFee = staticFee != null || suggestedParams.flatFee,
            firstRound = suggestedParams.firstRoundValid,
            lastRound = suggestedParams.lastRoundValid,
            genesisHashBase64 =
                suggestedParams.genesisHash
                    .toNSData()
                    .base64EncodedStringWithOptions(0.toULong()),
            genesisID = suggestedParams.genesisID,
        )

    if (encodedTx.length == 0UL) {
        println("Failed to create asset acceptance transaction")
        return ByteArray(0)
    }

    return encodedTx.toByteArray()
}

@OptIn(ExperimentalEncodingApi::class, ExperimentalForeignApi::class)
actual fun signHdKeyArbitraryData(
    data: ByteArray,
    seed: ByteArray,
    account: Int,
    change: Int,
    key: Int,
): ByteArray? {
    return try {
        // Convert seed and data to Base64 strings
        val seedBase64 = seed.toNSData().base64EncodedStringWithOptions(0.toULong())
        val dataBase64 = data.toNSData().base64EncodedStringWithOptions(0.toULong())

        // Call Swift bridge to sign with Ed25519 (using Peikert derivation)
        val signatureBase64 =
            bridge.signHdArbitraryDataWithSeedBase64WithSeedBase64(
                seedBase64 = seedBase64,
                account = account.toLong(),
                change = change.toLong(),
                keyIndex = key.toLong(),
                dataBase64 = dataBase64,
            )

        if (signatureBase64.isEmpty()) {
            Napier.e("HD Key signing returned empty string", tag = "HdKeySign")
            return null
        }

        // Decode Base64 signature to ByteArray
        Base64.decode(signatureBase64)
    } catch (e: Exception) {
        Napier.e("HD Key arbitrary data signing failed: ${e.message}", tag = "HdKeySign")
        null
    }
}

@OptIn(ExperimentalEncodingApi::class, ExperimentalForeignApi::class)
actual fun signFalcon24ArbitraryData(
    data: ByteArray,
    publicKey: ByteArray,
    privateKey: ByteArray,
): ByteArray? =
    try {
        val dataBase64 = data.toNSData().base64EncodedStringWithOptions(0.toULong())
        val publicKeyBase64 = publicKey.toNSData().base64EncodedStringWithOptions(0.toULong())
        val privateKeyBase64 = privateKey.toNSData().base64EncodedStringWithOptions(0.toULong())

        val signedDataBase64 =
            bridge.signFalcon24ArbitraryDataWithDataBase64(
                dataBase64 = dataBase64,
                publicKeyBase64 = publicKeyBase64,
                privateKeyBase64 = privateKeyBase64,
            )

        Base64.decode(signedDataBase64)
    } catch (e: Exception) {
        println("Falcon24 arbitrary data signing failed: ${e.message}")
        null
    }

@OptIn(ExperimentalEncodingApi::class, ExperimentalForeignApi::class)
actual fun signFalcon25ArbitraryData(
    data: ByteArray,
    publicKey: ByteArray,
    privateKey: ByteArray,
): ByteArray? =
    try {
        val signedDataBase64 =
            bridge.signFalcon25ArbitraryDataWithDataBase64(
                dataBase64 = data.toNSData().base64EncodedStringWithOptions(0.toULong()),
                publicKeyBase64 = publicKey.toNSData().base64EncodedStringWithOptions(0.toULong()),
                privateKeyBase64 = privateKey.toNSData().base64EncodedStringWithOptions(0.toULong()),
            )
        Base64.decode(signedDataBase64)
    } catch (e: Exception) {
        println("Falcon25 arbitrary data signing failed: ${e.message}")
        null
    }

@OptIn(ExperimentalEncodingApi::class, ExperimentalForeignApi::class)
actual fun signAlgo25ArbitraryData(
    data: ByteArray,
    secretKey: ByteArray,
): ByteArray? =
    try {
        val secretKeyBase64 = Base64.encode(secretKey)
        val dataBase64 = Base64.encode(data)

        val signedDataBase64 =
            bridge.signAlgo25ArbitraryDataWithBase64WithSkBase64(
                skBase64 = secretKeyBase64,
                dataBase64 = dataBase64,
            )

        Base64.decode(signedDataBase64)
    } catch (e: Exception) {
        Napier.e("Algo25 arbitrary data signing failed: ${e.message}", tag = "Algo25Sign")
        null
    }

actual fun signHdKeyData(
    data: ByteArray,
    seed: ByteArray,
    account: Int,
    change: Int,
    key: Int,
): ByteArray? {
    // This function is just an alias for signHdKeyArbitraryData
    // Both sign arbitrary data (not transactions) with HD keys
    return signHdKeyArbitraryData(data, seed, account, change, key)
}
