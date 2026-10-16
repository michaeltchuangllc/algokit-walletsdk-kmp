package com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Throttles a high-frequency "a video frame was rendered" signal down to a low-rate heartbeat.
 *
 * Native WebRTC renderers deliver frames at up to the source frame rate (e.g. 30-60fps), but
 * all we need in order to keep [LiquidAuthViewerStateHolder]'s stream-timeout watchdog alive is
 * an occasional pulse. Sharing this logic in `commonMain` (rather than duplicating it per
 * platform) guarantees Android's native WebRTC `VideoSink` adapter (`StreamHeartbeatVideoSink`)
 * and iOS's Swift-driven renderer callback behave identically, regardless of how often each
 * platform actually invokes them - callers on both platforms can simply forward every frame and
 * let this class decide when a heartbeat should actually fire.
 */
class FrameHeartbeatThrottle(
    private val minIntervalMs: Long = 500L,
) {
    private var lastFiredAtMs = 0L

    @OptIn(ExperimentalTime::class)
    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    /**
     * Call this on every frame/signal. Returns `true` at most once every [minIntervalMs] -
     * callers should treat a `true` result as "emit the heartbeat now" and ignore `false`.
     */
    fun onSignal(): Boolean {
        val now = nowMs()
        if (now - lastFiredAtMs >= minIntervalMs) {
            lastFiredAtMs = now
            return true
        }
        return false
    }
}
