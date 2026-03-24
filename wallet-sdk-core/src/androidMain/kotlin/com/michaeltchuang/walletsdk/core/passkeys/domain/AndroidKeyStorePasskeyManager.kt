package com.michaeltchuang.walletsdk.core.passkeys.domain

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class AndroidKeyStorePasskeyManager {
    fun generateRandomCredentialId(byteLength: Int = 32): ByteArray =
        ByteArray(byteLength).also { SecureRandom().nextBytes(it) }

    fun createOrGetKeyPair(alias: String): KeyPair? {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existingPrivateKey = keyStore.getKey(alias, null)
        val existingPublicKey = keyStore.getCertificate(alias)?.publicKey
        if (existingPrivateKey != null && existingPublicKey != null) {
            return KeyPair(existingPublicKey, existingPrivateKey as java.security.PrivateKey)
        }

        val keyPairGenerator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE)
        val spec =
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec(SECP256R1_CURVE))
                .setDigests(
                    KeyProperties.DIGEST_SHA256,
                    KeyProperties.DIGEST_SHA384,
                    KeyProperties.DIGEST_SHA512,
                ).build()

        keyPairGenerator.initialize(spec)
        return keyPairGenerator.generateKeyPair()
    }

    fun sign(credentialId: String, payload: ByteArray): ByteArray? {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val privateKey = keyStore.getKey(credentialId, null) ?: return null
        val signature = Signature.getInstance(SHA256_WITH_ECDSA)
        signature.initSign(privateKey as java.security.PrivateKey)
        signature.update(payload)
        return signature.sign()
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val SECP256R1_CURVE = "secp256r1"
        const val SHA256_WITH_ECDSA = "SHA256withECDSA"
    }
}
