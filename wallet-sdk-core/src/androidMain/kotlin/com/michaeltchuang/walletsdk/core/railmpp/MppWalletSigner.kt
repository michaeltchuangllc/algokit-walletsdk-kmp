package com.michaeltchuang.walletsdk.core.railmpp

import com.algorand.algosdk.account.Account
import com.algorand.algosdk.transaction.Transaction
import com.algorand.algosdk.util.Encoder

/**
 * Consumer-side wallet signer used by [MppPaymentRail] in client mode.
 *
 * Mirrors the rail-x402 signer interface — apps with an in-process Algorand
 * keypair can use [AccountMppSigner] for zero-boilerplate setup.
 */
interface MppWalletSigner {
    /** The Algorand address this signer authorizes. */
    val address: String

    /**
     * Sign the given Algorand [Transaction] and return the raw msgpack bytes
     * of the signed transaction (as produced by `Encoder.encodeToMsgPack`).
     * Must not mutate the input transaction other than attaching a signature.
     */
    suspend fun signTransaction(txn: Transaction): ByteArray
}

/**
 * Convenience [MppWalletSigner] that signs with an in-process
 * [com.algorand.algosdk.account.Account].
 */
class AccountMppSigner(
    private val account: Account,
) : MppWalletSigner {
    override val address: String
        get() = account.address.toString()

    override suspend fun signTransaction(txn: Transaction): ByteArray {
        val signed = account.signTransaction(txn)
        return Encoder.encodeToMsgPack(signed)
    }
}
