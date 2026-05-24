package com.michaeltchuang.walletsdk.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.GetLocalAccountsUseCase
import com.michaeltchuang.walletsdk.core.utils.AppId
import com.michaeltchuang.walletsdk.demo.ui.viewmodel.BroadcastViewModel
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.createLiquidAuthConnectionManager
import com.michaeltchuang.walletsdk.ui.liquidAuth.utils.getSupportedLocalAccountsByAppId
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@Composable
actual fun rememberBroadcastPlatformState(): BroadcastPlatformState {
    val viewModel: BroadcastViewModel = koinViewModel()
    val broadcastState by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val connectionManager = remember(context) { createLiquidAuthConnectionManager(context) }

    val getLocalAccountsUseCase = koinInject<GetLocalAccountsUseCase>()
    val accountResult by produceState<Pair<Boolean, List<LocalAccount>>>(initialValue = false to emptyList()) {
        value = true to
            getSupportedLocalAccountsByAppId(
                appId = AppId.LIQUID_AUTH_STREAM.name,
                localAccount = getLocalAccountsUseCase(),
            )
    }

    return BroadcastPlatformState(
        isMainnetUnsupported = broadcastState is BroadcastViewModel.BroadcastState.MainnetUnsupported,
        accountsLoaded = accountResult.first,
        accounts = accountResult.second,
        connectionManager = connectionManager,
    )
}
