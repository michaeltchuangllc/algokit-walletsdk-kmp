package com.michaeltchuang.walletsdk.core.railmpp.internal

import com.ionspin.kotlin.bignum.integer.BigInteger
import com.michaeltchuang.walletsdk.core.railmpp.ALGO_ASSET
import com.michaeltchuang.walletsdk.core.railmpp.MppServerConfig
import com.michaeltchuang.walletsdk.core.railmpp.spec.Base64Std
import com.michaeltchuang.walletsdk.core.railmpp.spec.ChargeChallenge
import com.michaeltchuang.walletsdk.core.railmpp.spec.ChargeChallengeCodec
import com.michaeltchuang.walletsdk.core.railmpp.spec.ChargeCredential
import com.michaeltchuang.walletsdk.core.railmpp.spec.ChargeCredentialCodec
import com.michaeltchuang.walletsdk.core.railmpp.spec.ChargeReceipt
import com.michaeltchuang.walletsdk.core.railmpp.spec.ChargeRequest
import com.michaeltchuang.walletsdk.core.railmpp.spec.ChargeRequestCodec
import com.michaeltchuang.walletsdk.core.railmpp.spec.SuggestedParams
import io.github.aakira.napier.Napier
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Provider-side flow for the algorand charge intent.
 *  1. issueChallenge() → builds the WWW-Authenticate header
 *  2. verifyAndBroadcast() → verifies credential, signs fee payer if any, broadcasts
 */
internal class MppProvider(
    private val config: MppServerConfig,
) {
    private companion object {
        const val TAG = "MppProvider"
    }

    @OptIn(ExperimentalUuidApi::class)
    suspend fun issueChallenge(
        amount: String,
        currency: String,
        asaId: String?,
    ): IssuedChallenge {
        Napier.d(
            "[ISSUE_CHALLENGE] method=algorand network=${config.network} recipient=${config.recipient} " +
                "amount=$amount currency=$currency asaId=${asaId ?: "ALGO"}",
            tag = TAG,
        )
        val challengeReference = Uuid.random().toString()
        val expires = futureRfc3339(config.challengeTtlSeconds)
        val lease = computeLease(challengeReference)

        val params = fetchAndSerializeParams()

        val request =
            buildJsonObject {
                put("amount", amount)
                put("currency", currency)
                put("recipient", config.recipient)
                putJsonObject("methodDetails") {
                    put("network", config.network)
                    put("challengeReference", challengeReference)
                    put("lease", lease)
                    if (asaId != null && asaId != ALGO_ASSET) {
                        put("asaId", asaId)
                    }
                    if (config.feePayer != null) {
                        put("feePayer", true)
                        put("feePayerKey", config.feePayer.address)
                    }
                    putJsonObject("suggestedParams") {
                        put("firstValid", params.firstValid)
                        put("lastValid", params.lastValid)
                        put("genesisHash", params.genesisHash)
                        put("genesisId", params.genesisId)
                        put("fee", params.fee)
                        put("minFee", params.minFee)
                    }
                }
            }

        return issueChallengeWithMethod(request = request, method = "algorand", expires = expires)
    }

    fun issueSolanaChallenge(
        amount: String,
        currency: String,
        mint: String?,
    ): IssuedChallenge {
        val expires = futureRfc3339(config.challengeTtlSeconds)
        val request =
            buildJsonObject {
                put("amount", amount)
                put("currency", currency)
                put("recipient", config.recipient)
                putJsonObject("methodDetails") {
                    put("network", config.network)
                    if (!mint.isNullOrBlank()) put("mint", mint)
                }
            }
        return issueChallengeWithMethod(request = request, method = "solana", expires = expires)
    }

    private fun issueChallengeWithMethod(
        request: JsonObject,
        method: String,
        expires: String,
    ): IssuedChallenge {
        val challengeForHmac =
            ChargeChallenge(
                id = "",
                realm = config.realm,
                method = method,
                intent = "charge",
                request = request,
                expires = expires,
            )
        val id = ChargeChallengeCodec.computeId(challengeForHmac, config.secretKey)
        val challenge = challengeForHmac.copy(id = id)

        return IssuedChallenge(
            challenge = challenge,
            wwwAuthenticate = ChargeChallengeCodec.toAuthHeader(challenge),
        )
    }

    /** Verify the credential and broadcast the txn group. Returns the on-chain TxID. */
    suspend fun verifyAndBroadcast(authHeader: String): ChargeReceipt =
        withContext(Dispatchers.Default) {
            Napier.d("[VERIFY_START] authHeaderBytes=${authHeader.length}", tag = TAG)
            val credential = ChargeCredentialCodec.fromAuthHeader(authHeader)
            val challenge = credential.challenge
            Napier.d("[VERIFY_CHALLENGE] method=${challenge.method} id=${challenge.id} expires=${challenge.expires ?: "none"}", tag = TAG)

            if (!ChargeChallengeCodec.verifyId(challenge, config.secretKey)) {
                throw MppVerifyException("Invalid challenge id (HMAC mismatch)")
            }

            challenge.expires?.let {
                val expiresMs = parseRfc3339Ms(it)
                if (expiresMs != null && expiresMs < mppNowMs()) {
                    throw MppVerifyException("Challenge expired")
                }
            }

            if (challenge.method.equals("solana", ignoreCase = true)) {
                return@withContext verifyAndBroadcastSolana(credential)
            }

            val req = ChargeRequestCodec.parseAlgorandRequest(challenge.request)
            Napier.d(
                "[VERIFY_ALGO_REQ] recipient=${req.recipient} configuredRecipient=${config.recipient} amount=${req.amount} " +
                    "currency=${req.currency} asaId=${req.asaId ?: "ALGO"} feePayer=${req.feePayer}",
                tag = TAG,
            )
            if (req.recipient != config.recipient) {
                throw MppVerifyException("Recipient mismatch: challenge=${req.recipient} configured=${config.recipient}")
            }

            val payment = credential.payload
            val paymentGroup = payment.paymentGroup ?: throw MppVerifyException("paymentGroup is required for algorand method")
            val paymentIndex = payment.paymentIndex ?: throw MppVerifyException("paymentIndex is required for algorand method")
            if (paymentGroup.isEmpty() || paymentGroup.size > 16) {
                throw MppVerifyException("paymentGroup must contain 1..16 entries")
            }
            if (paymentIndex !in paymentGroup.indices) {
                throw MppVerifyException("paymentIndex out of range")
            }

            val decoded: List<MppDecodedTxn> =
                paymentGroup.mapIndexed { i, b64 ->
                    val bytes = Base64Std.decode(b64)
                    mppDecodeTxn(bytes, isFeePayerSlot = req.feePayer && i == 0)
                }
            Napier.d("[VERIFY_ALGO_GROUP] txCount=${decoded.size} providedPaymentIndex=$paymentIndex feePayer=${req.feePayer}", tag = TAG)

            val resolvedPaymentIndex = verifyGroup(req, paymentIndex, decoded)
            Napier.d("[VERIFY_ALGO_GROUP_OK] resolvedPaymentIndex=$resolvedPaymentIndex", tag = TAG)

            val algodUrl = resolveAlgodUrl(config.algodUrl, config.network)
            val txIdToReturn: String =
                if (req.feePayer) {
                    val feePayerSlot = decoded[0]
                    val signer =
                        config.feePayer
                            ?: throw MppVerifyException("Challenge requires fee payer but provider has none configured")
                    val unsignedFeePayerTxn =
                        feePayerSlot.unsignedRaw
                            ?: throw MppVerifyException("Fee payer slot must contain an unsigned transaction")
                    val feePayerBytes = signer.signTransactionBytes(unsignedFeePayerTxn)
                    val others =
                        decoded.drop(1).map {
                            it.signedRaw ?: throw MppVerifyException("Non-fee-payer slot must be signed")
                        }
                    val broadcastTxId = mppBroadcastGroup(algodUrl, listOf(feePayerBytes) + others)
                    decoded[resolvedPaymentIndex].computedTxId
                        ?: broadcastTxId
                        ?: throw MppVerifyException("Could not derive payment TxID")
                } else {
                    val allSigned =
                        decoded.map {
                            it.signedRaw ?: throw MppVerifyException("All txns must be signed when feePayer = false")
                        }
                    val broadcastTxId = mppBroadcastGroup(algodUrl, allSigned)
                    decoded[resolvedPaymentIndex].computedTxId
                        ?: broadcastTxId
                        ?: throw MppVerifyException("Could not derive payment TxID")
                }

            Napier.d("[VERIFY_SETTLE_OK] txId=$txIdToReturn", tag = TAG)
            ChargeReceipt(reference = txIdToReturn)
        }

    // ─── Internal helpers ─────────────────────────────────

    private suspend fun fetchAndSerializeParams(): SuggestedParams {
        val params = mppFetchSuggestedParams(resolveAlgodUrl(config.algodUrl, config.network))
        return SuggestedParams(
            firstValid = params.lastRound,
            lastValid = params.lastRound + 1000L,
            genesisHash = params.genesisHashB64,
            genesisId = params.genesisId,
            fee = params.fee,
            minFee = params.minFee,
        )
    }

    private fun computeLease(challengeReference: String): String {
        val hash = sha256(challengeReference.encodeToByteArray())
        return Base64Std.encode(hash)
    }

    private fun verifyGroup(
        req: ChargeRequest,
        paymentIndex: Int,
        decoded: List<MppDecodedTxn>,
    ): Int {
        // All txns in the group must share a group id.
        val firstGroupBytes =
            decoded.first().groupId
                ?: throw MppVerifyException("Group id missing on first txn")
        for (d in decoded) {
            val gBytes = d.groupId ?: throw MppVerifyException("Group id missing on txn")
            if (!gBytes.contentEquals(firstGroupBytes)) {
                throw MppVerifyException("Group id mismatch across txns")
            }
        }

        // Dangerous-field checks apply ONLY to the fee payer txn (spec §7).
        if (req.feePayer && decoded.isNotEmpty()) {
            val fp = decoded[0]
            if (fp.hasCloseRemainderTo) throw MppVerifyException("Fee payer txn has closeRemainderTo — dangerous")
            if (fp.hasAssetCloseTo) throw MppVerifyException("Fee payer txn has assetCloseTo — dangerous")
            if (fp.hasRekeyTo) throw MppVerifyException("Fee payer txn has rekeyTo — dangerous")
        }

        val isAlgo = req.asaId == null || req.asaId == ALGO_ASSET
        val expectedAmount = BigInteger.parseString(req.amount.trim())
        val expectedAsaId = if (isAlgo) null else parseMppAsaId(req.asaId, context = "ASA charge verification")

        val resolvedPaymentIndex = resolvePaymentIndex(req, paymentIndex, decoded, isAlgo, expectedAsaId, expectedAmount)
        val payment = decoded[resolvedPaymentIndex]

        // Lease is REQUIRED per spec — verify SHA-256(challengeReference) matches.
        val expectedLease = Base64Std.decode(req.lease)
        if (payment.lease == null || !payment.lease.contentEquals(expectedLease)) {
            throw MppVerifyException("Lease mismatch (REQUIRED per spec)")
        }

        return resolvedPaymentIndex
    }

    private fun resolvePaymentIndex(
        req: ChargeRequest,
        providedPaymentIndex: Int,
        decoded: List<MppDecodedTxn>,
        isAlgo: Boolean,
        expectedAsaId: Long?,
        expectedAmount: BigInteger,
    ): Int {
        // Validate the recipient address format up front (matches the original byte-decode guard).
        decodeAlgorandAddressBytes(req.recipient)
            ?: throw MppVerifyException("Invalid recipient address in challenge: ${req.recipient}")

        fun matchesCharge(txn: MppDecodedTxn): Boolean =
            if (isAlgo) {
                txn.type == MppDecodedTxn.TYPE_PAYMENT &&
                    txn.receiver == req.recipient &&
                    BigInteger.fromLong(txn.amount ?: 0L) == expectedAmount
            } else {
                txn.type == MppDecodedTxn.TYPE_ASSET_TRANSFER &&
                    txn.assetReceiver == req.recipient &&
                    BigInteger.fromLong(txn.assetAmount ?: 0L) == expectedAmount &&
                    (txn.xferAsset ?: 0L) == expectedAsaId
            }

        if (matchesCharge(decoded[providedPaymentIndex])) {
            return providedPaymentIndex
        }

        val matchedIndex = decoded.indexOfFirst { matchesCharge(it) }
        if (matchedIndex >= 0) {
            return matchedIndex
        }

        val txnDebug =
            decoded
                .mapIndexed { index, d ->
                    "idx=$index type=${d.type} sender=${d.sender} receiver=${d.receiver} amount=${d.amount} " +
                        "assetReceiver=${d.assetReceiver} assetAmount=${d.assetAmount} xferAsset=${d.xferAsset}"
                }.joinToString(separator = " | ")

        if (isAlgo) {
            throw MppVerifyException(
                "Payment txn must be type=pay for ALGO charge; providedIndex=$providedPaymentIndex " +
                    "expectedRecipient=${req.recipient} expectedAmount=$expectedAmount txns=[$txnDebug]",
            )
        }
        throw MppVerifyException(
            "Payment txn must be type=axfer for ASA charge; providedIndex=$providedPaymentIndex " +
                "expectedAsaId=${req.asaId} expectedRecipient=${req.recipient} expectedAmount=$expectedAmount txns=[$txnDebug]",
        )
    }

    private suspend fun verifyAndBroadcastSolana(credential: ChargeCredential): ChargeReceipt {
        val req = ChargeRequestCodec.parseSolanaRequest(credential.challenge.request)
        if (req.recipient != config.recipient) {
            throw MppVerifyException("Recipient mismatch: challenge=${req.recipient} configured=${config.recipient}")
        }

        val signedTxB64 =
            credential.payload.signedTransaction
                ?: throw MppVerifyException("signedTransaction is required for solana method")
        val signedTx = Base64Std.decode(signedTxB64)

        val txId = broadcastSolanaTransaction(signedTx, req.network ?: config.network)
        return ChargeReceipt(reference = txId)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun broadcastSolanaTransaction(
        signedTransaction: ByteArray,
        network: String,
    ): String {
        val endpoint =
            when {
                network.contains("mainnet", ignoreCase = true) -> "https://api.mainnet-beta.solana.com"
                network.contains("testnet", ignoreCase = true) -> "https://api.testnet.solana.com"
                else -> "https://api.devnet.solana.com"
            }

        val rpcPayload =
            JsonObject(
                mapOf(
                    "jsonrpc" to JsonPrimitive("2.0"),
                    "id" to JsonPrimitive(1),
                    "method" to JsonPrimitive("sendTransaction"),
                    "params" to
                        JsonArray(
                            listOf(
                                JsonPrimitive(Base64.encode(signedTransaction)),
                                JsonObject(
                                    mapOf(
                                        "encoding" to JsonPrimitive("base64"),
                                        "skipPreflight" to JsonPrimitive(false),
                                        "preflightCommitment" to JsonPrimitive("confirmed"),
                                    ),
                                ),
                            ),
                        ),
                ),
            )

        val httpClient = HttpClient()
        val responseText =
            try {
                httpClient
                    .post(endpoint) {
                        contentType(ContentType.Application.Json)
                        setBody(rpcPayload.toString())
                    }.body<String>()
            } finally {
                httpClient.close()
            }

        val json = Json.parseToJsonElement(responseText).jsonObject
        val rpcError = json["error"]?.toString()
        if (rpcError != null) {
            Napier.e("[BROADCAST_SOLANA_FAILED] network=$network endpoint=$endpoint error=$rpcError", tag = TAG)
            throw MppVerifyException("Solana RPC error: $rpcError")
        }

        val signature =
            json["result"]?.toString()?.trim('"')
                ?: throw MppVerifyException("Missing Solana transaction signature in RPC response")
        Napier.d("[BROADCAST_SOLANA_OK] network=$network signature=$signature", tag = TAG)
        return signature
    }

    internal data class IssuedChallenge(
        val challenge: ChargeChallenge,
        val wwwAuthenticate: String,
    )
}

internal class MppVerifyException(
    message: String,
) : RuntimeException(message)
