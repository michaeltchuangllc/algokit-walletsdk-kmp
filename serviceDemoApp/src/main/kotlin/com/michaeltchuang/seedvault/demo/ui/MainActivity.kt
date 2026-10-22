package com.michaeltchuang.seedvault.demo.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.michaeltchuang.seedvault.demo.ui.viewmodel.ViewModelEvent
import com.michaeltchuang.seedvault.demo.ui.viewmodel.WalletServiceDemoViewModel
import com.michaeltchuang.seedvault.sdk.client.SolanaSeedVaultClient
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: WalletServiceDemoViewModel by viewModels()
    private val solanaVaultClient by lazy { SolanaSeedVaultClient(this) }
    private var pendingConfirmation: ViewModelEvent.ConfirmSign? = null

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_AUTHORIZE_SEED = 100
        private const val REQUEST_CODE_SIGN_TRANSACTION = 101
        private const val REQUEST_CODE_AUTHORIZE_APP = 102
        private const val REQUEST_CODE_CONFIRM_SIGN = 103
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Collect ViewModel events
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.viewModelEvents.collect { event ->
                    handleViewModelEvent(event)
                }
            }
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    WalletServiceDemoScreen(viewModel, onAuthorizeApp = ::authorizeApp)
                }
            }
        }
    }

    private fun handleViewModelEvent(event: ViewModelEvent) {
        when (event) {
            is ViewModelEvent.AuthorizeNewSeed -> {
                Log.d(TAG, "Handling AuthorizeNewSeed event")
                authorizeNewSeed()
            }
            is ViewModelEvent.ConfirmSign -> {
                pendingConfirmation = event
                try {
                    startActivityForResult(event.intent, REQUEST_CODE_CONFIRM_SIGN)
                } catch (e: Exception) {
                    pendingConfirmation = null
                    event.onDenied()
                    Log.e(TAG, "Could not open signing confirmation", e)
                }
            }
            is ViewModelEvent.SignTransferTransaction -> {
                Log.d(TAG, "Handling SignTransferTransaction event for authToken=${event.authToken}")
                signTransferTransaction(event.transactionBase64, event.authToken, event.signerDerivationPath)
            }
        }
    }

    private fun signTransferTransaction(
        transactionBase64: String,
        authToken: Long,
        derivationPath: Uri,
    ) {
        try {
            val intent = solanaVaultClient.signingIntent(transactionBase64, authToken, derivationPath)
            startActivityForResult(intent, REQUEST_CODE_SIGN_TRANSACTION)
        } catch (e: Exception) {
            viewModel.onTransferSigningFailed("Could not start transaction signing: ${e.message}")
            Log.e(TAG, "Could not start transaction signing", e)
        }
    }

    /** Asks the user in Seed Vault Service which seeds this app may use. */
    private fun authorizeApp() {
        startActivityForResult(viewModel.authorizeAppIntent(), REQUEST_CODE_AUTHORIZE_APP)
    }

    private fun authorizeNewSeed() {
        val intent = solanaVaultClient.authorizeSeedIntent()
        startActivityForResult(intent, REQUEST_CODE_AUTHORIZE_SEED)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_CODE_AUTHORIZE_APP -> {
                if (resultCode == RESULT_OK) viewModel.fetchSeedVaultSeeds()
            }
            REQUEST_CODE_CONFIRM_SIGN -> {
                val confirmation = pendingConfirmation
                pendingConfirmation = null
                if (resultCode == RESULT_OK) confirmation?.onApproved?.invoke() else confirmation?.onDenied?.invoke()
            }
            REQUEST_CODE_AUTHORIZE_SEED -> {
                try {
                    val authToken = solanaVaultClient.authorizationResult(resultCode, data)
                    Log.d(TAG, "Seed authorized successfully, authToken=$authToken")
                    // Mark accounts as user wallets (required for them to appear in queries)
                    viewModel.markAccountsAsUserWallets(authToken)
                } catch (e: Exception) {
                    Log.e(TAG, "Seed authorization failed: ${e.message}")
                }
            }
            REQUEST_CODE_SIGN_TRANSACTION -> {
                try {
                    val signatureBase64 = solanaVaultClient.signatureResult(resultCode, data)
                    viewModel.sendSignedTransferTransaction(signatureBase64)
                } catch (e: Exception) {
                    viewModel.onTransferSigningFailed("Transaction signing failed: ${e.message}")
                    Log.e(TAG, "Transaction signing failed: ${e.message}")
                }
            }
        }
    }
}
