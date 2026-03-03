package com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk

import com.algorand.algosdk.account.Account
import com.algorand.algosdk.sdk.Sdk
import com.michaeltchuang.walletsdk.core.algosdk.domain.model.Algo25Account
import com.michaeltchuang.walletsdk.core.encryption.domain.utils.clearFromMemory
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.NoSuchAlgorithmException
import java.security.Security

internal class AlgoAccountSdkImpl : AlgoAccountSdk {
    init {
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 0)
    }

    override fun createAlgo25Account(): Algo25Account? =
        try {
            var secretKey = Sdk.generateSK()
            val output =
                Algo25Account(
                    address = Sdk.generateAddressFromSK(secretKey),
                    secretKey = secretKey.copyOf(),
                )
            secretKey.clearFromMemory()
            output
        } catch (e: Exception) {
            null
        }

    override fun isValidAlgorandAddress(address: String): Boolean {
        return try {
            return Sdk.isValidAddress(address)
        } catch (e: Exception) {
            false
        }
    }

    override fun getMnemonicFromAlgo25SecretKey(secretKey: ByteArray): String? =
        try {
            Account(secretKey).toMnemonic()
        } catch (e: NoSuchAlgorithmException) {
            null
        } catch (e: IllegalArgumentException) {
            // Work around Android EdDSA key size bug by using BouncyCastle
            // The secret key from Sdk.generateSK() is 64 bytes (32-byte seed + 32-byte public key)
            // Extract the 32-byte seed and create the account from that
            try {
                val seed =
                    when (secretKey.size) {
                        64 -> secretKey.copyOfRange(0, 32) // Extract seed from expanded key
                        32 -> secretKey.copyOf() // Already a seed
                        else -> null
                    }
                seed?.let { Account(it).toMnemonic() }
            } catch (_: Exception) {
                null
            }
        } catch (e: Exception) {
            null
        }

    override fun recoverAlgo25Account(mnemonic: String): Algo25Account? =
        try {
            var secretKey = Sdk.mnemonicToPrivateKey(mnemonic)

            val output =
                Algo25Account(
                    address = Sdk.generateAddressFromSK(secretKey),
                    secretKey = secretKey.copyOf(),
                )
            secretKey.clearFromMemory()
            output
        } catch (e: Exception) {
            null
        }
}
