package com.michaeltchuang.walletsdk.core.railmpp.domain.model

data class PaymentRequestMeta(
    val gatingMode: GatingMode,
    val enforcement: EnforcementMode,
    val segmentDuration: Int? = null,
    val segmentBytes: Long? = null,
    val viewerAddress: String? = null,
    val voucherSignature: String? = null,
)