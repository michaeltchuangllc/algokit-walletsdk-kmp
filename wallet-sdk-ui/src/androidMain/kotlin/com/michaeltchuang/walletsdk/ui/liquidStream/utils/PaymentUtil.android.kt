package com.michaeltchuang.walletsdk.ui.liquidStream.utils

import com.michaeltchuang.walletsdk.ui.test.PaymentTestViewModel
import org.koin.java.KoinJavaComponent.getKoin

actual fun startSettlePayment(
    viewerAddress: String,
    creatorAddress: String,
    viewerAuthSignKey: ByteArray,
) {
    val paymentViewModel: PaymentTestViewModel = getKoin().get()
    paymentViewModel.startSettlePayment(
        viewerAddress = viewerAddress,
        creatorAddress = creatorAddress,
        amountUsdc = 1L,
        viewerAuthSignKey = viewerAuthSignKey,
    )
}
