package com.michaeltchuang.walletsdk.core.railmpp

import com.algorand.algosdk.account.Account
import com.algorand.algosdk.transaction.Transaction
import com.algorand.algosdk.util.Encoder

/** [AndroidMppWalletSigner] backed by an in-process algosdk [Account]. */
class AccountMppSigner(
    private val account: Account,
) : AndroidMppWalletSigner {
    override val address: String get() = account.address.toString()
    override val authorizedSignerPublicKey: ByteArray get() = account.address.getBytes()

    override suspend fun signTransaction(txn: Transaction): ByteArray =
        Encoder.encodeToMsgPack(account.signTransaction(txn))
}
