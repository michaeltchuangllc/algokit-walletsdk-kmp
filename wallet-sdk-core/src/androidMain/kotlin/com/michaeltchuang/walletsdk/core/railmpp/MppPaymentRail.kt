package com.michaeltchuang.walletsdk.core.railmpp

import com.michaeltchuang.walletsdk.core.railmpp.core.PaymentRail
import com.michaeltchuang.walletsdk.core.railmpp.core.PaymentRailRequestParams
import com.michaeltchuang.walletsdk.core.railmpp.core.PaymentReceipt
import com.michaeltchuang.walletsdk.core.railmpp.core.PaymentRequest
import com.michaeltchuang.walletsdk.core.railmpp.core.RailPayment
import org.json.JSONObject
import com.michaeltchuang.walletsdk.core.railmpp.internal.MppConsumer
import com.michaeltchuang.walletsdk.core.railmpp.internal.MppProvider
import com.michaeltchuang.walletsdk.core.railmpp.spec.AuthParams
import com.michaeltchuang.walletsdk.core.railmpp.spec.ChargeChallengeCodec
import java.util.UUID
import kotlin.collections.get

/**
 * MPP "charge" payment rail for Algorand — Kotlin port.
 *
 * Wire-compatible with `@goplausible/webrtc-payment-rail-mpp` (TS): a Kotlin
 * provider can serve a web consumer and vice versa.
 *
 * One instance can act as either provider or consumer (or both, when used
 * by the dispatching layer in the demo). Pass [MppServerConfig] to enable
 * provider mode and [MppClientConfig] to enable consumer mode.
 */
class MppPaymentRail(
    private val serverConfig: MppServerConfig? = null,
    private val clientConfig: MppClientConfig? = null,
) : PaymentRail {

    override val railId: String = "mpp"
    override val supportedNetworks: List<String> =
        listOf(MppNetworks.MAINNET, MppNetworks.TESTNET)

    private val provider: MppProvider? = serverConfig?.let { MppProvider(it) }
    private val consumer: MppConsumer? = clientConfig?.let { MppConsumer(it) }

    /** Provider-side: issue a fresh challenge and embed it in PaymentRequest.railPayload. */
    override suspend fun createPaymentRequest(params: PaymentRailRequestParams): PaymentRequest {
        val provider = provider ?: error(
            "MppPaymentRail: provider mode requires MppServerConfig in the constructor"
        )
        if (params.payTo != provider.serverConfig.recipient) {
            error("MppPaymentRail: payTo (${params.payTo}) does not match recipient (${provider.serverConfig.recipient})")
        }

        val asset = params.asset.trim()
        val normalizedAsset = if (asset.equals("algo", ignoreCase = true)) ALGO_ASSET else asset
        val isAlgo = normalizedAsset.isBlank() || normalizedAsset == ALGO_ASSET
        val currency = if (isAlgo) "ALGO" else "ASA"

        val issued = provider.issueChallenge(
            amount = params.amount,
            currency = currency,
            asaId = if (isAlgo) null else normalizedAsset,
        )

        val challengeId = issued.challenge.id
        val railPayload = JSONObject().apply {
            put("protocol", "mpp")
            put("version", 0)
            put("challengeId", challengeId)
            put("wwwAuthenticate", issued.wwwAuthenticate)
            put("issuedAt", System.currentTimeMillis())
        }

        return PaymentRequest(
            id = UUID.randomUUID().toString(),
            sessionId = params.sessionId,
            segmentIndex = params.segmentIndex,
            amount = params.amount,
            asset = params.asset,
            network = params.network,
            payTo = params.payTo,
            ttl = params.ttl,
            nonce = "mpp:$challengeId",
            meta = params.meta,
            railPayload = railPayload,
        )
    }

    /** Consumer-side: parse the challenge, build + sign txn group, return a credential. */
    override suspend fun createRailPayment(request: PaymentRequest): RailPayment {
        val consumer = consumer ?: error(
            "MppPaymentRail: consumer mode requires MppClientConfig in the constructor"
        )
        val payload = request.railPayload as? JSONObject
            ?: error("MppPaymentRail: PaymentRequest.railPayload must be a JSONObject")
        val wwwAuth = payload.optString("wwwAuthenticate")
            .ifBlank { error("MppPaymentRail: railPayload missing 'wwwAuthenticate'") }

        val challenge = ChargeChallengeCodec.fromAuthHeader(wwwAuth)
        val credential = consumer.createCredential(challenge)

        // Wrap the credential as the paymentPayload. Mirror the TS rail's wire
        // format: { credential: "Payment ..." } so the same provider verifies both.
        val paymentPayload = JSONObject().apply {
            put("credential", credential)
        }
        val paymentRequirements = JSONObject().apply {
            put("scheme", "charge")
            put("network", request.network)
            put("amount", request.amount)
            put("asset", request.asset)
            put("payTo", request.payTo)
        }

        return RailPayment(
            railId = railId,
            version = 0,
            nonce = request.nonce,
            paymentPayload = paymentPayload,
            paymentRequirements = paymentRequirements,
        )
    }

    /** Provider-side: verify credential, sign fee payer if any, broadcast to algod. */
    override suspend fun verifyAndSettle(
        railPayment: RailPayment,
        request: PaymentRequest,
    ): PaymentReceipt {
        val provider = provider ?: error(
            "MppPaymentRail: provider mode requires MppServerConfig in the constructor"
        )
        if (railPayment.nonce != request.nonce) {
            error("Nonce mismatch")
        }

        val credentialString = extractCredential(railPayment.paymentPayload)
        val authHeader = if (AuthParams.hasPaymentPrefix(credentialString)) {
            credentialString
        } else {
            "Payment $credentialString"
        }

        val receipt = provider.verifyAndBroadcast(authHeader)

        return PaymentReceipt(
            txId = receipt.reference,
            sessionId = request.sessionId,
            segmentIndex = request.segmentIndex,
            amount = request.amount,
            asset = request.asset,
            payTo = request.payTo,
            payFrom = "",
            feePayer = provider.serverConfig.feePayer?.address?.toString(),
            facilitator = null,
            network = request.network,
            timestamp = System.currentTimeMillis(),
        )
    }

    private fun extractCredential(payload: Any?): String {
        if (payload is String) return payload
        if (payload is JSONObject) {
            val s = payload.optString("credential")
            if (s.isNotBlank()) return s
        }
        // Cross-language objects from the TS rail arrive as Map<String, *> after
        // the core SDK's JSON.parse — handle that shape too.
        if (payload is Map<*, *>) {
            val s = payload["credential"]?.toString()
            if (!s.isNullOrBlank()) return s
        }
        error("MppPaymentRail: railPayment.paymentPayload missing 'credential' field")
    }

    /** Internal accessor for the provider — used by extractCredential bridging. */
    internal val MppProvider.serverConfig: MppServerConfig
        get() = this@MppPaymentRail.serverConfig
            ?: error("MppPaymentRail: provider mode invoked without MppServerConfig")
}
