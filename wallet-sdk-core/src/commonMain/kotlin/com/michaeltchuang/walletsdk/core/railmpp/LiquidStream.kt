package com.michaeltchuang.walletsdk.core.railmpp

import com.michaeltchuang.walletsdk.core.railmpp.core.ConsentHandler
import com.michaeltchuang.walletsdk.core.railmpp.core.PaywalledRTCClient
import com.michaeltchuang.walletsdk.core.railmpp.core.PaywalledRTCServer
import com.michaeltchuang.walletsdk.core.railmpp.core.RtcDataChannel
import com.michaeltchuang.walletsdk.core.railmpp.core.RtcRtpSender
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ChatMessage
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ClientConfig
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.GatingConfig
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ServerConfig
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetRemainingSessionVaultBalanceUseCase
import com.michaeltchuang.walletsdk.core.railmpp.usecases.SetLiquidStreamViewerAutoPayUseCase
import com.michaeltchuang.walletsdk.core.railmpp.usecases.StartLiquidStreamCreatorUseCase
import com.michaeltchuang.walletsdk.core.railmpp.usecases.StartLiquidStreamViewerUseCase
import com.michaeltchuang.walletsdk.core.railmpp.usecases.StopLiquidStreamCreatorUseCase
import com.michaeltchuang.walletsdk.core.railmpp.usecases.StopLiquidStreamViewerUseCase
import com.michaeltchuang.walletsdk.core.railmpp.usecases.UpdateLiquidStreamCreatorConfigUseCase
import com.michaeltchuang.walletsdk.core.railmpp.usecases.UpdateLiquidStreamCreatorGatingUseCase

/**
 * Provider-side convenience wrapper for a paywalled RTC stream using [MppPaymentRail].
 */
class LiquidStreamCreator(
    private val dataChannel: RtcDataChannel,
    private val rtpSenders: List<RtcRtpSender>,
    mppServerConfig: MppServerConfig,
    serverConfig: ServerConfig,
    private val getRemainingSessionVaultBalanceUseCase: GetRemainingSessionVaultBalanceUseCase,
    private val startUseCase: StartLiquidStreamCreatorUseCase = StartLiquidStreamCreatorUseCase(),
    private val stopUseCase: StopLiquidStreamCreatorUseCase = StopLiquidStreamCreatorUseCase(),
    private val updateConfigUseCase: UpdateLiquidStreamCreatorConfigUseCase = UpdateLiquidStreamCreatorConfigUseCase(),
    private val updateGatingUseCase: UpdateLiquidStreamCreatorGatingUseCase = UpdateLiquidStreamCreatorGatingUseCase(),
) {
    val paymentRail: MppPaymentRail = MppPaymentRail(serverConfig = mppServerConfig)
    val rtcServer: PaywalledRTCServer =
        PaywalledRTCServer(
            paymentRail = paymentRail,
            config = serverConfig,
            getRemainingSessionVaultBalanceUseCase = getRemainingSessionVaultBalanceUseCase,
        )

    init {
        require(serverConfig.gating.payTo == mppServerConfig.recipient) {
            "LiquidStreamCreator: gating.payTo must match MppServerConfig.recipient"
        }
    }

    val sessionId: String
        get() = rtcServer.sessionId

    fun start() {
        startUseCase(rtcServer, dataChannel, rtpSenders)
    }

    fun terminate(reason: String? = null) {
        stopUseCase(this, reason)
    }

    fun updateGating(gating: GatingConfig) {
        updateGatingUseCase(this, gating)
    }

    fun updateConfig(config: ServerConfig) {
        updateConfigUseCase(this, config)
    }

    fun sendChatMessage(message: ChatMessage) {
        rtcServer.sendChatMessage(message)
    }

    var onChatMessageReceived: ((ChatMessage) -> Unit)?
        get() = rtcServer.onChatMessageReceived
        set(value) {
            rtcServer.onChatMessageReceived = value
        }
}

/**
 * Consumer-side convenience wrapper for a paywalled RTC stream using [MppPaymentRail].
 */
class LiquidStreamViewer(
    private val dataChannel: RtcDataChannel,
    mppClientConfig: MppClientConfig,
    consentHandler: ConsentHandler,
    clientConfig: ClientConfig = ClientConfig(),
    private val startUseCase: StartLiquidStreamViewerUseCase = StartLiquidStreamViewerUseCase(),
    private val stopUseCase: StopLiquidStreamViewerUseCase = StopLiquidStreamViewerUseCase(),
    private val setAutoPayUseCase: SetLiquidStreamViewerAutoPayUseCase = SetLiquidStreamViewerAutoPayUseCase(),
) {
    val paymentRail: MppPaymentRail = MppPaymentRail(clientConfig = mppClientConfig)
    val rtcClient: PaywalledRTCClient =
        PaywalledRTCClient(
            paymentRail = paymentRail,
            consent = consentHandler,
            config = clientConfig,
        )

    fun start() {
        startUseCase(rtcClient, dataChannel)
    }

    fun terminate() {
        stopUseCase(this)
    }

    fun setAutoPay(enabled: Boolean) {
        setAutoPayUseCase(this, enabled)
    }

    fun sendChatMessage(message: ChatMessage) {
        rtcClient.sendChatMessage(message)
    }

    var onChatMessageReceived: ((ChatMessage) -> Unit)?
        get() = rtcClient.onChatMessageReceived
        set(value) {
            rtcClient.onChatMessageReceived = value
        }
}

/**
 * Backward-compatible alias for the original typo.
 */
typealias LiquidSreamViewer = LiquidStreamViewer
