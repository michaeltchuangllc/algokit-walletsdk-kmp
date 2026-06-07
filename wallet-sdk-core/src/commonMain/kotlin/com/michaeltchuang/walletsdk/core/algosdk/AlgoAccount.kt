package com.michaeltchuang.walletsdk.core.algosdk

import com.michaeltchuang.walletsdk.core.algosdk.bip39.sdk.Bip39Wallet
import com.michaeltchuang.walletsdk.core.algosdk.domain.model.Algo25Account
import com.michaeltchuang.walletsdk.core.foundation.utils.SuggestedParams
import com.michaeltchuang.walletsdk.core.transaction.model.OfflineKeyRegTransactionPayload
import com.michaeltchuang.walletsdk.core.transaction.model.OnlineKeyRegTransactionPayload

expect fun recoverAlgo25Account(mnemonic: String): Algo25Account?

expect fun createAlgo25Account(): Algo25Account?

expect fun getMnemonicFromAlgo25SecretKey(secretKey: ByteArray): String?

expect fun getBip39Wallet(entropy: ByteArray): Bip39Wallet

expect fun createBip39Wallet(): Bip39Wallet

expect fun getSeedFromEntropy(entropy: ByteArray): ByteArray?

expect fun signHdKeyTransaction(
    transactionByteArray: ByteArray,
    seed: ByteArray,
    account: Int,
    change: Int,
    key: Int,
): ByteArray?

expect fun signFalcon24Transaction(
    transactionByteArray: ByteArray,
    publicKey: ByteArray,
    privateKey: ByteArray,
): ByteArray?

/**
 * Signs a group of Falcon24 transactions as a bundle.
 * Transactions should NOT have group IDs pre-assigned — the underlying SDK will assign group IDs
 * and prepend the necessary dummy transactions to satisfy the AVM LogicSig verification budget.
 * Returns the full list of signed transaction msgpack bytes (including any added dummies).
 */
expect fun signFalcon24GroupBundle(
    txnsByteArrays: List<ByteArray>,
    publicKey: ByteArray,
    privateKey: ByteArray,
): List<ByteArray>

expect fun signHdKeyArbitraryData(
    data: ByteArray,
    seed: ByteArray,
    account: Int,
    change: Int,
    key: Int,
): ByteArray?

expect fun signHdKeyData(
    data: ByteArray,
    seed: ByteArray,
    account: Int,
    change: Int,
    key: Int,
): ByteArray?

expect fun signFalcon24ArbitraryData(
    data: ByteArray,
    publicKey: ByteArray,
    privateKey: ByteArray,
): ByteArray?

expect fun signAlgo25ArbitraryData(
    data: ByteArray,
    secretKey: ByteArray,
): ByteArray?

expect fun signAlgo25Transaction(
    secretKey: ByteArray,
    transactionByteArray: ByteArray,
): ByteArray

expect fun getReceiverMinBalanceFee(
    receiverAlgoAmount: String,
    receiverMinBalanceAmount: String,
): Long

expect fun createTransaction(payload: OfflineKeyRegTransactionPayload): ByteArray

expect fun createTransaction(payload: OnlineKeyRegTransactionPayload): ByteArray

expect fun makeAssetTransferTxn(
    senderAddress: String,
    receiverAddress: String,
    amount: String,
    assetId: Long,
    noteInByteArray: ByteArray?,
    suggestedParams: SuggestedParams,
): ByteArray

expect fun makePaymentTxn(
    senderAddress: String,
    receiverAddress: String,
    amount: String,
    isMax: Boolean,
    noteInByteArray: ByteArray?,
    suggestedParams: SuggestedParams,
): ByteArray

expect fun makeAssetAcceptanceTxn(
    publicKey: String,
    assetId: Long,
    suggestedParams: SuggestedParams,
): ByteArray

expect fun isValidAlgorandAddress(accountAddress: String): Boolean
