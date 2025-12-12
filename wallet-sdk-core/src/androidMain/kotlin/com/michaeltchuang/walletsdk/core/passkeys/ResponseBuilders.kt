package com.michaeltchuang.walletsdk.core.passkeys

import androidx.credentials.GetCredentialResponse
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.CreatePublicKeyCredentialResponseArgs
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.CreatePublicKeyCredentialResponseData
import com.michaeltchuang.walletsdk.core.passkeys.model.GetCredentialsParams

fun interface GetCredentialResponseProcessor {
    suspend fun getResponseWithSignature(params: GetCredentialsParams): GetCredentialResponse
}

 fun interface CreatePublicKeyCredentialResponseProcessor {
    operator fun invoke(args: CreatePublicKeyCredentialResponseArgs): CreatePublicKeyCredentialResponseData
}
