package com.michaeltchuang.walletsdk.core.passkeys.domain.usecase

import com.michaeltchuang.walletsdk.core.passkeys.domain.WebAuthnUtils
import com.michaeltchuang.walletsdk.core.passkeys.model.AddPasskeyArgs
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.PublicKeyCredentialCreationOptions
import com.michaeltchuang.walletsdk.core.passkeys.domain.repository.PasskeyRepository


internal class AddNewPasskeyUseCase(
    private val passkeyRepository: PasskeyRepository
) : AddNewPasskey {

    override suspend fun invoke(
        bip44Address: String,
        requestOptions: PublicKeyCredentialCreationOptions,
        credId: ByteArray
    ) {
        val args = getAddPasskeyArgs(bip44Address, requestOptions, credId)
        passkeyRepository.addNewPasskey(args)
    }

    private fun getAddPasskeyArgs(
        algoAddress: String,
        requestOptions: PublicKeyCredentialCreationOptions,
        credId: ByteArray
    ): AddPasskeyArgs {
        return AddPasskeyArgs(
            siteUrl = requestOptions.rp.id,
            siteName = requestOptions.rp.name,
            algoAddress = algoAddress,
            uid = WebAuthnUtils.b64Encode(requestOptions.user.id),
            username = requestOptions.user.name,
            displayName = requestOptions.user.displayName,
            credId = WebAuthnUtils.b64Encode(credId)
        )
    }
}
