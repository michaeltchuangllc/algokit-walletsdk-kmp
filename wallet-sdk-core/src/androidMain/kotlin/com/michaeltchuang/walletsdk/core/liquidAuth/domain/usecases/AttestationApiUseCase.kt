package com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases

import com.google.android.gms.fido.fido2.api.common.PublicKeyCredential
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.fido2.AttestationApi
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AttestationApiUseCase(
    private val httpClient: OkHttpClient,
) {
    private val attestationApi by lazy { AttestationApi(httpClient) }

    suspend fun postAttestationOptions(origin: String, userAgent: String, options: JSONObject = JSONObject()): Response =
        attestationApi.postAttestationOptions(origin, userAgent, options).await()

    suspend fun postAttestationResult(origin: String, userAgent: String, credential: PublicKeyCredential, liquidExt: JSONObject? = null): Response =
        attestationApi.postAttestationResult(origin, userAgent, credential, liquidExt).await()

}
suspend fun Call.await(): Response {
    return suspendCancellableCoroutine { continuation ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }

            override fun onFailure(call: Call, e: IOException) {
                // Don't bother with resuming the continuation if it is already cancelled.
                if (continuation.isCancelled) return
                continuation.resumeWithException(e)
            }
        })

        continuation.invokeOnCancellation {
            try {
                cancel()
            } catch (ex: Throwable) {
                //Ignore cancel exception
            }
        }
    }
}
