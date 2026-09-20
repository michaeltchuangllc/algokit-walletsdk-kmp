package com.michaeltchuang.walletsdk.ui.liquidStream.domain.manager

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64

/** Connection-local metadata only. The host validates channel hints against on-chain participants. */
internal class ViewerVaultHandshake(
    private val viewer: String,
    signerPublicKey: ByteArray,
) {
    private val signerPublicKey = signerPublicKey.copyOf()
    private var lastSent: String? = null

    fun send(
        channelId: ByteArray?,
        isOpen: Boolean,
        force: Boolean = false,
        sendMessage: (String) -> Boolean,
    ): Boolean {
        if (!isOpen) return false
        val message =
            buildJsonObject {
                put("type", "segment:handshake")
                put("viewer", viewer)
                put("viewerPublicKey", Base64.encode(signerPublicKey))
                if (channelId?.size == 32) {
                    put("channelId", Base64.encode(channelId))
                }
            }.toString()
        if (!force && message == lastSent) return false
        if (!sendMessage(message)) return false
        lastSent = message
        return true
    }
}
