package com.michaeltchuang.walletsdk.ui.liquidAuth.usecases

import com.google.android.gms.fido.fido2.api.common.PublicKeyCredential
import foundation.algorand.auth.fido2.AttestationApi
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONObject

class AttestationApiUseCase(
    private val httpClient: OkHttpClient,
) {
    private val attestationApi by lazy { AttestationApi(httpClient) }

    suspend fun postAttestationOptions(origin: String, userAgent: String, options: JSONObject = JSONObject()): Response =
        attestationApi.postAttestationOptions(origin, userAgent, options).execute()

    suspend fun postAttestationResult(origin: String, userAgent: String, credential: PublicKeyCredential, liquidExt: JSONObject? = null): Response =
        attestationApi.postAttestationResult(origin, userAgent, credential, liquidExt).execute()

    fun getHttpClient(): OkHttpClient = httpClient
}
