package com.michaeltchuang.walletsdk.ui.onboarding.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.account.domain.model.core.OnboardingAccountType
import com.michaeltchuang.walletsdk.core.account.domain.usecase.recoverypassphrase.RecoverPassphraseUseCase
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.utils.manager.AccountCreationManager
import com.michaeltchuang.walletsdk.core.foundation.utils.splitMnemonic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class RecoverPassphraseViewModel(
    private val recoverPassphrasePreviewUseCase: RecoverPassphraseUseCase,
    private val eventDelegate: EventDelegate<ViewEvent>,
) : ViewModel(),
    EventViewModel<RecoverPassphraseViewModel.ViewEvent> by eventDelegate {
    fun onRecoverAccount(
        mnemonic: String,
        onboardingAccountType: OnboardingAccountType,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            recoverPassphrasePreviewUseCase
                .validateEnteredMnemonics(
                    mnemonic,
                    onboardingAccountType,
                ).collectLatest { accountCreation ->
                    if (accountCreation != null) {
                        // Store the account creation data in the manager
                        AccountCreationManager.storePendingAccountCreation(accountCreation)
                        when (onboardingAccountType) {
                            OnboardingAccountType.HdKey -> {
                                eventDelegate.sendEvent(ViewEvent.NavigateToRecoverRegisteredAccountScreen)
                            }

                            else -> {
                                eventDelegate.sendEvent(
                                    ViewEvent.NavigateToAccountNameScreen,
                                )
                            }
                        }
                    } else {
                        eventDelegate.sendEvent(
                            ViewEvent.ShowError("Invalid recovery phrase. Please check your words and try again."),
                        )
                    }
                }
        }
    }

    fun onClipBoardPastedMnemonic(
        mnemonic: String,
        isValid: () -> Unit,
    ) {
        val splittedText = mnemonic.splitMnemonic()
        if (
            splittedText.size != OnboardingAccountType.Algo25.wordCount &&
            splittedText.size != OnboardingAccountType.HdKey.wordCount &&
            splittedText.size != OnboardingAccountType.Falcon24.wordCount
        ) {
            viewModelScope.launch {
                eventDelegate.sendEvent(
                    ViewEvent.ShowError("The last copied text is not a valid passphrase. Please copy a valid passphrase and try again."),
                )
            }
        } else {
            isValid()
        }
    }

    interface ViewEvent {
        data object NavigateToAccountNameScreen : ViewEvent

        data object NavigateToRecoverRegisteredAccountScreen : ViewEvent

        data class ShowError(
            val error: String,
        ) : ViewEvent
    }
}
