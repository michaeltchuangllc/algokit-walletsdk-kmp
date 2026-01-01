package com.michaeltchuang.walletsdk.core.passkeys.domain

import app.perawallet.deterministicP256.DeterministicP256
import cash.z.ecc.android.bip39.Mnemonics
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAllHdSeedFirstAddresses
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetHdEntropy
import com.michaeltchuang.walletsdk.core.encryption.domain.utils.clearFromMemory
import java.security.KeyPair

internal class DeterministicBip39SignManager(
    private val deterministicSigner: DeterministicP256,
    private val getAllHdSeedFirstAddresses: GetAllHdSeedFirstAddresses,
    private val getHdEntropy: GetHdEntropy,
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
        val allFirstAddresses = getAllHdSeedFirstAddresses()
        val seedId = allFirstAddresses.firstOrNull { it.firstAddress == address }?.seedId ?: return null
        val entropy = getHdEntropy(seedId) ?: return null
        return try {
            val key = deterministicSigner.genDerivedMainKeyWithBIP39(Mnemonics.MnemonicCode(entropy).joinToString(" "))
            deterministicSigner.genDomainSpecificKeypair(key, origin, userHandle)
        } catch (_: Exception) {
            null
        } finally {
            entropy.clearFromMemory()
        }
    }

    override fun deriveCredentialId(keyPair: KeyPair): ByteArray {
        val publicKeyBytes = keyPair.public.encoded
        val md = PeraMessageDigest.getInstance()
        return md.digest(publicKeyBytes)
    }
}
