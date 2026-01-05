package com.michaeltchuang.walletsdk.core.liquidAuth.auth.fido2

import android.os.Build
import android.util.Log
import com.google.android.gms.fido.fido2.api.common.AuthenticatorAttestationResponse
import com.google.android.gms.fido.fido2.api.common.PublicKeyCredential
import com.google.android.gms.fido.fido2.api.common.PublicKeyCredentialType
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.crypto.toBase64
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import javax.inject.Inject

/**
 * Attestation/Registration API
 *
 * Handles credential creation endpoints and returning the appropriate FIDO2 shapes to the caller.
 *
 * Attestation is a two-step process to fully register a credential.
 *
 * 1. Submit a POST request to a `/attestation/options` endpoint
 * which takes an optional PublicKeyCredentialOptions as a JSON object and returns PublicKeyCredentialOptions as the result.
 * 2. Submit a POST request to a `/attestation/result` endpoint
 * which receives the AuthenticatorAttestationResponse and returns a 200 when it receives a valid response.
 *
 * In the first phase of registration, the client can accept the default server options or add a JSON POST body which
 * indicates what type of authenticator they wish to register.
 * In the second phase of registration, the client takes the PublicKeyCredentialOptions and submits it to the native authenticator API.
 * The result should produce a PublicKeyCredential with an associated AuthenticatorAttestationResponse which
 * is then submitted and verified on the FIDO2 service
 */
class AttestationApi
    @Inject
    constructor(
        client: OkHttpClient,
    ) {
        companion object {
            const val TAG = "fido2.AttestationApi"
        }

        private val client: OkHttpClient =
            client
                .newBuilder()
                .addInterceptor(LoggingInterceptor())
                .build()

        /**
         * Logging Interceptor for API responses
         */
        private class LoggingInterceptor : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                val response = chain.proceed(request)

                Log.d(TAG, "Response: ${response.code} ${response.message}")
                Log.d(TAG, "Response URL: ${response.request.url}")

                // Log response body without consuming it
                response.body?.let { body ->
                    val source = body.source()
                    source.request(Long.MAX_VALUE)
                    val buffer = source.buffer.clone()
                    val responseBody = buffer.readUtf8()
                    Log.d(TAG, "Response body: $responseBody")
                }

                return response
            }
        }

        /**
         * POST request to retrieve PublicKeyCredentialCreationOptions
         *
         * @param origin - Base URL for the service
         * @param userAgent - User Agent for FIDO Server parsing
         * @param session - Cookie for the Session
         * @param options - PublicKeyCredentialCreationOptions in JSON
         */
        fun postAttestationOptions(
            origin: String,
            userAgent: String,
            options: JSONObject = JSONObject(),
        ): Call {
            val path = "$origin/attestation/request"
            Log.d(TAG, "POST $path")
            Log.d(TAG, "Request body: $options")
            val body = options.toString().toRequestBody("application/json".toMediaTypeOrNull())
            return client.newCall(
                Request
                    .Builder()
                    .url(path)
                    .addHeader("User-Agent", userAgent)
                    .method("POST", body)
                    .build(),
            )
        }

        /**
         * POST request to register a PublicKeyCredential
         *
         * @param origin - Base URL for the service
         * @param userAgent - User Agent for FIDO Server parsing
         * @param session - Cookie for the Session
         * @param credential - PublicKeyCredential from Authenticator Response
         */
        fun postAttestationResult(
            origin: String,
            userAgent: String,
            credential: PublicKeyCredential,
            liquidExt: JSONObject? = null,
        ): Call {
            val path = "$origin/attestation/response"
            val rawId = credential.rawId!!.toBase64()
            val response = credential.response as AuthenticatorAttestationResponse

            val payload = JSONObject()
            payload.put("id", rawId)
            payload.put("type", "${PublicKeyCredentialType.PUBLIC_KEY}")
            payload.put("rawId", rawId)
            if (liquidExt != null) {
                val clientExtensionResults = JSONObject()
                clientExtensionResults.put("liquid", liquidExt)
                payload.put("clientExtensionResults", clientExtensionResults)
            }
            val jsonResponse = JSONObject()
            jsonResponse.put("clientDataJSON", response.clientDataJSON.toBase64())
            jsonResponse.put("attestationObject", response.attestationObject.toBase64())
            payload.put("response", jsonResponse)

            payload.put("device", Build.MODEL)
            Log.d(TAG, "POST $path")
            Log.d(TAG, "Request body: $payload")
            val requestBody = payload.toString().toRequestBody("application/json".toMediaTypeOrNull())
            return client.newCall(
                Request
                    .Builder()
                    .url(path)
                    .addHeader("User-Agent", userAgent)
                    .method("POST", requestBody)
                    .build(),
            )
        }
    }
