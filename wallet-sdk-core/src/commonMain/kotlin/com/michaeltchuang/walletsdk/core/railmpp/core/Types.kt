package com.michaeltchuang.walletsdk.core.railmpp.core

/**
 * Core types for webrtc-payment-sdk.
 * All types are payment-rail agnostic — rail-specific details
 * live in the rail extension packages.
 */

enum class PeerRole { PROVIDER, CONSUMER }

enum class GatingMode(
    val value: String,
) {
    WHOLE_STREAM("whole-stream"),
    PARTIAL_TIME("partial-time"),
    PARTIAL_BYTES("partial-bytes"),
    ;

    companion object {
        fun fromString(s: String): GatingMode =
            values().firstOrNull { it.value == s }
                ?: throw IllegalArgumentException("Unknown GatingMode: $s")
    }
}

enum class EnforcementMode(
    val value: String,
) {
    TRACK("track"),
    CRYPTO("crypto"),
}

// ─── Authentication ──────────────────────────────────────

data class AuthIdentity(
    val address: String,
    val credentialId: String? = null,
    val provider: String,
    val meta: Map<String, Any>? = null,
)

data class AuthChallenge(
    val challenge: String,
    val sessionId: String,
    val expiresAt: Long,
)

// ─── Payment Rail (generic) ──────────────────────────────

data class PaymentRequestMeta(
    val gatingMode: GatingMode,
    val enforcement: EnforcementMode,
    val segmentDuration: Int? = null,
    val segmentBytes: Long? = null,
    val viewerAddress: String? = null,
    val voucherSignature: String? = null,
)

data class PaymentRequest(
    val id: String,
    val sessionId: String,
    val segmentIndex: Int,
    val amount: String,
    val asset: String,
    val network: String,
    val payTo: String,
    val ttl: Int,
    val nonce: String,
    val meta: PaymentRequestMeta,
    val railPayload: Any? = null,
)

/**
 * Rail payment — created by the consumer after signing.
 * Sent to the provider via DataChannel for facilitator submission.
 */
data class RailPayment(
    val railId: String,
    val version: Int,
    val nonce: String,
    val paymentPayload: Any,
    val paymentRequirements: Any,
)

/**
 * Payment receipt — created by the provider after successful facilitator settlement.
 */
data class PaymentReceipt(
    val txId: String,
    val sessionId: String,
    val segmentIndex: Int,
    val amount: String,
    val asset: String,
    val payTo: String,
    val payFrom: String = "",
    val feePayer: String? = null,
    val facilitator: String? = null,
    val network: String,
    val timestamp: Long,
)

// ─── Consent ─────────────────────────────────────────────

data class ConsentTerms(
    val gatingMode: GatingMode,
    val amount: String,
    val asset: String,
    val network: String,
    val payTo: String? = null,
    val segmentDuration: Int? = null,
    val segmentBytes: Long? = null,
    val suggestedBudgetCap: String? = null,
)

data class BudgetCap(
    val amount: String,
    val asset: String,
)

data class ConsentApproval(
    val approved: Boolean,
    val autoPaySegments: Boolean,
    val budgetCap: BudgetCap? = null,
    val maxAutoPaySegments: Int? = null,
    val voucherSignature: ByteArray? = null,
)

// ─── Session Stats ───────────────────────────────────────

data class SessionStats(
    val sessionId: String,
    var segmentsDelivered: Int = 0,
    var segmentsPaid: Int = 0,
    var totalAmountReceived: String = "0",
    var totalBytesTransferred: Long = 0,
    var durationSeconds: Long = 0,
)

data class SpendTransaction(
    val txId: String,
    val amount: String,
    val segmentIndex: Int,
    val timestamp: Long,
)

data class SpendSummary(
    var totalAmount: String = "0",
    var asset: String = "",
    var segmentsPaid: Int = 0,
    val transactions: MutableList<SpendTransaction> = mutableListOf(),
)

// ─── Configuration ───────────────────────────────────────

data class GatingConfig(
    val mode: GatingMode,
    val amount: String,
    val asset: String,
    val network: String,
    val payTo: String,
    val segmentDuration: Int? = null,
    val leadTime: Int? = null,
    val segmentBytes: Long? = null,
    val leadBytes: Long? = null,
)

data class ServerConfig(
    val sessionId: String? = null,
    val gating: GatingConfig,
    val enforcement: EnforcementMode = EnforcementMode.TRACK,
    val paymentTTL: Int = 30,
    val gracePeriod: Int = 0,
    val viewerAddress: String? = null,
    val viewerAuthorizedSignerPublicKey: ByteArray? = null,
    val skipPaymentRequestWhenSessionFunded: Boolean = false,
)

data class ClientConfig(
    val autoPaySegments: Boolean = false,
    val maxAutoPaySegments: Int? = null,
    val budgetCap: BudgetCap? = null,
)
