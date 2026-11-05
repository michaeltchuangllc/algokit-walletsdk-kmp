package com.michaeltchuang.walletsdk.core.passkeys.domain

import app.perawallet.deterministicP256.DeterministicP256
import cash.z.ecc.android.bip39.Mnemonics
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAlgo25SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAllHdSeedFirstAddresses
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetFalcon25Entropy
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetHdEntropy
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccount
import com.michaeltchuang.walletsdk.core.encryption.domain.utils.clearFromMemory
import java.security.KeyPair
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal class DeterministicBip39SignManager(
    private val deterministicSigner: DeterministicP256,
    private val getAllHdSeedFirstAddresses: GetAllHdSeedFirstAddresses,
    private val getHdEntropy: GetHdEntropy,
    private val getLocalAccount: GetLocalAccount,
    private val getAlgo25SecretKey: GetAlgo25SecretKey,
    private val getFalcon25Entropy: GetFalcon25Entropy,
) : Bip39SignManager {
    override suspend fun sign(
        address: String,
        origin: String,
        userHandle: String,
        payload: ByteArray,
    ): ByteArray? {
        val keyPair = deriveKeyPair(address, origin, userHandle) ?: return null
        return deterministicSigner.signWithDomainSpecificKeyPair(keyPair, payload)
    }

    override suspend fun deriveKeyPair(
        address: String,
        origin: String,
        userHandle: String,
    ): KeyPair? {
        return try {
            val mainKey = deriveMainKey(address) ?: return null
            try {
                deterministicSigner.genDomainSpecificKeypair(mainKey, origin, userHandle)
            } finally {
                mainKey.fill(0)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The per-account secret all domain keys come from. HD accounts and Falcon24 use the BIP39 seed;
     * Algo25 and Falcon25 own their entropy, which is already high entropy, so it is only domain-separated.
     */
    private suspend fun deriveMainKey(address: String): ByteArray? {
        val seedId =
            getAllHdSeedFirstAddresses().firstOrNull { it.firstAddress == address }?.seedId
                ?: (getLocalAccount(address) as? LocalAccount.Falcon24)?.seedId
        if (seedId != null) {
            val entropy = getHdEntropy(seedId) ?: return null
            return try {
                deterministicSigner.genDerivedMainKeyWithBIP39(Mnemonics.MnemonicCode(entropy).joinToString(" "))
            } finally {
                entropy.clearFromMemory()
            }
        }
        return when (getLocalAccount(address)) {
            is LocalAccount.Algo25 -> getAlgo25SecretKey(address)?.let { separate(ALGO25_LABEL, it) }
            is LocalAccount.Falcon25 -> getFalcon25Entropy(address)?.let { separate(FALCON25_LABEL, it) }
            else -> null
        }
    }

    private fun separate(
        label: String,
        secret: ByteArray,
    ): ByteArray {
        try {
            val mac = Mac.getInstance("HmacSHA512")
            mac.init(SecretKeySpec(label.encodeToByteArray(), "HmacSHA512"))
            return mac.doFinal(secret)
        } finally {
            secret.clearFromMemory()
        }
    }

    override fun deriveCredentialId(keyPair: KeyPair): ByteArray {
        val publicKeyBytes = keyPair.public.encoded
        val md = PeraMessageDigest.getInstance()
        return md.digest(publicKeyBytes)
    }

    private companion object {
        const val ALGO25_LABEL = "algokit-passkey-v1/algo25"
        const val FALCON25_LABEL = "algokit-passkey-v1/falcon25"
    }
}
