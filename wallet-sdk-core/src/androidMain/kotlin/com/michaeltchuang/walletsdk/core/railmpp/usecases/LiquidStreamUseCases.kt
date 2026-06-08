package com.michaeltchuang.walletsdk.core.railmpp.usecases

import com.michaeltchuang.walletsdk.core.railmpp.LiquidStreamCreator
import com.michaeltchuang.walletsdk.core.railmpp.LiquidStreamViewer
import com.michaeltchuang.walletsdk.core.railmpp.core.GatingConfig
import com.michaeltchuang.walletsdk.core.railmpp.core.PaywalledRTCClient
import com.michaeltchuang.walletsdk.core.railmpp.core.PaywalledRTCServer
import com.michaeltchuang.walletsdk.core.railmpp.core.RtcDataChannel
import com.michaeltchuang.walletsdk.core.railmpp.core.RtcRtpSender
import com.michaeltchuang.walletsdk.core.railmpp.core.ServerConfig

/**
 * Starts the creator payment stream on the existing RTC data channel.
 */
class StartLiquidStreamCreatorUseCase {
    operator fun invoke(
        rtcServer: PaywalledRTCServer,
        dataChannel: RtcDataChannel,
        rtpSenders: List<RtcRtpSender>,
    ) {
        rtcServer.listen(dataChannel, rtpSenders)
    }
}

/**
 * Stops the creator payment stream.
 */
class StopLiquidStreamCreatorUseCase {
    operator fun invoke(
        creator: LiquidStreamCreator,
        reason: String? = null,
    ) {
        creator.rtcServer.terminate(reason)
    }
}

/**
 * Starts the viewer payment stream on the existing RTC data channel.
 */
class StartLiquidStreamViewerUseCase {
    operator fun invoke(
        rtcClient: PaywalledRTCClient,
        dataChannel: RtcDataChannel,
    ) {
        rtcClient.connect(dataChannel)
    }
}

/**
 * Stops the viewer payment stream.
 */
class StopLiquidStreamViewerUseCase {
    operator fun invoke(viewer: LiquidStreamViewer) {
        viewer.rtcClient.terminate()
    }
}

/**
 * Updates creator-side stream config.
 */
class UpdateLiquidStreamCreatorConfigUseCase {
    operator fun invoke(
        creator: LiquidStreamCreator,
        config: ServerConfig,
    ) {
        creator.rtcServer.updateConfig(config)
    }
}

/**
 * Updates creator-side stream gating.
 */
class UpdateLiquidStreamCreatorGatingUseCase {
    operator fun invoke(
        creator: LiquidStreamCreator,
        gating: GatingConfig,
    ) {
        creator.rtcServer.updateGating(gating)
    }
}

/**
 * Updates viewer-side auto-pay behavior.
 */
class SetLiquidStreamViewerAutoPayUseCase {
    operator fun invoke(
        viewer: LiquidStreamViewer,
        enabled: Boolean,
    ) {
        viewer.rtcClient.setAutoPay(enabled)
    }
}
