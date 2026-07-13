package com.michaeltchuang.walletsdk.ui.liquidStream.domain.usecases

import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetSessionVaultConfigUseCase
import com.michaeltchuang.walletsdk.ui.liquidStream.domain.manager.MppPaymentViewerManager

expect class SetupMppPaymentViewerUseCase(
    viewerManager: MppPaymentViewerManager,
    getSessionVaultConfigUseCase: GetSessionVaultConfigUseCase,
)
