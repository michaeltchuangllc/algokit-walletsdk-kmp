package com.michaeltchuang.walletsdk.ui.onboarding.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.AccountCreationHdKeyTypeMapper
import com.michaeltchuang.walletsdk.core.account.domain.model.core.AccountCreation
import com.michaeltchuang.walletsdk.core.account.domain.model.local.RegisteredHdKeyItem
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.RecoverRegisteredAccountsAccountProcessor
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.AccountAdditionUseCase
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.HdKeyAddress
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.HdKeyAddressIndex
import com.michaeltchuang.walletsdk.core.algosdk.bip39.sdk.Bip39Wallet
import com.michaeltchuang.walletsdk.core.algosdk.getBip39Wallet
import com.michaeltchuang.walletsdk.core.encryption.decryptByteArray
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.core.foundation.utils.CreationType
import com.michaeltchuang.walletsdk.core.foundation.utils.clearFromMemory
import com.michaeltchuang.walletsdk.core.foundation.utils.manager.AccountCreationManager
import com.michaeltchuang.walletsdk.core.foundation.utils.toShortenedAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch

class RecoverRegisteredAccountsViewModel(
    private val accountAdditionUseCase: AccountAdditionUseCase,
    private val registeredAccountsProcessor: RecoverRegisteredAccountsAccountProcessor,
    private val accountCreationHdKeyTypeMapper: AccountCreationHdKeyTypeMapper,
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>
) : ViewModel(), StateViewModel<RecoverRegisteredAccountsViewModel.ViewState> by stateDelegate,
    EventViewModel<RecoverRegisteredAccountsViewModel.ViewEvent> by eventDelegate {

    private val encryptedEntropy =
        AccountCreationManager.getPendingAccountCreation().let {
            (it?.type as? AccountCreation.Type.HdKey)?.encryptedEntropy
        }

    init {
        stateDelegate.setDefaultState(ViewState.Idle)
    }

    fun loadRegisteredAccounts() {
        stateDelegate.onState<ViewState.Idle> {
            stateDelegate.updateState { ViewState.Loading }
            viewModelScope.launch(Dispatchers.IO) {
                encryptedEntropy?.let { encryptedEntropyValue ->
                    val registeredAccounts =
                        registeredAccountsProcessor.getRegisteredHdKeyItems(encryptedEntropyValue)
                    val notImportedAddresses = registeredAccounts.mapNotNull {
                        it.takeIf { !it.isImportedToDB }?.address
                    }.toSet()
                    stateDelegate.updateState {
                        ViewState.Content(
                            registeredAccounts = registeredAccounts,
                            registeredAddressesNotImported = notImportedAddresses
                        )
                    }
                } ?: run {
                    // Handle the case where no encrypted entropy is available
                    stateDelegate.updateState { ViewState.Idle }
                }
            }
        }
    }

    fun toggleAccountSelection(address: String, isSelected: Boolean) {
        stateDelegate.onState<ViewState.Content> { currentState ->
            val updatedSelection = if (isSelected) {
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
            stateDelegate.updateState {
                currentState.copy(
                    selectedAddresses = currentState.registeredAddressesNotImported
                )
            }
        }
    }

    fun unselectAllAccounts() {
        stateDelegate.onState<ViewState.Content> { currentState ->
            stateDelegate.updateState {
                currentState.copy(selectedAddresses = emptySet())
            }
        }
    }

    fun importSelectedAccounts() {
        stateDelegate.onState<ViewState.Content> { currentState ->
            stateDelegate.updateState { currentState.copy(type = ViewState.Content.ContentType.LoadingRekeyedAddresses) }
            viewModelScope.launch(Dispatchers.IO) {
                encryptedEntropy?.let { encryptedEntropyValue ->
                    val selectedAddresses = currentState.registeredAccounts.filter {
                        currentState.selectedAddresses.contains(it.address)
                    }
                    val entropy = decryptByteArray(encryptedEntropyValue)
                    val addressesToImport = getAddressesToImport(entropy, selectedAddresses)
                    addSelectedAddresses(entropy, addressesToImport)
                    stateDelegate.updateState { currentState.copy(type = ViewState.Content.ContentType.Idle) }
                    if (addressesToImport.size == 1) {
                        eventDelegate.sendEvent(
                            ViewEvent.NavigateToAddressNaming(
                                addressesToImport.single().address
                            )
                        )
                    } else {
                        val isNewAccountAdded = addressesToImport.isNotEmpty()
                        eventDelegate.sendEvent(ViewEvent.NavigateToHome(isNewAccountAdded))
                    }

                    entropy.clearFromMemory()
                } ?: run {
                    // Handle the case where no encrypted entropy is available
                    stateDelegate.updateState { currentState.copy(type = ViewState.Content.ContentType.Idle) }
                }
            }
        }
    }

    private suspend fun addSelectedAddresses(
        entropy: ByteArray,
        selectedAddresses: List<HdKeyAddress>
    ) {
        selectedAddresses.forEach { hdKeyAccount ->
            val newAccountCreation = createAccountCreation(entropy, hdKeyAccount)
            accountAdditionUseCase.addNewAccount(newAccountCreation)
        }
    }

    private fun getAddressesToImport(
        entropy: ByteArray,
        selectedAddresses: List<RegisteredHdKeyItem>
    ): List<HdKeyAddress> {
        val wallet = getBip39Wallet(entropy.copyOf())
        return selectedAddresses.map { accountItem ->
            createHdKeyAddress(wallet, accountItem)
        }.also {
            wallet.invalidate()
        }
    }


    private fun createHdKeyAddress(
        bip39Wallet: Bip39Wallet,
        accountItem: RegisteredHdKeyItem
    ): HdKeyAddress {
        return with(accountItem) {
            val index =
                HdKeyAddressIndex(accountIndex = account, changeIndex = change, keyIndex = keyIndex)
            bip39Wallet.generateAddress(index)
        }
    }

    private fun createAccountCreation(
        entropy: ByteArray,
        hdKeyAddress: HdKeyAddress
    ): AccountCreation {
        return with(hdKeyAddress) {
            val hdKeyType = accountCreationHdKeyTypeMapper(entropy, hdKeyAddress, seedId = null)
            AccountCreation(
                address = address,
                customName = address.toShortenedAddress(),
                isBackedUp = false,
                type = hdKeyType,
                creationType = CreationType.RECOVER
            )
        }
    }

    sealed interface ViewState {
        data object Idle : ViewState
        data object Loading : ViewState

        data class Content(
            val registeredAccounts: List<RegisteredHdKeyItem> = emptyList(),
            val registeredAddressesNotImported: Set<String> = emptySet(),
            val selectedAddresses: Set<String> = emptySet(),
            val type: ContentType = ContentType.Idle
        ) : ViewState {

            sealed interface ContentType {
                data object Idle : ContentType
                data object LoadingRekeyedAddresses : ContentType
            }
        }
    }

    sealed interface ViewEvent {
        data class NavigateToHome(val isNewAccountAdded: Boolean) : ViewEvent
        data class NavigateToAddressNaming(val address: String) : ViewEvent
    }
}
