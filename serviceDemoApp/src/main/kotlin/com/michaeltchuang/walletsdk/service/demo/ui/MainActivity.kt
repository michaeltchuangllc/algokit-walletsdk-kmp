package com.michaeltchuang.walletsdk.service.demo.ui

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
import com.michaeltchuang.walletsdk.service.demo.ui.viewmodel.ViewModelEvent
import com.michaeltchuang.walletsdk.service.demo.ui.viewmodel.WalletServiceDemoViewModel
import com.solanamobile.seedvault.SigningRequest
import com.solanamobile.seedvault.Wallet
import com.solanamobile.seedvault.WalletContractV1
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: WalletServiceDemoViewModel by viewModels()

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_AUTHORIZE_SEED = 100
        private const val REQUEST_CODE_SIGN_TRANSACTION = 101
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
                    color = MaterialTheme.colorScheme.background
                ) {
                    WalletServiceDemoScreen(viewModel)
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
            is ViewModelEvent.SignTransferTransaction -> {
                Log.d(TAG, "Handling SignTransferTransaction event for authToken=${event.authToken}")
                signTransferTransaction(event.transactionBase64, event.authToken, event.signerDerivationPath)
            }
        }
    }

    private fun signTransferTransaction(transactionBase64: String, authToken: Long, derivationPath: Uri) {
        // Decode the base64 transaction message
        val transactionBytes = android.util.Base64.decode(transactionBase64, android.util.Base64.DEFAULT)

        // Create a SigningRequest
        val paths = arrayListOf(derivationPath)
        val signingRequest = SigningRequest(transactionBytes, paths)
        val signingRequests = arrayListOf(signingRequest)

        // Launch the signing intent
        val intent = Wallet.signTransactions(this, authToken, signingRequests)
        startActivityForResult(intent, REQUEST_CODE_SIGN_TRANSACTION)
    }

    private fun authorizeNewSeed() {
        val intent = Wallet.authorizeSeed(this, WalletContractV1.PURPOSE_SIGN_SOLANA_TRANSACTION)
        startActivityForResult(intent, REQUEST_CODE_AUTHORIZE_SEED)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_CODE_AUTHORIZE_SEED -> {
                try {
                    val authToken = Wallet.onAuthorizeSeedResult(resultCode, data)
                    Log.d(TAG, "Seed authorized successfully, authToken=$authToken")
                    // Mark accounts as user wallets (required for them to appear in queries)
                    viewModel.markAccountsAsUserWallets(authToken)
                } catch (e: Wallet.ActionFailedException) {
                    Log.e(TAG, "Seed authorization failed: ${e.message}")
                }
            }
            REQUEST_CODE_SIGN_TRANSACTION -> {
                try {
                    val signingResponses = Wallet.onSignTransactionsResult(resultCode, data)
                    Log.d(TAG, "Transaction signed successfully, responses count=${signingResponses.size}")

                    // Get the signatures from the response
                    val response = signingResponses.firstOrNull()
                    val signatures = response?.signatures

                    if (signatures != null && signatures.isNotEmpty()) {
                        // Get the first signature and send it to the ViewModel
                        val signature = signatures.first()

                        Log.d(TAG, "Got signature: ${signature.size} bytes")

                        // Encode the signature and send to ViewModel
                        val signatureBase64 = android.util.Base64.encodeToString(signature, android.util.Base64.DEFAULT)
                        viewModel.sendSignedTransferTransaction(signatureBase64)
                    } else {
                        Log.e(TAG, "No signatures returned from Seed Vault")
                    }
                } catch (e: Wallet.ActionFailedException) {
                    Log.e(TAG, "Transaction signing failed: ${e.message}")
                }
            }
        }
    }
}
