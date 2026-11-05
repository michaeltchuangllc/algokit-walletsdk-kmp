package com.michaeltchuang.walletsdk.core.passkeys.domain

import android.content.Context
import androidx.core.net.toUri
import app.perawallet.deterministicP256.DeterministicP256
import com.michaeltchuang.walletsdk.core.account.domain.repository.solana.SeedVaultRepository
import com.michaeltchuang.walletsdk.core.solana.utils.Base58
import com.solanamobile.seedvault.SigningRequest
import com.solanamobile.seedvault.Wallet
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.KeyPair
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Passkey keys for Solana Seed Vault accounts. The Seed Vault signs a fixed, readable message with the
 * account's Ed25519 key. Ed25519 signatures are deterministic (RFC 8032), so the same account always
 * gives the same signature, and the P-256 key comes from it with the same [DeterministicP256] step as the
 * other accounts. The signature and key are never stored, so every sign-in asks Seed Vault again.
 */
class SolanaSeedVaultPasskeyKeyManager(
    context: Context,
    private val seedVaultRepository: SeedVaultRepository,
    private val approverHolder: SeedVaultApproverHolder,
    private val deterministicSigner: DeterministicP256,
) {
    private val applicationContext = context.applicationContext

    suspend fun sign(
        address: String,
        origin: String,
        userHandle: String,
        payload: ByteArray,
    ): ByteArray? = deriveKeyPair(address, origin, userHandle)?.let { deterministicSigner.signWithDomainSpecificKeyPair(it, payload) }

    suspend fun deriveKeyPair(
        address: String,
        origin: String,
        userHandle: String,
    ): KeyPair? {
        val publicKey = Base58.decode(address)
        require(publicKey.size == 32) { "Solana address must encode a 32-byte public key" }
        val (authToken, account) =
            checkNotNull(
                seedVaultRepository.getSolanaSeeds().firstNotNullOfOrNull { seed ->
                    seed.accounts.firstOrNull { it.address == address }?.let { seed.authToken to it }
                },
            ) { "No authorized Seed Vault account matches this address." }
        require(account.derivationPath.isNotBlank()) { "Seed Vault account has no derivation path" }
        val launcher = checkNotNull(approverHolder.approver) { "No screen is available to ask for Seed Vault approval." }
        val message = message(address, origin, userHandle)
        val result =
            launcher.launch(
                Wallet.signMessages(
                    applicationContext,
                    authToken,
                    arrayListOf(SigningRequest(message, listOf(account.derivationPath.toUri()))),
                ),
            )
        check(result.approved) { "Seed Vault signing was cancelled." }
        val responses = Wallet.onSignMessagesResult(result.resultCode, result.data)
        check(responses.size == 1 && responses.single().signatures.size == 1) { "Seed Vault must return exactly one signature." }
        val signature =
            responses
                .single()
                .signatures
                .single()
                .copyOf()
        try {
            check(signature.size == 64) { "Seed Vault did not return a detached Ed25519 signature." }
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            verifier.update(message, 0, message.size)
            check(verifier.verifySignature(signature)) { "Seed Vault signature does not match this account." }
            val mainKey = separate(signature)
            return try {
                deterministicSigner.genDomainSpecificKeypair(mainKey, origin, userHandle)
            } finally {
                mainKey.fill(0)
            }
        } finally {
            signature.fill(0)
        }
    }

    /** What the user sees in the Seed Vault prompt. It is part of the key, so changing it moves every key. */
    private fun message(
        address: String,
        origin: String,
        userHandle: String,
    ): ByteArray =
        (
            "AlgoKit passkey v1\n" +
                "Sign this to create or use your passkey. It does not move funds.\n" +
                "Account: $address\nSite: $origin\nUser: $userHandle"
        ).encodeToByteArray()

    private fun separate(signature: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(LABEL.encodeToByteArray(), "HmacSHA512"))
        return mac.doFinal(signature)
    }

    private companion object {
        const val LABEL = "algokit-passkey-v1/solana-seed-vault"
    }
}
