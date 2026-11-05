package com.michaeltchuang.walletsdk.core.passkeys.model

import androidx.credentials.provider.CallingAppInfo
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.PublicKeyCredentialCreationOptions

/**
 * How the passkey private key is produced/held. Named by key custody and curve, not by wallet type,
 * so other algorithms (e.g. falcon-1024, ml-dsa) can be added later.
 * [BIP39_DETERMINISTIC] derives the key from the account secret (HD seed, Falcon24, Algo25, Falcon25), so it is recoverable.
 * [ALGORAND_SEED_VAULT_DERIVED] derives it from bytes the Algorand Seed Vault derives for the account.
 * [SOLANA_SEED_VAULT_DERIVED] derives it from an Ed25519 signature the Solana Seed Vault makes over a fixed message.
 * [ANDROID_KEYSTORE_SECP256R1] is a random secp256r1 (ES256) key generated in the Android Keystore,
 * not derived from the account's key, so it works for any account type but is not recoverable.
 */
enum class PasskeySigningProvider {
    BIP39_DETERMINISTIC,
    ALGORAND_SEED_VAULT_DERIVED,
    SOLANA_SEED_VAULT_DERIVED,
    ANDROID_KEYSTORE_SECP256R1,
}

data class CreatePasskeyParams(
    val requestOptions: PublicKeyCredentialCreationOptions,
    val callingAppInfo: CallingAppInfo,
    val clientDataHash: ByteArray?,
    val address: String,
    val appInfoOrigin: String,
    val signingProvider: PasskeySigningProvider,
) {
    val rpId: String
        get() = requestOptions.rp.id
}
