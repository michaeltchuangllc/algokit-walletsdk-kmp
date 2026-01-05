@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
package com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases

import android.util.Log
import com.algorand.algosdk.transaction.Transaction
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAlgo25SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetFalcon24SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetHdSeed
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccount
import com.michaeltchuang.walletsdk.core.algosdk.signFalcon24Transaction
import com.michaeltchuang.walletsdk.core.algosdk.signHdKeyData
import foundation.algorand.crypto.avm.KeyPairs
import foundation.algorand.provider.avm.models.SignTransactionsParams
import foundation.algorand.provider.avm.models.SignTransactionsResult
import kotlin.io.encoding.Base64

class ProcessSignTransactionsUseCase(
    private val getLocalAccount: GetLocalAccount,
    private val getAlgo25SecretKey: GetAlgo25SecretKey,
    private val getFalcon24SecretKey: GetFalcon24SecretKey,
    private val getSeed: GetHdSeed,
    private val getMnemonic: suspend (String) -> String?,
    private val decodeUnsignedTransaction: (String) -> Transaction?
) {
    suspend operator fun invoke(
        params: SignTransactionsParams,
        providerId: String,
        accountAddress: String
    ): SignTransactionsResult {
        Log.d("ProcessSignTxUC", "========================================")
        Log.d("ProcessSignTxUC", "📝 PROCESSING SIGN TRANSACTIONS")
        Log.d("ProcessSignTxUC", "Number of transactions: ${'$'}{params.txns.size}")
        Log.d("ProcessSignTxUC", "Provider ID: ${'$'}providerId")
        Log.d("ProcessSignTxUC", "Account Address: ${'$'}accountAddress")
        Log.d("ProcessSignTxUC", "========================================")
        require(params.validate())

        val signedTxns = mutableListOf<String>()
        params.txns.forEachIndexed { index, txn ->
            Log.d("ProcessSignTxUC", "Signing transaction ${'$'}{index + 1}/${'$'}{params.txns.size}")
            val transactionBytes =
                Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(txn.txn!!)
            val unsignedTransaction = decodeUnsignedTransaction(Base64.encode(transactionBytes))
            when (val it = getLocalAccount(accountAddress)) {
                is LocalAccount.Algo25 -> {
                    val secretKey = getAlgo25SecretKey.invoke(accountAddress)
                    if (secretKey == null) {
                        throw IllegalArgumentException("Secret key not found for address: ${'$'}accountAddress")
                    }
                    val keyPair = KeyPairs.getKeyPair(getMnemonic(accountAddress)!!)
                    val signature =
                        KeyPairs.rawSignBytes(unsignedTransaction!!.bytesToSign(), keyPair.private)
                    signedTxns.add(Base64.UrlSafe.encode(signature!!))
                }
                is LocalAccount.Falcon24 -> {
                    val privateKey = getFalcon24SecretKey(accountAddress)
                    val signedGroupBytes = signFalcon24Transaction(
                        transactionBytes,
                        it.publicKey,
                        privateKey!!
                    )!!
                    signedTxns.add(Base64.UrlSafe.encode(signedGroupBytes))
                }
                is LocalAccount.HdKey -> {
                    val signature = signHdKeyData(
                        data = unsignedTransaction!!.bytesToSign(),
                        seed = getSeed(it.seedId)!!,
                        account = it.account,
                        change = it.change,
                        key = it.keyIndex,
                    )!!
                    signedTxns.add(Base64.UrlSafe.encode(signature))
                }
                is LocalAccount.LedgerBle -> TODO()
                is LocalAccount.NoAuth -> TODO()
                null -> TODO()
            }
        }
        Log.d("ProcessSignTxUC", "========================================")
        Log.d("ProcessSignTxUC", "✅ TRANSACTION SIGNING COMPLETE")
        Log.d("ProcessSignTxUC", "Successfully signed ${'$'}{signedTxns.size} transaction(s)")
        Log.d("ProcessSignTxUC", "========================================")
        return SignTransactionsResult(providerId, signedTxns)
    }
}
