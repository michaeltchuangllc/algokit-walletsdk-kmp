package com.michaeltchuang.walletsdk.core.liquidAuth.auth.fido2

import android.os.Build
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
        private val client: OkHttpClient,
    ) {
        /**
         * POST request to retrieve PublicKeyCredentialCreationOptions
         *
         * @param origin - Base URL for the service
         * @param userAgent - User Agent for FIDO Server parsing
         * @param options - PublicKeyCredentialCreationOptions in JSON
         */
        fun postAttestationOptions(
            origin: String,
            userAgent: String,
            options: JSONObject = JSONObject(),
        ): Call {
            val path = "$origin/attestation/request"
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
         * @param credential - Provider's WebAuthn authenticator response
         */
        fun postAttestationResult(
            origin: String,
            userAgent: String,
            credential: WebAuthnCredential,
            liquidExt: JSONObject? = null,
        ): Call {
            val path = "$origin/attestation/response"
            val payload = credential.toJson()
            if (liquidExt != null) {
                val clientExtensionResults = payload.optJSONObject("clientExtensionResults") ?: JSONObject()
                clientExtensionResults.put("liquid", liquidExt)
                payload.put("clientExtensionResults", clientExtensionResults)
            }

            payload.put("device", Build.MODEL)
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
