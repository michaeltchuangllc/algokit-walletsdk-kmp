package com.michaeltchuang.walletsdk.ui.onboarding.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.account.domain.model.local.SolanaAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetImportedSolanaAddressesUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetSolanaAccountsFromSeedVaultUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.ImportSolanaAccountsUseCase
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch

/**
 * ViewModel for Solana Accounts screen.
 * Handles loading Solana accounts from the seed vault and importing selected accounts.
 */
class SolanaAccountsViewModel(
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
    private val getSolanaAccountsFromSeedVaultUseCase: GetSolanaAccountsFromSeedVaultUseCase,
    private val getImportedSolanaAddressesUseCase: GetImportedSolanaAddressesUseCase,
    private val importSolanaAccountsUseCase: ImportSolanaAccountsUseCase,
) : ViewModel(),
    StateViewModel<SolanaAccountsViewModel.ViewState> by stateDelegate,
    EventViewModel<SolanaAccountsViewModel.ViewEvent> by eventDelegate {
    init {
        stateDelegate.setDefaultState(ViewState.Idle)
    }

    /**
     * Loads Solana accounts from the seed vault.
     */
    fun loadSolanaAccounts(selectedSeedIds: Set<String> = emptySet()) {
        stateDelegate.onState<ViewState.Idle> {
            stateDelegate.updateState { ViewState.Loading }
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val seeds = getSolanaAccountsFromSeedVaultUseCase()

                    if (seeds.isEmpty()) {
                        stateDelegate.updateState {
                            ViewState.Error("No Solana seeds found in Seed Vault. Please authorize a seed first.")
                        }
                        return@launch
                    }

                    val filteredSeeds =
                        if (selectedSeedIds.isEmpty()) {
                            seeds
                        } else {
                            seeds.filter { selectedSeedIds.contains(it.authToken.toString()) }
                        }
                    if (filteredSeeds.isEmpty()) {
                        stateDelegate.updateState {
                            ViewState.Error("No Solana accounts found for selected seed.")
                        }
                        return@launch
                    }
                    // Flatten all accounts from selected seeds
                    val allAccounts =
                        filteredSeeds.flatMap { seed ->
                            seed.accounts.map { account ->
                                Triple(account.address, account.accountName ?: seed.name, account.derivationPath)
                            }
                        }

                    // Log all accounts details
                    println("📋 SolanaAccountsViewModel - All accounts from Seed Vault:")
                    allAccounts.forEachIndexed { index, (address, seedName, derivationPath) ->
                        println("   [$index] Address: $address, Seed: $seedName, DerivationPath: $derivationPath")
                    }

                    // Check which accounts are already imported
                    val allAddresses = allAccounts.map { it.first }
                    val importedAddresses = getImportedSolanaAddressesUseCase(allAddresses)

                    val accountItems =
                        allAccounts.map { (address, seedName, derivationPath) ->
                            SolanaAccountItem(
                                address = address,
                                accountName = seedName,
                                derivationPath = derivationPath,
                                isImported = importedAddresses.contains(address),
                            )
                        }

                    stateDelegate.updateState {
                        ViewState.Content(
                            solanaAccounts = accountItems,
                            selectedAddresses = emptySet(),
                        )
                    }
                } catch (e: Exception) {
                    // Check if this is a permission/security error
                    val errorMessage =
                        when {
                            e.message?.contains("permission", ignoreCase = true) == true ||
                                e.message?.contains("security", ignoreCase = true) == true ->
                                "Permission denied to access Seed Vault. Please ensure the app has the necessary permissions."
                            else -> "Failed to load Solana accounts: ${e.message}"
                        }
                    stateDelegate.updateState {
                        ViewState.Error(errorMessage)
                    }
                }
            }
        }
    }

    /**
     * Toggles the selection state of a Solana account.
     */
    fun toggleAccountSelection(
        address: String,
        isSelected: Boolean,
    ) {
        stateDelegate.onState<ViewState.Content> { currentState ->
            val updatedSelection =
                if (isSelected) {
                    currentState.selectedAddresses + address
                } else {
                    currentState.selectedAddresses - address
                }
            stateDelegate.updateState {
                currentState.copy(selectedAddresses = updatedSelection)
            }
        }
    }

    /**
     * Selects all non-imported Solana accounts.
     */
    fun selectAllAccounts() {
        stateDelegate.onState<ViewState.Content> { currentState ->
            val notImportedAddresses =
                currentState.solanaAccounts
                    .filter { !it.isImported }
                    .map { it.address }
                    .toSet()
            stateDelegate.updateState {
                currentState.copy(selectedAddresses = notImportedAddresses)
            }
        }
    }

    /**
     * Unselects all Solana accounts.
     */
    fun unselectAllAccounts() {
        stateDelegate.onState<ViewState.Content> { currentState ->
            stateDelegate.updateState {
                currentState.copy(selectedAddresses = emptySet())
            }
        }
    }

    /**
     * Imports the selected Solana accounts.
     */
    fun importSelectedAccounts() {
        stateDelegate.onState<ViewState.Content> { currentState ->
            viewModelScope.launch(Dispatchers.IO) {
                val selectedAccounts =
                    currentState.solanaAccounts.filter {
                        currentState.selectedAddresses.contains(it.address)
                    }

                if (selectedAccounts.isEmpty()) {
                    eventDelegate.sendEvent(ViewEvent.Error("No accounts selected for import"))
                    return@launch
                }

                try {
                    // Convert to domain models and import
                    val accountsToImport =
                        selectedAccounts.map { item ->
                            // Extract chainId from derivation path (e.g., "m/44'/501'/0'" -> "501")
                            val chainId = extractChainIdFromDerivationPath(item.derivationPath)
                            SolanaAccount(
                                publicKey = item.address,
                                address = item.address,
                                chainId = chainId,
                                accountName = item.accountName,
                            )
                        }

                    importSolanaAccountsUseCase(accountsToImport)
                    eventDelegate.sendEvent(ViewEvent.AccountsImported(selectedAccounts.size))
                } catch (e: Exception) {
                    eventDelegate.sendEvent(ViewEvent.Error("Failed to import accounts: ${e.message}"))
                }
            }
        }
    }

    /**
     * Extracts chainId from derivation path.
     * e.g., "m/44'/501'/0'" -> "501" or "m/44/501/0/0" -> "501"
     */
    private fun extractChainIdFromDerivationPath(derivationPath: String): String {
        // Split by '/' and find the coin type segment (index 2 in BIP44 format)
        return derivationPath
            .split("/")
            .getOrNull(2)  // Index 2 should be coin type (e.g., "501'" or "501")
            ?.replace("'", "")  // Remove the hardened derivation marker if present
            ?: "501"  // Default to Solana if not found
    }

    /**
     * Data class representing a Solana account item in the UI.
     */
    data class SolanaAccountItem(
        val address: String,
        val accountName: String? = null,
        val derivationPath: String,
        val isImported: Boolean = false,
    )

    /**
     * Sealed interface representing the different states of the screen.
     */
    sealed interface ViewState {
        data object Idle : ViewState

        data object Loading : ViewState

        data class Content(
            val solanaAccounts: List<SolanaAccountItem> = emptyList(),
            val selectedAddresses: Set<String> = emptySet(),
        ) : ViewState

        data class Error(
            val message: String,
        ) : ViewState
    }

    /**
     * Sealed interface representing events that can be triggered from the ViewModel.
     */
    sealed interface ViewEvent {
        data class AccountsImported(
            val count: Int,
        ) : ViewEvent

        data class Error(
            val message: String,
        ) : ViewEvent
    }
}
