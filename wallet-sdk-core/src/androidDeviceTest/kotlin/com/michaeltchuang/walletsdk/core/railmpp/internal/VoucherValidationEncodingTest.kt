package com.michaeltchuang.walletsdk.core.railmpp.internal

import com.fasterxml.jackson.databind.ObjectMapper
import org.msgpack.jackson.dataformat.MessagePackFactory
import uniffi.algokit_transact_ffi.LogicSignature
import uniffi.algokit_transact_ffi.PaymentTransactionFields
import uniffi.algokit_transact_ffi.SignedTransaction
import uniffi.algokit_transact_ffi.Transaction
import uniffi.algokit_transact_ffi.TransactionType
import uniffi.algokit_transact_ffi.decodeSignedTransaction
import uniffi.algokit_transact_ffi.decodeTransaction
import uniffi.algokit_transact_ffi.encodeSignedTransaction
import uniffi.algokit_transact_ffi.encodeTransactionRaw
import uniffi.algokit_transact_ffi.getLogicSignatureAddress
import uniffi.algokit_transact_ffi.groupTransactions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoucherValidationEncodingTest {
    @Test
    fun realSdkEnvelopesKeepOnlyVerifierLogicSignedAndSponsorUnsigned() {
        val program = byteArrayOf(7, 0x80.toByte(), 0, 0x2d, 0x80.toByte(), 32) +
            ByteArray(32) + byteArrayOf(0x84.toByte(), 0x43)
        verifyGroup(program, ByteArray(64) { it.toByte() }, 0, 2)
    }

    @Test
    fun falconSizedEnvelopesPoolFeesAndKeepPaddingUnsigned() {
        verifyGroup(ByteArray(1900) { 12 }, ByteArray(1330) { (it % 251).toByte() }, 10, 4)
    }

    @Test
    fun maximumSizeGroupEncodesAllSixteenTransactions() {
        verifyGroup(ByteArray(15_936) { 12 }, ByteArray(64) { it.toByte() }, 0, 16)
    }

    private fun verifyGroup(program: ByteArray, signature: ByteArray, feePerByte: Long, expectedCount: Int) {
        val sponsor = getLogicSignatureAddress(byteArrayOf(1, 0x20, 1, 1, 0x22))
        val verifier = getLogicSignatureAddress(program)
        val envelopes = buildVoucherValidationGroup(
            program, signature, sponsor, verifier, 1000, feePerByte,
            object : VoucherValidationTransactions {
                override fun payment(sender: String, receiver: String, amount: Long, fee: Long, note: ByteArray): ByteArray =
                    encodeTransactionRaw(
                        Transaction(
                            transactionType = TransactionType.PAYMENT,
                            sender = sender,
                            fee = fee.toULong(),
                            firstValid = 1uL,
                            lastValid = 100uL,
                            genesisHash = ByteArray(32) { 1 },
                            genesisId = "testnet-v1.0",
                            note = note,
                            payment = PaymentTransactionFields(receiver = receiver, amount = amount.toULong()),
                        ),
                    )

                override fun group(transactions: List<ByteArray>): List<ByteArray> =
                    groupTransactions(transactions.map { decodeTransaction(it) }).map { encodeTransactionRaw(it) }

                override fun logicSign(program: ByteArray, signature: ByteArray, transaction: ByteArray): ByteArray =
                    encodeSignedTransaction(
                        SignedTransaction(
                            transaction = decodeTransaction(transaction),
                            logicSignature = LogicSignature(logic = program, args = listOf(signature)),
                        ),
                    )
            },
        )
        val funding = decodeSignedTransaction(envelopes.first())
        val verification = decodeSignedTransaction(envelopes.last())
        assertEquals(expectedCount, envelopes.size)
        assertNull(funding.signature)
        assertNull(funding.logicSignature)
        assertNull(verification.signature)
        assertEquals(sponsor, funding.transaction.sender)
        assertEquals(verifier, funding.transaction.payment?.receiver)
        assertEquals(100_000uL, funding.transaction.payment?.amount)
        val expectedFee = envelopes.mapIndexed { index, bytes ->
            val minimum = if (index == envelopes.lastIndex && signature.size != 64) 3000L else 1000L
            maxOf(minimum, (bytes.size.toLong() + if (index != envelopes.lastIndex) 75L else 0L) * feePerByte)
        }.sum()
        assertEquals(expectedFee.toULong(), funding.transaction.fee)
        assertEquals(verifier, verification.transaction.sender)
        assertEquals(verifier, verification.transaction.payment?.receiver)
        assertEquals(0uL, verification.transaction.payment?.amount ?: 0uL)
        assertEquals(0uL, verification.transaction.fee ?: 0uL)
        assertContentEquals(program, verification.logicSignature?.logic)
        assertContentEquals(signature, verification.logicSignature?.args?.single())
        val request = ObjectMapper(MessagePackFactory()).readTree(buildVoucherValidationRequest(envelopes))
        assertTrue(request["allow-empty-signatures"].booleanValue())
        assertFalse(request.has("fix-signers"))
        assertFalse(request.has("exec-trace-config"))
        assertFalse(request.has("extra-opcode-budget"))
        assertEquals(2, request.size())
        val groups = request["txn-groups"]
        assertEquals(1, groups.size())
        val txns = groups[0]["txns"]
        assertEquals(expectedCount, txns.size())
        val groupId = txns[0]["txn"]["grp"].binaryValue()
        assertEquals(32, groupId.size)
        val notes = mutableSetOf<List<Byte>>()
        txns.forEachIndexed { index, envelope ->
            val txn = envelope["txn"]
            assertContentEquals(groupId, txn["grp"].binaryValue())
            assertEquals("pay", txn["type"].textValue())
            assertFalse(txn.has("rekey"))
            assertFalse(txn.has("close"))
            assertTrue(notes.add(txn["note"].binaryValue().toList()))
            assertFalse(envelope.has("sig"))
            assertFalse(envelope.has("msig"))
            assertFalse(envelope.has("sgnr"))
            if (index == envelopes.lastIndex) {
                assertContentEquals(program, envelope["lsig"]["l"].binaryValue())
                assertContentEquals(signature, envelope["lsig"]["arg"][0].binaryValue())
            } else {
                assertEquals(1, envelope.size())
                assertContentEquals(decodeAlgorandAddressPublicKey(sponsor), txn["snd"].binaryValue())
                if (index > 0) {
                    assertContentEquals(txn["snd"].binaryValue(), txn["rcv"].binaryValue())
                    assertEquals(0L, txn.path("amt").asLong())
                    assertEquals(0L, txn.path("fee").asLong())
                }
            }
        }
    }
}
