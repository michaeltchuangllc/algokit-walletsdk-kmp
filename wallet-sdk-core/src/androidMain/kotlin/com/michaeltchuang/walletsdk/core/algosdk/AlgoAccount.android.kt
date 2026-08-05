package com.michaeltchuang.walletsdk.core.algosdk

import android.util.Base64
import com.michaeltchuang.walletsdk.core.algosdk.bip39.sdk.AlgorandBip39WalletProvider
import com.michaeltchuang.walletsdk.core.algosdk.bip39.sdk.Bip39Wallet
import com.michaeltchuang.walletsdk.core.algosdk.domain.model.Algo25Account
import com.michaeltchuang.walletsdk.core.algosdk.domain.model.Falcon25Account
import com.michaeltchuang.walletsdk.core.utils.GoMobileDispatcher
import io.github.algorandecosystem.sdk.Sdk
import com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk.AlgoKitBip39SdkImpl
import com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk.SignFalcon24TransactionImpl
import com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk.SignFalcon25TransactionImpl
import com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk.SignHdKeyTransactionImpl
import com.michaeltchuang.walletsdk.core.encryption.domain.utils.clearFromMemory
import com.michaeltchuang.walletsdk.core.foundation.utils.getMinimumFee
import com.michaeltchuang.walletsdk.core.network.model.TransactionParams
import com.michaeltchuang.walletsdk.core.transaction.model.OfflineKeyRegTransactionPayload
import com.michaeltchuang.walletsdk.core.transaction.model.OnlineKeyRegTransactionPayload
import org.bouncycastle.jce.provider.BouncyCastleProvider
import uniffi.algokit_composer_ffi.AssetOptInParams
import uniffi.algokit_composer_ffi.AssetTransferParams
import uniffi.algokit_composer_ffi.CommonTxnParams
import uniffi.algokit_composer_ffi.ComposerParams
import uniffi.algokit_composer_ffi.OfflineKeyRegParams
import uniffi.algokit_composer_ffi.OnlineKeyRegParams
import uniffi.algokit_composer_ffi.PaymentParams
import uniffi.algokit_composer_ffi.TxnParams
import uniffi.algokit_composer_ffi.TxnParamsKind
import uniffi.algokit_composer_ffi.compose
import uniffi.algokit_crypto_ffi.secretKeyToMnemonic
import uniffi.algokit_crypto_ffi.seedFromMnemonic
import uniffi.algokit_crypto_ffi.xhdSeedFromMnemonic
import uniffi.algokit_crypto_ffi.ed25519PublicKeyFromSeed
import uniffi.algokit_crypto_ffi.ed25519RawSign
import uniffi.algokit_crypto_ffi.randomBytes
import uniffi.algokit_transact_ffi.addressFromPublicKey
import uniffi.algokit_transact_ffi.decodeTransaction
import uniffi.algokit_transact_ffi.ed25519SignTransaction
import uniffi.algokit_transact_ffi.encodeSignedTransaction
import uniffi.algokit_transact_ffi.publicKeyFromAddress
import java.security.Security

const val ROUND_THRESHOLD = 1000L

internal actual fun deriveBip39Seed(mnemonic: String): ByteArray = xhdSeedFromMnemonic(mnemonic)

internal actual fun ByteArray.sha256(): ByteArray = java.security.MessageDigest.getInstance("SHA-256").digest(this)

actual fun TransactionParams.toSuggestedParams(addGenesisId: Boolean): SuggestedParams =
    SuggestedParams(
        fee = fee.takeIf { it > 0L } ?: getMinimumFee(),
        genesisID = if (addGenesisId) genesisId else "",
        firstRoundValid = lastRound,
        lastRoundValid = lastRound + ROUND_THRESHOLD,
        genesisHash = Base64.decode(genesisHash, Base64.DEFAULT),
        flatFee = fee <= 0L,
    )

fun String.urlSafeBase64ToStandard(): String =
    this
        .replace('-', '+')
        .replace('_', '/')
        .let {
            // Add padding if needed (base64 strings should be multiples of 4)
            val padding = (4 - it.length % 4) % 4
            it + "=".repeat(padding)
        }

// Use rust library for account creation (generates random seed, derives public key and address)
actual fun createAlgo25Account(): Algo25Account? =
    try {
        // Generate cryptographically secure random 32-byte seed
        val seed = randomBytes(32u)

        // Derive the 32-byte public key from the seed using Ed25519
        val publicKey = ed25519PublicKeyFromSeed(seed)

        // Generate the Algorand address from the public key
        val address = addressFromPublicKey(publicKey)

        // Create the 64-byte Algorand secret key (seed + publicKey)
        val secretKey = seed.copyOf(64)
        System.arraycopy(publicKey, 0, secretKey, 32, 32)

        // Clear sensitive data
        seed.clearFromMemory()
        publicKey.clearFromMemory()

        Algo25Account(address = address, secretKey = secretKey)
    } catch (e: Exception) {
        null
    }

// Use rust library for account recovery (extracts seed, derives public key and address)
actual fun recoverAlgo25Account(mnemonic: String): Algo25Account? =
    try {
        // Extract the 32-byte seed from the mnemonic
        val seed = seedFromMnemonic(mnemonic)
        // Derive the 32-byte public key from the seed using Ed25519
        val publicKey = ed25519PublicKeyFromSeed(seed)

        // Generate the Algorand address from the public key
        val address = addressFromPublicKey(publicKey)

        // Create the 64-byte Algorand secret key (seed + publicKey)
        val secretKey = seed.copyOf(64)
        System.arraycopy(publicKey, 0, secretKey, 32, 32)

        // Clear sensitive data
        seed.clearFromMemory()
        publicKey.clearFromMemory()

        Algo25Account(address = address, secretKey = secretKey)
    } catch (e: Exception) {
        null
    }

actual fun createFalcon25Account(): Falcon25Account? =
    try {
        val entropy = createAlgo25Account()?.secretKey?.copyOfRange(0, 32) ?: return null
        val mnemonic = getFalcon25MnemonicFromEntropy(entropy) ?: return null
        deriveFalcon25Account(mnemonic, entropy)
    } catch (_: Exception) {
        null
    }

actual fun recoverFalcon25Account(mnemonic: String): Falcon25Account? =
    try {
        val entropy = AlgoKitBip39.getEntropyFromMnemonic(mnemonic)
        deriveFalcon25Account(mnemonic, entropy)
    } catch (_: Exception) {
        null
    }

private fun deriveFalcon25Account(mnemonic: String, entropy: ByteArray): Falcon25Account? =
    try {
        GoMobileDispatcher.runOnGoThread { Sdk.deriveFromMnemonic(mnemonic, "") }.let {
            Falcon25Account(
                address = it.algorandAddress,
                publicKey = it.publicKey,
                privateKey = it.privateKey,
                entropy = entropy,
                seed = getFalcon25SeedFromEntropy(entropy) ?: ByteArray(0),
            )
        }
    } catch (_: Exception) {
        null
    }

actual fun getFalcon25MnemonicFromEntropy(entropy: ByteArray): String? =
    try {
        GoMobileDispatcher.runOnGoThread {
            Sdk.mnemonicFromEntropy(entropy.copyOf())
        }
    } catch (_: Exception) {
        null
    }

actual fun getFalcon25SeedFromEntropy(entropy: ByteArray): ByteArray? =
    try {
        GoMobileDispatcher.runOnGoThread {
            Sdk.seedFromEntropy(entropy.copyOf(), "")
        }
    } catch (_: Exception) {
        null
    }

// Use rust library for address validation via public key conversion
actual fun isValidAlgorandAddress(accountAddress: String): Boolean =
    try {
        publicKeyFromAddress(accountAddress)
        true
    } catch (e: Exception) {
        false
    }

// Use rust library for mnemonic generation from secret key
actual fun getMnemonicFromAlgo25SecretKey(secretKey: ByteArray): String? =
    try {
        // The Rust library can extract the mnemonic from the full 64-byte secret key
        secretKeyToMnemonic(secretKey)
    } catch (e: Exception) {
        null
    }

actual fun createBip39Wallet(): Bip39Wallet = AlgorandBip39WalletProvider().createBip39Wallet()

actual fun getBip39Wallet(entropy: ByteArray): Bip39Wallet =
    AlgorandBip39WalletProvider().getBip39Wallet(entropy)

actual fun getSeedFromEntropy(entropy: ByteArray): ByteArray? =
    AlgoKitBip39SdkImpl().getSeedFromEntropy(entropy)

actual fun signHdKeyTransaction(
    transactionByteArray: ByteArray,
    seed: ByteArray,
    account: Int,
    change: Int,
    key: Int,
): ByteArray? {
    Security.removeProvider("BC")
    Security.insertProviderAt(BouncyCastleProvider(), 0)
    return SignHdKeyTransactionImpl().signTransaction(
        transactionByteArray,
        seed,
        account,
        change,
        key,
    )
}

/** Signs a group of Falcon24 LogicSig transactions on Android. */
actual fun signFalcon24GroupBundle(
    txnsByteArrays: List<ByteArray>,
    publicKey: ByteArray,
    privateKey: ByteArray,
): List<ByteArray> =
    txnsByteArrays.mapNotNull { txn ->
        signFalcon24Transaction(txn, publicKey, privateKey)
    }

actual fun signFalcon24Transaction(
    transactionByteArray: ByteArray,
    publicKey: ByteArray,
    privateKey: ByteArray,
): ByteArray? {
    Security.removeProvider("BC")
    Security.insertProviderAt(BouncyCastleProvider(), 0)
    return SignFalcon24TransactionImpl().signLogicSigTransaction(
        transactionByteArray,
        publicKey,
        privateKey,
    )
}

actual fun signFalcon25Transaction(
    transactionByteArray: ByteArray,
    seed: ByteArray,
): ByteArray? {
    Security.removeProvider("BC")
    Security.insertProviderAt(BouncyCastleProvider(), 0)
    return SignFalcon25TransactionImpl().signTransaction(
        transactionByteArray,
        seed,
    )
}

// Use rust library for transaction signing
actual fun signAlgo25Transaction(
    secretKey: ByteArray,
    transactionByteArray: ByteArray,
): ByteArray {
    Security.removeProvider("BC")
    Security.insertProviderAt(BouncyCastleProvider(), 0)

    // Decode the transaction using the rust library
    val transaction = decodeTransaction(transactionByteArray)

    // Extract the 32-byte seed from the 64-byte Algorand secret key
    // The rust library expects a 32-byte seed for Ed25519 signing
    val seed =
        if (secretKey.size == 64) {
            secretKey.copyOfRange(0, 32)
        } else {
            secretKey.copyOf()
        }

    // Sign using the rust library
    val signedTransaction = ed25519SignTransaction(seed, transaction)

    // Encode the signed transaction
    return encodeSignedTransaction(signedTransaction)
}

actual fun signHdKeyArbitraryData(
    data: ByteArray,
    seed: ByteArray,
    account: Int,
    change: Int,
    key: Int,
): ByteArray? =
    SignHdKeyTransactionImpl().signLegacyArbitraryData(
        data,
        seed,
        account,
        change,
        key,
    )

actual fun signHdKeyData(
    data: ByteArray,
    seed: ByteArray,
    account: Int,
    change: Int,
    key: Int,
): ByteArray? =
    SignHdKeyTransactionImpl().signArbitraryData(
        data,
        seed,
        account,
        change,
        key,
    )

actual fun signFalcon24ArbitraryData(
    data: ByteArray,
    publicKey: ByteArray,
    privateKey: ByteArray,
): ByteArray? =
    SignFalcon24TransactionImpl().signArbitraryData(
        data,
        publicKey,
        privateKey,
    )

actual fun signFalcon25ArbitraryData(
    data: ByteArray,
    publicKey: ByteArray,
    privateKey: ByteArray,
): ByteArray? =
    SignFalcon25TransactionImpl().signArbitraryData(
        data,
        publicKey,
        privateKey,
    )

// Use rust library for arbitrary data signing
actual fun signAlgo25ArbitraryData(
    data: ByteArray,
    secretKey: ByteArray,
): ByteArray? {
    println("DEBUG: signAlgo25ArbitraryData called with secretKey size: ${secretKey.size}")

    return try {
        // Extract the 32-byte seed from the 64-byte Algorand secret key
        // The rust library expects a 32-byte seed for Ed25519 signing
        val seed =
            if (secretKey.size == 64) {
                secretKey.copyOfRange(0, 32)
            } else {
                secretKey.copyOf()
            }
        ed25519RawSign(seed, data)
    } catch (e: Exception) {
        println("DEBUG: BouncyCastle Ed25519 signing failed: ${e.message}")
        e.printStackTrace()
        null
    }
}

actual fun createTransaction(payload: OfflineKeyRegTransactionPayload): ByteArray =
    with(payload) {
        composeSingleTransaction(
            txnParams =
                TxnParams(
                    kind = TxnParamsKind.OFFLINE_KEY_REG,
                    offlineKeyReg =
                        OfflineKeyRegParams(
                            common =
                                commonTxnParams(
                                    senderAddress = senderAddress,
                                    noteInByteArray = note?.toByteArray(),
                                    staticFee = flatFee?.toString()?.toULongOrNull(),
                                ),
                        ),
                ),
            suggestedParams = txnParams.toSuggestedParams(),
        )
    }

actual fun createTransaction(payload: OnlineKeyRegTransactionPayload): ByteArray =
    with(payload) {
        composeSingleTransaction(
            txnParams =
                TxnParams(
                    kind = TxnParamsKind.ONLINE_KEY_REG,
                    onlineKeyReg =
                        OnlineKeyRegParams(
                            common =
                                commonTxnParams(
                                    senderAddress = senderAddress,
                                    noteInByteArray = note?.toByteArray(),
                                    staticFee = flatFee?.toString()?.toULongOrNull(),
                                ),
                            voteKey = Base64.decode(voteKey.urlSafeBase64ToStandard(), Base64.DEFAULT),
                            selectionKey = Base64.decode(selectionPublicKey.urlSafeBase64ToStandard(), Base64.DEFAULT),
                            stateProofKey = Base64.decode(stateProofKey.urlSafeBase64ToStandard(), Base64.DEFAULT),
                            voteFirst = (voteFirstRound.toLongOrNull() ?: 0L).toULong(),
                            voteLast = (voteLastRound.toLongOrNull() ?: 0L).toULong(),
                            voteKeyDilution = (voteKeyDilution.toLongOrNull() ?: 0L).toULong(),
                        ),
                ),
            suggestedParams = txnParams.toSuggestedParams(),
        )
    }

actual fun makeAssetTransferTxn(
    senderAddress: String,
    receiverAddress: String,
    amount: String,
    assetId: Long,
    noteInByteArray: ByteArray?,
    suggestedParams: SuggestedParams,
    staticFee: Long?,
): ByteArray =
    composeSingleTransaction(
        txnParams =
            TxnParams(
                kind = TxnParamsKind.ASSET_TRANSFER,
                assetTransfer =
                    AssetTransferParams(
                        common = commonTxnParams(senderAddress, noteInByteArray, staticFee?.toULong()),
                        assetId = assetId.toULong(),
                        receiver = receiverAddress,
                        amount = amount.toULong(),
                    ),
            ),
        suggestedParams = suggestedParams,
    )

actual fun makePaymentTxn(
    senderAddress: String,
    receiverAddress: String,
    amount: String,
    isMax: Boolean,
    noteInByteArray: ByteArray?,
    suggestedParams: SuggestedParams,
    staticFee: Long?,
): ByteArray =
    composeSingleTransaction(
        txnParams =
            TxnParams(
                kind = TxnParamsKind.PAYMENT,
                payment =
                    PaymentParams(
                        common = commonTxnParams(senderAddress, noteInByteArray, staticFee?.toULong()),
                        receiver = receiverAddress,
                        amount = amount.toULong(),
                        closeRemainderTo = if (isMax) receiverAddress else null,
                    ),
            ),
        suggestedParams = suggestedParams,
    )

actual fun makeAssetAcceptanceTxn(
    publicKey: String,
    assetId: Long,
    suggestedParams: SuggestedParams,
    staticFee: Long?,
): ByteArray =
    composeSingleTransaction(
        txnParams =
            TxnParams(
                kind = TxnParamsKind.ASSET_OPT_IN,
                assetOptIn =
                    AssetOptInParams(
                        common = commonTxnParams(publicKey, staticFee = staticFee?.toULong()),
                        assetId = assetId.toULong(),
                    ),
            ),
        suggestedParams = suggestedParams,
    )

private fun composeSingleTransaction(
    txnParams: TxnParams,
    suggestedParams: SuggestedParams,
): ByteArray =
    compose(
        txnParams = listOf(txnParams),
        composerParams =
            ComposerParams(
                suggestedParams = suggestedParams.toComposerSuggestedParams(),
            ),
    ).single()

private fun commonTxnParams(
    senderAddress: String,
    noteInByteArray: ByteArray? = null,
    staticFee: ULong? = null,
): CommonTxnParams =
    CommonTxnParams(
        sender = senderAddress,
        note = noteInByteArray,
        staticFee = staticFee,
    )

private fun SuggestedParams.toComposerSuggestedParams(): uniffi.algokit_composer_ffi.SuggestedParams =
    uniffi.algokit_composer_ffi.SuggestedParams(
        fee = fee.toULong(),
        flatFee = flatFee,
        firstRoundValid = firstRoundValid.toULong(),
        lastRoundValid = lastRoundValid.toULong(),
        genesisHash = genesisHash,
        genesisId = genesisID,
    )
