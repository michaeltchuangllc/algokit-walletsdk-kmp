package com.michaeltchuang.walletsdk.core.railmpp.internal

import android.util.Base64
import android.util.Log
import com.algorand.algosdk.v2.client.common.AlgodClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.michaeltchuang.walletsdk.core.railmpp.DEFAULT_ALGOD_URLS
import com.michaeltchuang.walletsdk.core.railmpp.MppClientConfig
import com.michaeltchuang.walletsdk.core.railmpp.MppNetworks
import com.michaeltchuang.walletsdk.core.railmpp.MppProgressEvent
import com.michaeltchuang.walletsdk.core.railmpp.spec.ChargeChallenge
import com.michaeltchuang.walletsdk.core.railmpp.spec.ChargeCredential
import com.michaeltchuang.walletsdk.core.railmpp.spec.ChargeCredentialCodec
import com.michaeltchuang.walletsdk.core.railmpp.spec.ChargePayload
import com.michaeltchuang.walletsdk.core.railmpp.spec.ChargeRequestCodec
import java.net.URI

/**
 * Consumer-side flow for the algorand charge intent.
 *
 *   challenge → parse → build txn group → sign → serialize credential
 */
internal class MppConsumer(private val config: MppClientConfig) {
    companion object {
        private const val TAG = "MppConsumer"
    }

    /** Build, sign, and serialize the credential for the given challenge. */
    suspend fun createCredential(challenge: ChargeChallenge): String = withContext(Dispatchers.IO) {
        try {
            Log.e(TAG, "[CREATE_CREDENTIAL_START] method=${challenge.method} id=${challenge.id} network=${config.network}")
            if (challenge.method.equals("solana", ignoreCase = true)) {
                createSolanaCredential(challenge)
            } else {
                createAlgorandCredential(challenge)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "[CREATE_CREDENTIAL_FAILED] method=${challenge.method} id=${challenge.id} error=${t.message}", t)
            throw t
        }
    }

    private suspend fun createAlgorandCredential(challenge: ChargeChallenge): String {
        val req = ChargeRequestCodec.parseAlgorandRequest(challenge.request)
        val network = req.network ?: config.network
        Log.e(TAG, "[CREATE_CREDENTIAL_ALGO] recipient=${req.recipient} amount=${req.amount} currency=${req.currency} asaId=${req.asaId ?: "ALGO"} feePayer=${req.feePayer} network=$network")

        config.onProgress?.invoke(
            MppProgressEvent.Challenge(
                amount = req.amount,
                currency = req.currency,
                recipient = req.recipient,
                asaId = req.asaId,
                feePayerKey = req.feePayerKey,
            )
        )

        // The spec allows the client to use the server's suggestedParams hint,
        // but fetching fresh from algod is always safe (validity window is >1000
        // rounds) and avoids client/server round drift.
        val params = TxnBuilder.fetchSuggestedParams(algodClient(network))

        val lease = req.lease.let { Base64.decode(it, Base64.NO_WRAP) }
        val noteBytes = req.externalId?.toByteArray(Charsets.UTF_8)
        val useFeePayer = req.feePayer && req.feePayerKey != null

        // Build payment txn
        val paymentTxn = TxnBuilder.buildPaymentTxn(
            sender = config.signer.address,
            receiver = req.recipient,
            amount = req.amount.toLong(),
            asaId = req.asaId,
            params = params,
            lease = lease,
            note = noteBytes,
            useFeePayer = useFeePayer,
        )

        // Build optional fee payer txn (unsigned — server signs it)
        val txns = if (useFeePayer) {
            val pooledFee = (params.minFee ?: 1000L) * 2L
            val feePayerNote = "mpp-fee-payer-${System.currentTimeMillis()}".toByteArray()
            val feePayerTxn = TxnBuilder.buildFeePayerTxn(
                feePayerAddress = req.feePayerKey,
                params = params,
                pooledFee = pooledFee,
                note = feePayerNote,
            )
            TxnBuilder.assignGroup(feePayerTxn, paymentTxn)
        } else {
            TxnBuilder.assignGroup(paymentTxn)
        }

        config.onProgress?.invoke(MppProgressEvent.Signing)

        val paymentIndex = if (useFeePayer) 1 else 0
        val paymentGroupB64 = mutableListOf<String>()
        for ((i, t) in txns.withIndex()) {
            if (useFeePayer && i == 0) {
                paymentGroupB64.add(TxnBuilder.encodeTxnBase64(t))
            } else {
                val signedBytes = config.signer.signTransaction(t)
                paymentGroupB64.add(TxnBuilder.encodeSignedTxnBase64(signedBytes))
            }
        }

        config.onProgress?.invoke(MppProgressEvent.Signed(paymentGroupB64.toList()))

        val credential = ChargeCredential(
            challenge = challenge,
            payload = ChargePayload(
                type = "transaction",
                paymentGroup = paymentGroupB64.toList(),
                paymentIndex = paymentIndex,
            ),
            source = config.signer.address,
        )
        val authHeader = ChargeCredentialCodec.toAuthHeader(credential)
        Log.e(TAG, "[CREATE_CREDENTIAL_ALGO_OK] challengeId=${challenge.id} paymentGroupSize=${paymentGroupB64.size} paymentIndex=$paymentIndex source=${config.signer.address} authHeaderBytes=${authHeader.length}")
        return authHeader
    }

    private suspend fun createSolanaCredential(challenge: ChargeChallenge): String {
        val req = ChargeRequestCodec.parseSolanaRequest(challenge.request)
        val network = req.network ?: config.network
        Log.e(TAG, "[CREATE_CREDENTIAL_SOLANA] recipient=${req.recipient} amount=${req.amount} mint=${req.mint ?: "SOL"} network=$network")

        config.onProgress?.invoke(
            MppProgressEvent.Challenge(
                amount = req.amount,
                currency = req.currency,
                recipient = req.recipient,
                asaId = req.mint,
                feePayerKey = null,
            )
        )

        config.onProgress?.invoke(MppProgressEvent.Signing)

        val signedTx = config.signer.createSolanaSignedTransaction(
            recipientAddress = req.recipient,
            amount = req.amount,
            network = network,
            mint = req.mint,
        )

        val signedTxB64 = Base64.encodeToString(signedTx, Base64.NO_WRAP)
        config.onProgress?.invoke(MppProgressEvent.Signed(listOf(signedTxB64)))

        val credential = ChargeCredential(
            challenge = challenge,
            payload = ChargePayload(
                type = "transaction",
                signedTransaction = signedTxB64,
            ),
            source = config.signer.address,
        )
        val authHeader = ChargeCredentialCodec.toAuthHeader(credential)
        Log.e(TAG, "[CREATE_CREDENTIAL_SOLANA_OK] challengeId=${challenge.id} source=${config.signer.address} authHeaderBytes=${authHeader.length}")
        return authHeader
    }

    private fun algodClient(network: String): AlgodClient {
        val url = config.algodUrl
            ?: DEFAULT_ALGOD_URLS[network]
            ?: DEFAULT_ALGOD_URLS[MppNetworks.ALGORAND_TESTNET]!!
        val parsed = URI(url)
        val port = if (parsed.port > 0) parsed.port else if (parsed.scheme == "https") 443 else 80
        val host = "${parsed.scheme}://${parsed.host}"
        return AlgodClient(host, port, "")
    }

}
