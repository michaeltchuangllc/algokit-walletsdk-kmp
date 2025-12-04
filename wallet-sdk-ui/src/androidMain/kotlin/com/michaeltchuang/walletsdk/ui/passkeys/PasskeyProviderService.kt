package com.michaeltchuang.walletsdk.ui.passkeys

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import androidx.credentials.provider.PublicKeyCredentialEntry
import com.michaeltchuang.walletsdk.core.passkeys.builder.PasskeyCreateCredentialEntryBuilder
import com.michaeltchuang.walletsdk.core.passkeys.builder.PasskeyGetCredentialsEntryBuilder
import com.michaeltchuang.walletsdk.ui.passkeys.model.CreatePasskeyCredentialCreateEntry
import com.michaeltchuang.walletsdk.ui.passkeys.model.GetPasskeyCredentialEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import org.koin.android.ext.android.getKoin
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger


@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class PasskeyProviderService : CredentialProviderService() {

    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val requestCode: AtomicInteger = AtomicInteger()

    // Lazy initialization with Koin check - ensures Koin is started before accessing dependencies
    private val createCredentialEntryBuilder: PasskeyCreateCredentialEntryBuilder by lazy {
        ensureKoinStarted()
        getKoin().get<PasskeyCreateCredentialEntryBuilder>()
    }

    private val getCredentialsEntryBuilder: PasskeyGetCredentialsEntryBuilder by lazy {
        ensureKoinStarted()
        getKoin().get<PasskeyGetCredentialsEntryBuilder>()
    }

    override fun onCreate() {
        super.onCreate()
        // Ensure Koin is started when service is created from background
        ensureKoinStarted()
    }

    private fun ensureKoinStarted() {
        try {
            // Try to get existing Koin instance - if it exists, we're good
            getKoin()
        } catch (e: Exception) {
            // If Koin is not started, initialize it
            // platformKoinModule() now includes all necessary modules
            try {
                startKoin {
                    androidContext(this@PasskeyProviderService)
                    modules(com.michaeltchuang.walletsdk.core.foundation.di.platformKoinModule())
                    modules(com.michaeltchuang.walletsdk.ui.base.di.uiPlatformModule())
                }
            } catch (e: Exception) {
                // Koin might already be started in another process, ignore
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext.cancelChildren()
    }

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>
    ) {

        when (request) {
            is BeginCreatePublicKeyCredentialRequest -> {
                scope.launch {
                    createCredentialEntryBuilder.buildEntries(request).use(
                        onSuccess = { entries ->
                            val response = buildCreateCredentialResponse(entries)
                            callback.onResult(response)
                        },
                        onFailed = { exception, _ ->
                            val error = (exception as? CreateCredentialException)
                                ?: CreateCredentialUnknownException()
                            callback.onError(error)
                        }
                    )
                }
            }

            else -> callback.onError(CreateCredentialUnknownException())
        }
    }

    private fun buildCreateCredentialResponse(
        entries: List<CreatePasskeyCredentialCreateEntry>
    ): BeginCreateCredentialResponse {
        val builder = BeginCreateCredentialResponse.Builder()
        entries.forEach { entry ->
            val extras = Bundle().apply { putString(ALGOADDRESS, entry.bip44Address) }
            val intent = createNewPendingIntent(CREATE_PASSKEY_INTENT, extras)
            val createEntry = getCreateEntry(entry.accountName, entry.passkeyCount, intent)
            builder.addCreateEntry(createEntry)
        }
        return builder.build()
    }

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>
    ) {

        val callingPackage = request.callingAppInfo?.packageName
        if (callingPackage == null) {
            callback.onError(NoCredentialException())
            return
        }

        scope.launch {
            getCredentialsEntryBuilder.buildEntries(request).use(
                onSuccess = { entries ->
                    val responseBuilder = BeginGetCredentialResponse.Builder()
                    entries.forEach {
                        val credEntry = createPublicKeyCredentialEntry(it)
                        responseBuilder.addCredentialEntry(credEntry)
                    }
                    callback.onResult(responseBuilder.build())
                },
                onFailed = { exception, _ ->
                    val error =
                        (exception as? GetCredentialException) ?: GetCredentialUnknownException()
                    callback.onError(error)
                }
            )
        }
    }

    private fun createPublicKeyCredentialEntry(entry: GetPasskeyCredentialEntry): PublicKeyCredentialEntry {
        val extras = Bundle().apply { putString(CRED_ID_KEY, entry.credentialId) }
        val intent = createNewPendingIntent(GET_PASSKEY_INTENT, extras)
        var entry = PublicKeyCredentialEntry.Builder(
            applicationContext,
            entry.username.orEmpty(),
            intent,
            entry.option
        ).setDisplayName(entry.userDisplayName)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            entry = entry.setBiometricPromptData(com.michaeltchuang.walletsdk.ui.passkeys.biometric.BiometricPromptDataBuilder.getDefaultPromptData())
        }

        return entry.build()
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>
    ) {
    }

    private fun getCreateEntry(
        accountName: String,
        passkeyCount: Int,
        intent: PendingIntent
    ): CreateEntry {
        val description = "your credential will be saved"
        var entry = CreateEntry.Builder(accountName, intent)
            .setLastUsedTime(Instant.ofEpochMilli(0L))
            .setPublicKeyCredentialCount(passkeyCount)
            .setTotalCredentialCount(passkeyCount)
            .setDescription(description)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            entry = entry.setBiometricPromptData(com.michaeltchuang.walletsdk.ui.passkeys.biometric.BiometricPromptDataBuilder.getDefaultPromptData())
        }

        return entry.build()
    }

    private fun createNewPendingIntent(action: String, extra: Bundle? = null): PendingIntent {
        val intent = Intent(action).setPackage(applicationContext.packageName)
        if (extra != null) {
            Intent.EXTRA_INTENT
            intent.putExtra(EXTRA_INTENT_DATA_KEY, extra)
        }
        val flags = PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getActivity(
            applicationContext,
            requestCode.incrementAndGet(),
            intent,
            flags
        )
    }

    internal companion object {
        const val CREATE_PASSKEY_INTENT = "com.algorand.android.credentials.CREATE_PASSKEY"
        const val GET_PASSKEY_INTENT = "com.algorand.android.credentials.GET_PASSKEY"
        const val ALGOADDRESS = "algoAddress"
        const val EXTRA_INTENT_DATA_KEY = "extraIntentData"
        const val CRED_ID_KEY = "credId"
    }
}
