package com.michaeltchuang.walletsdk.ui.liquidAuth.usecases

import com.michaeltchuang.walletsdk.core.liquidAuth.auth.fido2.AssertionApi
import com.google.android.gms.fido.fido2.api.common.PublicKeyCredential
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONObject

class AssertionApiUseCase(
    private val httpClient: OkHttpClient,
) {
    private val assertionApi by lazy { AssertionApi(httpClient) }

    fun postAssertionOptions(origin: String, userAgent: String, credentialId: String, liquidExt: Boolean? = true): Response =
        assertionApi.postAssertionOptions(origin, userAgent, credentialId, liquidExt).execute()

    fun postAssertionResult(origin: String, userAgent: String, credential: PublicKeyCredential, liquidExt: JSONObject?): Response =
        assertionApi.postAssertionResult(origin, userAgent, credential, liquidExt).execute()
}
