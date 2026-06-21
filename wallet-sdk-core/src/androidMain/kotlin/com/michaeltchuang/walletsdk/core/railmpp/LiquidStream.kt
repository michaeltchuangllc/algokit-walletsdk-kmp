package com.michaeltchuang.walletsdk.core.railmpp

import com.michaeltchuang.walletsdk.core.railmpp.core.ClientConfig
import com.michaeltchuang.walletsdk.core.railmpp.core.ConsentHandler
import com.michaeltchuang.walletsdk.core.railmpp.core.GatingConfig
import com.michaeltchuang.walletsdk.core.railmpp.core.PaywalledRTCClient
import com.michaeltchuang.walletsdk.core.railmpp.core.PaywalledRTCServer
import com.michaeltchuang.walletsdk.core.railmpp.core.ServerConfig
import com.michaeltchuang.walletsdk.core.railmpp.core.WebRtcDataChannel
import com.michaeltchuang.walletsdk.core.railmpp.core.WebRtcRtpSender
import com.michaeltchuang.walletsdk.core.railmpp.data.repository.AndroidSessionVaultBalanceRepository
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetRemainingSessionVaultBalanceUseCase
import com.michaeltchuang.walletsdk.core.railmpp.usecases.SetLiquidStreamViewerAutoPayUseCase
import com.michaeltchuang.walletsdk.core.railmpp.usecases.StartLiquidStreamCreatorUseCase
import com.michaeltchuang.walletsdk.core.railmpp.usecases.StartLiquidStreamViewerUseCase
import com.michaeltchuang.walletsdk.core.railmpp.usecases.StopLiquidStreamCreatorUseCase
import com.michaeltchuang.walletsdk.core.railmpp.usecases.StopLiquidStreamViewerUseCase
import com.michaeltchuang.walletsdk.core.railmpp.usecases.UpdateLiquidStreamCreatorConfigUseCase
import com.michaeltchuang.walletsdk.core.railmpp.usecases.UpdateLiquidStreamCreatorGatingUseCase
import org.webrtc.DataChannel
import org.webrtc.RtpSender

/**
 * Provider-side convenience wrapper for a paywalled RTC stream using [MppPaymentRail].
 */
class LiquidStreamCreator(
    private val dataChannel: DataChannel,
    private val rtpSenders: List<RtpSender>,
    mppServerConfig: MppServerConfig,
    serverConfig: ServerConfig,
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
            getRemainingSessionVaultBalanceUseCase =
                GetRemainingSessionVaultBalanceUseCase(AndroidSessionVaultBalanceRepository()),
        )

    init {
        require(serverConfig.gating.payTo == mppServerConfig.recipient) {
            "LiquidStreamCreator: gating.payTo must match MppServerConfig.recipient"
        }
    }

    val sessionId: String
        get() = rtcServer.sessionId

    fun start() {
        startUseCase(rtcServer, WebRtcDataChannel(dataChannel), rtpSenders.map { WebRtcRtpSender(it) })
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
}

/**
 * Consumer-side convenience wrapper for a paywalled RTC stream using [MppPaymentRail].
 */
class LiquidStreamViewer(
    private val dataChannel: DataChannel,
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
        startUseCase(rtcClient, WebRtcDataChannel(dataChannel))
    }

    fun terminate() {
        stopUseCase(this)
    }

    fun setAutoPay(enabled: Boolean) {
        setAutoPayUseCase(this, enabled)
    }
}

/**
 * Backward-compatible alias for the original typo.
 */
typealias LiquidSreamViewer = LiquidStreamViewer
