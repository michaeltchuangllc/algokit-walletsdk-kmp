package com.michaeltchuang.walletsdk.ui.liquidStream.utils

var iosSettlePaymentHandler: ((viewerAddress: String, creatorAddress: String, viewerAuthSignKey: ByteArray) -> Unit)? = null

actual fun startSettlePayment(
    viewerAddress: String,
    creatorAddress: String,
    viewerAuthSignKey: ByteArray,
) {
    val handler = iosSettlePaymentHandler
    if (handler != null) {
        println("PaymentUtil.ios: delegating settlement viewerAddress=$viewerAddress creatorAddress=$creatorAddress")
        handler(viewerAddress, creatorAddress, viewerAuthSignKey)
    } else {
        println("PaymentUtil.ios: iosSettlePaymentHandler not set — viewerAddress=$viewerAddress creatorAddress=$creatorAddress")
    }
}
