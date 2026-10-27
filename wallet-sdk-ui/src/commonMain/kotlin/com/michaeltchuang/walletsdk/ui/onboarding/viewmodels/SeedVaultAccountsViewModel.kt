package com.michaeltchuang.walletsdk.ui.onboarding.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.account.domain.model.local.SeedVaultAccount
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.SeedVaultAccountRepository
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAlgorandSeedVaultSeeds
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetImportedSolanaAddressesUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetSolanaAccountsFromSeedVaultUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.ImportAlgorandSeedVaultAccounts
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.ImportSolanaAccountsUseCase
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.core.foundation.utils.Chain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SeedVaultAccountsViewModel(
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
    private val getSolanaAccountsFromSeedVaultUseCase: GetSolanaAccountsFromSeedVaultUseCase,
    private val getImportedSolanaAddressesUseCase: GetImportedSolanaAddressesUseCase,
    private val importSolanaAccountsUseCase: ImportSolanaAccountsUseCase,
    private val getAlgorandSeedVaultSeeds: GetAlgorandSeedVaultSeeds,
    private val importAlgorandSeedVaultAccounts: ImportAlgorandSeedVaultAccounts,
    private val accountRepository: SeedVaultAccountRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel(),
    StateViewModel<SeedVaultAccountsViewModel.ViewState> by stateDelegate,
    EventViewModel<SeedVaultAccountsViewModel.ViewEvent> by eventDelegate {
    init {
        stateDelegate.setDefaultState(ViewState.Idle)
    }

    private var loadJob: Job? = null

    fun setAccountItems(accountItems: List<SeedVaultAccountItem>) {
        if (accountItems.isEmpty()) {
            stateDelegate.updateState {
                ViewState.Error("No accounts found for selected seed.")
            }
        } else {
            stateDelegate.updateState {
                ViewState.Content(
                    accounts = accountItems,
                    selectedAddresses = emptySet(),
                )
            }
        }
    }

    fun setError(message: String) {
        stateDelegate.updateState { ViewState.Error(message) }
    }

    fun setLoading() {
        stateDelegate.updateState { ViewState.Loading }
    }

    suspend fun checkImportedAddresses(addresses: List<String>): Set<String> = getImportedSolanaAddressesUseCase(addresses)

    fun loadSolanaAccounts(selectedSeedIds: Set<String> = emptySet()) = loadAccounts(Chain.SOLANA, selectedSeedIds)

    fun loadAccounts(
        accountType: Chain,
        selectedSeedIds: Set<String> = emptySet(),
    ) {
        if ((state.value as? ViewState.Content)?.isImporting == true) return
        loadJob?.cancel()
        stateDelegate.updateState { ViewState.Loading }
        loadJob =
            viewModelScope.launch(dispatcher) {
                try {
                    val accounts =
                        when (accountType) {
                            Chain.ALGORAND ->
                                getAlgorandSeedVaultSeeds()
                                    .filter { selectedSeedIds.isEmpty() || it.id in selectedSeedIds }
                                    // Only the primary (index 0) account of each seed is importable.
                                    .mapNotNull { seed ->
                                        seed.primaryAccount?.let { account ->
                                            SeedVaultAccountItem(
                                                address = account.address,
                                                publicKey = account.publicKey,
                                                accountName = account.address,
                                                derivationPath = "",
                                                chainId = Chain.ALGORAND.id,
                                            )
                                        }
                                    }
                            Chain.SOLANA ->
                                getSolanaAccountsFromSeedVaultUseCase()
                                    .filter { selectedSeedIds.isEmpty() || it.authToken.toString() in selectedSeedIds }
                                    .flatMap { seed ->
                                        seed.accounts.map { account ->
                                            SeedVaultAccountItem(
                                                address = account.address,
                                                accountName = account.accountName ?: seed.name,
                                                derivationPath = account.derivationPath,
                                                chainId = Chain.SOLANA.id,
                                            )
                                        }
                                    }
                        }.distinctBy { it.address }
                    val importedAddresses = accountRepository.getAllAddresses().toSet()
                    setAccountItems(accounts.map { it.copy(isImported = it.address in importedAddresses) })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    setError("Failed to load accounts from Seed Vault: ${e.message}")
                }
            }
    }

    fun toggleAccountSelection(
        address: String,
        isSelected: Boolean,
    ) {
        stateDelegate.onState<ViewState.Content> { currentState ->
            if (currentState.isImporting || currentState.accounts.none { it.address == address && !it.isImported }) {
                return@onState
            }
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

    fun selectAllAccounts() {
        stateDelegate.onState<ViewState.Content> { currentState ->
            if (currentState.isImporting) return@onState
            val notImportedAddresses =
                currentState.accounts
                    .filter { !it.isImported }
                    .map { it.address }
                    .toSet()
            stateDelegate.updateState {
                currentState.copy(selectedAddresses = notImportedAddresses)
            }
        }
    }

    fun unselectAllAccounts() {
        stateDelegate.onState<ViewState.Content> { currentState ->
            if (currentState.isImporting) return@onState
            stateDelegate.updateState {
                currentState.copy(selectedAddresses = emptySet())
            }
        }
    }

    fun importSelectedAccounts() {
        stateDelegate.onState<ViewState.Content> { currentState ->
            if (currentState.isImporting || currentState.selectedAddresses.isEmpty()) return@onState
            stateDelegate.updateState { currentState.copy(isImporting = true) }
            viewModelScope.launch(dispatcher) {
                val selectedAccounts =
                    currentState.accounts.filter {
                        currentState.selectedAddresses.contains(it.address) && !it.isImported
                    }

                if (selectedAccounts.isEmpty()) {
                    stateDelegate.updateState { currentState.copy(isImporting = false) }
                    eventDelegate.sendEvent(ViewEvent.Error("No accounts selected for import"))
                    return@launch
                }

                try {
                    // Convert to domain models and import
                    val accountsToImport =
                        selectedAccounts.map { item ->
                            SeedVaultAccount(
                                publicKey = item.publicKey,
                                address = item.address,
                                chainId = item.chainId,
                                accountName = item.accountName,
                            )
                        }

                    when {
                        accountsToImport.all { it.chainId == Chain.ALGORAND.id } ->
                            importAlgorandSeedVaultAccounts(accountsToImport)
                        accountsToImport.all { it.chainId == Chain.SOLANA.id } ->
                            importSolanaAccountsUseCase(accountsToImport)
                        else -> error("Cannot import mixed or unsupported Seed Vault chains")
                    }
                    stateDelegate.updateState {
                        currentState.copy(
                            accounts =
                                currentState.accounts.map {
                                    if (it.address in currentState.selectedAddresses) it.copy(isImported = true) else it
                                },
                            selectedAddresses = emptySet(),
                            isImporting = false,
                        )
                    }
                    eventDelegate.sendEvent(ViewEvent.AccountsImported(selectedAccounts.size))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    stateDelegate.updateState { currentState.copy(isImporting = false) }
                    eventDelegate.sendEvent(ViewEvent.Error("Failed to import accounts: ${e.message}"))
                }
            }
        }
    }

    data class SeedVaultAccountItem(
        val address: String,
        val accountName: String? = null,
        val derivationPath: String,
        val isImported: Boolean = false,
        val publicKey: String = address,
        val chainId: String,
    )

    sealed interface ViewState {
        data object Idle : ViewState

        data object Loading : ViewState

        data class Content(
            val accounts: List<SeedVaultAccountItem> = emptyList(),
            val selectedAddresses: Set<String> = emptySet(),
            val isImporting: Boolean = false,
        ) : ViewState

        data class Error(
            val message: String,
        ) : ViewState
    }

    sealed interface ViewEvent {
        data class AccountsImported(
            val count: Int,
        ) : ViewEvent

        data class Error(
            val message: String,
        ) : ViewEvent
    }
}
