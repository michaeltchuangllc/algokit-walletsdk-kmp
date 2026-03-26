package com.michaeltchuang.walletsdk.core.passkeys.model

import androidx.credentials.provider.CallingAppInfo
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.PublicKeyCredentialCreationOptions

enum class PasskeySigningProvider {
    BIP39_DETERMINISTIC,
    SOLANA_SEED_VAULT,
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
