package com.michaeltchuang.walletsdk.demo

import androidx.compose.ui.window.ComposeUIViewController
import io.github.aakira.napier.DebugAntilog
import io.github.aakira.napier.Napier
import org.koin.core.Koin
import org.koin.mp.KoinPlatform
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// iOS-specific implementations
object IosApp

/**
 * Create the main view controller for iOS app
 */
fun MainViewController() = ComposeUIViewController { App() }

/**
 * Set the app group directory for sharing data between app and extensions.
 * MUST be called BEFORE initializeKoin() to take effect.
 *
 * @param directory The path to the app group's shared container directory
 */
fun setAppGroupDirectory(directory: String) {
    // Forward to wallet-sdk-core
    com.michaeltchuang.walletsdk.core.foundation.di
        .setSharedAppGroupDirectory(directory)
}

/**
 * Initialize ONLY Napier logging for iOS (uses NSLog).
 * Call this at app startup to enable logging BEFORE Compose starts.
 *
 * This does NOT initialize Koin - Compose's KoinApplication will handle that.
 */
fun initializeNapierLogging() {
    Napier.base(DebugAntilog())
    Napier.d("✅ Napier logging initialized for iOS", tag = "Napier")
}

/**
 * Initialize Koin for iOS app extensions (e.g., AutofillCredentialExtension).
 * Call this function before accessing any Koin dependencies from Swift.
 *
 * Note: This should only be called once per process. If Koin is already started,
 * this will stop and restart it.
 *
 * WARNING: Don't call this in the main app - Compose's KoinApplication handles Koin init.
 * This is only for extensions that don't use Compose.
 */
fun initializeKoin() {
    // Initialize Napier for logging (uses NSLog on iOS)
    initializeNapierLogging()

    try {
        // Stop existing Koin instance if it exists
       // stopKoin()
    } catch (e: Exception) {
        // Koin wasn't started, ignore
    }

   // startKoin(initKoinConfig)
}

/**
 * Get the Koin instance for dependency injection.
 * Call initializeKoin() first before using this.
 */
fun getKoin(): Koin = KoinPlatform.getKoin()

/**
 * Get PasskeyRepository instance from Koin.
 * Call initializeKoin() first before using this.
 */
fun getPasskeyRepository(): com.michaeltchuang.walletsdk.core.passkeys.domain.repository.PasskeyRepository = KoinPlatform.getKoin().get()

/**
 * Get all HD seed first addresses from the database (synchronous for iOS).
 * Call initializeKoin() first before using this.
 * Throws an exception if the operation fails.
 */
@Throws(Exception::class)
fun getAllHdSeedFirstAddresses(): List<com.michaeltchuang.walletsdk.core.account.domain.model.local.HdSeedFirstAddress> {
    val useCase: com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAllHdSeedFirstAddresses =
        KoinPlatform.getKoin().get()
    return kotlinx.coroutines.runBlocking {
        useCase()
    }
}

/**
 * Get the mnemonic for a given account address (synchronous for iOS).
 * Call initializeKoin() first before using this.
 * Returns AccountMnemonic or throws an exception if not found.
 */
@Throws(Exception::class)
fun getAccountMnemonic(address: String): com.michaeltchuang.walletsdk.core.account.domain.model.local.AccountMnemonic {
    val useCase: com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAccountMnemonic =
        KoinPlatform.getKoin().get()

    return kotlinx.coroutines.runBlocking {
        when (val result = useCase(address)) {
            is com.michaeltchuang.walletsdk.core.foundation.WalletSdkResult.Success -> result.data
            is com.michaeltchuang.walletsdk.core.foundation.WalletSdkResult.Error ->
                throw result.exception
        }
    }
}

/**
 * Get the account type for FIDO2 (for liquid auth extension).
 * Returns "falcon-1024" for Falcon24 accounts, "algorand" for all others.
 */
fun getAccountTypeForFido2(address: String): String {
    val useCase: com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccount =
        KoinPlatform.getKoin().get()

    return kotlinx.coroutines.runBlocking {
        val account = useCase(address)
        when (account) {
            is com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount.Falcon24 -> "falcon-1024"
            else -> "algorand"
        }
    }
}

/**
 * Get the LocalAccount for a given address.
 * Returns the account or null if not found.
 */
fun getLocalAccount(address: String): com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount? {
    val useCase: com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccount =
        KoinPlatform.getKoin().get()

    return kotlinx.coroutines.runBlocking {
        useCase(address)
    }
}

/**
 * Sign arbitrary data with an Algorand wallet account.
 * This handles all account types (Algo25, HD Key, Falcon24).
 *
 * @param address The Algorand address to sign with
 * @param challenge The challenge data to sign
 * @return The signature bytes, or null if signing fails
 */
fun signWithAlgorandWallet(
    address: String,
    challenge: ByteArray,
): ByteArray? {
    return try {
        val localAccount =
            getLocalAccount(address) ?: run {
                platform.Foundation.NSLog("❌ Account not found: $address")
                return null
            }

        val mnemonic =
            try {
                getAccountMnemonic(address).words.joinToString(" ")
            } catch (e: Exception) {
                platform.Foundation.NSLog("❌ Failed to get mnemonic: ${e.message}")
                return null
            }

        platform.Foundation.NSLog("🔐 Signing challenge with ${localAccount::class.simpleName} account")
        platform.Foundation.NSLog("   Address: $address")
        platform.Foundation.NSLog("   Challenge size: ${challenge.size} bytes")

        when (localAccount) {
            is com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount.Algo25 -> {
                // Algo25 account
                val algo25Account =
                    com.michaeltchuang.walletsdk.core.algosdk
                        .recoverAlgo25Account(mnemonic)
                        ?: return null.also { platform.Foundation.NSLog("❌ Failed to recover Algo25 account") }

                val signature =
                    com.michaeltchuang.walletsdk.core.algosdk.signAlgo25ArbitraryData(
                        data = challenge,
                        secretKey = algo25Account.secretKey,
                    ) ?: return null.also { platform.Foundation.NSLog("❌ Algo25 signing failed") }

                platform.Foundation.NSLog("✅ Signed with Algo25, signature size: ${signature.size} bytes")
                signature
            }

            is com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount.HdKey -> {
                // HD Key account - get seed from database
                val hdSeedRepo: com.michaeltchuang.walletsdk.core.account.domain.repository.local.HdSeedRepository =
                    KoinPlatform.getKoin().get()

                val seedData =
                    kotlinx.coroutines.runBlocking {
                        hdSeedRepo.getSeed(localAccount.seedId)
                    } ?: return null.also { platform.Foundation.NSLog("❌ Failed to get HD seed") }

                val signature =
                    com.michaeltchuang.walletsdk.core.algosdk.signHdKeyData(
                        data = challenge,
                        seed = seedData,
                        account = localAccount.account,
                        change = localAccount.change,
                        key = localAccount.keyIndex,
                    ) ?: return null.also { platform.Foundation.NSLog("❌ HD Key signing failed") }

                platform.Foundation.NSLog("✅ Signed with HD Key, signature size: ${signature.size} bytes")
                signature
            }

            is com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount.Falcon24 -> {
                // Falcon24 account - get private key from database
                val falcon24Repo: com.michaeltchuang.walletsdk.core.account.domain.repository.local.Falcon24AccountRepository =
                    KoinPlatform.getKoin().get()

                val privateKey =
                    kotlinx.coroutines.runBlocking {
                        falcon24Repo.getSecretKey(address)
                    } ?: return null.also { platform.Foundation.NSLog("❌ Failed to get Falcon24 private key") }

                platform.Foundation.NSLog("🔍 Falcon24 signing debug:")
                platform.Foundation.NSLog("   Public key size: ${localAccount.publicKey.size} bytes")
                platform.Foundation.NSLog("   Private key size: ${privateKey.size} bytes")
                platform.Foundation.NSLog("   Challenge size: ${challenge.size} bytes")

                val signature =
                    com.michaeltchuang.walletsdk.core.algosdk.signFalcon24ArbitraryData(
                        data = challenge,
                        publicKey = localAccount.publicKey,
                        privateKey = privateKey,
                    )

                if (signature == null) {
                    platform.Foundation.NSLog("❌ Falcon24 signing returned null")
                    return null
                }

                if (signature.isEmpty()) {
                    platform.Foundation.NSLog("❌ Falcon24 signing returned empty array!")
                    return null
                }

                platform.Foundation.NSLog("✅ Signed with Falcon24, signature size: ${signature.size} bytes")
                signature
            }

            else -> {
                platform.Foundation.NSLog("❌ Unsupported account type: ${localAccount::class.simpleName}")
                null
            }
        }
    } catch (e: Exception) {
        platform.Foundation.NSLog("❌ Signing failed: ${e.message}")
        null
    }
}

/**
 * Sign arbitrary data with an Algorand wallet account.
 * This handles all account types (Algo25, HD Key, Falcon24).
 *
 * @param address The Algorand address to sign with
 * @param challenge The challenge data to sign
 * @return The signature bytes, or null if signing fails
 */

/**
 * Sign a transaction with an Algorand wallet account.
 * This is specifically for signing transactions received via WebRTC (Liquid Auth).
 * Uses transaction-specific signing methods for each account type.
 *
 * @param address The Algorand address to sign with
 * @param txnBytes The transaction bytes to sign
 * @return The signed transaction bytes, or null if signing fails
 */
fun signTxnWithAlgorandWallet(
    address: String,
    txnBytes: ByteArray,
): ByteArray? {
    return try {
        platform.Foundation.NSLog("🔏 Signing transaction with Algorand wallet")
        platform.Foundation.NSLog("   Address: $address")
        platform.Foundation.NSLog("   Transaction size: ${txnBytes.size} bytes")

        // Get the local account
        val localAccount =
            getLocalAccount(address) ?: run {
                platform.Foundation.NSLog("❌ Account not found: $address")
                return null
            }

        // Get mnemonic
        val accountMnemonic = getAccountMnemonic(address)
        val mnemonic = accountMnemonic.words.joinToString(" ")

        platform.Foundation.NSLog("📋 Account type: ${localAccount::class.simpleName}")

        // Sign based on account type (using transaction-specific signing)
        when (localAccount) {
            is com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount.Algo25 -> {
                // Algo25 account - recover from mnemonic and sign transaction
                val algo25Account =
                    com.michaeltchuang.walletsdk.core.algosdk
                        .recoverAlgo25Account(mnemonic)
                        ?: return null.also { platform.Foundation.NSLog("❌ Failed to recover Algo25 account") }

                val signedTxn =
                    com.michaeltchuang.walletsdk.core.algosdk.signAlgo25Transaction(
                        secretKey = algo25Account.secretKey,
                        transactionByteArray = txnBytes,
                    )

                if (signedTxn == null || signedTxn.isEmpty()) {
                    platform.Foundation.NSLog("❌ Algo25 transaction signing failed")
                    return null
                }

                platform.Foundation.NSLog("✅ Signed with Algo25, signed txn size: ${signedTxn.size} bytes")
                signedTxn
            }

            is com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount.HdKey -> {
                // HD Key account - get seed and sign transaction
                val hdSeedRepo: com.michaeltchuang.walletsdk.core.account.domain.repository.local.HdSeedRepository =
                    KoinPlatform.getKoin().get()

                val seedData =
                    kotlinx.coroutines.runBlocking {
                        hdSeedRepo.getSeed(localAccount.seedId)
                    } ?: return null.also { platform.Foundation.NSLog("❌ Failed to get HD seed") }

                val signedTxn =
                    com.michaeltchuang.walletsdk.core.algosdk.signHdKeyTransaction(
                        transactionByteArray = txnBytes,
                        seed = seedData,
                        account = localAccount.account,
                        change = localAccount.change,
                        key = localAccount.keyIndex,
                    )

                if (signedTxn == null || signedTxn.isEmpty()) {
                    platform.Foundation.NSLog("❌ HD Key transaction signing failed")
                    return null
                }

                platform.Foundation.NSLog("✅ Signed with HD Key, signed txn size: ${signedTxn.size} bytes")
                signedTxn
            }

            is com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount.Falcon24 -> {
                // Falcon24 account - get private key and sign transaction
                val falcon24Repo: com.michaeltchuang.walletsdk.core.account.domain.repository.local.Falcon24AccountRepository =
                    KoinPlatform.getKoin().get()

                val privateKey =
                    kotlinx.coroutines.runBlocking {
                        falcon24Repo.getSecretKey(address)
                    } ?: return null.also { platform.Foundation.NSLog("❌ Failed to get Falcon24 private key") }

                platform.Foundation.NSLog("🔍 Falcon24 transaction signing debug:")
                platform.Foundation.NSLog("   Public key size: ${localAccount.publicKey.size} bytes")
                platform.Foundation.NSLog("   Private key size: ${privateKey.size} bytes")
                platform.Foundation.NSLog("   Transaction size: ${txnBytes.size} bytes")

                val signedTxn =
                    com.michaeltchuang.walletsdk.core.algosdk.signFalcon24Transaction(
                        transactionByteArray = txnBytes,
                        publicKey = localAccount.publicKey,
                        privateKey = privateKey,
                    )

                if (signedTxn == null) {
                    platform.Foundation.NSLog("❌ Falcon24 transaction signing returned null")
                    return null
                }

                if (signedTxn.isEmpty()) {
                    platform.Foundation.NSLog("❌ Falcon24 transaction signing returned empty array!")
                    return null
                }

                platform.Foundation.NSLog("✅ Signed with Falcon24, signed txn size: ${signedTxn.size} bytes")
                signedTxn
            }

            else -> {
                platform.Foundation.NSLog("❌ Unsupported account type: ${localAccount::class.simpleName}")
                null
            }
        }
    } catch (e: Exception) {
        platform.Foundation.NSLog("❌ Transaction signing failed: ${e.message}")
        e.printStackTrace()
        null
    }
}

/**
 * Get Algo25 secret key from mnemonic (for P256 derivation).
 * Returns the 64-byte Ed25519 secret key.
 *
 * @param phrase The 25-word Algorand mnemonic
 * @return 64-byte Ed25519 secret key, or null if recovery fails
 */
fun getAlgo25SecretKeyFromMnemonic(phrase: String): ByteArray? =
    try {
        val algo25Account =
            com.michaeltchuang.walletsdk.core.algosdk
                .recoverAlgo25Account(phrase)
        algo25Account?.secretKey
    } catch (e: Exception) {
        platform.Foundation.NSLog("❌ Failed to recover Algo25 account: ${e.message}")
        null
    }

/**
 * Get the Algorand wallet public key for a given address.
 * Returns the public key as a base64-encoded string.
 *
 * @param address The Algorand address
 * @return Base64-encoded public key, or null if not found
 */
@OptIn(ExperimentalEncodingApi::class)
fun getPublicKeyForAlgorandWallet(address: String): String? {
    return try {
        val localAccount =
            getLocalAccount(address) ?: run {
                platform.Foundation.NSLog("❌ Account not found: $address")
                return null
            }

        platform.Foundation.NSLog("🔑 Getting public key for ${localAccount::class.simpleName} account")

        val publicKey =
            when (localAccount) {
                is com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount.Algo25 -> {
                    // Algo25 account - derive public key from mnemonic
                    val mnemonic =
                        try {
                            getAccountMnemonic(address).words.joinToString(" ")
                        } catch (e: Exception) {
                            platform.Foundation.NSLog("❌ Failed to get mnemonic: ${e.message}")
                            return null
                        }

                    val algo25Account =
                        com.michaeltchuang.walletsdk.core.algosdk
                            .recoverAlgo25Account(mnemonic)
                            ?: return null.also { platform.Foundation.NSLog("❌ Failed to recover Algo25 account") }

                    // Ed25519 secret key is 64 bytes: first 32 are seed, last 32 are public key
                    algo25Account.secretKey.copyOfRange(32, 64)
                }

                is com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount.HdKey -> {
                    // HD Key account - public key is already a ByteArray
                    localAccount.publicKey
                }

                is com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount.Falcon24 -> {
                    // Falcon24 account - public key is already a ByteArray
                    localAccount.publicKey
                }

                else -> {
                    platform.Foundation.NSLog("❌ Unsupported account type: ${localAccount::class.simpleName}")
                    return null
                }
            }

        // Convert ByteArray to base64 string
        val publicKeyBase64 = Base64.encode(publicKey)
        platform.Foundation.NSLog(
            "✅ ${localAccount::class.simpleName} public key (${publicKey.size} bytes): ${publicKeyBase64.take(20)}...",
        )
        publicKeyBase64
    } catch (e: Exception) {
        platform.Foundation.NSLog("❌ Failed to get public key: ${e.message}")
        null
    }
}

/**
 * Set the handler for iOS Liquid Auth.
 * This bridges the wallet-sdk-ui module to Swift.
 * Call this from Swift during app initialization.
 *
 * @param handler Callback that receives (origin, requestId, algoAddress)
 */
fun setIosLiquidAuthHandler(handler: (String, String, String) -> Unit) {
    com.michaeltchuang.walletsdk.ui.liquidAuth.iosLiquidAuthHandler = handler
    platform.Foundation.NSLog("✅ iOS Liquid Auth handler registered")
}
