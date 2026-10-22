package com.michaeltchuang.seedvault.demo.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.seedvault.sdk.client.AlgorandPaymentClient
import com.michaeltchuang.seedvault.sdk.client.SeedVaultServiceClient
import com.michaeltchuang.seedvault.sdk.client.SolanaSeedVaultClient
import com.michaeltchuang.seedvault.sdk.client.SolanaTransferSession
import com.michaeltchuang.seedvault.sdk.data.repository.AlgorandRepository
import com.michaeltchuang.seedvault.sdk.data.repository.SolanaBalanceRepository
import com.michaeltchuang.seedvault.sdk.data.repository.SolanaBalanceRepository.Cluster
import com.michaeltchuang.seedvault.sdk.data.repository.SolanaTransferRepository
import com.michaeltchuang.seedvault.sdk.domain.model.SeedVaultLimits
import com.michaeltchuang.seedvault.sdk.domain.model.SeedVaultSeed
import com.michaeltchuang.seedvault.sdk.domain.model.SolanaSeed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI State for the Wallet Service Demo screen.
 */
data class WalletServiceDemoUiState(
    val serviceConnected: Boolean = false,
    val loading: Boolean = false,
    val balanceLoading: Boolean = false, // Separate loading state for balance fetching
    val errorMessage: String? = null,
    val seedVaultSeeds: List<SeedVaultSeed> = emptyList(), // Falcon-1024 seeds from Seed Vault Service
    val signResult: String? = null,
    // Algorand (TestNet) balances in microAlgos by address, and the last payment sent
    val algorandBalances: Map<String, Long> = emptyMap(),
    val algorandBalanceLoading: Boolean = false,
    val algorandPaymentResult: String? = null,
    val algorandPaymentTxId: String? = null,
    val seeds: List<SolanaSeed> = emptyList(),
    val hasUnauthorizedSeeds: Boolean = false,
    val seedVaultLimits: SeedVaultLimits? = null,
    val totalBalance: Double? = null, // Sum of all fetched balances
    val balanceError: String? = null, // Separate error for balance operations
    // Transfer-related fields
    val transferLoading: Boolean = false,
    val transferError: String? = null,
    val lastTransferSignature: String? = null,
    // Network info
    val currentNetwork: String = "DEVNET", // Always defaults to DEVNET
)

/**
 * ViewModel for the Wallet Service Demo screen.
 * Manages connection to the wallet service and account data fetching.
 */
class WalletServiceDemoViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(WalletServiceDemoUiState())
    val uiState: StateFlow<WalletServiceDemoUiState> = _uiState

    private val _viewModelEvents = MutableSharedFlow<ViewModelEvent>()
    val viewModelEvents: SharedFlow<ViewModelEvent> = _viewModelEvents.asSharedFlow()

    private val seedVaultClient = SeedVaultServiceClient(application)
    private val solanaVaultClient = SolanaSeedVaultClient(application)
    private val algorandRepository = AlgorandRepository()
    private val algorandPaymentClient = AlgorandPaymentClient(seedVaultClient, algorandRepository)
    private val balanceRepository = SolanaBalanceRepository()
    private val transferRepository = SolanaTransferRepository()

    private val transferSession = SolanaTransferSession(transferRepository)

    init {
        // Auto-connect on start
        connectToService()

        // Observe Seed Vault content changes to auto-refresh UI
        observeSeedVaultContentChanges()
    }

    /**
     * Connect to the wallet service.
     */
    fun connectToService() {
        _uiState.update { it.copy(loading = true, errorMessage = null) }

        viewModelScope.launch {
            try {
                seedVaultClient.bindAsync()
                _uiState.update {
                    it.copy(
                        serviceConnected = true,
                        errorMessage = null,
                        loading = false,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        serviceConnected = false,
                        errorMessage = "Failed to connect: ${e.message}\n\nIs Seed Vault Service installed, and is its permission granted?",
                        loading = false,
                    )
                }
            }
        }
    }

    /**
     * Fetch the Falcon-1024 seeds this app is authorized to use from Seed Vault Service.
     */
    fun fetchSeedVaultSeeds() {
        if (!_uiState.value.serviceConnected) {
            _uiState.update { it.copy(errorMessage = "Not connected to service") }
            return
        }

        _uiState.update { it.copy(loading = true, errorMessage = null) }

        viewModelScope.launch {
            try {
                val seeds = withContext(Dispatchers.IO) { seedVaultClient.getAuthorizedSeeds() }
                _uiState.update {
                    it.copy(
                        seedVaultSeeds = seeds,
                        errorMessage = if (seeds.isEmpty()) "No authorized seeds. Tap Authorize App to choose seeds." else null,
                        loading = false,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        errorMessage = "Failed to fetch seeds: ${e.message}",
                        loading = false,
                    )
                }
            }
        }
    }

    /**
     * Sign [SIGN_DEMO_MESSAGE] with a seed over AIDL and show the size and start of the signature.
     */
    fun signDemoMessage(seed: SeedVaultSeed) {
        val data = SIGN_DEMO_MESSAGE.toByteArray()
        requestConfirmation(seedVaultClient.confirmSignBytesIntent(seed.id, data)) { signDemoMessageApproved(seed) }
    }

    private fun signDemoMessageApproved(seed: SeedVaultSeed) {
        _uiState.update { it.copy(loading = true, errorMessage = null, signResult = null) }

        viewModelScope.launch {
            try {
                val signature =
                    withContext(Dispatchers.IO) {
                        seedVaultClient.signBytes(seed.id, SIGN_DEMO_MESSAGE.toByteArray())
                    }
                _uiState.update {
                    it.copy(
                        signResult =
                            if (signature == null) {
                                "Signing failed for ${seed.name}"
                            } else {
                                "${seed.name} signed \"$SIGN_DEMO_MESSAGE\": ${signature.size} bytes, " +
                                    signature.take(16).joinToString("") { b -> "%02x".format(b) } + "..."
                            },
                        loading = false,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = "Failed to sign: ${e.message}", loading = false)
                }
            }
        }
    }

    /** Fetch the TestNet ALGO balance of every authorized seed's account from algod. */
    fun refreshAlgorandBalances() {
        val seeds = _uiState.value.seedVaultSeeds
        if (seeds.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "No authorized seeds. Tap Fetch Seeds first.") }
            return
        }

        _uiState.update { it.copy(algorandBalanceLoading = true, errorMessage = null) }

        viewModelScope.launch {
            val balances = mutableMapOf<String, Long>()
            var failed = 0
            seeds.forEach { seed ->
                try {
                    balances[seed.address] = algorandRepository.getBalanceMicroAlgos(seed.address)
                } catch (e: Exception) {
                    failed++
                    Log.e("WalletServiceDemo", "Algorand balance failed for ${seed.address}", e)
                }
            }
            _uiState.update {
                it.copy(
                    algorandBalances = it.algorandBalances + balances,
                    algorandBalanceLoading = false,
                    errorMessage = if (failed > 0) "Failed to fetch $failed Algorand balance(s)" else null,
                )
            }
        }
    }

    /**
     * Send a 0 ALGO payment from a seed's account to itself on TestNet. The vault builds and signs
     * the transaction; the SDK handles network parameters and submission.
     */
    fun sendZeroAlgoPayment(seed: SeedVaultSeed) {
        _uiState.update {
            it.copy(loading = true, errorMessage = null, algorandPaymentResult = null, algorandPaymentTxId = null)
        }

        viewModelScope.launch {
            try {
                val payment = algorandPaymentClient.preparePayment(seed.id, seed.address, 0L)
                requestConfirmation(payment.confirmationIntent) {
                    signAndSendPayment(seed) { algorandPaymentClient.signAndSend(payment) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("WalletServiceDemo", "Algorand payment failed", e)
                _uiState.update {
                    it.copy(errorMessage = "Algorand payment failed: ${e.message}", loading = false)
                }
            }
        }
    }

    private fun signAndSendPayment(
        seed: SeedVaultSeed,
        sendPayment: suspend () -> String,
    ) {
        _uiState.update { it.copy(loading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val txId = sendPayment()
                _uiState.update {
                    it.copy(
                        algorandPaymentResult = "${seed.name} sent a 0 ALGO payment to itself",
                        algorandPaymentTxId = txId,
                        loading = false,
                    )
                }
                refreshAlgorandBalances()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("WalletServiceDemo", "Algorand payment failed", e)
                _uiState.update {
                    it.copy(errorMessage = "Algorand payment failed: ${e.message}", loading = false)
                }
            }
        }
    }

    /**
     * Seed Vault Service only signs what the user approved on its confirm screen, which this app has
     * to open itself. [onApproved] runs when the user approved and verified themselves.
     */
    private fun requestConfirmation(
        intent: Intent,
        onApproved: () -> Unit,
    ) {
        viewModelScope.launch {
            _viewModelEvents.emit(
                ViewModelEvent.ConfirmSign(
                    intent = intent,
                    onApproved = onApproved,
                    onDenied = {
                        _uiState.update { it.copy(loading = false, errorMessage = "Signature not approved") }
                    },
                ),
            )
        }
    }

    /** Intent for the Seed Vault Service screen where the user authorizes this app. */
    fun authorizeAppIntent() = seedVaultClient.authorizeAppIntent()

    /**
     * Clear any error message.
     */
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // ==================== Solana Balance Fetching Methods ====================

    /**
     * Initialize the Solana balance repository with network connection.
     * @param cluster The Solana network cluster (default: DEVNET)
     */
    fun initializeBalanceRepository(cluster: Cluster = Cluster.DEVNET) {
        balanceRepository.initialize(cluster)
        _uiState.update { it.copy(currentNetwork = cluster.name) }
        Log.d("WalletServiceDemo", "Balance repository initialized for ${cluster.name} at ${balanceRepository.getRpcEndpoint()}")
    }

    /**
     * Switch to a different Solana network cluster.
     * This reinitializes both balance and transfer repositories.
     * @param cluster The network cluster to switch to
     */
    fun switchNetwork(cluster: Cluster) {
        if (_uiState.value.transferLoading) return
        clearTransferState()
        // Close existing connections
        balanceRepository.close()
        transferRepository.close()

        // Reinitialize with new cluster
        balanceRepository.initialize(cluster)
        transferRepository.initialize(
            when (cluster) {
                Cluster.MAINNET_BETA -> SolanaTransferRepository.Cluster.MAINNET_BETA
                Cluster.DEVNET -> SolanaTransferRepository.Cluster.DEVNET
                Cluster.TESTNET -> SolanaTransferRepository.Cluster.TESTNET
            },
        )

        _uiState.update { it.copy(currentNetwork = cluster.name) }
        Log.d(
            "WalletServiceDemo",
            "Switched to ${cluster.name} - Balance: ${balanceRepository.getRpcEndpoint()}, Transfer: ${transferRepository.getRpcEndpoint()}",
        )

        // Refresh balances if seeds are available
        if (_uiState.value.seeds.isNotEmpty()) {
            viewModelScope.launch {
                fetchBalancesForSeeds()
            }
        }
    }

    /**
     * Fetch on-chain balance for a specific account.
     * @param publicKeyBase58 The account's public key in base58 format
     */
    fun fetchAccountBalance(publicKeyBase58: String) {
        if (!balanceRepository.isInitialized()) {
            _uiState.update { it.copy(errorMessage = "Balance repository not initialized. Call initializeBalanceRepository() first.") }
            return
        }

        _uiState.update { it.copy(loading = true, errorMessage = null) }

        viewModelScope.launch {
            try {
                val balance = balanceRepository.fetchBalance(publicKeyBase58)
                if (balance != null) {
                    Log.d("WalletServiceDemo", "Fetched balance: $balance SOL for $publicKeyBase58")
                    // You could update UI state here with balance info
                    _uiState.update {
                        it.copy(
                            errorMessage = "Balance: $balance SOL",
                            loading = false,
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            errorMessage = "Failed to fetch balance for $publicKeyBase58",
                            loading = false,
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        errorMessage = "Error fetching balance: ${e.message}",
                        loading = false,
                    )
                }
            }
        }
    }

    /**
     * Fetch balances for all derived accounts from Seed Vault.
     * Call this after refreshSolanaSeeds() to get on-chain balances.
     */
    fun fetchAllAccountBalances() {
        if (_uiState.value.seeds.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "No seeds available. Call refreshSolanaSeeds() first.") }
            return
        }

        if (!balanceRepository.isInitialized()) {
            balanceRepository.initialize(Cluster.DEVNET)
            _uiState.update { it.copy(currentNetwork = Cluster.DEVNET.name) }
        }

        _uiState.update { it.copy(errorMessage = null) }

        // Writes the balances into the account cards, which is what the button is for
        viewModelScope.launch {
            fetchBalancesForSeeds()
        }
    }

    // ==================== Solana Seed Vault Methods ====================

    /**
     * Refresh Solana seeds and implementation limits from Seed Vault.
     * Automatically initializes the balance repository and fetches on-chain balances.
     */
    fun refreshSolanaSeeds() {
        _uiState.update { it.copy(loading = true, errorMessage = null) }

        viewModelScope.launch {
            try {
                // Auto-initialize balance repository if not already initialized
                if (!balanceRepository.isInitialized()) {
                    balanceRepository.initialize(Cluster.DEVNET)
                    _uiState.update { it.copy(currentNetwork = Cluster.DEVNET.name) }
                    Log.d("WalletServiceDemo", "Auto-initialized balance repository for DEVNET at ${balanceRepository.getRpcEndpoint()}")
                }

                // First fetch seeds from Seed Vault
                loadSolanaSnapshot()

                // Auto-fetch balances if repository is initialized and we have seeds
                if (balanceRepository.isInitialized() && _uiState.value.seeds.isNotEmpty()) {
                    fetchBalancesForSeeds()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        errorMessage = "Failed to refresh seeds: ${e.message}",
                        loading = false,
                    )
                }
            }
        }
    }

    /**
     * Authorize a new seed from the Seed Vault.
     * This emits an event that the UI should handle to launch the authorization intent.
     */
    fun authorizeNewSeed() {
        viewModelScope.launch {
            _viewModelEvents.emit(ViewModelEvent.AuthorizeNewSeed)
        }
    }

    private suspend fun loadSolanaSnapshot() {
        val snapshot = solanaVaultClient.getSnapshot()
        _uiState.update {
            it.copy(
                seeds = snapshot.seeds,
                hasUnauthorizedSeeds = snapshot.hasUnauthorizedSeeds,
                seedVaultLimits = snapshot.limits,
                totalBalance = null,
                balanceError = null,
                loading = false,
                errorMessage =
                    if (snapshot.seeds.isEmpty()) {
                        "No authorized seeds found. Make sure you have created seeds in the Seed Vault."
                    } else {
                        null
                    },
            )
        }
    }

    /**
     * Fetch balances for all accounts in the current seeds.
     * Updates the UI state with balance information.
     */
    private suspend fun fetchBalancesForSeeds() {
        val currentSeeds = _uiState.value.seeds
        if (currentSeeds.isEmpty()) {
            Log.d("WalletServiceDemo", "No seeds to fetch balances for")
            return
        }

        _uiState.update { it.copy(balanceLoading = true, balanceError = null) }

        // Collect all public keys
        val allAccounts = currentSeeds.flatMap { it.accounts }
        val publicKeys = allAccounts.map { it.publicKeyEncoded }

        Log.d("WalletServiceDemo", "========================================")
        Log.d("WalletServiceDemo", "FETCHING BALANCES FOR ${publicKeys.size} ACCOUNTS:")
        Log.d("WalletServiceDemo", "Network: ${balanceRepository.getRpcEndpoint()}")
        Log.d("WalletServiceDemo", "Addresses:")
        publicKeys.forEachIndexed { index, address ->
            Log.d("WalletServiceDemo", "  [$index] $address")
        }
        Log.d("WalletServiceDemo", "========================================")

        // Fetch all balances
        val balances =
            try {
                balanceRepository.fetchBalances(publicKeys)
            } catch (e: CancellationException) {
                _uiState.update { it.copy(balanceLoading = false) }
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(balanceLoading = false, balanceError = "Failed to fetch balances: ${e.message}") }
                return
            }

        // Calculate total balance
        val totalBalance = balances.values.filterNotNull().sum()
        val successCount = balances.values.count { it != null }
        val failCount = balances.size - successCount

        // Log which accounts failed
        if (failCount > 0) {
            Log.w("WalletServiceDemo", "Failed to fetch balances for:")
            balances.filter { it.value == null }.forEach { (address, _) ->
                Log.w("WalletServiceDemo", "  - $address")
            }
        }

        Log.d("WalletServiceDemo", "Balance fetch complete: $successCount success, $failCount failed, Total: $totalBalance SOL")

        // Update seeds with balance data
        _uiState.update { state ->
            val updatedSeeds =
                state.seeds.map { seed ->
                    seed.copy(
                        accounts =
                            seed.accounts.map { account ->
                                val balance = balances[account.publicKeyEncoded]
                                Log.d(
                                    "WalletServiceDemo",
                                    "Assigning balance to ${account.name}: ${balance ?: "NULL (failed)"} SOL for ${account.publicKeyEncoded}",
                                )
                                account.copy(balance = balance)
                            },
                    )
                }

            // Log summary
            val allUpdatedAccounts = updatedSeeds.flatMap { it.accounts }
            val totalUpdatedBalance = allUpdatedAccounts.mapNotNull { it.balance }.sum()
            Log.d("WalletServiceDemo", "UI updated with ${allUpdatedAccounts.size} accounts, total balance: $totalUpdatedBalance SOL")

            state.copy(
                seeds = updatedSeeds,
                totalBalance = totalBalance,
                balanceLoading = false,
                balanceError = if (failCount > 0) "Failed to fetch $failCount balance(s)" else null,
            )
        }
    }

    /**
     * Observe Seed Vault content changes and auto-refresh UI state.
     */
    private fun observeSeedVaultContentChanges() {
        viewModelScope.launch {
            try {
                solanaVaultClient.observeChanges().collect {
                    try {
                        loadSolanaSnapshot()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _uiState.update { it.copy(loading = false, errorMessage = "Failed to refresh seeds: ${e.message}") }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to observe Seed Vault: ${e.message}") }
            }
        }
    }

    /**
     * Mark accounts as user wallets after a seed is authorized/created/imported.
     * Only user-wallet accounts are included in SDK snapshots.
     *
     * @param authToken The authorization token of the newly added seed
     */
    fun markAccountsAsUserWallets(authToken: Long) {
        // Mark two accounts as user wallets. This simulates a real wallet app exploring each
        // account and marking them as containing user funds.
        viewModelScope.launch {
            try {
                solanaVaultClient.markAccountsAsUserWallets(authToken)
                // Refresh UI after marking accounts
                loadSolanaSnapshot()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("WalletServiceDemo", "Failed to mark accounts as user wallets", e)
                _uiState.update { it.copy(loading = false, errorMessage = "Failed to mark accounts as user wallets: ${e.message}") }
            }
        }
    }

    // ==================== SOL Transfer Methods ====================

    /**
     * Create a SOL transfer transaction (unsigned).
     * The SDK session owns the transaction; the UI launches the signing request.
     *
     * @param fromPublicKey The sender's public key
     * @param toPublicKey The recipient's public key
     * @param amountSol The amount of SOL to transfer
     */
    fun createTransferTransaction(
        fromPublicKey: String,
        toPublicKey: String,
        amountSol: Double,
    ) {
        if (_uiState.value.transferLoading) return
        if (!transferRepository.isInitialized()) {
            transferRepository.initialize(SolanaTransferRepository.Cluster.DEVNET)
            _uiState.update { it.copy(currentNetwork = SolanaTransferRepository.Cluster.DEVNET.name) }
            Log.d("WalletServiceDemo", "Auto-initialized transfer repository for DEVNET at ${transferRepository.getRpcEndpoint()}")
        }

        _uiState.update {
            it.copy(
                transferLoading = true,
                transferError = null,
                lastTransferSignature = null,
            )
        }

        viewModelScope.launch {
            try {
                val prepared =
                    transferSession.prepare(
                        seeds = _uiState.value.seeds,
                        fromPublicKey = fromPublicKey,
                        toPublicKey = toPublicKey,
                        amountSol = amountSol,
                    )
                _viewModelEvents.emit(
                    ViewModelEvent.SignTransferTransaction(
                        prepared.messageBase64,
                        prepared.authToken,
                        prepared.derivationPath,
                    ),
                )
            } catch (e: CancellationException) {
                transferSession.clear()
                throw e
            } catch (e: Exception) {
                transferSession.clear()
                Log.e("WalletServiceDemo", "Error creating transfer transaction", e)
                _uiState.update {
                    it.copy(
                        transferError = "Error: ${e.message}",
                        transferLoading = false,
                    )
                }
            }
        }
    }

    /**
     * Send a signed transfer transaction to the network.
     * This should be called after the transaction has been signed by the Seed Vault.
     *
     * @param signatureBase64 The signature as a base64-encoded string
     */
    fun sendSignedTransferTransaction(signatureBase64: String) {
        _uiState.update {
            it.copy(
                transferLoading = true,
                transferError = null,
            )
        }

        viewModelScope.launch {
            try {
                val result = transferSession.submit(signatureBase64)
                transferSession.clear()

                if (result.success) {
                    Log.d("WalletServiceDemo", "Transfer sent! Signature: ${result.signature}")
                    _uiState.update {
                        it.copy(
                            lastTransferSignature = result.signature,
                            transferLoading = false,
                            transferError = null,
                        )
                    }
                    // Refresh balances after successful transfer
                    fetchAllAccountBalances()
                } else {
                    _uiState.update {
                        it.copy(
                            transferError = result.error ?: "Failed to send transfer",
                            transferLoading = false,
                        )
                    }
                }
            } catch (e: CancellationException) {
                transferSession.clear()
                throw e
            } catch (e: Exception) {
                transferSession.clear()
                Log.e("WalletServiceDemo", "Error sending transfer", e)
                _uiState.update {
                    it.copy(
                        transferError = "Error: ${e.message}",
                        transferLoading = false,
                    )
                }
            }
        }
    }

    /**
     * Clear transfer-related state.
     */
    fun clearTransferState() {
        transferSession.clear()
        _uiState.update {
            it.copy(
                transferError = null,
                lastTransferSignature = null,
                transferLoading = false,
            )
        }
    }

    fun onTransferSigningFailed(message: String) {
        transferSession.clear()
        _uiState.update { it.copy(transferLoading = false, transferError = message) }
    }

    override fun onCleared() {
        super.onCleared()
        transferSession.clear()
        seedVaultClient.unbind()
        balanceRepository.close()
        transferRepository.close()
        algorandRepository.close()
    }

    companion object {
        private const val SIGN_DEMO_MESSAGE = "Hello from serviceDemoApp"
    }
}

/**
 * Events emitted by the ViewModel for UI actions that require Activity context.
 */
sealed interface ViewModelEvent {
    data object AuthorizeNewSeed : ViewModelEvent

    data class ConfirmSign(
        val intent: Intent,
        val onApproved: () -> Unit,
        val onDenied: () -> Unit,
    ) : ViewModelEvent

    data class SignTransferTransaction(
        val transactionBase64: String,
        val authToken: Long,
        val signerDerivationPath: Uri,
    ) : ViewModelEvent
}
