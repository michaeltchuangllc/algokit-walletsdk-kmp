package com.michaeltchuang.walletsdk.ui.settings.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.AccountCreationHdKeyTypeMapper
import com.michaeltchuang.walletsdk.core.account.domain.model.core.AccountCreation
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetHdEntropy
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetHdWalletSummaries
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.HdKeyAddressIndex
import com.michaeltchuang.walletsdk.core.algosdk.createBip39Wallet
import com.michaeltchuang.walletsdk.core.algosdk.getBip39Wallet
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.core.foundation.utils.CreationType
import com.michaeltchuang.walletsdk.core.foundation.utils.manager.AccountCreationManager
import kotlinx.coroutines.launch

class HDWalletSelectionViewModel(
    private val getHdWalletSummaries: GetHdWalletSummaries,
    private val accountCreationHdKeyTypeMapper: AccountCreationHdKeyTypeMapper,
    private val getHdEntropy: GetHdEntropy,
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
) : ViewModel(),
    StateViewModel<HDWalletSelectionViewModel.ViewState> by stateDelegate,
    EventViewModel<HDWalletSelectionViewModel.ViewEvent> by eventDelegate {
    init {
        stateDelegate.setDefaultState(ViewState.Idle)
        loadLocalWallets()
    }

    fun loadLocalWallets() {
        stateDelegate.updateState { ViewState.Loading }
        viewModelScope.launch {
            val walletItemPreviews =
                getHdWalletSummaries()
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

    fun createNewHdAccount(
        seedId: Int,
        maxAccountIndex: Int,
    ) {
        viewModelScope.launch {
            val entropy = getHdEntropy(seedId) ?: return@launch
            val nextHdAccountIndex = maxAccountIndex + 1
            val wallet = getBip39Wallet(entropy)
            val index = HdKeyAddressIndex(nextHdAccountIndex, changeIndex = 0, keyIndex = 0)
            val hdKeyAddress = wallet.generateAddress(index)
            val accountCreation =
                AccountCreation(
                    address = hdKeyAddress.address,
                    customName = null,
                    isBackedUp = false,
                    type = accountCreationHdKeyTypeMapper(entropy, hdKeyAddress, seedId),
                    creationType = CreationType.CREATE,
                )
            // Store the account creation data in the manager
            AccountCreationManager.storePendingAccountCreation(accountCreation)
            eventDelegate.sendEvent(
                ViewEvent.AccountCreated(
                    accountCreation,
                ),
            )
        }
    }

    fun createHdKeyAccount() {
        viewModelScope.launch {
            val wallet = createBip39Wallet()
            val hdKeyAddress = wallet.generateAddress(HdKeyAddressIndex())
            val hdKeyType =
                accountCreationHdKeyTypeMapper(
                    wallet.getEntropy().value,
                    hdKeyAddress,
                    seedId = null,
                )
            val accountCreation =
                AccountCreation(
                    address = hdKeyAddress.address,
                    customName = null,
                    isBackedUp = false,
                    type = hdKeyType,
                    creationType = CreationType.CREATE,
                )
            // Store the account creation data in the manager
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
