@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases

import com.algorand.algosdk.sdk.BytesArray
import com.algorand.algosdk.sdk.Sdk
import com.algorand.algosdk.transaction.Transaction
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAlgo25SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetFalcon24SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetHdSeed
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccount
import com.michaeltchuang.walletsdk.core.algosdk.signAlgo25ArbitraryData
import com.michaeltchuang.walletsdk.core.algosdk.signHdKeyData
import com.michaeltchuang.walletsdk.core.utils.GoMobileDispatcher
import foundation.algorand.provider.avm.models.SignTransactionsParams
import foundation.algorand.provider.avm.models.SignTransactionsResult
import io.github.aakira.napier.Napier
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64

class ProcessSignTransactionsUseCase(
    private val getLocalAccount: GetLocalAccount,
    private val getAlgo25SecretKey: GetAlgo25SecretKey,
    private val getFalcon24SecretKey: GetFalcon24SecretKey,
    private val getSeed: GetHdSeed,
    private val getMnemonic: suspend (String) -> String?,
    private val decodeUnsignedTransaction: (String) -> Transaction?,
) {
    suspend operator fun invoke(
        params: SignTransactionsParams,
        providerId: String,
        accountAddress: String,
    ): SignTransactionsResult {
        Napier.d(tag = TAG, message = "========================================")
        Napier.d(tag = TAG, message = "📝 PROCESSING SIGN TRANSACTIONS")
        Napier.d(tag = TAG, message = "Number of transactions: ${params.txns.size}")
        Napier.d(tag = TAG, message = "Account Address: $accountAddress")
        Napier.d(tag = TAG, message = "========================================")
        require(params.validate())

        val localAccount = getLocalAccount(accountAddress)
        val signedTxns = mutableListOf<String>()

        // --- SPECIAL HANDLING FOR FALCON24 (BUNDLE) ---
        if (localAccount is LocalAccount.Falcon24) {
            Napier.d(tag = TAG, message = "Falcon24 detected: Signing only this account's transactions")

            val privateKey =
                getFalcon24SecretKey(accountAddress)
                    ?: throw IllegalArgumentException("Falcon24 private key not found for address: $accountAddress")

            // Prepare the list of all transactions - SDK handles sender filtering internally.
            // BytesArray construction, append, and signFalconBundle must all run on the
            // dedicated Go-mobile OS thread to prevent "bulkBarrierPreWrite: unaligned arguments".
            val decodedTxnBytes =
                params.txns.mapIndexed { index, txn ->
                    val bytes = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(txn.txn!!)
                    Napier.d(tag = TAG, message = "Falcon24 - Decoded transaction $index")
                    bytes
                }

            if (decodedTxnBytes.isEmpty()) {
                throw IllegalStateException("No transactions found to sign")
            }

            Napier.d(tag = TAG, message = "Falcon24 - Signing ${decodedTxnBytes.size} transaction(s)")

            // Call the Go Bundle Signer - SDK auto-adds dummies and manages group
            try {
                val resultCsv =
                    withContext(GoMobileDispatcher.dispatcher) {
                        val txnList = BytesArray()
                        decodedTxnBytes.forEach { txnList.append(it) }
                        Sdk.signFalconBundle(
                            txnList,
                            localAccount.publicKey.copyOf(),
                            privateKey.copyOf(),
                        )
                    }

                // SDK returns all transactions signed in order
                val signedResults = resultCsv.split(",")
                signedTxns.addAll(signedResults)
                Napier.d(tag = TAG, message = "Falcon24 - Successfully signed ${signedResults.size} transaction(s)")
            } catch (e: Exception) {
                Napier.e(tag = TAG, message = "Falcon24 - Signing failed", throwable = e)
                throw e
            }
        } else {
            // --- STANDARD HANDLING FOR ALGO25 / HDKEY (INDIVIDUAL) ---
            params.txns.forEachIndexed { index, txn ->
                Napier.d(tag = TAG, message = "Signing transaction ${index + 1}/${params.txns.size}")

                val transactionBytes = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(txn.txn!!)
                val unsignedTransaction = decodeUnsignedTransaction(Base64.encode(transactionBytes))

                when (localAccount) {
                    is LocalAccount.Algo25 -> {
                        val secretKey = getAlgo25SecretKey(accountAddress)!!
                        val signature = signAlgo25ArbitraryData(unsignedTransaction!!.bytesToSign(), secretKey)!!
                        signedTxns.add(Base64.UrlSafe.encode(signature))
                    }
                    is LocalAccount.HdKey -> {
                        val signature =
                            signHdKeyData(
                                data = unsignedTransaction!!.bytesToSign(),
                                seed = getSeed(localAccount.seedId)!!,
                                account = localAccount.account,
                                change = localAccount.change,
                                key = localAccount.keyIndex,
                            )!!
                        signedTxns.add(Base64.UrlSafe.encode(signature))
                    }
                    is LocalAccount.LedgerBle -> TODO("Implement Ledger Support")
                    is LocalAccount.NoAuth -> TODO("Implement NoAuth Support")
                    else -> throw IllegalStateException("Unsupported account type")
                }
            }
        }

        Napier.d(tag = TAG, message = "========================================")
        Napier.d(tag = TAG, message = "✅ TRANSACTION SIGNING COMPLETE")
        Napier.d(tag = TAG, message = "Total signed: ${signedTxns.size}")
        Napier.d(tag = TAG, message = "========================================")

        return SignTransactionsResult(providerId, signedTxns)
    }

    companion object {
        private val TAG = ProcessSignTransactionsUseCase::class.java.simpleName
    }
}
