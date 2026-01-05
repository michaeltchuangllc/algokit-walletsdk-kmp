package com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases

import com.google.android.gms.fido.fido2.api.common.PublicKeyCredential
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.fido2.AssertionApi
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONObject

class AssertionApiUseCase(
    private val httpClient: OkHttpClient,
) {
    private val assertionApi by lazy { AssertionApi(httpClient) }

    suspend fun postAssertionOptions(origin: String, userAgent: String, credentialId: String, liquidExt: Boolean? = true): Response =
        assertionApi.postAssertionOptions(origin, userAgent, credentialId, liquidExt).await()

    suspend fun postAssertionResult(origin: String, userAgent: String, credential: PublicKeyCredential, liquidExt: JSONObject?): Response =
        assertionApi.postAssertionResult(origin, userAgent, credential, liquidExt).await()
}
