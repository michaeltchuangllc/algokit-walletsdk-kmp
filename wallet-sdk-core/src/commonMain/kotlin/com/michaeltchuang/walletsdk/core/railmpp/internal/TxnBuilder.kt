package com.michaeltchuang.walletsdk.core.railmpp.internal

import com.michaeltchuang.walletsdk.core.railmpp.spec.Base64Std

/**
 * Platform-agnostic txn group construction matching the algorand charge spec.
 * Delegates the actual Algorand encoding to the [mppBuildPaymentTxn] /
 * [mppBuildFeePayerTxn] / [mppAssignGroup] expect layer. All txns are handled
 * as msgpack bytes so consumer/provider logic is fully common.
 */
internal object TxnBuilder {
    /** Build the unsigned payment txn (ALGO `pay` or ASA `axfer`) as msgpack bytes. */
    fun buildPaymentTxn(
        sender: String,
        receiver: String,
        amount: Long,
        asaId: String?,
        params: MppBuildParams,
        lease: ByteArray?,
        note: ByteArray?,
        useFeePayer: Boolean,
    ): ByteArray =
        mppBuildPaymentTxn(
            sender = sender,
            receiver = receiver,
            amount = amount,
            asaId = asaId,
            params = params,
            lease = lease,
            note = note,
            useFeePayer = useFeePayer,
        )

    /** Build the unsigned fee payer txn — 0-ALGO self-payment with pooled fee — as msgpack bytes. */
    fun buildFeePayerTxn(
        feePayerAddress: String,
        params: MppBuildParams,
        pooledFee: Long,
        note: ByteArray?,
    ): ByteArray =
        mppBuildFeePayerTxn(
            feePayerAddress = feePayerAddress,
            params = params,
            pooledFee = pooledFee,
            note = note,
        )

    /** Assign a shared group id; returns the grouped unsigned txns (msgpack bytes) in order. */
    fun assignGroup(vararg unsignedTxns: ByteArray): List<ByteArray> = mppAssignGroup(unsignedTxns.toList())

    /** Encode a single (signed or unsigned) txn as base64-msgpack. */
    fun encodeTxnBase64(txnBytes: ByteArray): String = Base64Std.encode(txnBytes)

    fun encodeSignedTxnBase64(signedBytes: ByteArray): String = Base64Std.encode(signedBytes)

    /** Fetch the network's suggested params from algod. */
    suspend fun fetchSuggestedParams(algodUrl: String): MppBuildParams = mppFetchSuggestedParams(algodUrl)
}
