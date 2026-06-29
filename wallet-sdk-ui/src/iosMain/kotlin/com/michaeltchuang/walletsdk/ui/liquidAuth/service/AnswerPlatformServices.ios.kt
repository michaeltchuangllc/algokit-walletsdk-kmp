package com.michaeltchuang.walletsdk.ui.liquidAuth.service

import com.michaeltchuang.walletsdk.ui.liquidStream.domain.transport.CallbackRtcDataChannel
import com.michaeltchuang.walletsdk.ui.liquidStream.iosViewerDetectConnectionTypeHandler
import com.michaeltchuang.walletsdk.ui.liquidStream.iosViewerFetchBalanceHandler
import com.michaeltchuang.walletsdk.ui.liquidStream.iosViewerIsConnectedHandler
import com.michaeltchuang.walletsdk.ui.liquidStream.iosViewerPaymentDCSendMessageHandler
import com.michaeltchuang.walletsdk.ui.liquidStream.iosViewerPublicKeyProvider
import com.michaeltchuang.walletsdk.ui.liquidStream.iosViewerSendMessageHandler
import com.michaeltchuang.walletsdk.ui.liquidStream.iosViewerStartHandler
import com.michaeltchuang.walletsdk.ui.liquidStream.iosViewerStopHandler

actual class AnswerPlatformServices {
    private var viewerPaymentDataChannel: CallbackRtcDataChannel? = null

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

    fun viewerPaymentMessageSender(): ((message: String) -> Unit)? =
        iosViewerPaymentDCSendMessageHandler ?: iosViewerSendMessageHandler

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
