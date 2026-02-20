package com.michaeltchuang.walletsdk.core.transaction.signmanager

import androidx.lifecycle.Lifecycle
import com.ionspin.kotlin.bignum.integer.BigInteger
import com.ionspin.kotlin.bignum.integer.toBigInteger
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAlgo25SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetFalcon24SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetHdSeed
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccount
import com.michaeltchuang.walletsdk.core.algosdk.signFalcon24Transaction
import com.michaeltchuang.walletsdk.core.algosdk.signHdKeyTransaction
import com.michaeltchuang.walletsdk.core.deeplink.utils.AssetConstants.ALGO_ID
import com.michaeltchuang.walletsdk.core.foundation.utils.ListQueuingHelper
import com.michaeltchuang.walletsdk.core.foundation.utils.clearFromMemory
import com.michaeltchuang.walletsdk.core.foundation.utils.makeAddAssetTx
import com.michaeltchuang.walletsdk.core.foundation.utils.makeRekeyTx
import com.michaeltchuang.walletsdk.core.foundation.utils.makeRemoveAssetTx
import com.michaeltchuang.walletsdk.core.foundation.utils.makeSendAndRemoveAssetTx
import com.michaeltchuang.walletsdk.core.foundation.utils.makeTx
import com.michaeltchuang.walletsdk.core.foundation.utils.signTx
import com.michaeltchuang.walletsdk.core.network.model.TransactionParams
import com.michaeltchuang.walletsdk.core.network.model.TransactionSigner
import com.michaeltchuang.walletsdk.core.transaction.domain.usecase.GetTransactionParams
import com.michaeltchuang.walletsdk.core.transaction.model.SignedTransactionDetail
import com.michaeltchuang.walletsdk.core.transaction.model.TransactionManagerResult
import com.michaeltchuang.walletsdk.core.transaction.model.TransactionManagerResult.Error.GlobalWarningError.Api
import com.michaeltchuang.walletsdk.core.transaction.model.TransactionManagerResult.Error.GlobalWarningError.Defined
import com.michaeltchuang.walletsdk.core.transaction.model.TransactionManagerResult.Error.GlobalWarningError.MinBalanceError
import com.michaeltchuang.walletsdk.core.transaction.model.TransactionSignData
import com.michaeltchuang.walletsdk.core.utils.TransactionSignSigningHelper
import com.michaeltchuang.walletsdk.core.utils.flatten
import com.michaeltchuang.walletsdk.core.utils.getTxFee
import com.michaeltchuang.walletsdk.core.utils.isLesserThan
import com.michaeltchuang.walletsdk.core.utils.mapToNotNullableListOrNull
import com.michaeltchuang.walletsdk.core.utils.minBalancePerAssetAsBigInteger
import com.michaeltchuang.walletsdk.utils.LifecycleScopedCoroutineOwner
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@Suppress("LongParameterList")
open class TransactionSignManager(
    private val getTransactionParams: GetTransactionParams,
    private val signHelper: TransactionSignSigningHelper,
    // private val getAccountAlgoBalance: GetAccountAlgoBalance,
    // private val getAccountMinBalance: GetAccountMinBalance,
    private val getFalcon24SecretKey: GetFalcon24SecretKey,
    private val getAlgo25SecretKey: GetAlgo25SecretKey,
    private val getHdSeed: GetHdSeed,
    private val getLocalAccount: GetLocalAccount,
) : LifecycleScopedCoroutineOwner() {
    val transactionManagerResultStateFlow = MutableStateFlow<TransactionManagerResult?>(null)

    private var transactionParams: TransactionParams? = null
    var transactionDataList: List<TransactionSignData>? = null

    private val signHelperListener =
        object : ListQueuingHelper.Listener<TransactionSignData, ByteArray> {
            override fun onAllItemsDequeued(signedTransactions: List<ByteArray?>) {
                if (signedTransactions.isEmpty() || signedTransactions.any { it == null }) {
                    setSignFailed(
                        Defined(),
                    )
                    return
                }
                if (signedTransactions.size == 1) {
                    transactionDataList?.let {
                        postTxnSignResult(
                            signedTransactions.firstOrNull(),
                            it.firstOrNull(),
                        )
                    }
                } else {
                    val safeSignedTransactions =
                        signedTransactions.mapToNotNullableListOrNull { it }
                    if (safeSignedTransactions == null) {
                        postResult(Defined())
                        return
                    }
                    transactionDataList?.let { postGroupTxnSignResult(safeSignedTransactions, it) }
                }
            }

            override fun onNextItemToBeDequeued(
                transaction: TransactionSignData,
                currentItemIndex: Int,
                totalItemCount: Int,
            ) {
                // TODO: add [currentItemIndex] and [totalItemCount] after merging this core swap screens
                currentScope.launch {
                    transaction.signTxn()
                }
            }
        }

    private suspend fun checkAndCacheSignedTransaction(transactionByteArray: ByteArray?) {
        if (transactionByteArray == null) {
            setSignFailed(TransactionManagerResult.Error.GlobalWarningError.Defined())
            return
        }
        signHelper.currentItem?.run {
            if (isArc59Transaction) {
                return@run
            }
            calculatedFee = transactionParams?.getTxFee(transactionByteArray)
            if (this is TransactionSignData.Send && projectedFee != calculatedFee) {
                currentScope.launch { resignCurrentTransaction() }
                return
            }

            if (isMinimumLimitViolated()) {
                return
            }
        }
        signHelper.cacheDequeuedItem(transactionByteArray)
    }

    private fun setSignFailed(transactionManagerResult: TransactionManagerResult) {
        postResult(transactionManagerResult)
        signHelper.clearCachedData()
    }

    private suspend fun resignCurrentTransaction() {
        signHelper.currentItem?.createTransaction()
        signHelper.requeueCurrentItem()
    }

    fun setup(lifecycle: Lifecycle) {
        assignToLifecycle(lifecycle)
        //  setupLedgerOperationManager(lifecycle)
        signHelper.initListener(signHelperListener)
    }

    /* private fun setupLedgerOperationManager(lifecycle: Lifecycle) {
         ledgerBleOperationManager.setup(lifecycle)
         lifecycle.coroutineScope.launch {
             ledgerBleOperationManager.ledgerBleResultFlow.collect {
                 operationManagerCollectorAction.invoke(it)
             }
         }
     }*/

    fun initSigningTransactions(
        isGroupTransaction: Boolean,
        vararg transactionData: TransactionSignData,
    ) {
        currentScope.launch {
            postResult(TransactionManagerResult.Loading)
            transactionData
                .toList()
                .ifEmpty {
                    setSignFailed(Defined())
                    return@launch
                }.let { transactionList ->
                    processTransactionDataList(transactionList, isGroupTransaction)?.let {
                        this@TransactionSignManager.transactionDataList = it
                        signHelper.initItemsToBeEnqueued(it)
                    }
                }
        }
    }

    /* suspend fun createArc59SendTransactionList(transactionData: TransactionSignData): List<Arc59TransactionData>? {
         return transactionData.createArc59SendTransactions()
     }

       suspend fun getReceiverMinBalanceFee(transactionData: TransactionSignData): Long? {
           val transactionParams = getTransactionParams(transactionData) ?: return null
           this@TransactionSignManager.transactionParams = transactionParams

           val sendTransaction = transactionData as? TransactionSignData.Send ?: return null
           val receiverAlgoAmount = sendTransaction.targetUser.algoBalance ?: return null
           val receiverMinBalanceAmount = sendTransaction.targetUser.minBalance ?: return null

           return com.michaeltchuang.walletsdk.core.algosdk.getReceiverMinBalanceFee(
               receiverAlgoAmount = receiverAlgoAmount.toString(),
               receiverMinBalanceAmount = receiverMinBalanceAmount.toString()
           )
       }*/

    private suspend fun TransactionSignData.signTxn() {
        when (signer) {
            is TransactionSigner.Algo25 -> {
                val secretKey =
                    getAlgo25SecretKey(signer.address) ?: run {
                        setSignFailed(Defined())
                        return
                    }
                checkAndCacheSignedTransaction(transactionByteArray?.signTx(secretKey))
            }

            is TransactionSigner.HdKey -> {
                val transactionBytes = transactionByteArray ?: return handleSignError()
                val hdKey =
                    getLocalAccount(signer.address) as? LocalAccount.HdKey
                        ?: return handleSignError()
                val seed = getHdSeed(seedId = hdKey.seedId) ?: return handleSignError()

                val transactionSignedByteArray =
                    signHdKeyTransaction(
                        transactionBytes,
                        seed,
                        hdKey.account,
                        hdKey.change,
                        hdKey.keyIndex,
                    ) ?: return handleSignError()

                checkAndCacheSignedTransaction(transactionSignedByteArray)
            }

            is TransactionSigner.LedgerBle -> { // sendTransactionWithLedger(signer as TransactionSigner.LedgerBle)
            }

            is TransactionSigner.SignerNotFound -> {
                postResult(Defined())
            }

            is TransactionSigner.Falcon24 -> {
                signFalconTransaction(transactionByteArray, signer.address)
            }
        }
    }

    private fun handleSignError() {
        setSignFailed(Defined())
    }

    /* private suspend fun TransactionSignData.createArc59SendTransactions(): List<Arc59TransactionData>? {
         val transactionParams = getTransactionParams(this) ?: return null
         this@TransactionSignManager.transactionParams = transactionParams
         val arc59TransactionData = mutableListOf<Arc59TransactionData>()
         (this as? TransactionSignData.Send)?.let {
             projectedFee = calculatedFee ?: transactionParams.getTxFee()
             val transactions = transactionParams.makeArc59Txn(
                 senderAddress = senderAccountAddress,
                 receiverAddress = targetUser.publicKey,
                 transactionAmount = amount,
                 senderAlgoAmount = senderAlgoAmount,
                 senderMinBalanceAmount = minimumBalance.toBigInteger(),
                 receiverAlgoAmount = targetUser.algoBalance ?: BigInteger.ZERO,
                 receiverMinBalanceAmount = targetUser.minBalance ?: BigInteger.ZERO,
                 assetId = assetId,
                 note = if (xnote.isNullOrBlank()) note else xnote
             )

             for (i in 0 until transactions.length()) {
                 val txn = transactions.getTxn(i)
                 val signer = transactions.getSigner(i)
                 arc59TransactionData.add(Arc59TransactionData(txn, signer))
             }
         }
         return arc59TransactionData
     }*/

    private suspend fun signFalconTransaction(
        transactionByteArray: ByteArray?,
        accountAddress: String,
    ) {
        val transactionBytes = transactionByteArray ?: return handleSignError()
        val falcon24Account = getLocalAccount(accountAddress) as? LocalAccount.Falcon24 ?: return
        val falcon24SecretKey = getFalcon24SecretKey(accountAddress) ?: return handleSignError()

        val transactionSignedByteArray =
            signFalcon24Transaction(
                transactionBytes,
                falcon24Account.publicKey.copyOf(),
                falcon24SecretKey.copyOf(),
            ) ?: return handleSignError()

        falcon24SecretKey.clearFromMemory()
        onTransactionSigned(transactionSignedByteArray)
    }

    protected open fun onTransactionSigned(signedTransaction: ByteArray?) {
        signHelper.cacheDequeuedItem(signedTransaction)
    }

    suspend fun TransactionSignData.createTransaction(): ByteArray? {
        val transactionParams = getTransactionParams(this) ?: return null
        this@TransactionSignManager.transactionParams = transactionParams

        val createdTransactionByteArray =
            when (this) {
                is TransactionSignData.Send -> {
                    projectedFee = calculatedFee ?: transactionParams.getTxFee()
                    // calculate isMax before calculating real amount because while isMax true fee will be deducted.
                    isMax = isTransactionMax(amount, senderAccountAddress, assetId)
                    // TODO: 10.08.2022 Get all those calculations from a single AmountTransactionValidationUseCase
                    amount = calculateAmount(
                        projectedAmount = amount,
                        isMax = isMax,
                        isSenderRekeyedToAnotherAccount = isSenderRekeyed(),
                        senderMinimumBalance = minimumBalance,
                        assetId = assetId,
                        fee = projectedFee,
                    ) ?: return null

                    if (isSenderRekeyed()) {
                        // if account is rekeyed to another account, min balance should be deducted from the amount.
                        // after it'll be deducted, isMax will be false to not write closeToAddress.
                        isMax = false
                    }

                    if (isCloseToSameAccount()) {
                        return null
                    }

                    transactionParams.makeTx(
                        senderAddress = senderAccountAddress,
                        receiverAddress = targetUser.publicKey,
                        amount = amount,
                        assetId = assetId,
                        isMax = isMax,
                        note = if (xnote.isNullOrBlank()) note else xnote,
                    )
                }

                is TransactionSignData.AddAsset -> {
                    transactionParams.makeAddAssetTx(senderAccountAddress, assetId)
                }

                is TransactionSignData.RemoveAsset -> {
                    if (shouldCreateAssetRemoveTransaction(senderAccountAddress, assetId)) {
                        transactionParams.makeRemoveAssetTx(
                            senderAddress = senderAccountAddress,
                            creatorPublicKey = creatorAddress,
                            assetId = assetId,
                        )
                    } else {
                        null
                    }
                }

                is TransactionSignData.SendAndRemoveAsset -> {
                    transactionParams.makeSendAndRemoveAssetTx(
                        senderAddress = senderAccountAddress,
                        receiverAddress = targetUser.publicKey,
                        assetId = assetId,
                        amount = amount,
                    )
                }

                is TransactionSignData.Rekey -> {
                    transactionParams.makeRekeyTx(senderAccountAddress, rekeyAdminAddress)
                }
            }

        transactionByteArray = createdTransactionByteArray

        return createdTransactionByteArray
    }

    private suspend fun getTransactionParams(transactionData: TransactionSignData): TransactionParams? {
        when (val result = getTransactionParams()) {
            is com.michaeltchuang.walletsdk.core.foundation.utils.Result.Success -> {
                transactionParams = result.data
            }

            is com.michaeltchuang.walletsdk.core.foundation.utils.Result.Error -> {
                transactionParams = null
                when (transactionData) {
                    is TransactionSignData.AddAsset -> {
                        postResult(Defined())
                    }

                    is TransactionSignData.Rekey,
                    is TransactionSignData.Send,
                    is TransactionSignData.SendAndRemoveAsset,
                    is TransactionSignData.RemoveAsset,
                    -> {
                        postResult(Api())
                    }
                }
            }
        }
        return transactionParams
    }

    /*  private fun sendCurrentTransaction(bluetoothDevice: BluetoothDevice) {
          signHelper.currentItem?.run {
              ledgerBleOperationManager.startLedgerOperation(TransactionSignOperation(bluetoothDevice, this))
          }
      }*/

    private fun calculateAmount(
        projectedAmount: BigInteger,
        isMax: Boolean,
        isSenderRekeyedToAnotherAccount: Boolean,
        senderMinimumBalance: Long,
        assetId: Long,
        fee: Long,
    ): BigInteger? {
        val calculatedAmount =
            if (isMax && assetId == ALGO_ID) {
                if (isSenderRekeyedToAnotherAccount) {
                    projectedAmount - fee.toBigInteger() - senderMinimumBalance.toBigInteger()
                } else {
                    projectedAmount - fee.toBigInteger()
                }
            } else {
                projectedAmount
            }

        if (calculatedAmount isLesserThan BigInteger.ZERO) {
            if (isSenderRekeyedToAnotherAccount) {
                postResult(Defined("errorMinBalance"))
            } else {
                postResult(Defined())
            }
            return null
        }

        return calculatedAmount
    }

    private suspend fun isTransactionMax(
        amount: BigInteger,
        publicKey: String,
        assetId: Long,
    ): Boolean =
        if (assetId != ALGO_ID) {
            false
        } else {
            getAccountAlgoBalance(publicKey) == amount
        }

    private suspend fun shouldCreateAssetRemoveTransaction(
        publicKey: String,
        assetId: Long,
    ): Boolean {
        /*  val assetHoldingAmount = getAccountAssetHoldingAmount(publicKey, assetId)
          return assetHoldingAmount != null && assetHoldingAmount == BigInteger.ZERO*/
        return false
    }

    private fun TransactionSignData.isCloseToSameAccount(): Boolean {
        if (this is TransactionSignData.Send && isMax && senderAccountAddress == targetUser.publicKey) {
            postResult(
                Defined("You cannot send your max balance to yourself. Please select a different amount or recipient and try again."),
            )
            return true
        }
        return false
    }

    private suspend fun TransactionSignData.isMinimumLimitViolated(): Boolean {
        if (this is TransactionSignData.Send && isMax) {
            return false
        }

        // every asset addition increases min balance by $MIN_BALANCE_PER_ASSET
        var minBalance = getAccountMinBalance(senderAccountAddress)
        when (this) {
            is TransactionSignData.AddAsset ->
                minBalance += minBalancePerAssetAsBigInteger

            is TransactionSignData.RemoveAsset -> {
                minBalance -= minBalancePerAssetAsBigInteger
            }

            else -> {
                println("Unhandled else case in isMinimumLimitViolated")
            }
        }

        val balance =
            getAccountAlgoBalance(senderAccountAddress) ?: run {
                setSignFailed(Defined("minimum_balance_required"))
                return true
            }

        val fee =
            calculatedFee?.toBigInteger() ?: run {
                setSignFailed(Defined("minimum_balance_required"))
                return true
            }

        // fee only drops from the algos.
        val balanceAfterTransaction =
            if (this is TransactionSignData.Send && assetId != ALGO_ID) {
                balance - fee
            } else {
                balance - fee - amount
            }

        if (balanceAfterTransaction < minBalance) {
            if (this is TransactionSignData.AddAsset) {
                postResult(MinBalanceError())
            } else {
                postResult(Defined())
            }
            return true
        }

        return false
    }

    private fun postResult(transactionManagerResult: TransactionManagerResult) {
        transactionManagerResultStateFlow.value = transactionManagerResult
    }

    /*
        private fun sendTransactionWithLedger(ledgerDetail: TransactionSigner.LedgerBle) {
            val bluetoothAddress = ledgerDetail.bluetoothAddress
            val currentConnectedDevice = ledgerBleOperationManager.connectedBluetoothDevice
            if (currentConnectedDevice != null && currentConnectedDevice.address == bluetoothAddress) {
                sendCurrentTransaction(currentConnectedDevice)
            } else {
                searchForDevice(bluetoothAddress)
            }
        }

        private fun searchForDevice(ledgerAddress: String) {
            ledgerBleSearchManager.scan(
                newScanCallback = scanCallback,
                filteredAddress = ledgerAddress,
                coroutineScope = currentScope
            )
        }

      // this also stops LedgerBleOperationManager.
      fun manualStopAllResources() {
          this.stopAllResources()
          currentScope.coroutineContext.cancelChildren()
          ledgerBleOperationManager.manualStopAllProcess()
      }*/

    override fun stopAllResources() {
        //  ledgerBleSearchManager.stop()
        transactionManagerResultStateFlow.value = null
        transactionDataList = null
        signHelper.clearCachedData()
        if (isCurrentScopeInitialized()) {
            currentScope.coroutineContext.cancelChildren()
        }
        clearLifecycle()
    }

    private suspend fun processTransactionDataList(
        transactionDataList: List<TransactionSignData>,
        isGroupTransaction: Boolean,
    ): List<TransactionSignData>? {
        for (transactionData in transactionDataList) {
            transactionData.transactionByteArray ?: transactionData.createTransaction()
                ?: return null
        }

        /* if (isGroupTransaction) {
             createGroupedBytesArray(transactionDataList)?.let {
                 for (index in 0L until it.length()) {
                     transactionDataList[index.toInt()].transactionByteArray = it.get(index)
                 }
             }
         }*/
        return transactionDataList
    }

    private fun postTxnSignResult(
        bytesArray: ByteArray?,
        transactionData: TransactionSignData?,
    ) {
        if (bytesArray == null || transactionData == null) {
            postResult(Defined())
        } else {
            postResult(
                TransactionManagerResult.Success(
                    transactionData.getSignedTransactionDetail(
                        bytesArray,
                    ),
                ),
            )
        }
    }

    private fun postGroupTxnSignResult(
        groupedBytesArrayList: List<ByteArray>,
        transactionDataList: List<TransactionSignData>,
    ) {
        val signedGroupTxnDetailList =
            createSignedTransactionDetailList(transactionDataList, groupedBytesArrayList)
        if (signedGroupTxnDetailList.isNotEmpty()) {
            postResult(
                TransactionManagerResult.Success(
                    SignedTransactionDetail.Group(
                        groupedBytesArrayList.flatten(),
                        signedGroupTxnDetailList,
                    ),
                ),
            )
        } else {
            postResult(Defined())
        }
    }

    private fun createSignedTransactionDetailList(
        transactionDataList: List<TransactionSignData>,
        signedBytesArrayList: List<ByteArray>,
    ): List<SignedTransactionDetail> =
        mutableListOf<SignedTransactionDetail>().apply {
            for (index in transactionDataList.indices) {
                val signedTxn = signedBytesArrayList[index]
                add(transactionDataList[index].getSignedTransactionDetail(signedTxn))
            }
        }

    /*    private fun createGroupedBytesArray(transactionDataList: List<TransactionSignData>): BytesArray? {
            return mutableListOf<ByteArray>().apply {
                transactionDataList.forEach {
                    it.transactionByteArray?.let { transactionByteArray ->
                        add(transactionByteArray)
                    } ?: return null
                }
            }.toBytesArray().assignGroupId()
        }*/
    fun getAccountAlgoBalance(string: String): BigInteger? = 8997000.toBigInteger()

    fun getAccountMinBalance(string: String): BigInteger = 100000.toBigInteger()
}
