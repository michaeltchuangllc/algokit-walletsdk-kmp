package com.michaeltchuang.walletsdk.core.railmpp.core

// Core interfaces for webrtc-payment-sdk.
// Extension points for auth providers, payment rails, and signaling adapters.

// ─── Authentication Provider ─────────────────────────────

interface AuthProvider {
    val providerId: String

    suspend fun createChallenge(sessionId: String): AuthChallenge

    suspend fun respondToChallenge(challenge: AuthChallenge): Pair<AuthIdentity, String>

    suspend fun verifyIdentity(
        challenge: AuthChallenge,
        proof: String,
    ): AuthIdentity?
}

// ─── Payment Rail ────────────────────────────────────────

data class PaymentRailRequestParams(
    val sessionId: String,
    val segmentIndex: Int,
    val amount: String,
    val asset: String,
    val network: String,
    val payTo: String,
    val ttl: Int,
    val meta: PaymentRequestMeta,
)

interface PaymentRail {
    val railId: String
    val supportedNetworks: List<String>

    suspend fun createPaymentRequest(params: PaymentRailRequestParams): PaymentRequest

    suspend fun createRailPayment(request: PaymentRequest): RailPayment

    suspend fun verifyAndSettle(
        railPayment: RailPayment,
        request: PaymentRequest,
    ): PaymentReceipt
}

// ─── Consent Handler ─────────────────────────────────────

interface ConsentHandler {
    suspend fun requestConsent(terms: ConsentTerms): ConsentApproval
}

// ─── Nonce Store ─────────────────────────────────────────

interface NonceStore {
    /** Returns false if nonce already exists (replay detected) */
    suspend fun checkAndStore(
        nonce: String,
        ttlSeconds: Int,
    ): Boolean
}
