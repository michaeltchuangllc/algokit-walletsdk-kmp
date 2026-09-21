package com.michaeltchuang.walletsdk.core.railmpp.internal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValidateSettlementVoucherTest {
    private val payee = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAY5HFKQ"

    @Test
    fun `EXPECT the verifier TEAL to use the exact raw domain and signature argument WHEN building the voucher verifier`() {
        val channel = ByteArray(32) { it.toByte() }
        val key = ByteArray(32) { (it + 32).toByte() }
        val teal = buildVoucherVerifierTeal(123, channel, 456, ByteArray(64), key, payee)
        val expectedMessage = encodeUint64(123) + channel + encodeUint64(456) +
            ByteArray(32) + "settle-lsig-v1".encodeToByteArray()
        assertEquals(
            listOf(
                "#pragma version 7",
                "pushbytes ${expectedMessage.toTealByteLiteral()}",
                "arg_0",
                "pushbytes ${key.toTealByteLiteral()}",
                "ed25519verify_bare",
                "return",
            ),
            teal.lines(),
        )
        val falcon = buildVoucherVerifierTeal(123, channel, 456, ByteArray(1232), ByteArray(1793), payee)
        assertTrue(falcon.startsWith("#pragma version 12"))
        assertTrue("\nfalcon_verify\nreturn" in falcon)
        assertFalse("txn " in falcon)
        assertFalse("gtxn" in falcon)
        assertFalse("ed25519verify" in falcon)
        assertFails { buildVoucherVerifierTeal(123, ByteArray(31), 456, ByteArray(64), key, payee) }
        assertFails { buildVoucherVerifierTeal(0, channel, 456, ByteArray(64), key, payee) }
        assertFails { buildVoucherVerifierTeal(123, channel, 0, ByteArray(64), key, payee) }
        assertFails { buildVoucherVerifierTeal(123, channel, 456, ByteArray(0), key, payee) }
        assertFails { buildVoucherVerifierTeal(123, channel, 456, ByteArray(64), ByteArray(33), payee) }
    }

    @Test
    fun `EXPECT group size to cover the compiled program and signature without oversized groups WHEN sizing the voucher group`() {
        assertEquals(2, voucherValidationGroupSize(140, 64))
        assertEquals(2, voucherValidationGroupSize(1000, 1000))
        assertEquals(3, voucherValidationGroupSize(1001, 1000))
        assertEquals(4, voucherValidationGroupSize(1900, 1232))
        assertEquals(16, voucherValidationGroupSize(15000, 1000))
        assertFails { voucherValidationGroupSize(15001, 1000) }
        assertFails { voucherValidationGroupSize(0, 64) }
    }

    @Test
    fun `EXPECT the Falcon verifier to pay three minimum units and Ed25519 to pay one WHEN funding the voucher group`() {
        for ((signatureSize, programSize, expectedCount, expectedFee) in listOf(
            listOf(64, 140, 2, 2000),
            listOf(1232, 1900, 4, 6000),
            listOf(65, 140, 2, 4000),
        )) {
            val fake = RecordingTransactions()
            val signature = ByteArray(signatureSize) { it.toByte() }
            val program = ByteArray(programSize) { 12 }
            val envelopes = buildVoucherValidationGroup(program, signature, "sponsor", "verifier", 1000, 0, fake)
            assertEquals(expectedCount, envelopes.size)
            assertEquals(expectedFee.toLong(), fake.payments.first().fee)
            assertEquals(LOGIC_SIG_MINIMUM_BALANCE, fake.payments.first().amount)
            assertEquals("sponsor", fake.payments.first().sender)
            assertEquals("verifier", fake.payments.first().receiver)
            assertTrue(fake.payments.drop(1).all { it.amount == 0L && it.fee == 0L })
            assertTrue(fake.payments.drop(2).all { it.sender == "sponsor" && it.receiver == "sponsor" })
            assertEquals("verifier", fake.payments[1].sender)
            assertEquals("verifier", fake.payments[1].receiver)
            assertEquals(expectedCount, fake.payments.map { it.note.single() }.toSet().size)
            assertEquals(1, fake.logicSignCalls)
            assertContentEquals(signature, fake.signedSignature)
            assertContentEquals(program, fake.signedProgram)
            assertContentEquals(fake.grouped[1], fake.signedTransaction)
            val envelopePrefix = byteArrayOf(0x81.toByte(), 0xa3.toByte()) + "txn".encodeToByteArray()
            envelopes.forEachIndexed { index, envelope ->
                if (index != 1) {
                    assertContentEquals(envelopePrefix + fake.grouped[index], envelope)
                    assertFalse("sig" in envelope.decodeToString())
                }
            }
            assertContentEquals(fake.signedEnvelope, envelopes[1])
        }
    }

    @Test
    fun `EXPECT the group to rebuild with the encoded byte fee and preserve the Falcon minimum WHEN a per-byte fee is supplied`() {
        val fake = RecordingTransactions()
        val envelopes = buildVoucherValidationGroup(ByteArray(1900), ByteArray(1232), "sponsor", "verifier", 1000, 10, fake)
        val required = envelopes.mapIndexed { index, bytes ->
            maxOf(if (index == 1) 3000L else 1000L, (bytes.size + if (index != 1) 75 else 0) * 10L)
        }.sum()
        assertTrue(fake.logicSignCalls > 1)
        assertEquals(required, fake.payments.takeLast(4).first().fee)
        assertTrue(required > 6000)
    }

    @Test
    fun `EXPECT the build to fail closed WHEN signing returns empty or grouping falls back`() {
        for (fake in listOf(
            RecordingTransactions(emptySignature = true),
            RecordingTransactions(groupingFallback = true),
        )) {
            assertFails { buildVoucherValidationGroup(ByteArray(140), ByteArray(64), "sponsor", "verifier", 1000, 0, fake) }
        }
        assertFails {
            buildVoucherValidationGroup(ByteArray(140), ByteArray(64), "verifier", "verifier", 1000, 0, RecordingTransactions())
        }
    }

    @Test
    fun `EXPECT envelopes to be preserved without signer-fixing or developer options WHEN building the simulate request`() {
        val envelopes = listOf(byteArrayOf(0x80.toByte()), byteArrayOf(0x81.toByte()))
        val request = buildVoucherValidationRequest(envelopes)
        val text = request.decodeToString()
        assertTrue("allow-empty-signatures" in text)
        assertTrue("txn-groups" in text)
        assertTrue("exec-trace-config" in text)
        for (forbidden in listOf("fix-signers", "accounts", "sources", "extra-opcode-budget", "allow-unnamed-resources")) {
            assertFalse(forbidden in text)
        }
        val prefix = "83b6616c6c6f772d656d7074792d7369676e617475726573c3b1657865632d74726163652d636f6e66696781a6656e61626c65c3aa74786e2d67726f7570739181a474786e7392"
        assertContentEquals(hex(prefix) + envelopes[0] + envelopes[1], request)
        assertFails { buildVoucherValidationRequest(emptyList()) }
        assertFails { buildVoucherValidationRequest(listOf(byteArrayOf(), byteArrayOf(1))) }
        assertFails { buildVoucherValidationRequest(List(17) { byteArrayOf(1) }) }
        val sixteen = buildVoucherValidationRequest(List(16) { byteArrayOf(0x80.toByte()) })
        assertContentEquals(byteArrayOf(0xdc.toByte(), 0, 16), sixteen.copyOfRange(sixteen.size - 19, sixteen.size - 16))
    }

    @Test
    fun `EXPECT only a fully successful per-transaction signature verification to be accepted WHEN validating the simulation response`() {
        requireVerifiedVoucherSimulation(success, 2, 64)
        requireVerifiedVoucherSimulation(success.replace("1904", "1704"), 2, 1232)
        for (response in listOf(
            "",
            "SIMULATE_ERROR: unavailable",
            "{}",
            """{"txn-groups":[]}""",
            success.replace("\"txn-groups\":", "\"error\":\"invalid signature\",\"txn-groups\":"),
            success.replace("\"txn-results\":", "\"failure-message\":\"overspend\",\"txn-results\":"),
            success.replace("\"txn-results\":", "\"failure-message\":\"signature rejected\",\"txn-results\":"),
            success.replace("\"txn-results\":", "\"failed-at\":[1],\"txn-results\":"),
            success.replace("\"txn-groups\":", "\"eval-overrides\":{\"fix-signers\":true},\"txn-groups\":"),
            success.replace("1904", "0"),
            success.replace("\"exec-trace\"", "\"missing-trace\""),
            success.replace("[{\"pc\":1}]", "[]"),
            success.replace("{\"pc\":1}", "{\"pc\":1,\"error\":\"invalid signature\"}"),
            success.replace("\"logic-sig-budget-consumed\":1904", "\"app-budget-consumed\":1904"),
            success.replace("\"logic-sig-budget-consumed\":1904", "\"fixed-signer\":\"other\",\"logic-sig-budget-consumed\":1904"),
            success.replace("\"txn-result\":{}", "\"txn-result\":{\"pool-error\":\"rejected\"}"),
            success.replace("\"txn-result\":{}", "\"txn-result\":{},\"logic-sig-budget-consumed\":1"),
            """{"txn-groups":[{"logic-sig-budget-consumed":1904,"txn-results":[{"txn-result":{}},{"txn-result":{}}]}]}""",
        )) {
            assertFails(response) { requireVerifiedVoucherSimulation(response, 2, 64) }
        }
        assertFails { requireVerifiedVoucherSimulation(success, 3, 64) }
    }

    private val success =
        """{"txn-groups":[{"txn-results":[{"txn-result":{}},{"txn-result":{},"logic-sig-budget-consumed":1904,"exec-trace":{"logic-sig-trace":[{"pc":1}]}}]}]}"""

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private class Payment(val sender: String, val receiver: String, val amount: Long, val fee: Long, val note: ByteArray)

    private class RecordingTransactions(
        val emptySignature: Boolean = false,
        val groupingFallback: Boolean = false,
    ) : VoucherValidationTransactions {
        val payments = mutableListOf<Payment>()
        var grouped = emptyList<ByteArray>()
        var logicSignCalls = 0
        var signedProgram = byteArrayOf()
        var signedSignature = byteArrayOf()
        var signedTransaction = byteArrayOf()
        var signedEnvelope = byteArrayOf()

        override fun payment(sender: String, receiver: String, amount: Long, fee: Long, note: ByteArray): ByteArray {
            payments += Payment(sender, receiver, amount, fee, note)
            return "$sender:$receiver:$amount:$fee:".encodeToByteArray() + note
        }

        override fun group(transactions: List<ByteArray>): List<ByteArray> {
            grouped = if (groupingFallback) transactions else transactions.map { it + ByteArray(32) { 7 } }
            return grouped
        }

        override fun logicSign(program: ByteArray, signature: ByteArray, transaction: ByteArray): ByteArray {
            logicSignCalls++
            signedProgram = program
            signedSignature = signature
            signedTransaction = transaction
            signedEnvelope = if (emptySignature) byteArrayOf() else "lsig".encodeToByteArray() + program + signature + transaction
            return signedEnvelope
        }
    }
}
