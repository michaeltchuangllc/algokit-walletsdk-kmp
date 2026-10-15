package com.michaeltchuang.walletsdk.demo

import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccount
import com.michaeltchuang.walletsdk.core.network.domain.usecase.GetCurrentNetworkUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.utils.resolveLiquidStreamAccountNetwork
import kotlinx.coroutines.flow.first
import org.koin.mp.KoinPlatform.getKoin

@Throws(Exception::class)
suspend fun resolveViewerIdentityNetwork(address: String): String =
    resolveLiquidStreamAccountNetwork(
        getKoin().get<GetLocalAccount>()(address),
        getKoin().get<GetCurrentNetworkUseCase>()().first(),
    )
