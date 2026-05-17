package com.michaeltchuang.walletsdk.ui.liquidStream.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.railmpp.usecases.GetRemainingSessionVaultBalanceUseCase
import com.michaeltchuang.walletsdk.ui.liquidStream.viewmodels.LiquidAuthViewerViewModel.UiState
import com.michaeltchuang.walletsdk.ui.liquidStream.viewmodels.LiquidAuthViewerViewModel.ViewEvent
import kotlinx.coroutines.launch

class ConnectedViewerViewModel(
    private val stateDelegate: StateDelegate<UiState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
    private val getRemainingSessionVaultBalanceUseCase: GetRemainingSessionVaultBalanceUseCase,
) : ViewModel() {
    private lateinit var viewerAddress: String
    private lateinit var creatorAddress: String

    fun getRemainingSessionVaultBalance(
        viewerAddress: String,
        creatorAddress: String,
    ) {
        val params = GetRemainingSessionVaultBalanceUseCase.Params(
            viewerAddress = viewerAddress,
            hostAddress = creatorAddress,
        )
        viewModelScope.launch {
            getRemainingSessionVaultBalanceUseCase(params)
        }
    }
}