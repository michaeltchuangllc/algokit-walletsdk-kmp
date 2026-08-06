package com.michaeltchuang.walletsdk.ui.liquidAuth.service

import com.michaeltchuang.walletsdk.ui.liquidStream.domain.transport.CallbackRtcDataChannel

actual class LiquidAuthPlatformServices {
    private var hostPaymentDataChannel: CallbackRtcDataChannel? = null
    private var viewerPaymentDataChannel: CallbackRtcDataChannel? = null

    fun isHostConnected(): Boolean = iosBroadcastIsConnectedHandler?.invoke() ?: false

    fun sendHostMessage(message: String): Boolean {
        val handler = iosBroadcastSendMessageHandler ?: return false
        handler(message)
        return true
    }

    fun hostPaymentMessageSender(): ((message: String) -> Unit)? = iosBroadcastPaymentDCSendMessageHandler ?: iosBroadcastSendMessageHandler

    fun createHostPaymentDataChannel(): CallbackRtcDataChannel =
        CallbackRtcDataChannel(
            sendMessageProvider = ::hostPaymentMessageSender,
            logTag = "IOSLiquidAuthPaymentDc",
        ).also { hostPaymentDataChannel = it }

    fun openHostPaymentDataChannel() {
        hostPaymentDataChannel?.notifyOpen()
    }

    fun closeHostPaymentDataChannel() {
        hostPaymentDataChannel?.notifyClosed()
        hostPaymentDataChannel = null
    }

    fun notifyHostPaymentMessage(message: String): Boolean {
        val channel = hostPaymentDataChannel ?: return false
        channel.notifyMessage(message)
        return true
    }

    fun detectHostConnectionType(): String? = iosBroadcastDetectConnectionTypeHandler?.invoke()

    fun startViewerConnection(
        origin: String,
        requestId: String,
    ): Boolean {
        val handler = iosViewerStartHandler ?: return false
        handler(origin, requestId)
        return true
    }

    fun stopViewerConnection() {
        iosViewerStopHandler?.invoke()
    }

    fun isViewerConnected(): Boolean = iosViewerIsConnectedHandler?.invoke() ?: false

    fun sendViewerMessage(message: String): Boolean {
        val handler = iosViewerSendMessageHandler ?: return false
        handler(message)
        return true
    }

    fun viewerPaymentMessageSender(): ((message: String) -> Unit)? = iosViewerPaymentDCSendMessageHandler ?: iosViewerSendMessageHandler

    fun createViewerPaymentDataChannel(): CallbackRtcDataChannel =
        CallbackRtcDataChannel(
            sendMessageProvider = ::viewerPaymentMessageSender,
            logTag = "IOSViewerPaymentDc",
        ).also { viewerPaymentDataChannel = it }

    fun openViewerPaymentDataChannel() {
        viewerPaymentDataChannel?.notifyOpen()
    }

    fun closeViewerPaymentDataChannel() {
        viewerPaymentDataChannel?.notifyClosed()
        viewerPaymentDataChannel = null
    }

    fun notifyViewerPaymentMessage(message: String): Boolean {
        val channel = viewerPaymentDataChannel ?: return false
        channel.notifyMessage(message)
        return true
    }

    fun detectViewerConnectionType(): String? = iosViewerDetectConnectionTypeHandler?.invoke()

    fun getViewerPublicKey(viewerAddress: String): String? = iosViewerPublicKeyProvider?.invoke(viewerAddress)

    fun setViewerPublicKeyProvider(provider: (viewerAddress: String) -> String?) {
        iosViewerPublicKeyProvider = provider
    }

    fun hasViewerPublicKeyProvider(): Boolean = iosViewerPublicKeyProvider != null

    @Suppress("unused")
    fun fetchViewerBalance(
        viewerAddress: String,
        hostAddress: String,
        callback: (remainingMicroUsdc: Long?) -> Unit,
    ): Boolean {
        val handler = iosViewerFetchBalanceHandler ?: return false
        handler(viewerAddress, hostAddress, callback)
        return true
    }
}
