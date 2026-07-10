package com.michaeltchuang.walletsdk.demo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.ui.initializeSdk.WalletSDK
import com.michaeltchuang.walletsdk.ui.settings.screens.networkNodeSettings
import kotlinx.coroutines.launch

class AppViewModel : ViewModel() {
    init {
        initializeNetworkStatus()
    }

    private fun initializeNetworkStatus() {
        viewModelScope.launch {
            WalletSDK.getCurrentNetwork().collect { network ->
                networkNodeSettings.value = network
            }
        }
    }
}
