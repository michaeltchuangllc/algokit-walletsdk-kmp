package com.michaeltchuang.walletsdk.ui.liquidAuth.utils

import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ChatMessage

internal fun <T : Any> relayHostChat(
    message: ChatMessage,
    recipients: List<T>,
    source: T? = null,
    send: (T, ChatMessage) -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    val delivered = mutableListOf<T>()
    recipients.forEach { recipient ->
        if (recipient !== source && delivered.none { it === recipient }) {
            delivered += recipient
            try {
                send(recipient, message)
            } catch (error: Exception) {
                onFailure(error)
            }
        }
    }
}
