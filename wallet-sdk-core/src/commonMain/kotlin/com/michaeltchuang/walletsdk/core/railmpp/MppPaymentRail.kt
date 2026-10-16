package com.michaeltchuang.walletsdk.core.railmpp

import com.michaeltchuang.walletsdk.core.deeplink.utils.AssetConstants
import com.michaeltchuang.walletsdk.core.railmpp.core.PaymentRail
import com.michaeltchuang.walletsdk.core.railmpp.core.PaymentRailRequestParams
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.PaymentReceipt
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.PaymentRequest
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.RailPayment
import com.michaeltchuang.walletsdk.core.railmpp.internal.MppConsumer
import com.michaeltchuang.walletsdk.core.railmpp.internal.MppProvider
import com.michaeltchuang.walletsdk.core.railmpp.internal.mppDecodeTxn
import com.michaeltchuang.walletsdk.core.railmpp.internal.mppNowMs
import com.michaeltchuang.walletsdk.core.railmpp.spec.AuthParams
import com.michaeltchuang.walletsdk.core.railmpp.spec.Base64Std
import com.michaeltchuang.walletsdk.core.railmpp.spec.ChargeChallengeCodec
import com.michaeltchuang.walletsdk.core.railmpp.spec.ChargeCredentialCodec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * MPP "charge" payment rail for Algorand — wire-compatible with
 * `@goplausible/webrtc-payment-rail-mpp` (TS), so a Kotlin provider can serve a
 * web consumer and vice versa.
 *
 * One instance acts as provider ([MppServerConfig]), consumer ([MppClientConfig]),
 * or both. Payloads use kotlinx [JsonObject] so the rail works on Android and iOS.
 */
class MppPaymentRail(
    private val serverConfig: MppServerConfig? = null,
    private val clientConfig: MppClientConfig? = null,
) : PaymentRail {
    override val railId: String = "mpp"
    override val supportedNetworks: List<String> =
        listOf(
            MppNetworks.ALGORAND_MAINNET,
            MppNetworks.ALGORAND_TESTNET,
            MppNetworks.ALGORAND_FUTURENET,
            MppNetworks.SOLANA_MAINNET,
            MppNetworks.SOLANA_DEVNET,
            MppNetworks.SOLANA_TESTNET,
        )

    private val provider: MppProvider? = serverConfig?.let { MppProvider(it) }
    private val consumer: MppConsumer? = clientConfig?.let { MppConsumer(it) }

    /** Provider-side: issue a fresh challenge and embed it in PaymentRequest.railPayload. */
    @OptIn(ExperimentalUuidApi::class)
    override suspend fun createPaymentRequest(params: PaymentRailRequestParams): PaymentRequest {
        val provider =
            provider ?: error("MppPaymentRail: provider mode requires MppServerConfig in the constructor")
        val serverConfig =
            serverConfig ?: error("MppPaymentRail: provider mode requires MppServerConfig in the constructor")
        if (params.payTo != serverConfig.recipient) {
            error("MppPaymentRail: payTo (${params.payTo}) does not match recipient (${serverConfig.recipient})")
        }

        val isSolana = params.network.startsWith("solana:", ignoreCase = true)

        val asset = params.asset.trim()
        val normalizedAsset = normalizeAssetForNetwork(asset, params.network)
        val isAlgo = normalizedAsset.isBlank() || normalizedAsset == ALGO_ASSET
        val currency =
            if (isSolana) {
                if (asset.equals("sol", ignoreCase = true) || asset.isBlank()) "SOL" else "SPL"
            } else {
                if (isAlgo) "ALGO" else "ASA"
            }

        val issued =
            if (isSolana) {
                provider.issueSolanaChallenge(
                    amount = params.amount,
                    currency = currency,
                    mint = if (currency == "SOL") null else asset,
                )
            } else {
                provider.issueChallenge(
                    amount = params.amount,
                    currency = currency,
                    asaId = if (isAlgo) null else normalizedAsset,
                )
            }

        val challengeId = issued.challenge.id
        val railPayload =
            buildJsonObject {
                put("protocol", "mpp")
                put("version", 0)
                put("challengeId", challengeId)
                put("wwwAuthenticate", issued.wwwAuthenticate)
                put("issuedAt", mppNowMs())
            }

        return PaymentRequest(
            id = Uuid.random().toString(),
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
        val consumer =
            consumer ?: error("MppPaymentRail: consumer mode requires MppClientConfig in the constructor")
        val payload =
            request.railPayload?.jsonObject
                ?: error("MppPaymentRail: PaymentRequest.railPayload must be a JsonObject")
        val wwwAuth =
            payload["wwwAuthenticate"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: error("MppPaymentRail: railPayload missing 'wwwAuthenticate'")

        val challenge = ChargeChallengeCodec.fromAuthHeader(wwwAuth)
        val credential = consumer.createCredential(challenge)

        // Mirror the TS rail's wire format: { credential: "Payment ..." } so the
        // same provider verifies both.
        val paymentPayload = buildJsonObject { put("credential", credential) }
        val paymentRequirements =
            buildJsonObject {
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
        val provider =
            provider ?: error("MppPaymentRail: provider mode requires MppServerConfig in the constructor")
        if (railPayment.nonce != request.nonce) {
            error("Nonce mismatch")
        }

        val credentialString = extractCredential(railPayment.paymentPayload)
        val authHeader =
            if (AuthParams.hasPaymentPrefix(credentialString)) credentialString else "Payment $credentialString"

        val receipt = provider.verifyAndBroadcast(authHeader)

        return PaymentReceipt(
            txId = receipt.reference,
            sessionId = request.sessionId,
            segmentIndex = request.segmentIndex,
            amount = request.amount,
            asset = request.asset,
            payTo = request.payTo,
            payFrom = extractPayerAddress(authHeader),
            feePayer = serverConfig?.feePayer?.address,
            facilitator = null,
            network = request.network,
            timestamp = mppNowMs(),
        )
    }

    private fun normalizeAssetForNetwork(
        asset: String,
        network: String,
    ): String =
        when {
            asset.equals("ALGO", ignoreCase = true) -> ALGO_ASSET
            asset.equals("USDC", ignoreCase = true) ->
                AssetConstants.usdcIdForNetwork(network).toString()
            else -> asset
        }

    private fun extractCredential(payload: JsonElement): String {
        val s = (payload as? JsonObject)?.get("credential")?.jsonPrimitive?.contentOrNull
        if (!s.isNullOrBlank()) return s
        error("MppPaymentRail: railPayment.paymentPayload missing 'credential' field")
    }

    private fun extractPayerAddress(authHeader: String): String =
        runCatching {
            val credential = ChargeCredentialCodec.fromAuthHeader(authHeader)
            val paymentGroup = credential.payload.paymentGroup ?: return@runCatching ""
            val paymentIndex = credential.payload.paymentIndex

            val candidateIndexes =
                buildList {
                    if (paymentIndex != null && paymentIndex in paymentGroup.indices) add(paymentIndex)
                    if (!contains(0) && paymentGroup.isNotEmpty()) add(0)
                }

            for (index in candidateIndexes) {
                val txnBytes = Base64Std.decode(paymentGroup[index])
                // isFeePayerSlot = true tolerates both signed and unsigned (fee-payer) txns.
                val sender = mppDecodeTxn(txnBytes, isFeePayerSlot = true).sender
                if (!sender.isNullOrBlank()) return@runCatching sender
            }
            ""
        }.getOrDefault("")
}
