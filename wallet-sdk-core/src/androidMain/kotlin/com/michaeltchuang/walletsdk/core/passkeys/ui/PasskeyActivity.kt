package com.michaeltchuang.walletsdk.core.passkeys.ui

import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.michaeltchuang.walletsdk.core.passkeys.ui.PasskeyProviderService.Companion.CREATE_PASSKEY_INTENT
import com.michaeltchuang.walletsdk.core.passkeys.ui.PasskeyProviderService.Companion.GET_PASSKEY_INTENT
import com.michaeltchuang.walletsdk.core.passkeys.ui.biometric.PasskeyBiometricAuthenticator
import com.michaeltchuang.walletsdk.core.passkeys.ui.viewmodel.CreatePasskeyViewModel
import com.michaeltchuang.walletsdk.core.passkeys.ui.viewmodel.CreatePasskeyViewModel.CreatePasskeyParams
import com.michaeltchuang.walletsdk.core.passkeys.ui.viewmodel.CreatePasskeyViewModel.ViewEvent.AuthenticateCreatePasskeyWithBiometrics
import com.michaeltchuang.walletsdk.core.passkeys.ui.viewmodel.CreatePasskeyViewModel.ViewEvent.FinishActivityWithCreateBiometricError
import com.michaeltchuang.walletsdk.core.passkeys.ui.viewmodel.CreatePasskeyViewModel.ViewEvent.FinishActivityWithCreateError
import com.michaeltchuang.walletsdk.core.passkeys.ui.viewmodel.CreatePasskeyViewModel.ViewEvent.SetCreateResponseAndFinishActivity
import com.michaeltchuang.walletsdk.core.passkeys.ui.viewmodel.GetPasskeyViewModel
import com.michaeltchuang.walletsdk.core.passkeys.ui.viewmodel.GetPasskeyViewModel.GetCredentialsParams
import com.michaeltchuang.walletsdk.core.passkeys.ui.viewmodel.GetPasskeyViewModel.ViewEvent.AuthenticateGetPasskeyWithBiometrics
import com.michaeltchuang.walletsdk.core.passkeys.ui.viewmodel.GetPasskeyViewModel.ViewEvent.FinishActivityWithGetError
import com.michaeltchuang.walletsdk.core.passkeys.ui.viewmodel.GetPasskeyViewModel.ViewEvent.SetGetResponseAndFinishActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.koin.android.ext.android.getKoin
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.context.startKoin
import java.security.Security

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class PasskeyActivity : FragmentActivity(),
    ActivityCredentialRequestResolver by DefaultActivityCredentialRequestResolver() {


    private val createPasskeyViewModel: CreatePasskeyViewModel by viewModel()
    private val getPasskeyViewModel: GetPasskeyViewModel by viewModel()

    private val createPasskeyViewEventCollector: suspend (CreatePasskeyViewModel.ViewEvent) -> Unit =
        { event ->
            when (event) {
                is FinishActivityWithCreateError -> finishWithCreateCredentialError(event.errorMessage)
                is SetCreateResponseAndFinishActivity -> finishWithCreateCredentialResponse(event.response)
                is AuthenticateCreatePasskeyWithBiometrics -> authenticateCreatePasskeyWithBiometrics(
                    event.params
                )

                is FinishActivityWithCreateBiometricError -> {
                    val errorMessage = "Biometric error: ${event.errorCode} - ${event.errorMessage}"
                    finishWithCreateCredentialError(errorMessage)
                }
            }
        }

    private val getPasskeyViewEventCollector: suspend (GetPasskeyViewModel.ViewEvent) -> Unit =
        { event ->
            when (event) {
                is FinishActivityWithGetError -> finishWithGetCredentialError(event.errorMessage)
                is SetGetResponseAndFinishActivity -> finishWithGetCredentialResponse(event.response)
                is AuthenticateGetPasskeyWithBiometrics -> authenticateGetPasskeyWithBiometrics(
                    event.params
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 0)
        // Ensure Koin is started before ViewModels are created
        ensureKoinStarted()
        initializeRequestResolver(this)
        when (intent.action) {
            CREATE_PASSKEY_INTENT -> processCreatePasskeyRequest()
            GET_PASSKEY_INTENT -> processGetPasskeyRequest()
        }
    }

    private fun ensureKoinStarted() {
        try {
            // Try to get existing Koin instance - if it exists, we're good
            getKoin()
        } catch (e: Exception) {
            // If Koin is not started, initialize it
            // platformKoinModule() includes all necessary modules
            try {
                startKoin {
                    androidContext(this@PasskeyActivity)
                    modules(com.michaeltchuang.walletsdk.core.foundation.di.platformKoinModule())
                }
            } catch (e: Exception) {
                // Koin might already be started in another process, ignore
                e.printStackTrace()
            }
        }
    }

    private fun processCreatePasskeyRequest() {
        observeCreatePasskeyViewEvents()
        createPasskeyViewModel.processIntent(intent)
    }

    private fun processGetPasskeyRequest() {
        observeGetPasskeyViewEvents()
        getPasskeyViewModel.processIntent(intent)
    }

    private fun authenticateCreatePasskeyWithBiometrics(params: CreatePasskeyParams) {
        PasskeyBiometricAuthenticator(onFinishActivity = { finish() }) {
            createPasskeyViewModel.createPasskey(params)
        }.authenticate(this)
    }

    private fun authenticateGetPasskeyWithBiometrics(params: GetCredentialsParams) {
        PasskeyBiometricAuthenticator(onFinishActivity = { finish() }) {
            getPasskeyViewModel.createGetCredentialResponse(params)
        }.authenticate(this)
    }

    private fun observeCreatePasskeyViewEvents() {
        lifecycleScope.launch {
            createPasskeyViewModel.viewEvent
                .flowWithLifecycle(lifecycle, Lifecycle.State.CREATED)
                .collectLatest(createPasskeyViewEventCollector)
        }
    }

    private fun observeGetPasskeyViewEvents() {
        lifecycleScope.launch {
            getPasskeyViewModel.viewEvent
                .flowWithLifecycle(lifecycle, Lifecycle.State.CREATED)
                .collectLatest(getPasskeyViewEventCollector)
        }
    }
}
