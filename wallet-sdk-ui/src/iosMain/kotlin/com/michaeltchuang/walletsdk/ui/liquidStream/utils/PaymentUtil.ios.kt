package com.michaeltchuang.walletsdk.ui.liquidStream.utils

actual fun startSettlePayment(
    viewerAddress: String,
    creatorAddress: String,
    viewerAuthSignKey: ByteArray,
) {
    println("startSettlePayment called with viewerAddress: $viewerAddress, creatorAddress: $creatorAddress")
}
