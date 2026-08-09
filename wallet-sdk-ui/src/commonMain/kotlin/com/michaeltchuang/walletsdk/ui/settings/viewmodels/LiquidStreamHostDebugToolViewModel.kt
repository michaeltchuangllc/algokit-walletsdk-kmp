package com.michaeltchuang.walletsdk.ui.settings.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.core.network.domain.usecase.GetCurrentNetworkUseCase
import com.michaeltchuang.walletsdk.core.network.usecase.GetCurrentBlockUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetSessionVaultContextUseCase
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultManagerClient
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.ui.settings.domain.DebugAddressHolder
import com.michaeltchuang.walletsdk.utils.DataResource
import io.github.aakira.napier.Napier
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class LiquidStreamHostDebugToolViewModel(
    private val getCurrentBlockUseCase: GetCurrentBlockUseCase,
    private val getCurrentNetworkUseCase: GetCurrentNetworkUseCase,
    private val getSessionVaultContextUseCase: GetSessionVaultContextUseCase,
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
) : ViewModel(),
    StateViewModel<LiquidStreamHostDebugToolViewModel.ViewState> by stateDelegate,
    EventViewModel<LiquidStreamHostDebugToolViewModel.ViewEvent> by eventDelegate {

    init {
        stateDelegate.setDefaultState(ViewState())
        startLivePolling()
        refreshViewerBalances()
    }

    private fun startLivePolling() {
        viewModelScope.launch {
            try {
                val network = getCurrentNetworkUseCase().first()
                stateDelegate.updateState { it.copy(liveNetworkLabel = network.displayName.uppercase()) }
            } catch (e: Exception) {
                Napier.e("Failed to fetch initial network", e, tag = "LiquidStreamHostDebugVM")
            }

            while (true) {
                getCurrentBlockUseCase().collect { result ->
                    if (result is DataResource.Success) {
                        stateDelegate.updateState { it.copy(liveBlockNumber = result.data) }
                    }
                }
                delay(3.seconds)
            }
        }
    }

    fun refreshViewerBalances() {
        viewModelScope.launch {
            try {
                val vaultContext = getSessionVaultContextUseCase()
                EscrowSessionVaultManagerClient.configureForNetwork(vaultContext.network)

                val addresses = listOf(
                    DebugAddressHolder.viewerAddress,
                    DebugAddressHolder.viewerAddress2,
                    DebugAddressHolder.viewerAddress3,
                ).filter { it.isNotBlank() }

                val newBalances = mutableMapOf<String, Double>()
                addresses.forEach { address ->
                    try {
                        val remaining = MppPayments.getRemainingBalanceFromSessionVault(address)
                        newBalances[address] = remaining / 1_000_000.0
                    } catch (e: Exception) {
                        Napier.e("Failed to fetch balance for $address", e, tag = "LiquidStreamHostDebugVM")
                        newBalances[address] = 0.0
                    }
                }
                stateDelegate.updateState { it.copy(viewerBalances = newBalances) }
            } catch (e: Exception) {
                Napier.e("Failed to configure vault for balances", e, tag = "LiquidStreamHostDebugVM")
            }
        }
    }

    data class ViewState(
        val liveBlockNumber: Long? = null,
        val liveNetworkLabel: String = "TESTNET",
        val viewerBalances: Map<String, Double> = emptyMap(),
    )

    sealed interface ViewEvent
}
