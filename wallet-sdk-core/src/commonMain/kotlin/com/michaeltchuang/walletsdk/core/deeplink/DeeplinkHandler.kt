package com.michaeltchuang.walletsdk.core.deeplink

import com.michaeltchuang.walletsdk.core.deeplink.model.DeepLink
import com.michaeltchuang.walletsdk.core.deeplink.parser.CreateDeepLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DeeplinkHandler(
    private val createDeepLink: CreateDeepLink,
) {
    private var _deepLinkState = MutableSharedFlow<DeepLinkState>()
    val deepLinkState: SharedFlow<DeepLinkState> = _deepLinkState

    suspend fun handleDeepLink(uri: String) {
        withContext(Dispatchers.Main) {
            val parsedDeepLink = createDeepLink(uri)
            handleDeepLink(parsedDeepLink)
        }
    }

    private fun handleDeepLink(deepLink: DeepLink) {
        when (deepLink) {
            is DeepLink.Mnemonic -> handleMnemonicDeepLink(deepLink)
            is DeepLink.KeyReg -> handleKeyReg(deepLink)
            is DeepLink.AssetTransfer -> handleAssetTransfer(deepLink)
            is DeepLink.AccountAddress -> handleAccountAddress(deepLink)
            is DeepLink.Fido -> handleFidoDeepLink(deepLink)
            is DeepLink.LiquidAuth -> handleLiquidAuthDeepLink(deepLink)
            else -> {
                handleUnrecognizedDeepLink()
            }
        }
    }

    private fun handleMnemonicDeepLink(deepLink: DeepLink.Mnemonic) {
        CoroutineScope(Dispatchers.Main).launch {
            _deepLinkState.emit(DeepLinkState.OnImportAccountDeepLink(deepLink.mnemonic))
        }
    }

    private fun handleKeyReg(deepLink: DeepLink.KeyReg) {
        CoroutineScope(Dispatchers.Main).launch {
            _deepLinkState.emit(DeepLinkState.KeyReg(deepLink))
        }
    }

    private fun handleAssetTransfer(deepLink: DeepLink.AssetTransfer) {
        CoroutineScope(Dispatchers.Main).launch {
            _deepLinkState.emit(DeepLinkState.AssetTransfer(deepLink))
        }
    }

    private fun handleAccountAddress(deepLink: DeepLink.AccountAddress) {
        CoroutineScope(Dispatchers.Main).launch {
            _deepLinkState.emit(DeepLinkState.AccountAddress(deepLink))
        }
    }

    private fun handleUnrecognizedDeepLink() {
        CoroutineScope(Dispatchers.Main).launch {
            _deepLinkState.emit(DeepLinkState.OnUnrecognizedDeepLink)
        }
    }

    private fun handleFidoDeepLink(deepLink: DeepLink.Fido) {
        CoroutineScope(Dispatchers.Main).launch {
            _deepLinkState.emit(DeepLinkState.FidoDeepLink(deepLink.uri))
        }
    }

    private fun handleLiquidAuthDeepLink(deepLink: DeepLink.LiquidAuth) {
        CoroutineScope(Dispatchers.Main).launch {
            _deepLinkState.emit(DeepLinkState.LiquAuthDeepLink(deepLink.uri))
        }
    }

    sealed class DeepLinkState {
        data class OnImportAccountDeepLink(
            var mnemonic: String,
        ) : DeepLinkState()

        data class KeyReg(
            var keyReg: DeepLink.KeyReg,
        ) : DeepLinkState()

        data class AssetTransfer(
            var assetTransfer: DeepLink.AssetTransfer,
        ) : DeepLinkState()

        data class AccountAddress(
            var accountAddress: DeepLink.AccountAddress,
        ) : DeepLinkState()

        data class FidoDeepLink(
            var uri: String,
        ) : DeepLinkState()

        data class LiquAuthDeepLink(
            var uri: String,
        ) : DeepLinkState()

        object OnUnrecognizedDeepLink : DeepLinkState()
    }
}
