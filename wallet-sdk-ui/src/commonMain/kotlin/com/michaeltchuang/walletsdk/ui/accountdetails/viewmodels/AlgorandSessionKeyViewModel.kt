package com.michaeltchuang.walletsdk.ui.accountdetails.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAccountAlgoBalance
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAlgorandSeedVaultSeeds
import com.michaeltchuang.walletsdk.core.deeplink.model.DeepLink
import com.michaeltchuang.walletsdk.core.deeplink.parser.CreateDeepLink
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.FUTURENET_DISPENSER_BASE_URL
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.TESTNET_DISPENSER_BASE_URL
import com.michaeltchuang.walletsdk.core.network.domain.usecase.GetCurrentNetworkUseCase
import com.michaeltchuang.walletsdk.core.network.model.AlgorandNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AlgorandSessionKeyViewModel(
    private val getAlgorandSeedVaultSeeds: GetAlgorandSeedVaultSeeds,
    private val getAccountAlgoBalance: GetAccountAlgoBalance,
    private val getCurrentNetworkUseCase: GetCurrentNetworkUseCase,
    private val createDeepLink: CreateDeepLink,
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel(),
    StateViewModel<AlgorandSessionKeyViewModel.ViewState> by stateDelegate,
    EventViewModel<AlgorandSessionKeyViewModel.ViewEvent> by eventDelegate {
    private var ownerAddress: String? = null
    private var loadJob: Job? = null
    private var sendJob: Job? = null

    init {
        stateDelegate.setDefaultState(ViewState.Loading)
    }

    fun load(ownerAddress: String) {
        this.ownerAddress = ownerAddress
        loadJob?.cancel()
        sendJob?.cancel()
        stateDelegate.updateState { ViewState.Loading }
        loadJob =
            viewModelScope.launch {
                try {
                    val address =
                        withContext(ioDispatcher) {
                            getAlgorandSeedVaultSeeds()
                                .firstOrNull { it.primaryAccount?.address == ownerAddress }
                                ?.accountAt(1)
                                ?.address
                        }
                    if (address.isNullOrBlank()) {
                        stateDelegate.updateState { ViewState.Error }
                        return@launch
                    }
                    getCurrentNetworkUseCase().collectLatest { network ->
                        stateDelegate.updateState { ViewState.Loading }
                        try {
                            val balance = withContext(ioDispatcher) { getAccountAlgoBalance(address)?.toString() }
                            val dispenserBaseUrl =
                                when (network) {
                                    AlgorandNetwork.MAINNET -> null
                                    AlgorandNetwork.TESTNET -> TESTNET_DISPENSER_BASE_URL
                                    AlgorandNetwork.FUTURENET -> FUTURENET_DISPENSER_BASE_URL
                                }
                            stateDelegate.updateState {
                                ViewState.Content(
                                    address = address,
                                    balance = balance,
                                    network = network,
                                    dispenserUrl = dispenserBaseUrl?.let { "$it/?account=$address" },
                                )
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            stateDelegate.updateState { ViewState.Error }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    stateDelegate.updateState { ViewState.Error }
                }
            }
    }

    fun refresh() {
        ownerAddress?.let(::load)
    }

    fun onSendAlgo() {
        val content = state.value as? ViewState.Content ?: return
        if (sendJob?.isActive == true) return
        sendJob =
            viewModelScope.launch {
                try {
                    val deepLink = withContext(ioDispatcher) { createDeepLink(fundingDeepLink(content.address)) }
                    if (deepLink is DeepLink.AssetTransfer) {
                        eventDelegate.sendEvent(ViewEvent.SendAlgo(deepLink))
                    } else {
                        eventDelegate.sendEvent(ViewEvent.Error)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    eventDelegate.sendEvent(ViewEvent.Error)
                }
            }
    }

    sealed interface ViewState {
        data object Loading : ViewState

        data object Error : ViewState

        data class Content(
            val address: String,
            val balance: String?,
            val network: AlgorandNetwork,
            val dispenserUrl: String?,
        ) : ViewState
    }

    sealed interface ViewEvent {
        data class SendAlgo(
            val transfer: DeepLink.AssetTransfer,
        ) : ViewEvent

        data object Error : ViewEvent
    }
}

internal fun fundingDeepLink(address: String): String = "algorand://$address?amount=1000000&note=1_ALGO_Transfer&chainId=283"
