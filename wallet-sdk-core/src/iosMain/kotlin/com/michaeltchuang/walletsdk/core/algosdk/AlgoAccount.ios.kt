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
import com.michaeltchuang.walletsdk.core.foundation.utils.SuggestedParams
import com.michaeltchuang.walletsdk.core.transaction.model.OfflineKeyRegTransactionPayload
import com.michaeltchuang.walletsdk.core.transaction.model.OnlineKeyRegTransactionPayload
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

        val derivedAddress = bridge.generateAddressFromPublicKeyWithPublicKey(derivedPublicKey)

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
        val transactionData = transactionByteArray.toNSData()
        val publicKeyBase64 = publicKey.toNSData().base64EncodedStringWithOptions(0u)
        val privateKeyBase64 = privateKey.toNSData().base64EncodedStringWithOptions(0u)

        val signedData =
            bridge.signFalconTransactionWithTransactionBytes(
                transactionBytes = transactionData,
                publicKeyBase64 = publicKeyBase64,
                privateKeyBase64 = privateKeyBase64,
            )

        signedData?.toByteArray()
    } catch (e: Exception) {
        println("Falcon transaction signing failed: ${e.message}")
        null
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
        println("Algo25 transaction signing failed: ${e.message}")
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

    val addressString = payload.senderAddress

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

actual fun getReceiverMinBalanceFee(
    receiverAlgoAmount: String,
    receiverMinBalanceAmount: String,
): Long = Long.MAX_VALUE

@OptIn(ExperimentalForeignApi::class)
actual fun makeAssetTransferTxn(
    senderAddress: String,
    receiverAddress: String,
    amount: String,
    assetId: Long,
    noteInByteArray: ByteArray?,
    suggestedParams: SuggestedParams,
): ByteArray {
    val noteBase64 = noteInByteArray?.toNSData()?.base64EncodedStringWithOptions(0.toULong())

    val encodedTx =
        bridge.makeAssetTransferTxnWithSenderAddress(
            senderAddress = senderAddress,
            receiverAddress = receiverAddress,
            amount = amount,
            assetId = assetId,
            noteBase64 = noteBase64,
            fee = suggestedParams.fee,
            flatFee = suggestedParams.flatFee,
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
): ByteArray {
    val noteBase64 = noteInByteArray?.toNSData()?.base64EncodedStringWithOptions(0.toULong())

    val encodedTx =
        bridge.makePaymentTxnWithSenderAddress(
            senderAddress = senderAddress,
            receiverAddress = receiverAddress,
            amount = amount,
            isMax = isMax,
            noteBase64 = noteBase64,
            fee = suggestedParams.fee,
            flatFee = suggestedParams.flatFee,
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
): ByteArray {
    val encodedTx =
        bridge.makeAssetAcceptanceTxnWithPublicKey(
            publicKey = publicKey,
            assetId = assetId,
            fee = suggestedParams.fee,
            flatFee = suggestedParams.flatFee,
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
): ByteArray? =
    try {
        val seedBase64 = seed.toNSData().base64EncodedStringWithOptions(0.toULong())
        val dataBase64 = data.toNSData().base64EncodedStringWithOptions(0.toULong())

       /* val signedDataBase64 =
            bridge.signHdArbitraryDataWithSeedBase64(
                seedBase64 = seedBase64,
                account = account.toLong(),
                change = change.toLong(),
                key = key.toLong(),
                dataBase64 = dataBase64,
            )*/

        Base64.decode("signedDataBase64")
    } catch (e: Exception) {
        println("HD arbitrary data signing failed: ${e.message}")
        null
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

        /*val signedDataBase64 =
            bridge.signFalconArbitraryDataWithPublicKeyBase64(
                dataBase64 = dataBase64,
                publicKeyBase64 = publicKeyBase64,
                privateKeyBase64 = privateKeyBase64,
            )*/

        Base64.decode("")
    } catch (e: Exception) {
        println("Falcon24 arbitrary data signing failed: ${e.message}")
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
        val result = Base64.decode(signedDataBase64)

        result
    } catch (e: Exception) {
        println("Algo25 arbitrary data signing failed: ${e.message}")
        null
    }

actual fun signHdKeyData(
    data: ByteArray,
    seed: ByteArray,
    account: Int,
    change: Int,
    key: Int,
): ByteArray? {
    TODO("Not yet implemented")
}
