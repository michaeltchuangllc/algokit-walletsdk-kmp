package com.michaeltchuang.walletsdk.ui.qrscanner.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.deeplink.DeeplinkHandler
import com.michaeltchuang.walletsdk.core.deeplink.model.DeepLink
import com.michaeltchuang.walletsdk.core.deeplink.model.KeyRegTransactionDetail
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlin.String

class QRScannerViewModel(
    private val deeplinkHandler: DeeplinkHandler,
    private val eventDelegate: EventDelegate<ViewEvent>,
) : ViewModel(),
    EventViewModel<QRScannerViewModel.ViewEvent> by eventDelegate {
    init {
        viewModelScope.launch {
            deeplinkHandler.deepLinkState.collect {
                when (it) {
                    is DeeplinkHandler.DeepLinkState.OnImportAccountDeepLink -> {
                        eventDelegate.sendEvent(ViewEvent.NavigateToRecoveryPhraseScreen(it.mnemonic))
                    }

                    is DeeplinkHandler.DeepLinkState.KeyReg -> {
                        handleKeyRegDeepLink(it.keyReg)
                    }

                    is DeeplinkHandler.DeepLinkState.AssetTransfer -> {
                        handleAssetTransferLink(it.assetTransfer)
                    }

                    is DeeplinkHandler.DeepLinkState.AccountAddress -> {
                        handleAccountAddressDeepLink(it.accountAddress)
                    }

                    is DeeplinkHandler.DeepLinkState.OnUnrecognizedDeepLink -> {
                        eventDelegate.sendEvent(ViewEvent.ShowUnrecognizedDeeplink)
                    }
                }
            }
        }
    }

    fun handleAssetTransferLink(deepLink: DeepLink.AssetTransfer) {
        val assetTransfer =
            DeepLink.AssetTransfer(
                assetId = deepLink.assetId,
                receiverAccountAddress = deepLink.receiverAccountAddress,
                amount = deepLink.amount,
                note = deepLink.note,
                xnote = deepLink.xnote,
                label = deepLink.label,
            )

        eventDelegate.sendEvent(
            scope = viewModelScope,
            newEvent = ViewEvent.NavigateToSelectAccountScreen(assetTransfer),
        )
    }

    fun handleAccountAddressDeepLink(deepLink: DeepLink.AccountAddress) {
        val accountAddress =
            DeepLink.AccountAddress(
                address = deepLink.address,
                label = deepLink.label,
            )

        eventDelegate.sendEvent(
            scope = viewModelScope,
            newEvent = ViewEvent.NavigateToAddressScreen(accountAddress),
        )
    }

    fun handleKeyRegDeepLink(deepLink: DeepLink.KeyReg) {
        val txnDetail =
            KeyRegTransactionDetail(
                address = deepLink.senderAddress,
                type = deepLink.type,
                voteKey = deepLink.voteKey,
                selectionPublicKey = deepLink.selkey,
                sprfkey = deepLink.sprfkey,
                voteFirstRound = deepLink.votefst,
                voteLastRound = deepLink.votelst,
                voteKeyDilution = deepLink.votekd,
                fee = deepLink.fee,
                note = deepLink.note,
                xnote = deepLink.xnote,
            )

        eventDelegate.sendEvent(
            scope = viewModelScope,
            newEvent = ViewEvent.NavigateToTransactionSignatureRequestScreen(txnDetail),
        )
    }

    fun handleDeeplink(uri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // First try to handle as a deeplink
            deeplinkHandler.handleDeepLink(uri)

            // If it's not recognized as a deeplink, check if it's a plain Algorand address
            if (isValidAlgorandAddress(uri.trim())) {
                // Treat plain address as an AccountAddress deeplink
                val accountAddress =
                    DeepLink.AccountAddress(
                        address = uri.trim(),
                        label = null,
                    )
                eventDelegate.sendEvent(
                    scope = viewModelScope,
                    newEvent = ViewEvent.NavigateToAddressScreen(accountAddress),
                )
            }
        }
    }

    private fun isValidAlgorandAddress(address: String): Boolean {
        // Basic validation: 58 characters, alphanumeric
        return address.length == 58 && address.all { it.isLetterOrDigit() }
    }

    interface ViewEvent {
        data class NavigateToRecoveryPhraseScreen(
            val mnemonic: String,
        ) : ViewEvent

        data class NavigateToTransactionSignatureRequestScreen(
            val txnDetail: KeyRegTransactionDetail,
        ) : ViewEvent

        data class NavigateToSelectAccountScreen(
            val assetTransfer: DeepLink.AssetTransfer,
        ) : ViewEvent

        data class NavigateToAddressScreen(
            val accountAddress: DeepLink.AccountAddress,
        ) : ViewEvent

        object ShowUnrecognizedDeeplink : ViewEvent
    }
}
