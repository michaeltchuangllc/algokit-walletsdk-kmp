package com.michaeltchuang.walletsdk.core.railmpp.internal

import android.util.Base64
import com.algorand.algosdk.crypto.Address
import com.algorand.algosdk.transaction.SignedTransaction
import com.algorand.algosdk.transaction.Transaction
import com.algorand.algosdk.util.Encoder
import com.algorand.algosdk.v2.client.common.AlgodClient
import com.algorand.algosdk.v2.client.common.Response
import com.algorand.algosdk.v2.client.model.PostTransactionsResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.michaeltchuang.walletsdk.core.railmpp.ALGO_ASSET
import com.michaeltchuang.walletsdk.core.railmpp.DEFAULT_ALGOD_URLS
import com.michaeltchuang.walletsdk.core.railmpp.MppNetworks
import com.michaeltchuang.walletsdk.core.railmpp.MppServerConfig
import xyz.goplausible.webrtcpaymentsdk.railmpp.spec.Base64Std
import com.michaeltchuang.walletsdk.core.railmpp.spec.ChargeChallenge
import com.michaeltchuang.walletsdk.core.railmpp.spec.ChargeChallengeCodec
import com.michaeltchuang.walletsdk.core.railmpp.spec.ChargeCredentialCodec
import com.michaeltchuang.walletsdk.core.railmpp.spec.ChargeReceipt
import com.michaeltchuang.walletsdk.core.railmpp.spec.ChargeRequest
import com.michaeltchuang.walletsdk.core.railmpp.spec.ChargeRequestCodec
import com.michaeltchuang.walletsdk.core.railmpp.spec.SuggestedParams
import java.math.BigInteger
import java.net.URI
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Provider-side flow for the algorand charge intent.
 *
 *  1. issueChallenge() → builds the WWW-Authenticate header
 *  2. verifyAndBroadcast() → verifies credential, signs fee payer if any, broadcasts
 */
internal class MppProvider(private val config: MppServerConfig) {

    // Note: no server-side TxID store needed per spec — the Algorand protocol
    // natively rejects duplicate TxIDs within the validity window and expired
    // ones outside it. The lease (REQUIRED, SHA-256(challengeReference)) provides
    // mutual exclusion between distinct txns covering the same charge.

    fun issueChallenge(
        amount: String,
        currency: String,
        asaId: String?,
    ): IssuedChallenge {
        val challengeReference = UUID.randomUUID().toString()
        val expires = futureRfc3339(config.challengeTtlSeconds)
        val lease = computeLease(challengeReference)

        val params = fetchAndSerializeParams()

        val methodDetails = JSONObject().apply {
            put("network", config.network)
            put("challengeReference", challengeReference)
            put("lease", lease)
            if (asaId != null && asaId != ALGO_ASSET) {
                put("asaId", asaId)
                // `decimals` removed per spec — amount is always in base units;
                // clients fetch decimals from chain via v2/assets/{asaId} if needed
            }
            if (config.feePayer != null) {
                put("feePayer", true)
                put("feePayerKey", config.feePayer.address.toString())
            }
            put("suggestedParams", JSONObject().apply {
                put("firstValid", params.firstValid)
                put("lastValid", params.lastValid)
                put("genesisHash", params.genesisHash)
                put("genesisId", params.genesisId)
                put("fee", params.fee)
                put("minFee", params.minFee)
            })
        }

        val request = JSONObject().apply {
            put("amount", amount)
            put("currency", currency)
            put("recipient", config.recipient)
            put("methodDetails", methodDetails)
        }

        // Compute HMAC challenge id with placeholder id, then re-emit with the real id.
        val challengeForHmac = ChargeChallenge(
            id = "",
            realm = config.realm,
            method = "algorand",
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

    /**
     * Verify the credential and broadcast the txn group. Returns the on-chain TxID.
     */
    suspend fun verifyAndBroadcast(authHeader: String): ChargeReceipt = withContext(Dispatchers.IO) {
        val credential = ChargeCredentialCodec.fromAuthHeader(authHeader)
        val challenge = credential.challenge

        // 1. Verify HMAC-bound challenge id
        if (!ChargeChallengeCodec.verifyId(challenge, config.secretKey)) {
            throw MppVerifyException("Invalid challenge id (HMAC mismatch)")
        }

        // 2. Validate expires
        challenge.expires?.let {
            val expiresMs = parseRfc3339Ms(it)
            if (expiresMs != null && expiresMs < System.currentTimeMillis()) {
                throw MppVerifyException("Challenge expired")
            }
        }

        // 3. Decode and verify the txn group
        val req = ChargeRequestCodec.parseAlgorandRequest(challenge.request)
        if (req.recipient != config.recipient) {
            throw MppVerifyException("Recipient mismatch: challenge=${req.recipient} configured=${config.recipient}")
        }

        val payment = credential.payload
        if (payment.paymentGroup.isEmpty() || payment.paymentGroup.size > 16) {
            throw MppVerifyException("paymentGroup must contain 1..16 entries")
        }
        if (payment.paymentIndex !in payment.paymentGroup.indices) {
            throw MppVerifyException("paymentIndex out of range")
        }

        // Decode each entry. An entry is either a SignedTransaction (most cases)
        // or a raw unsigned Transaction (the fee payer txn at index 0 when
        // feePayer mode is active).
        val decoded: List<DecodedTxn> = payment.paymentGroup.mapIndexed { i, b64 ->
            val bytes = Base64Std.decode(b64)
            tryDecode(bytes, isFeePayerSlot = req.feePayer && i == 0)
        }

        val resolvedPaymentIndex = verifyGroup(req, payment.paymentIndex, decoded)

        // 4. Sign the fee payer txn if applicable, then broadcast.
        val txIdToReturn: String = if (req.feePayer) {
            val feePayerSlot = decoded[0]
            val signer = config.feePayer
                ?: throw MppVerifyException("Challenge requires fee payer but provider has none configured")
            val unsignedFeePayerTxn = feePayerSlot.unsigned
                ?: throw MppVerifyException("Fee payer slot must contain an unsigned transaction")
            val signedFeePayer = signer.signTransaction(unsignedFeePayerTxn)
            val feePayerBytes = Encoder.encodeToMsgPack(signedFeePayer)
            val others = decoded.drop(1).map {
                it.signedRaw ?: throw MppVerifyException("Non-fee-payer slot must be signed")
            }
            val broadcastTxId = broadcastGroup(listOf(feePayerBytes) + others)
            // The payment txn is the canonical proof
            decoded[resolvedPaymentIndex].computedTxId
                ?: broadcastTxId
                ?: throw MppVerifyException("Could not derive payment TxID")
        } else {
            val allSigned = decoded.map {
                it.signedRaw ?: throw MppVerifyException("All txns must be signed when feePayer = false")
            }
            val broadcastTxId = broadcastGroup(allSigned)
            decoded[resolvedPaymentIndex].computedTxId
                ?: broadcastTxId
                ?: throw MppVerifyException("Could not derive payment TxID")
        }

        // No server-side TxID tracking needed — Algorand protocol rejects
        // duplicate TxIDs within validity window and expired ones outside it.
        // Lease provides mutual exclusion between distinct txns for the same charge.

        ChargeReceipt(reference = txIdToReturn)
    }

    // ─── Internal helpers ─────────────────────────────────

    private fun fetchAndSerializeParams(): SuggestedParams {
        val client = algodClient()
        val resp = client.TransactionParams().execute().body()
        val genesisHashB64 =
            Base64.encodeToString(resp.genesisHash, Base64.NO_WRAP)
        return SuggestedParams(
            firstValid = resp.lastRound,
            lastValid = resp.lastRound + 1000L,
            genesisHash = genesisHashB64,
            genesisId = resp.genesisId,
            fee = resp.fee,
            minFee = resp.minFee,
        )
    }

    private fun computeLease(challengeReference: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(challengeReference.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    private fun verifyGroup(
        req: ChargeRequest,
        paymentIndex: Int,
        decoded: List<DecodedTxn>,
    ): Int {
        // All txns in the group must share a group id.
        val firstGroupBytes = decoded.first().txn.group?.bytes
            ?: throw MppVerifyException("Group id missing on first txn")
        for (d in decoded) {
            val gBytes = d.txn.group?.bytes
                ?: throw MppVerifyException("Group id missing on txn")
            if (!gBytes.contentEquals(firstGroupBytes)) {
                throw MppVerifyException("Group id mismatch across txns")
            }
        }

        // Dangerous-field checks apply ONLY to the fee payer txn (spec §7).
        // close/aclose/rekey on the client's own payment txn are the client's
        // prerogative — the server only protects its own fee payer account.
        if (req.feePayer && decoded.isNotEmpty()) {
            val fp = decoded[0].txn
            if (fp.closeRemainderTo != null && fp.closeRemainderTo.bytes.any { it != 0.toByte() }) {
                throw MppVerifyException("Fee payer txn has closeRemainderTo — dangerous")
            }
            if (fp.assetCloseTo != null && fp.assetCloseTo.bytes.any { it != 0.toByte() }) {
                throw MppVerifyException("Fee payer txn has assetCloseTo — dangerous")
            }
            if (fp.rekeyTo != null && fp.rekeyTo.bytes.any { it != 0.toByte() }) {
                throw MppVerifyException("Fee payer txn has rekeyTo — dangerous")
            }
        }

        // The payment txn must match the challenge.
        val isAlgo = req.asaId == null || req.asaId == ALGO_ASSET
        val expectedAmount = req.amount.toBigInteger()

        // Some clients may provide a wrong paymentIndex (commonly 0 in feePayer mode).
        // First attempt the provided index, then fall back to discovery by challenge match.
        val resolvedPaymentIndex = resolvePaymentIndex(req, paymentIndex, decoded, isAlgo, expectedAmount)
        val payment = decoded[resolvedPaymentIndex].txn

        // Lease is REQUIRED per spec — verify SHA-256(challengeReference) matches.
        val expectedLease = Base64.decode(req.lease, Base64.NO_WRAP)
        if (payment.lease == null || !payment.lease.contentEquals(expectedLease)) {
            throw MppVerifyException("Lease mismatch (REQUIRED per spec)")
        }

        return resolvedPaymentIndex
    }

    private fun resolvePaymentIndex(
        req: ChargeRequest,
        providedPaymentIndex: Int,
        decoded: List<DecodedTxn>,
        isAlgo: Boolean,
        expectedAmount: BigInteger,
    ): Int {
        val expectedRecipientBytes = decodeAlgorandAddressBytes(req.recipient)
            ?: throw MppVerifyException("Invalid recipient address in challenge: ${req.recipient}")

        fun sameAddress(actual: Address?): Boolean =
            actual != null && actual.bytes.contentEquals(expectedRecipientBytes)

        fun matchesCharge(txn: Transaction): Boolean {
            return if (isAlgo) {
                txn.type == Transaction.Type.Payment &&
                    sameAddress(txn.receiver) &&
                    (txn.amount ?: BigInteger.ZERO) == expectedAmount
            } else {
                txn.type == Transaction.Type.AssetTransfer &&
                    sameAddress(txn.assetReceiver) &&
                    (txn.assetAmount ?: BigInteger.ZERO) == expectedAmount &&
                    (txn.xferAsset ?: BigInteger.ZERO).toLong() == req.asaId!!.toLong()
            }
        }

        val providedTxn = decoded[providedPaymentIndex].txn
        if (matchesCharge(providedTxn)) {
            return providedPaymentIndex
        }

        val matchedIndex = decoded.indexOfFirst { matchesCharge(it.txn) }
        if (matchedIndex >= 0) {
            return matchedIndex
        }

        val txnDebug = decoded.mapIndexed { index, d ->
            "idx=$index type=${d.txn.type} sender=${safeAddress(d.txn.sender)} receiver=${safeAddress(d.txn.receiver)} amount=${d.txn.amount} " +
                "assetReceiver=${safeAddress(d.txn.assetReceiver)} assetAmount=${d.txn.assetAmount} xferAsset=${d.txn.xferAsset} assetIndex=${d.txn.assetIndex}"
        }.joinToString(separator = " | ")

        if (isAlgo) {
            throw MppVerifyException(
                "Payment txn must be type=pay for ALGO charge; providedIndex=$providedPaymentIndex " +
                    "expectedRecipient=${req.recipient} expectedAmount=$expectedAmount txns=[$txnDebug]"
            )
        }
        throw MppVerifyException(
            "Payment txn must be type=axfer for ASA charge; providedIndex=$providedPaymentIndex " +
                "expectedAsaId=${req.asaId} expectedRecipient=${req.recipient} expectedAmount=$expectedAmount txns=[$txnDebug]"
        )
    }

    private fun safeAddress(address: Address?): String {
        if (address == null) return "null"
        return try {
            address.toString()
        } catch (_: Exception) {
            "addrBytes:${address.bytes.joinToString("") { b -> "%02x".format(b) }}"
        }
    }

    private fun decodeAlgorandAddressBytes(address: String): ByteArray? {
        val cleaned = address.trim().uppercase(Locale.US)
        if (cleaned.length != 58) return null

        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var bits = 0
        var bitBuffer = 0
        val out = ArrayList<Byte>(36)

        for (ch in cleaned) {
            val value = alphabet.indexOf(ch)
            if (value < 0) return null
            bitBuffer = (bitBuffer shl 5) or value
            bits += 5
            while (bits >= 8) {
                bits -= 8
                out.add(((bitBuffer shr bits) and 0xFF).toByte())
            }
        }

        if (out.size != 36) return null
        return out.take(32).toByteArray()
    }

    private fun broadcastGroup(signedBlobs: List<ByteArray>): String? {
        val concatenated = signedBlobs.fold(ByteArray(0)) { acc, b -> acc + b }
        val client = algodClient()
        val resp: Response<PostTransactionsResponse> = client.RawTransaction().rawtxn(concatenated).execute()
        if (!resp.isSuccessful) {
            val err = resp.message() ?: "algod rejected the group"
            throw MppVerifyException("Broadcast failed: $err")
        }
        return resp.body()?.txId
    }

    private fun tryDecode(bytes: ByteArray, isFeePayerSlot: Boolean): DecodedTxn {
        // Try signed first (most common); fall back to unsigned for fee payer slot.
        val signed = try {
            Encoder.decodeFromMsgPack(bytes, SignedTransaction::class.java)
        } catch (signedDecodeErr: Exception) {
            if (!isFeePayerSlot) {
                throw MppVerifyException(
                    "Could not decode signed transaction at non-fee-payer slot: bytes=${bytes.size}. signedDecode=${signedDecodeErr.message}."
                )
            }
            try {
                val unsigned: Transaction = Encoder.decodeFromMsgPack(bytes, Transaction::class.java)
                return DecodedTxn(txn = unsigned, signedRaw = null, unsigned = unsigned, computedTxId = unsigned.txID())
            } catch (e: Exception) {
                throw MppVerifyException("Could not decode unsigned fee payer txn: ${e.message}")
            }
        }

        val txn = signed.tx ?: throw MppVerifyException("Signed txn missing inner txn body")
        val txId = try {
            txn.txID()
        } catch (_: Exception) {
            // Some signed blobs decode fine but cannot be re-serialized by the SDK
            // for local txid derivation. Keep verification/broadcast flow alive.
            null
        }
        return DecodedTxn(txn = txn, signedRaw = bytes, unsigned = null, computedTxId = txId)
    }

    private fun algodClient(): AlgodClient {
        val url = config.algodUrl
            ?: DEFAULT_ALGOD_URLS[config.network]
            ?: DEFAULT_ALGOD_URLS[MppNetworks.TESTNET]!!
        val parsed = URI(url)
        val port = if (parsed.port > 0) parsed.port else if (parsed.scheme == "https") 443 else 80
        val host = "${parsed.scheme}://${parsed.host}"
        return AlgodClient(host, port, "")
    }

    private fun futureRfc3339(secondsFromNow: Int): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return sdf.format(Date(System.currentTimeMillis() + secondsFromNow * 1000L))
    }

    private fun parseRfc3339Ms(value: String): Long? = try {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        sdf.parse(value)?.time
    } catch (_: Exception) {
        null
    }

    internal data class IssuedChallenge(
        val challenge: ChargeChallenge,
        val wwwAuthenticate: String,
    )

    private data class DecodedTxn(
        val txn: Transaction,
        val signedRaw: ByteArray?,
        val unsigned: Transaction?,
        val computedTxId: String?,
    )
}

internal class MppVerifyException(message: String) : RuntimeException(message)
