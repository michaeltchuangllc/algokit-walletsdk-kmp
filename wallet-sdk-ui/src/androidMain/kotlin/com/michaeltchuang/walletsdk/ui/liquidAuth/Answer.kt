package com.michaeltchuang.walletsdk.ui.liquidAuth

import android.app.Activity
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.algorand.algosdk.account.Account
import com.google.android.gms.fido.Fido
import com.google.android.gms.fido.fido2.Fido2ApiClient
import com.google.android.gms.fido.fido2.api.common.PublicKeyCredential
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.Cookie
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.fido2.toPublicKeyCredentialCreationOptions
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.AuthMessage
import foundation.algorand.auth.fido2.AttestationApi
import foundation.algorand.crypto.avm.KeyPairs
import kotlinx.coroutines.tasks.await
import okhttp3.OkHttpClient
import okhttp3.internal.userAgent
import org.json.JSONObject
import ru.gildor.coroutines.okhttp.await as awaitCall

class Answer(
    private val activity: ComponentActivity,
) {
    private val tag = "LiquidAuth"
    private var httpClient = OkHttpClient.Builder().cookieJar(Cookies()).build()
    private val attestationApi = AttestationApi(httpClient)
    private var signature: ByteArray? = null

    private var fido2Client: Fido2ApiClient? = null

    private val attestationIntentLauncher: ActivityResultLauncher<IntentSenderRequest> =
        activity.registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result ->
            handleAuthenticatorAttestationResult(result)
        }

    suspend fun register(
        msg: AuthMessage,
        options: JSONObject = JSONObject(),
    ) {
        fido2Client = Fido2ApiClient(activity)
        // val account = wallet.account.value!!
        // val selected = wallet.selected.value!!
        val account = Account()
        val selected = account
        Log.d(tag, "Registering new Credential with ${account.address} at ${msg.origin}")

        // Create Options for FIDO2 Server
        options.put("username", account.address.toString())
        options.put("displayName", "Liquid Auth User")
        options.put("authenticatorSelection", JSONObject().put("userVerification", "required"))
        val extensions = JSONObject()
        extensions.put("liquid", true)
        options.put("extensions", extensions)

        // FIDO2 Server API Response for PublicKeyCredentialCreationOptions
        val response = attestationApi.postAttestationOptions(msg.origin, userAgent, options).awaitCall()
        val session = Cookie.fromResponse(response)
        // session?.let { setSession(Cookie.getID(it)) }
        // Convert ResponseBody to FIDO2 PublicKeyCredentialCreationOptions
        val pubKeyCredentialCreationOptions = response.body!!.toPublicKeyCredentialCreationOptions()
        // Sign the challenge with the algorand account, this is used in the liquid FIDO2 extension
        signature =
            KeyPairs.rawSignBytes(
                pubKeyCredentialCreationOptions.challenge,
                KeyPairs.getKeyPair(selected.toMnemonic()).private,
            )
        // Kick off FIDO2 Client Intent
        val pendingIntent =
            fido2Client!!.getRegisterPendingIntent(pubKeyCredentialCreationOptions).await()
        attestationIntentLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
    }

    private fun handleAuthenticatorAttestationResult(result: androidx.activity.result.ActivityResult) {
        val bytes = result.data?.getByteArrayExtra(Fido.FIDO2_KEY_CREDENTIAL_EXTRA)
        when {
            result.resultCode != Activity.RESULT_OK ->
                Log.e(tag, "Attestation result code: ${result.resultCode}")
            bytes == null ->
                Log.e(tag, "Error: No credential returned")
            else -> {
                val credential = PublicKeyCredential.deserializeFromBytes(bytes)
                Log.d(tag, "Credential created successfully: ${credential.id}")
                // TODO: Send credential to server via attestationApi.postAttestationResult()
            }
        }
    }
}
