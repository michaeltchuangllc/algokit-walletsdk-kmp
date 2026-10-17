package com.michaeltchuang.seedvault.service.data.vault

import com.michaeltchuang.seedvault.service.domain.model.AlgorandPaymentParams
import com.michaeltchuang.walletsdk.core.algosdk.getFalcon25MnemonicFromEntropy
import com.michaeltchuang.walletsdk.core.algosdk.getFalcon25SeedFromEntropy
import com.michaeltchuang.walletsdk.core.algosdk.recoverFalcon25Account
import com.michaeltchuang.walletsdk.core.algosdk.signFalcon25ArbitraryData
import com.michaeltchuang.walletsdk.core.algosdk.signFalcon25Transaction
import com.michaeltchuang.walletsdk.core.encryption.decryptByteArray
import com.michaeltchuang.walletsdk.core.encryption.encryptByteArray
import com.michaeltchuang.walletsdk.core.encryption.initializeEncryptionManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the entropy behind each seed phrase and the derived private key encrypted with the AES-GCM
 * Android Keystore key from wallet-sdk-core, in the seed row. Used when the device has no Trusty
 * applet.
 */
internal class RoomSeedKeyVault : SeedKeyVault {
    override val storage = SeedStorage.ROOM

    private val keyMutex = Mutex()
    private var isKeyReady = false

    override suspend fun isAvailable() = true

    override suspend fun addSeed(seedPhrase: List<String>): StoredSeed {
        ensureEncryptionKey()
        val account = checkNotNull(recoverFalcon25Account(seedPhrase.joinToString(" "))) { "Could not derive the Falcon-1024 account" }
        return try {
            StoredSeed(
                address = account.address,
                publicKey = account.publicKey,
                encryptedEntropy = encryptByteArray(account.entropy),
                encryptedPrivateKey = encryptByteArray(account.privateKey),
            )
        } finally {
            account.entropy.fill(0)
            account.seed.fill(0)
            account.privateKey.fill(0)
        }
    }

    override suspend fun revealSeedPhrase(seed: StoredSeed): List<String> {
        ensureEncryptionKey()
        val entropy = decryptByteArray(seed.encryptedEntropy)
        try {
            return checkNotNull(getFalcon25MnemonicFromEntropy(entropy)) { "Could not encode the Falcon-1024 seed phrase" }
                .split(" ")
        } finally {
            entropy.fill(0)
        }
    }

    override suspend fun signBytes(
        seed: StoredSeed,
        data: ByteArray,
    ): ByteArray? {
        ensureEncryptionKey()
        val privateKey = decryptByteArray(seed.encryptedPrivateKey)
        try {
            return signFalcon25ArbitraryData(data, seed.publicKey, privateKey)
        } finally {
            privateKey.fill(0)
        }
    }

    override suspend fun signAlgorandPayment(
        seed: StoredSeed,
        receiver: String,
        amountMicroAlgos: Long,
        params: AlgorandPaymentParams,
    ): ByteArray? {
        val transaction = buildFalconPayment(seed.address, receiver, amountMicroAlgos, params) ?: return null
        ensureEncryptionKey()
        val entropy = decryptByteArray(seed.encryptedEntropy)
        val falconSeed =
            try {
                getFalcon25SeedFromEntropy(entropy)
            } finally {
                entropy.fill(0)
            } ?: return null
        try {
            return signFalcon25Transaction(transaction, falconSeed)
        } finally {
            falconSeed.fill(0)
        }
    }

    /** The secrets only exist in the seed row, which the repository deletes. */
    override suspend fun deleteSeed(seed: StoredSeed) = Unit

    /**
     * Creates the Keystore key before the first encrypt / decrypt. wallet-sdk-core also does this
     * from a background coroutine when its AES manager is created, which could race the first write.
     */
    private suspend fun ensureEncryptionKey() {
        keyMutex.withLock {
            if (!isKeyReady) {
                initializeEncryptionManager()
                isKeyReady = true
            }
        }
    }
}
