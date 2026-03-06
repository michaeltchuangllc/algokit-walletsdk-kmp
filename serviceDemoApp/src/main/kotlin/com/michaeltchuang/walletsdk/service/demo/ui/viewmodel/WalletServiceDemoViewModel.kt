package com.michaeltchuang.walletsdk.service.demo.ui.viewmodel

import android.app.Application
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.util.Log
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.service.demo.client.WalletServiceClient
import com.michaeltchuang.walletsdk.service.demo.data.model.AccountLite
import com.michaeltchuang.walletsdk.service.demo.data.model.SolanaAccount
import com.michaeltchuang.walletsdk.service.demo.data.model.SolanaSeed
import com.michaeltchuang.walletsdk.service.demo.data.model.SeedVaultLimits
import com.michaeltchuang.walletsdk.service.demo.data.repository.SolanaBalanceRepository
import com.michaeltchuang.walletsdk.service.demo.data.repository.SolanaTransferRepository
import com.solanamobile.seedvault.Bip32DerivationPath
import com.solanamobile.seedvault.Bip44DerivationPath
import com.solanamobile.seedvault.BipLevel
import com.solanamobile.seedvault.SeedVault
import com.solanamobile.seedvault.Wallet
import com.solanamobile.seedvault.WalletContractV1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import com.michaeltchuang.walletsdk.service.demo.data.repository.SolanaBalanceRepository.Cluster

/**
 * UI State for the Wallet Service Demo screen.
 */
data class WalletServiceDemoUiState(
    val serviceConnected: Boolean = false,
    val loading: Boolean = false,
    val balanceLoading: Boolean = false,  // Separate loading state for balance fetching
    val errorMessage: String? = null,
    val accounts: List<AccountLite> = emptyList(),
    val seeds: List<SolanaSeed> = emptyList(),
    val hasUnauthorizedSeeds: Boolean = false,
    val seedVaultLimits: SeedVaultLimits? = null,
    val totalBalance: Double? = null,  // Sum of all fetched balances
    val balanceError: String? = null,  // Separate error for balance operations
    // Transfer-related fields
    val transferLoading: Boolean = false,
    val transferError: String? = null,
    val lastTransferSignature: String? = null,
    val pendingTransferTransaction: String? = null  // Base64-encoded unsigned transaction
)

/**
 * ViewModel for the Wallet Service Demo screen.
 * Manages connection to the wallet service and account data fetching.
 */
class WalletServiceDemoViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(WalletServiceDemoUiState())
    val uiState: StateFlow<WalletServiceDemoUiState> = _uiState

    private val _viewModelEvents = MutableSharedFlow<ViewModelEvent>()
    val viewModelEvents: SharedFlow<ViewModelEvent> = _viewModelEvents.asSharedFlow()

    private val walletClient = WalletServiceClient(application)
    private val balanceRepository = SolanaBalanceRepository()
    private val transferRepository = SolanaTransferRepository()

    // Store pending transaction data for signing
    private var pendingTransaction: org.sol4k.Transaction? = null
    private var pendingSerializedMessage: ByteArray? = null

    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

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
                walletClient.bindAsync()
                _uiState.update {
                    it.copy(
                        serviceConnected = true,
                        errorMessage = null,
                        loading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        serviceConnected = false,
                        errorMessage = "Failed to connect: ${e.message}\n\nIs wallet-sdk-service installed?",
                        loading = false
                    )
                }
            }
        }
    }

    /**
     * Fetch accounts with balances from the wallet service.
     */
    fun fetchAccounts() {
        if (!_uiState.value.serviceConnected) {
            _uiState.update { it.copy(errorMessage = "Not connected to service") }
            return
        }

        _uiState.update { it.copy(loading = true, errorMessage = null) }

        viewModelScope.launch {
            try {
                val accountsJson = walletClient.getAccountsWithBalances()
                val accounts = json.decodeFromString<List<AccountLite>>(accountsJson)
                _uiState.update {
                    it.copy(
                        accounts = accounts,
                        errorMessage = if (accounts.isEmpty()) "No accounts found" else null,
                        loading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        errorMessage = "Failed to fetch accounts: ${e.message}",
                        loading = false
                    )
                }
            }
        }
    }

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
        Log.d("WalletServiceDemo", "Balance repository initialized for ${cluster.name}")
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
                            loading = false
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            errorMessage = "Failed to fetch balance for $publicKeyBase58",
                            loading = false
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        errorMessage = "Error fetching balance: ${e.message}",
                        loading = false
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
        val seeds = _uiState.value.seeds
        if (seeds.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "No seeds available. Call refreshSolanaSeeds() first.") }
            return
        }

        if (!balanceRepository.isInitialized()) {
            _uiState.update { it.copy(errorMessage = "Balance repository not initialized. Call initializeBalanceRepository() first.") }
            return
        }

        _uiState.update { it.copy(loading = true, errorMessage = null) }

        viewModelScope.launch {
            try {
                val allAccounts = seeds.flatMap { it.accounts }
                val publicKeys = allAccounts.map { it.publicKeyEncoded }

                Log.d("WalletServiceDemo", "Fetching balances for ${publicKeys.size} accounts")

                val balances = balanceRepository.fetchBalances(publicKeys)

                // Log results
                balances.forEach { (pubKey, balance) ->
                    Log.d("WalletServiceDemo", "Account $pubKey: ${balance ?: "N/A"} SOL")
                }

                val totalBalance = balances.values.filterNotNull().sum()

                _uiState.update {
                    it.copy(
                        errorMessage = "Fetched ${balances.size} balances. Total: $totalBalance SOL",
                        loading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        errorMessage = "Error fetching balances: ${e.message}",
                        loading = false
                    )
                }
            }
        }
    }

    // ==================== Solana Seed Vault Methods ====================

    /**
     * Refresh Solana seeds and implementation limits from Seed Vault.
     * This method replicates the functionality from fakewallet's MainViewModel.
     * Automatically initializes the balance repository and fetches on-chain balances.
     */
    fun refreshSolanaSeeds() {
        if (!_uiState.value.serviceConnected) {
            _uiState.update { it.copy(errorMessage = "Not connected to service") }
            return
        }

        _uiState.update { it.copy(loading = true, errorMessage = null) }

        viewModelScope.launch {
            try {
                // Auto-initialize balance repository if not already initialized
                if (!balanceRepository.isInitialized()) {
                    balanceRepository.initialize(Cluster.DEVNET)
                    Log.d("WalletServiceDemo", "Auto-initialized balance repository for DEVNET")
                }

                // First fetch seeds from Seed Vault
                refreshUiState()

                // Auto-fetch balances if repository is initialized and we have seeds
                if (balanceRepository.isInitialized() && _uiState.value.seeds.isNotEmpty()) {
                    fetchBalancesForSeeds()
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        errorMessage = "Failed to refresh seeds: ${e.message}",
                        loading = false
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

    private suspend fun refreshUiState() {
        val context = getApplication<Application>()
        Log.d("WalletServiceDemo", "Starting refreshUiState for Solana seeds")

        // Check for unauthorized seeds - matches fakewallet pattern
        val hasUnauthorizedSeeds = try {
            withContext(Dispatchers.Default) {
                Wallet.hasUnauthorizedSeedsForPurpose(
                    context,
                    WalletContractV1.PURPOSE_SIGN_SOLANA_TRANSACTION
                )
            }
        } catch (e: SecurityException) {
            Log.w("WalletServiceDemo", "SecurityException checking unauthorized seeds - permission not granted", e)
            false
        }
        Log.d("WalletServiceDemo", "Has unauthorized seeds: $hasUnauthorizedSeeds")

        val seeds = mutableListOf<SolanaSeed>()

        // Get authorized seeds - matches fakewallet pattern
        val authorizedSeedsCursor: Cursor? = try {
            withContext(Dispatchers.Default) {
                Wallet.getAuthorizedSeeds(
                    context,
                    WalletContractV1.AUTHORIZED_SEEDS_ALL_COLUMNS
                )
            }
        } catch (e: SecurityException) {
            Log.e("WalletServiceDemo", "SecurityException getting authorized seeds. " +
                "Make sure ACCESS_SEED_VAULT permission is granted via PermissionGauntletActivity.", e)
            null
        }

        Log.d("WalletServiceDemo", "Authorized seeds cursor: $authorizedSeedsCursor")

        authorizedSeedsCursor?.use { cursor ->
            Log.d("WalletServiceDemo", "Authorized seeds cursor count: ${cursor.count}")
            while (cursor.moveToNext()) {
                val authToken = cursor.getLong(0)
                val authPurpose = cursor.getInt(1)
                val seedName = cursor.getString(2)
                val isBackedUp =
                    if (cursor.columnCount == 4) cursor.getShort(3) == 1.toShort() else false

                Log.d("WalletServiceDemo", "Processing seed: authToken=$authToken, name=$seedName")

                val accounts = mutableListOf<SolanaAccount>()

                val accountsCursor: Cursor? = withContext(Dispatchers.Default) {
                    Wallet.getAccounts(
                        context,
                        authToken,
                        WalletContractV1.ACCOUNTS_ALL_COLUMNS,
                        WalletContractV1.ACCOUNTS_ACCOUNT_IS_USER_WALLET, "1"
                    )
                }

                accountsCursor?.use { acCursor ->
                    while (acCursor.moveToNext()) {
                        val accountId = acCursor.getLong(0)
                        val derivationPath = Uri.parse(acCursor.getString(1))
                        val publicKeyEncoded = acCursor.getString(3)
                        val accountName = acCursor.getString(4)
                        accounts.add(
                            SolanaAccount(
                                accountId = accountId,
                                name = accountName.ifBlank { publicKeyEncoded.substring(0, 10) },
                                derivationPath = derivationPath,
                                publicKeyEncoded = publicKeyEncoded
                            )
                        )
                        Log.d("Mithilesh",publicKeyEncoded.toString())
                    }
                }

                seeds.add(
                    SolanaSeed(
                        authToken = authToken,
                        name = seedName.ifBlank { authToken.toString() },
                        authPurpose = authPurpose,
                        isBackedUp = isBackedUp,
                        accounts = accounts
                    )
                )
            }
        }

        // Get implementation limits
        val implementationLimits = withContext(Dispatchers.Default) {
            Wallet.getImplementationLimitsForPurpose(
                context,
                WalletContractV1.PURPOSE_SIGN_SOLANA_TRANSACTION
            )
        }

        // Add synthetic entry for BIP32 path length limit
        val limitsWithBip32Depth = implementationLimits.plus(
            IMPLEMENTATION_LIMITS_MAX_BIP32_PATH_DEPTH to WalletContractV1.BIP32_URI_MAX_DEPTH.toLong()
        )

        val maxSigningRequests =
            limitsWithBip32Depth[WalletContractV1.IMPLEMENTATION_LIMITS_MAX_SIGNING_REQUESTS]?.toInt() ?: 0
        val maxRequestedSignatures =
            limitsWithBip32Depth[WalletContractV1.IMPLEMENTATION_LIMITS_MAX_REQUESTED_SIGNATURES]?.toInt() ?: 0
        val maxRequestedPublicKeys =
            limitsWithBip32Depth[WalletContractV1.IMPLEMENTATION_LIMITS_MAX_REQUESTED_PUBLIC_KEYS]?.toInt() ?: 0

        val firstRequestedPublicKey = Bip32DerivationPath.newBuilder()
            .appendLevel(BipLevel(FIRST_REQUESTED_PUBLIC_KEY_INDEX, true))
            .build().toUri()
            .toString()

        val lastRequestedPublicKey = Bip32DerivationPath.newBuilder()
            .appendLevel(
                BipLevel(
                    FIRST_REQUESTED_PUBLIC_KEY_INDEX + maxRequestedPublicKeys - 1,
                    true
                )
            )
            .build().toUri()
            .toString()

        val seedVaultLimits = SeedVaultLimits(
            maxSigningRequests = maxSigningRequests,
            maxRequestedSignatures = maxRequestedSignatures,
            maxRequestedPublicKeys = maxRequestedPublicKeys,
            firstRequestedPublicKey = firstRequestedPublicKey,
            lastRequestedPublicKey = lastRequestedPublicKey
        )

        Log.d("WalletServiceDemo", "Total seeds loaded: ${seeds.size}")

        // Determine appropriate error message
        val errorMessage = when {
            authorizedSeedsCursor == null -> {
                "Permission denied to access Seed Vault. " +
                "Please ensure the PermissionGauntletActivity granted ACCESS_SEED_VAULT permission."
            }
            seeds.isEmpty() -> "No authorized seeds found. Make sure you have created seeds in the Seed Vault."
            else -> null
        }

        _uiState.update {
            it.copy(
                seeds = seeds,
                hasUnauthorizedSeeds = hasUnauthorizedSeeds,
                seedVaultLimits = seedVaultLimits,
                loading = false,
                errorMessage = errorMessage
            )
        }

        Log.d("WalletServiceDemo", "refreshUiState completed with ${seeds.size} seeds")
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

        // Mark all accounts as loading
        _uiState.update { state ->
            state.copy(
                balanceLoading = true,
                seeds = state.seeds.map { seed ->
                    seed.copy(
                        accounts = seed.accounts.map { account ->
                            account.copy(isBalanceLoading = true)
                        }
                    )
                }
            )
        }

        // Collect all public keys
        val allAccounts = currentSeeds.flatMap { it.accounts }
        val publicKeys = allAccounts.map { it.publicKeyEncoded }

        Log.d("WalletServiceDemo", "Fetching balances for ${publicKeys.size} accounts from ${balanceRepository.getRpcEndpoint()}")

        // Fetch all balances
        val balances = balanceRepository.fetchBalances(publicKeys)

        // Calculate total balance
        val totalBalance = balances.values.filterNotNull().sum()
        val successCount = balances.values.count { it != null }
        val failCount = balances.size - successCount

        Log.d("WalletServiceDemo", "Balance fetch complete: $successCount success, $failCount failed, Total: $totalBalance SOL")

        // Update seeds with balance data
        _uiState.update { state ->
            state.copy(
                seeds = state.seeds.map { seed ->
                    seed.copy(
                        accounts = seed.accounts.map { account ->
                            val balance = balances[account.publicKeyEncoded]
                            account.copy(
                                balance = balance,
                                isBalanceLoading = false
                            )
                        }
                    )
                },
                totalBalance = totalBalance,
                balanceLoading = false,
                balanceError = if (failCount > 0) "Failed to fetch $failCount balance(s)" else null
            )
        }
    }

    /**
     * Observe Seed Vault content changes and auto-refresh UI state.
     * This replicates the functionality from fakewallet's MainViewModel.
     */
    private fun observeSeedVaultContentChanges() {
        val context = getApplication<Application>()
        context.contentResolver.registerContentObserver(
            WalletContractV1.WALLET_PROVIDER_CONTENT_URI_BASE,
            true,
            object : ContentObserver(Handler(context.mainLooper)) {
                override fun onChange(selfChange: Boolean) =
                    throw NotImplementedError("Stub for legacy onChange")
                override fun onChange(selfChange: Boolean, uri: Uri?) =
                    throw NotImplementedError("Stub for legacy onChange")
                override fun onChange(selfChange: Boolean, uri: Uri?, flags: Int) =
                    throw NotImplementedError("Stub for legacy onChange")

                override fun onChange(selfChange: Boolean, uris: Collection<Uri>, flags: Int) {
                    Log.d("WalletServiceDemo", "Received change notification for $uris (flags=$flags); refreshing viewmodel")
                    viewModelScope.launch {
                        refreshUiState()
                    }
                }
            }
        )
    }

    /**
     * Mark accounts as user wallets after a seed is authorized/created/imported.
     * This is REQUIRED for accounts to appear in getAccounts() queries with
     * ACCOUNTS_ACCOUNT_IS_USER_WALLET filter.
     * 
     * This replicates the functionality from fakewallet's MainViewModel.
     * 
     * @param authToken The authorization token of the newly added seed
     */
    fun markAccountsAsUserWallets(authToken: Long) {
        // Mark two accounts as user wallets. This simulates a real wallet app exploring each
        // account and marking them as containing user funds.
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                for (i in 0..1) {
                    val derivationPath = Bip44DerivationPath.newBuilder()
                        .setAccount(BipLevel(i, true))
                        .build()
                    val resolvedDerivationPath = Wallet.resolveDerivationPath(
                        context,
                        derivationPath.toUri(),
                        WalletContractV1.PURPOSE_SIGN_SOLANA_TRANSACTION
                    )
                    Log.d("WalletServiceDemo", "Resolved BIP derivation path '$derivationPath' to BIP32 derivation path '$resolvedDerivationPath' for purpose ${WalletContractV1.PURPOSE_SIGN_SOLANA_TRANSACTION}")
                    
                    val cursor: Cursor? = withContext(Dispatchers.Default) {
                        Wallet.getAccounts(
                            context,
                            authToken,
                            arrayOf(
                                WalletContractV1.ACCOUNTS_ACCOUNT_ID,
                                WalletContractV1.ACCOUNTS_ACCOUNT_IS_USER_WALLET
                            ),
                            WalletContractV1.ACCOUNTS_BIP32_DERIVATION_PATH,
                            resolvedDerivationPath.toString()
                        )
                    }
                    
                    cursor?.use { c ->
                        if (c.moveToNext()) {
                            val accountId = c.getLong(0)
                            val isUserWallet = (c.getShort(1) == 1.toShort())
                            if (!isUserWallet) {
                                Wallet.updateAccountIsUserWallet(context, authToken, accountId, true)
                                Log.d("WalletServiceDemo", "Marking account '$resolvedDerivationPath' as a user wallet")
                            } else {
                                Log.d("WalletServiceDemo", "Account '$resolvedDerivationPath' is already marked as a user wallet")
                            }
                        } else {
                            Log.w("WalletServiceDemo", "Failed to find expected account '$resolvedDerivationPath'")
                        }
                    }
                }
                // Refresh UI after marking accounts
                refreshUiState()
            } catch (e: Exception) {
                Log.e("WalletServiceDemo", "Failed to mark accounts as user wallets", e)
            }
        }
    }

    // ==================== SOL Transfer Methods ====================

    /**
     * Create a SOL transfer transaction (unsigned).
     * The created transaction will be stored in the UI state as a base64-encoded string,
     * ready to be signed by the Seed Vault.
     *
     * @param fromPublicKey The sender's public key
     * @param toPublicKey The recipient's public key
     * @param amountSol The amount of SOL to transfer
     */
    fun createTransferTransaction(
        fromPublicKey: String,
        toPublicKey: String,
        amountSol: Double
    ) {
        if (!transferRepository.isInitialized()) {
            transferRepository.initialize(SolanaTransferRepository.Cluster.DEVNET)
            Log.d("WalletServiceDemo", "Auto-initialized transfer repository for DEVNET")
        }

        // Validate inputs
        if (amountSol <= 0) {
            _uiState.update {
                it.copy(transferError = "Amount must be greater than 0")
            }
            return
        }

        _uiState.update {
            it.copy(
                transferLoading = true,
                transferError = null,
                pendingTransferTransaction = null
            )
        }

        viewModelScope.launch {
            try {
                // Use the new method that returns both transaction and serialized message
                val txData = transferRepository.createTransferTransactionData(
                    fromPublicKey,
                    toPublicKey,
                    amountSol
                )

                if (txData != null) {
                    val (transaction, serializedMessage) = txData
                    Log.d("WalletServiceDemo", "Transfer transaction created successfully")
                    
                    // Store for later signing
                    pendingTransaction = transaction
                    pendingSerializedMessage = serializedMessage
                    
                    // Find the account and its seed for auth token and derivation path
                    val accountWithSeed = uiState.value.seeds.flatMap { seed ->
                        seed.accounts.map { account -> seed to account }
                    }.find { (_, account) ->
                        account.publicKeyEncoded == fromPublicKey
                    }

                    val authToken = accountWithSeed?.first?.authToken ?: 0L
                    val derivationPath = accountWithSeed?.second?.derivationPath
                    
                    // Serialize the message for Seed Vault to sign
                    val messageBase64 = Base64.encodeToString(serializedMessage, Base64.DEFAULT)

                    _uiState.update {
                        it.copy(
                            pendingTransferTransaction = messageBase64,
                            transferLoading = false,
                            transferError = null
                        )
                    }

                    // Emit event to trigger Seed Vault signing (only if we have a derivation path)
                    if (derivationPath != null) {
                        _viewModelEvents.emit(
                            ViewModelEvent.SignTransferTransaction(messageBase64, authToken, derivationPath)
                        )
                    } else {
                        _uiState.update {
                            it.copy(
                                transferError = "Could not find derivation path for account",
                                transferLoading = false
                            )
                        }
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            transferError = "Failed to create transfer transaction",
                            transferLoading = false
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("WalletServiceDemo", "Error creating transfer transaction", e)
                _uiState.update {
                    it.copy(
                        transferError = "Error: ${e.message}",
                        transferLoading = false
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
                transferError = null
            )
        }

        viewModelScope.launch {
            try {
                val transaction = pendingTransaction
                val message = pendingSerializedMessage
                
                if (transaction == null || message == null) {
                    _uiState.update {
                        it.copy(
                            transferError = "No pending transaction to sign",
                            transferLoading = false
                        )
                    }
                    return@launch
                }

                // Decode the signature
                val signature = Base64.decode(signatureBase64, Base64.DEFAULT)
                
                Log.d("WalletServiceDemo", "Creating signed transaction with signature: ${signature.size} bytes")
                
                // Serialize the signed transaction properly
                val signedTxBytes = transferRepository.serializeSignedTransaction(message, signature)
                
                // Send the transaction
                val result = transferRepository.sendSignedTransaction(signedTxBytes)

                if (result.success) {
                    Log.d("WalletServiceDemo", "Transfer sent! Signature: ${result.signature}")
                    _uiState.update {
                        it.copy(
                            lastTransferSignature = result.signature,
                            pendingTransferTransaction = null,
                            transferLoading = false,
                            transferError = null
                        )
                    }
                    // Clear pending transaction data
                    pendingTransaction = null
                    pendingSerializedMessage = null
                    
                    // Refresh balances after successful transfer
                    fetchAllAccountBalances()
                } else {
                    _uiState.update {
                        it.copy(
                            transferError = result.error ?: "Failed to send transfer",
                            transferLoading = false
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("WalletServiceDemo", "Error sending transfer", e)
                _uiState.update {
                    it.copy(
                        transferError = "Error: ${e.message}",
                        transferLoading = false
                    )
                }
            }
        }
    }

    /**
     * Clear transfer-related state.
     */
    fun clearTransferState() {
        _uiState.update {
            it.copy(
                transferError = null,
                lastTransferSignature = null,
                pendingTransferTransaction = null
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        walletClient.unbind()
        balanceRepository.close()
        transferRepository.close()
    }

    companion object {
        private const val FIRST_REQUESTED_PUBLIC_KEY_INDEX = 1000
        private const val IMPLEMENTATION_LIMITS_MAX_BIP32_PATH_DEPTH = "MaxBip32PathDepth"
    }
}

/**
 * Events emitted by the ViewModel for UI actions that require Activity context.
 */
sealed interface ViewModelEvent {
    data object AuthorizeNewSeed : ViewModelEvent
    data class SignTransferTransaction(
        val transactionBase64: String,
        val authToken: Long,
        val signerDerivationPath: Uri
    ) : ViewModelEvent
}
