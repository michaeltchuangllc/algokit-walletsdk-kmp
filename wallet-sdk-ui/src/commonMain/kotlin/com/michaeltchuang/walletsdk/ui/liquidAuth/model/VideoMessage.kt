package com.michaeltchuang.walletsdk.ui.liquidAuth.model

import kotlinx.serialization.Serializable

/**
 * Video frame message for streaming over WebRTC data channel.
 * Uses ARC-0027 style messaging format.
 */
@Serializable
data class VideoFrameMessage(
    val reference: String = "liquid:video:frame",
    val id: String,
    val timestamp: Long,
    val format: String = "jpeg", // jpeg, png, h264
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val isKeyFrame: Boolean = true,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as VideoFrameMessage

        if (reference != other.reference) return false
        if (id != other.id) return false
        if (timestamp != other.timestamp) return false
        if (format != other.format) return false
        if (!data.contentEquals(other.data)) return false
        if (width != other.width) return false
        if (height != other.height) return false
        if (isKeyFrame != other.isKeyFrame) return false

        return true
    }

    override fun hashCode(): Int {
        var result = reference.hashCode()
        result = 31 * result + id.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + isKeyFrame.hashCode()
        return result
    }

    companion object {
        const val REFERENCE = "liquid:video:frame"
    }
}

/**
 * Video streaming control messages
 */
@Serializable
sealed class VideoControlMessage {
    abstract val reference: String

    @Serializable
    data class StartStreaming(
        override val reference: String = "liquid:video:start",
        val resolution: String = "640x480",
        val fps: Int = 30,
    ) : VideoControlMessage()

    @Serializable
    data class StopStreaming(
        override val reference: String = "liquid:video:stop",
    ) : VideoControlMessage()

    @Serializable
    data class FrameAck(
        override val reference: String = "liquid:video:ack",
        val frameId: String,
    ) : VideoControlMessage()
}
