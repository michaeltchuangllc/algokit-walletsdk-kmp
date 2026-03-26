package com.michaeltchuang.walletsdk.core.passkeys.mapper

import androidx.credentials.provider.ProviderCreateCredentialRequest
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.CreatePublicKeyCredentialResponseArgs
import com.michaeltchuang.walletsdk.core.passkeys.model.CreatePasskeyParams
import com.michaeltchuang.walletsdk.core.passkeys.model.PasskeySigningProvider

fun interface CreatePasskeyParamsMapper {
    operator fun invoke(
        request: ProviderCreateCredentialRequest,
        algoAddress: String,
        appInfoOrigin: String,
        signingProvider: PasskeySigningProvider,
    ): CreatePasskeyParams
}

fun interface CreatePublicKeyCredentialResponseArgsMapper {
    suspend operator fun invoke(
        params: CreatePasskeyParams,
        appInfoOrigin: String,
    ): CreatePublicKeyCredentialResponseArgs
}
