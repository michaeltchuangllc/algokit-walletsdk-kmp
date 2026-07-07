package com.michaeltchuang.walletsdk.core.railmpp.domain.model

data class ServerConfig(
    val sessionId: String? = null,
    val gating: GatingConfig,
    val enforcement: EnforcementMode = EnforcementMode.TRACK,
    val paymentTTL: Int = 30,
    val gracePeriod: Int = 0,
    val viewerAddress: String? = null,
    val viewerAuthorizedSignerPublicKey: ByteArray? = null,
    val skipPaymentRequestWhenSessionFunded: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ServerConfig

        if (paymentTTL != other.paymentTTL) return false
        if (gracePeriod != other.gracePeriod) return false
        if (skipPaymentRequestWhenSessionFunded != other.skipPaymentRequestWhenSessionFunded) return false
        if (sessionId != other.sessionId) return false
        if (gating != other.gating) return false
        if (enforcement != other.enforcement) return false
        if (viewerAddress != other.viewerAddress) return false
        if (!viewerAuthorizedSignerPublicKey.contentEquals(other.viewerAuthorizedSignerPublicKey)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = paymentTTL
        result = 31 * result + gracePeriod
        result = 31 * result + skipPaymentRequestWhenSessionFunded.hashCode()
        result = 31 * result + (sessionId?.hashCode() ?: 0)
        result = 31 * result + gating.hashCode()
        result = 31 * result + enforcement.hashCode()
        result = 31 * result + (viewerAddress?.hashCode() ?: 0)
        result = 31 * result + (viewerAuthorizedSignerPublicKey?.contentHashCode() ?: 0)
        return result
    }
}