package com.michaeltchuang.seedvault.service.data.vault

import com.michaeltchuang.seedvault.service.domain.model.AlgorandPaymentParams
import com.michaeltchuang.walletsdk.core.account.domain.model.local.TransactionFee
import com.michaeltchuang.walletsdk.core.algosdk.SuggestedParams
import com.michaeltchuang.walletsdk.core.algosdk.isValidAlgorandAddress
import com.michaeltchuang.walletsdk.core.algosdk.makePaymentTxn

/**
 * Builds the unsigned payment a vault signs. The vault chooses the sender and the fee, so a caller
 * cannot get anything else signed. Returns null for a negative amount or an invalid [receiver].
 */
internal fun buildFalconPayment(
    sender: String,
    receiver: String,
    amountMicroAlgos: Long,
    params: AlgorandPaymentParams,
): ByteArray? {
    if (amountMicroAlgos < 0 || !isValidAlgorandAddress(receiver)) return null
    // A Falcon-1024 account needs more than the minimum fee
    val fee = TransactionFee.FALCON25_FEE_MICRO_ALGOS
    return makePaymentTxn(
        senderAddress = sender,
        receiverAddress = receiver,
        amount = amountMicroAlgos.toString(),
        isMax = false,
        noteInByteArray = null,
        suggestedParams =
            SuggestedParams(
                fee = fee,
                genesisID = params.genesisId,
                firstRoundValid = params.firstValid,
                lastRoundValid = params.lastValid,
                genesisHash = params.genesisHash,
                flatFee = true,
            ),
        staticFee = fee,
    )
}
