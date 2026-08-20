package com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels

import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * A [VideoSink] that can be attached to a remote [org.webrtc.VideoTrack] purely to detect
 * stream activity, independent of whatever sink is actually rendering the frames
 * (e.g. `WebRtcTextureViewRenderer`). WebRTC tracks support multiple simultaneous sinks, so
 * this can be added/removed alongside the real renderer without affecting playback.
 *
 * This is the only bit that has to be Android-specific, since [VideoSink]/[VideoFrame] come
 * from the Android WebRTC library. The actual throttling behavior lives in the shared
 * [FrameHeartbeatThrottle] so it's identical to iOS's heartbeat path.
 */
class StreamHeartbeatVideoSink(
    minIntervalMs: Long = 500L,
    private val onHeartbeat: () -> Unit,
) : VideoSink {
    private val throttle = FrameHeartbeatThrottle(minIntervalMs)

    override fun onFrame(frame: VideoFrame) {
        if (throttle.onSignal()) {
            onHeartbeat()
        }
    }
}
