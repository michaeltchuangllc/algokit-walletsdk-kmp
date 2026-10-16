package com.michaeltchuang.walletsdk.core.solana.transaction

import com.michaeltchuang.walletsdk.core.railmpp.internal.signEd25519
import com.michaeltchuang.walletsdk.core.solana.utils.Base58

class SolanaAccountMeta(
    val publicKey: String,
    val isSigner: Boolean,
    val isWritable: Boolean,
)

class SolanaInstruction(
    val programId: String,
    val accounts: List<SolanaAccountMeta>,
    val data: ByteArray,
)

/**
 * Minimal legacy-message Solana transaction builder: enough for System transfers and
 * SPL `TransferChecked`, with a single local fee-payer/signer.
 */
object SolanaTransactionBuilder {
    const val SYSTEM_PROGRAM_ID = "11111111111111111111111111111111"
    const val TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    const val TOKEN_2022_PROGRAM_ID = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
    const val ASSOCIATED_TOKEN_PROGRAM_ID = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"

    /** Rent-exempt minimum for a 165-byte SPL token account. */
    const val TOKEN_ACCOUNT_RENT_LAMPORTS = 2_039_280L

    private const val SYSTEM_TRANSFER_IX = 2
    private const val TOKEN_TRANSFER_CHECKED_IX: Byte = 12
    private const val TOKEN_CLOSE_ACCOUNT_IX: Byte = 9
    private const val ATA_CREATE_IDEMPOTENT_IX: Byte = 1

    fun systemTransfer(
        from: String,
        to: String,
        lamports: Long,
    ): SolanaInstruction {
        require(lamports > 0) { "Transfer amount must be positive" }
        val data = ByteArray(12)
        writeU32Le(data, 0, SYSTEM_TRANSFER_IX)
        writeU64Le(data, 4, lamports)
        return SolanaInstruction(
            programId = SYSTEM_PROGRAM_ID,
            accounts =
                listOf(
                    SolanaAccountMeta(from, isSigner = true, isWritable = true),
                    SolanaAccountMeta(to, isSigner = false, isWritable = true),
                ),
            data = data,
        )
    }

    fun tokenTransferChecked(
        sourceTokenAccount: String,
        mint: String,
        destinationTokenAccount: String,
        authority: String,
        amount: Long,
        decimals: Int,
        tokenProgramId: String = TOKEN_PROGRAM_ID,
    ): SolanaInstruction {
        require(amount > 0) { "Transfer amount must be positive" }
        val data = ByteArray(10)
        data[0] = TOKEN_TRANSFER_CHECKED_IX
        writeU64Le(data, 1, amount)
        data[9] = decimals.toByte()
        return SolanaInstruction(
            programId = tokenProgramId,
            accounts =
                listOf(
                    SolanaAccountMeta(sourceTokenAccount, isSigner = false, isWritable = true),
                    SolanaAccountMeta(mint, isSigner = false, isWritable = false),
                    SolanaAccountMeta(destinationTokenAccount, isSigner = false, isWritable = true),
                    SolanaAccountMeta(authority, isSigner = true, isWritable = false),
                ),
            data = data,
        )
    }

    /** Creates [associatedAccount] for [owner]/[mint] if it doesn't exist yet; rent is paid by [funder]. */
    fun createAssociatedTokenAccountIdempotent(
        funder: String,
        associatedAccount: String,
        owner: String,
        mint: String,
        tokenProgramId: String = TOKEN_PROGRAM_ID,
    ): SolanaInstruction =
        SolanaInstruction(
            programId = ASSOCIATED_TOKEN_PROGRAM_ID,
            accounts =
                listOf(
                    SolanaAccountMeta(funder, isSigner = true, isWritable = true),
                    SolanaAccountMeta(associatedAccount, isSigner = false, isWritable = true),
                    SolanaAccountMeta(owner, isSigner = false, isWritable = false),
                    SolanaAccountMeta(mint, isSigner = false, isWritable = false),
                    SolanaAccountMeta(SYSTEM_PROGRAM_ID, isSigner = false, isWritable = false),
                    SolanaAccountMeta(tokenProgramId, isSigner = false, isWritable = false),
                ),
            data = byteArrayOf(ATA_CREATE_IDEMPOTENT_IX),
        )

    /** Closes an empty token account, sending its rent lamports to [destination]. */
    fun closeTokenAccount(
        tokenAccount: String,
        destination: String,
        authority: String,
        tokenProgramId: String = TOKEN_PROGRAM_ID,
    ): SolanaInstruction =
        SolanaInstruction(
            programId = tokenProgramId,
            accounts =
                listOf(
                    SolanaAccountMeta(tokenAccount, isSigner = false, isWritable = true),
                    SolanaAccountMeta(destination, isSigner = false, isWritable = true),
                    SolanaAccountMeta(authority, isSigner = true, isWritable = false),
                ),
            data = byteArrayOf(TOKEN_CLOSE_ACCOUNT_IX),
        )

    /**
     * Compiles a legacy message. [feePayer] is always the first (writable) signer; any other
     * account marked `isSigner` in [instructions] becomes an additional required signer.
     */
    fun compileMessage(
        feePayer: String,
        recentBlockhash: String,
        instructions: List<SolanaInstruction>,
    ): ByteArray {
        val metas = LinkedHashMap<String, Pair<Boolean, Boolean>>() // key -> (signer, writable)
        metas[feePayer] = true to true
        instructions.forEach { ix ->
            ix.accounts.forEach { meta ->
                val prev = metas[meta.publicKey]
                metas[meta.publicKey] =
                    (meta.isSigner || prev?.first == true) to (meta.isWritable || prev?.second == true)
            }
            if (ix.programId !in metas) metas[ix.programId] = false to false
        }

        val ordered =
            buildList {
                add(feePayer)
                addAll(metas.filter { it.key != feePayer && it.value.first && it.value.second }.keys)
                addAll(metas.filter { it.key != feePayer && it.value.first && !it.value.second }.keys)
                addAll(metas.filter { !it.value.first && it.value.second }.keys)
                addAll(metas.filter { !it.value.first && !it.value.second }.keys)
            }
        val numSigners = metas.count { it.value.first }
        val numReadonlySigned = metas.count { it.key != feePayer && it.value.first && !it.value.second }
        val numReadonlyUnsigned = metas.count { !it.value.first && !it.value.second }

        val out = ByteSink()
        out.writeByte(numSigners)
        out.writeByte(numReadonlySigned)
        out.writeByte(numReadonlyUnsigned)
        out.writeShortVec(ordered.size)
        ordered.forEach { out.writeBytes(decodeKey(it)) }
        out.writeBytes(decodeKey(recentBlockhash))
        out.writeShortVec(instructions.size)
        instructions.forEach { ix ->
            out.writeByte(ordered.indexOf(ix.programId))
            out.writeShortVec(ix.accounts.size)
            ix.accounts.forEach { out.writeByte(ordered.indexOf(it.publicKey)) }
            out.writeShortVec(ix.data.size)
            out.writeBytes(ix.data)
        }
        return out.toByteArray()
    }

    /** Signs [message] with [privateSeed] and returns the wire-format transaction bytes. */
    fun signAndSerialize(
        message: ByteArray,
        privateSeed: ByteArray,
    ): ByteArray {
        val signature = signEd25519(privateSeed, message) ?: error("Ed25519 signing failed")
        check(signature.size == 64) { "Unexpected signature length ${signature.size}" }
        val out = ByteSink()
        out.writeShortVec(1)
        out.writeBytes(signature)
        out.writeBytes(message)
        return out.toByteArray()
    }

    /** Required signer public keys (Base58) of a compiled legacy [message], in signature-slot order. */
    fun requiredSigners(message: ByteArray): List<String> {
        val numSigners = message[0].toInt() and 0xFF
        val (keyCount, offset) = readShortVec(message, 3)
        require(numSigners <= keyCount) { "Malformed message header" }
        return (0 until numSigners).map { i ->
            Base58.encode(message.copyOfRange(offset + i * 32, offset + (i + 1) * 32))
        }
    }

    /** Wire-format transaction with one 64-byte slot per required signer (unsigned slots are zero). */
    fun serialize(
        message: ByteArray,
        signatures: List<ByteArray?>,
    ): ByteArray {
        val signers = requiredSigners(message)
        require(signatures.size == signers.size) { "Expected ${signers.size} signatures, got ${signatures.size}" }
        val out = ByteSink()
        out.writeShortVec(signers.size)
        signatures.forEach { sig ->
            require(sig == null || sig.size == 64) { "Signatures must be 64 bytes" }
            out.writeBytes(sig ?: ByteArray(64))
        }
        out.writeBytes(message)
        return out.toByteArray()
    }

    /** Splits a wire-format transaction into its signature slots and message bytes. */
    fun deserialize(transaction: ByteArray): Pair<List<ByteArray>, ByteArray> {
        val (count, offset) = readShortVec(transaction, 0)
        val signatures = (0 until count).map { i -> transaction.copyOfRange(offset + i * 64, offset + (i + 1) * 64) }
        val message = transaction.copyOfRange(offset + count * 64, transaction.size)
        require(requiredSigners(message).size == count) { "Signature count does not match message header" }
        return signatures to message
    }

    /** Places [signature] for [signer] into an existing (possibly partially signed) [transaction]. */
    fun addSignature(
        transaction: ByteArray,
        signer: String,
        signature: ByteArray,
    ): ByteArray {
        val (signatures, message) = deserialize(transaction)
        val index = requiredSigners(message).indexOf(signer)
        require(index >= 0) { "$signer is not a required signer of this transaction" }
        val updated = signatures.map { it.takeUnless { sig -> sig.all { b -> b == 0.toByte() } } }.toMutableList()
        updated[index] = signature
        return serialize(message, updated)
    }

    internal fun readShortVec(
        bytes: ByteArray,
        start: Int,
    ): Pair<Int, Int> {
        var value = 0
        var shift = 0
        var offset = start
        while (true) {
            val b = bytes[offset++].toInt() and 0xFF
            value = value or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return value to offset
            shift += 7
            require(shift <= 14) { "Invalid short_vec" }
        }
    }

    private fun decodeKey(base58: String): ByteArray =
        Base58.decode(base58).also { require(it.size == 32) { "Invalid 32-byte key: $base58" } }

    private fun writeU32Le(
        buf: ByteArray,
        offset: Int,
        value: Int,
    ) {
        for (i in 0 until 4) buf[offset + i] = (value ushr (8 * i)).toByte()
    }

    private fun writeU64Le(
        buf: ByteArray,
        offset: Int,
        value: Long,
    ) {
        for (i in 0 until 8) buf[offset + i] = (value ushr (8 * i)).toByte()
    }

    private class ByteSink {
        private val bytes = ArrayList<Byte>(256)

        fun writeByte(value: Int) {
            bytes.add(value.toByte())
        }

        fun writeBytes(value: ByteArray) {
            value.forEach { bytes.add(it) }
        }

        fun writeShortVec(length: Int) {
            var remaining = length
            while (true) {
                var elem = remaining and 0x7F
                remaining = remaining ushr 7
                if (remaining == 0) {
                    writeByte(elem)
                    return
                }
                elem = elem or 0x80
                writeByte(elem)
            }
        }

        fun toByteArray(): ByteArray = bytes.toByteArray()
    }
}
