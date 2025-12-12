package com.michaeltchuang.walletsdk.ui.passkeys.mapper

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.provider.ProviderCreateCredentialRequest
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.PublicKeyCredentialCreationOptions
import com.michaeltchuang.walletsdk.core.passkeys.mapper.CreatePasskeyParamsMapper
import com.michaeltchuang.walletsdk.core.passkeys.model.CreatePasskeyParams


@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class DefaultCreatePasskeyParamsMapper() : CreatePasskeyParamsMapper {

    override fun invoke(
        request: ProviderCreateCredentialRequest,
        bip44Address: String,
        appInfoOrigin: String
    ): CreatePasskeyParams {
        val publicKeyRequest = request.callingRequest as CreatePublicKeyCredentialRequest
        return with(publicKeyRequest) {
           CreatePasskeyParams(
                requestOptions = PublicKeyCredentialCreationOptions(requestJson),
                callingAppInfo = request.callingAppInfo,
                clientDataHash = clientDataHash,
                bip44Address = bip44Address,
                appInfoOrigin = appInfoOrigin
            )
        }
    }
}
