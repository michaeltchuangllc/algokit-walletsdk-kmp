package com.michaeltchuang.walletsdk.core.transaction.model

import com.ionspin.kotlin.bignum.integer.BigInteger
import com.michaeltchuang.walletsdk.core.foundation.utils.MIN_FEE
import com.michaeltchuang.walletsdk.core.network.model.TransactionSigner

sealed class TransactionSignData {
    abstract val senderAccountAddress: String
    abstract val signer: TransactionSigner
    abstract val senderAuthAddress: String?

    open var calculatedFee: Long? = null
    open var transactionByteArray: ByteArray? = null
    open var amount: BigInteger = BigInteger.ZERO
    open val targetUser: TargetUser? = null
    open var isArc59Transaction: Boolean = false

    abstract fun getSignedTransactionDetail(signedTransactionData: ByteArray): SignedTransactionDetail

    fun isSenderRekeyed(): Boolean = senderAuthAddress != null && senderAuthAddress != senderAccountAddress

    data class Send(
        override val senderAccountAddress: String,
        override val senderAuthAddress: String?,
        override val signer: TransactionSigner,
        override var amount: BigInteger,
        override var targetUser: TargetUser,
        override var transactionByteArray: ByteArray? = null,
        override var isArc59Transaction: Boolean,
        val senderAlgoAmount: BigInteger,
        val minimumBalance: Long,
        val senderAccountName: String,
        val assetId: Long,
        val note: String? = null,
        val xnote: String? = null,
        var isMax: Boolean = false,
        var projectedFee: Long = MIN_FEE,
    ) : TransactionSignData() {
        override fun getSignedTransactionDetail(signedTransactionData: ByteArray): SignedTransactionDetail =
            SignedTransactionDetail.Send(
                signedTransactionData = signedTransactionData,
                amount = amount,
                targetUser = targetUser,
                isMax = isMax,
                fee = calculatedFee ?: 0,
                assetId = assetId,
                note = note,
                xnote = xnote,
                senderAccountAddress = senderAccountAddress,
                senderAccountName = senderAccountName,
            )
    }

    data class AddAsset(
        override val senderAccountAddress: String,
        override val senderAuthAddress: String?,
        override val signer: TransactionSigner,
        override var transactionByteArray: ByteArray? = null,
        override var isArc59Transaction: Boolean = false,
        val assetId: Long,
    ) : TransactionSignData() {
        override fun getSignedTransactionDetail(signedTransactionData: ByteArray): SignedTransactionDetail =
            SignedTransactionDetail.AssetOperation.AssetAddition(
                signedTransactionData = signedTransactionData,
                senderAccountAddress = senderAccountAddress,
                assetId = assetId,
            )
    }

    data class RemoveAsset(
        override val senderAccountAddress: String,
        override val senderAuthAddress: String?,
        override val signer: TransactionSigner,
        val assetId: Long,
        val creatorAddress: String,
    ) : TransactionSignData() {
        override fun getSignedTransactionDetail(signedTransactionData: ByteArray): SignedTransactionDetail =
            SignedTransactionDetail.AssetOperation.AssetRemoval(
                signedTransactionData = signedTransactionData,
                senderAccountAddress = senderAccountAddress,
                assetId = assetId,
            )
    }

    data class SendAndRemoveAsset(
        override val senderAccountAddress: String,
        override val senderAuthAddress: String?,
        override val signer: TransactionSigner,
        override var amount: BigInteger,
        val senderAccountName: String,
        val assetId: Long,
        val note: String? = null,
        override val targetUser: TargetUser,
    ) : TransactionSignData() {
        override fun getSignedTransactionDetail(signedTransactionData: ByteArray): SignedTransactionDetail =
            SignedTransactionDetail.Send(
                signedTransactionData = signedTransactionData,
                amount = amount,
                targetUser = targetUser,
                isMax = false,
                fee = calculatedFee ?: 0,
                assetId = assetId,
                note = note,
                senderAccountAddress = senderAccountAddress,
                senderAccountName = senderAccountName,
            )
    }

    data class Rekey(
        override val senderAccountAddress: String,
        override val senderAuthAddress: String?,
        override val signer: TransactionSigner,
        val senderAccountName: String,
        val rekeyAdminAddress: String,
    ) : TransactionSignData() {
        override fun getSignedTransactionDetail(signedTransactionData: ByteArray): SignedTransactionDetail =
            SignedTransactionDetail.RekeyOperation(
                signedTransactionData = signedTransactionData,
                accountAddress = senderAccountAddress,
                rekeyAdminAddress = rekeyAdminAddress,
                accountName = senderAccountName,
            )
    }
}
