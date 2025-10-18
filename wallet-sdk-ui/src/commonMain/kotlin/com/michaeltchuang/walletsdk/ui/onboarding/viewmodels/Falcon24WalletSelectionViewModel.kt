package com.michaeltchuang.walletsdk.ui.onboarding.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.AccountCreationFalcon24TypeMapper
import com.michaeltchuang.walletsdk.core.account.domain.model.core.AccountCreation
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetFalcon24WalletSummaries
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetHdEntropy
import com.michaeltchuang.walletsdk.core.algosdk.createBip39Wallet
import com.michaeltchuang.walletsdk.core.algosdk.getBip39Wallet
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.core.foundation.utils.CreationType
import com.michaeltchuang.walletsdk.core.foundation.utils.manager.AccountCreationManager
import kotlinx.coroutines.launch

class Falcon24WalletSelectionViewModel(
    private val getFalcon24WalletSummaries: GetFalcon24WalletSummaries,
    private val accountCreationFalcon24TypeMapper: AccountCreationFalcon24TypeMapper,
    private val getHdEntropy: GetHdEntropy,
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
) : ViewModel(),
    StateViewModel<Falcon24WalletSelectionViewModel.ViewState> by stateDelegate,
    EventViewModel<Falcon24WalletSelectionViewModel.ViewEvent> by eventDelegate {
    init {
        stateDelegate.setDefaultState(ViewState.Idle)
        loadLocalWallets()
    }

    fun loadLocalWallets() {
        stateDelegate.updateState { ViewState.Loading }
        viewModelScope.launch {
            val walletItemPreviews =
                getFalcon24WalletSummaries()
                    ?.map {
                        WalletItemPreview(
                            seedId = it.seedId,
                            name = "Wallet #${it.seedId}",
                            numberOfAccounts = "${it.accountCount} account",
                            primaryValue = it.primaryValue,
                            secondaryValue = it.secondaryValue,
                            maxAccountIndex = it.maxAccountIndex,
                        )
                    }.orEmpty()
            stateDelegate.updateState {
                ViewState.Content(
                    walletItemPreviews = walletItemPreviews,
                )
            }
        }
    }

    fun createNewFalcon24Account(seedId: Int) {
        viewModelScope.launch {
            val entropy = getHdEntropy(seedId) ?: return@launch
            val wallet = getBip39Wallet(entropy)
            val mnemonic = wallet.getMnemonic().words.joinToString(" ")
            val falcon24 = wallet.generateFalcon24Address(mnemonic)
            val accountCreation =
                AccountCreation(
                    address = falcon24.address,
                    customName = null,
                    isBackedUp = false,
                    type = accountCreationFalcon24TypeMapper(entropy, falcon24, seedId),
                    creationType = CreationType.CREATE,
                )

            AccountCreationManager.storePendingAccountCreation(accountCreation)
            eventDelegate.sendEvent(
                ViewEvent.AccountCreated(
                    accountCreation,
                ),
            )
        }
    }

    fun createFalcon24Account() {
        viewModelScope.launch {
            val wallet = createBip39Wallet()
            val mnemonic = wallet.getMnemonic().words.joinToString(" ")
            val falcon24 = wallet.generateFalcon24Address(mnemonic)
            val falcon24Type =
                accountCreationFalcon24TypeMapper(
                    wallet.getEntropy().value,
                    falcon24,
                    seedId = null,
                )
            val accountCreation =
                AccountCreation(
                    address = falcon24.address,
                    customName = null,
                    isBackedUp = false,
                    type = falcon24Type,
                    creationType = CreationType.CREATE,
                )

            AccountCreationManager.storePendingAccountCreation(accountCreation)
            eventDelegate.sendEvent(
                ViewEvent.AccountCreated(
                    accountCreation,
                ),
            )
        }
    }

    private fun displayError(message: String) {
        viewModelScope.launch {
            eventDelegate.sendEvent(ViewEvent.Error(message))
        }
    }

    data class WalletItemPreview(
        val seedId: Int,
        val name: String,
        val numberOfAccounts: String,
        val primaryValue: String,
        val secondaryValue: String,
        val maxAccountIndex: Int,
    )

    sealed interface ViewState {
        data object Idle : ViewState

        data object Loading : ViewState

        data class Content(
            val walletItemPreviews: List<WalletItemPreview> = emptyList(),
        ) : ViewState

        data class Error(
            val message: String,
        ) : ViewState
    }

    sealed interface ViewEvent {
        data class AccountCreated(
            val accountCreation: AccountCreation,
        ) : ViewEvent

        data class Error(
            val message: String,
        ) : ViewEvent
    }
}
