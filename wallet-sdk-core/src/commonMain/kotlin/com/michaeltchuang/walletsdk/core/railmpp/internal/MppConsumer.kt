package com.michaeltchuang.walletsdk.core.railmpp.internal

import com.michaeltchuang.walletsdk.core.railmpp.MppClientConfig
import com.michaeltchuang.walletsdk.core.railmpp.MppProgressEvent
import com.michaeltchuang.walletsdk.core.railmpp.spec.ChargeChallenge
import com.michaeltchuang.walletsdk.core.railmpp.spec.ChargeCredential
import com.michaeltchuang.walletsdk.core.railmpp.spec.ChargeCredentialCodec
import com.michaeltchuang.walletsdk.core.railmpp.spec.ChargePayload
import com.michaeltchuang.walletsdk.core.railmpp.spec.ChargeRequestCodec
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.michaeltchuang.walletsdk.core.railmpp.spec.Base64Std

/**
 * Consumer-side flow for the algorand charge intent:
 *   challenge → parse → build txn group → sign → serialize credential
 */
internal class MppConsumer(
    private val config: MppClientConfig,
) {
    private companion object {
        const val TAG = "MppConsumer"
    }

    /** Build, sign, and serialize the credential for the given challenge. */
    suspend fun createCredential(challenge: ChargeChallenge): String =
        withContext(Dispatchers.Default) {
            try {
                if (challenge.method.equals("solana", ignoreCase = true)) {
                    createSolanaCredential(challenge)
                } else {
                    createAlgorandCredential(challenge)
                }
            } catch (t: Throwable) {
                Napier.e("[CREATE_CREDENTIAL_FAILED] method=${challenge.method} id=${challenge.id} error=${t.message}", t, tag = TAG)
                throw t
            }
        }

    private suspend fun createAlgorandCredential(challenge: ChargeChallenge): String {
        val req = ChargeRequestCodec.parseAlgorandRequest(challenge.request)
        val network = req.network ?: config.network
        Napier.d(
            "[CREATE_CREDENTIAL_ALGO] recipient=${req.recipient} amount=${req.amount} currency=${req.currency} " +
                "asaId=${req.asaId ?: "ALGO"} feePayer=${req.feePayer} network=$network",
            tag = TAG,
        )

        config.onProgress?.invoke(
            MppProgressEvent.Challenge(
                amount = req.amount,
                currency = req.currency,
                recipient = req.recipient,
                asaId = req.asaId,
                feePayerKey = req.feePayerKey,
            ),
        )

        // Fetching fresh params from algod is always safe (validity window > 1000 rounds)
        // and avoids client/server round drift.
        val params = TxnBuilder.fetchSuggestedParams(resolveAlgodUrl(config.algodUrl, network))

        val lease = Base64Std.decode(req.lease)
        val noteBytes = req.externalId?.encodeToByteArray()
        val useFeePayer = req.feePayer && req.feePayerKey != null

        val paymentTxn =
            TxnBuilder.buildPaymentTxn(
                sender = config.signer.address,
                receiver = req.recipient,
                amount = req.amount.toLong(),
                asaId = req.asaId,
                params = params,
                lease = lease,
                note = noteBytes,
                useFeePayer = useFeePayer,
            )

        // Build optional unsigned fee payer txn (server signs it), then group.
        val txns =
            if (useFeePayer) {
                val pooledFee = params.minFee * 2L
                val feePayerNote = "mpp-fee-payer-${mppNowMs()}".encodeToByteArray()
                val feePayerTxn =
                    TxnBuilder.buildFeePayerTxn(
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
                val signedBytes = config.signer.signTransactionBytes(t)
                paymentGroupB64.add(TxnBuilder.encodeSignedTxnBase64(signedBytes))
            }
        }

        config.onProgress?.invoke(MppProgressEvent.Signed(paymentGroupB64.toList()))

        val credential =
            ChargeCredential(
                challenge = challenge,
                payload =
                    ChargePayload(
                        type = "transaction",
                        paymentGroup = paymentGroupB64.toList(),
                        paymentIndex = paymentIndex,
                    ),
                source = config.signer.address,
            )
        val authHeader = ChargeCredentialCodec.toAuthHeader(credential)
        Napier.d(
            "[CREATE_CREDENTIAL_ALGO_OK] challengeId=${challenge.id} paymentGroupSize=${paymentGroupB64.size} " +
                "paymentIndex=$paymentIndex source=${config.signer.address} authHeaderBytes=${authHeader.length}",
            tag = TAG,
        )
        return authHeader
    }

    private suspend fun createSolanaCredential(challenge: ChargeChallenge): String {
        val req = ChargeRequestCodec.parseSolanaRequest(challenge.request)
        val network = req.network ?: config.network

        config.onProgress?.invoke(
            MppProgressEvent.Challenge(
                amount = req.amount,
                currency = req.currency,
                recipient = req.recipient,
                asaId = req.mint,
                feePayerKey = null,
            ),
        )

        config.onProgress?.invoke(MppProgressEvent.Signing)

        val signedTx =
            config.signer.createSolanaSignedTransaction(
                recipientAddress = req.recipient,
                amount = req.amount,
                network = network,
                mint = req.mint,
            )

        val signedTxB64 = Base64Std.encode(signedTx)
        config.onProgress?.invoke(MppProgressEvent.Signed(listOf(signedTxB64)))

        val credential =
            ChargeCredential(
                challenge = challenge,
                payload =
                    ChargePayload(
                        type = "transaction",
                        signedTransaction = signedTxB64,
                    ),
                source = config.signer.address,
            )
        val authHeader = ChargeCredentialCodec.toAuthHeader(credential)
        Napier.d(
            "[CREATE_CREDENTIAL_SOLANA_OK] challengeId=${challenge.id} source=${config.signer.address} authHeaderBytes=${authHeader.length}",
            tag = TAG,
        )
        return authHeader
    }
}
