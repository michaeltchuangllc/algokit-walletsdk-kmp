package com.michaeltchuang.walletsdk.core.railmpp.internal

import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val VOUCHER_LOGIC_SIG_BYTES_PER_TRANSACTION = 1000
private const val VOUCHER_LOGIC_SIG_BUDGET_PER_TRANSACTION = 20_000
private const val VOUCHER_VERIFIER_OVERHEAD_COST = 4
private const val VOUCHER_MAX_GROUP_SIZE = 16
private const val VOUCHER_UNSIGNED_SIGNATURE_FEE_BYTES = 75L
internal const val VOUCHER_VERIFIER_INDEX = 1

internal expect suspend fun validateLogicSigSettlementInternal(
    funderSigner: MppWalletSigner,
    appId: Long,
    usdcAssetId: Long,
    algodUrl: String,
    channelId: ByteArray,
    cumulativeAmountMicroUsdc: Long,
    voucherSignature: ByteArray,
    authorizedSignerPublicKey: ByteArray,
    payeeAddress: String,
)

internal fun buildVoucherVerifierTeal(
    appId: Long,
    channelId: ByteArray,
    cumulativeAmountMicroUsdc: Long,
    voucherSignature: ByteArray,
    authorizedSignerPublicKey: ByteArray,
    payeeAddress: String,
): String {
    require(appId > 0) { "appId must be positive" }
    require(channelId.size == 32) { "channelId must be 32 bytes" }
    require(cumulativeAmountMicroUsdc > 0) { "Voucher amount must be positive" }
    require(voucherSignature.size in 1..4096) { "Invalid voucher signature length" }
    val ed25519 = voucherSignature.size == 64
    require(authorizedSignerPublicKey.size == if (ed25519) 32 else 1793) { "Invalid voucher public key length" }
    val message = encodeUint64(appId) + channelId + encodeUint64(cumulativeAmountMicroUsdc) +
        decodeAlgorandAddressPublicKey(payeeAddress) + "settle-lsig-v1".encodeToByteArray()
    return """
        #pragma version ${if (ed25519) 7 else 12}
        pushbytes ${message.toTealByteLiteral()}
        arg_0
        pushbytes ${authorizedSignerPublicKey.toTealByteLiteral()}
        ${if (ed25519) "ed25519verify_bare" else "falcon_verify"}
        return
    """.trimIndent()
}

internal interface VoucherValidationTransactions {
    fun payment(sender: String, receiver: String, amount: Long, fee: Long, note: ByteArray): ByteArray

    fun group(transactions: List<ByteArray>): List<ByteArray>

    fun logicSign(program: ByteArray, signature: ByteArray, transaction: ByteArray): ByteArray
}

private fun voucherVerifierCost(signatureSize: Int): Int =
    (if (signatureSize == 64) 1900 else 1700) + VOUCHER_VERIFIER_OVERHEAD_COST

internal fun voucherValidationGroupSize(programSize: Int, signatureSize: Int): Int {
    require(programSize in 1..16_000 && signatureSize in 1..4096) { "Invalid voucher LogicSig size" }
    val count = maxOf(
        2,
        (programSize + signatureSize + VOUCHER_LOGIC_SIG_BYTES_PER_TRANSACTION - 1) / VOUCHER_LOGIC_SIG_BYTES_PER_TRANSACTION,
        (voucherVerifierCost(signatureSize) + VOUCHER_LOGIC_SIG_BUDGET_PER_TRANSACTION - 1) /
            VOUCHER_LOGIC_SIG_BUDGET_PER_TRANSACTION,
    )
    require(count <= VOUCHER_MAX_GROUP_SIZE) { "Voucher LogicSig exceeds group size budget" }
    return count
}

internal fun buildVoucherValidationGroup(
    program: ByteArray,
    signature: ByteArray,
    sponsorAddress: String,
    verifierAddress: String,
    minFee: Long,
    feePerByte: Long,
    transactions: VoucherValidationTransactions,
): List<ByteArray> {
    require(sponsorAddress.isNotBlank() && verifierAddress.isNotBlank() && sponsorAddress != verifierAddress)
    require(minFee in 1..1_000_000 && feePerByte in 0..1_000_000) { "Invalid simulation fee parameters" }
    val count = voucherValidationGroupSize(program.size, signature.size)
    val verifierFeeUnits = if (signature.size == 64) 1L else 3L
    var pooledFee = minFee * (count - 1 + verifierFeeUnits)
    repeat(8) {
        val unsigned = buildList {
            add(transactions.payment(sponsorAddress, verifierAddress, LOGIC_SIG_MINIMUM_BALANCE, pooledFee, byteArrayOf(0)))
            add(transactions.payment(verifierAddress, verifierAddress, 0L, 0L, byteArrayOf(VOUCHER_VERIFIER_INDEX.toByte())))
            for (index in 2 until count) {
                add(transactions.payment(sponsorAddress, sponsorAddress, 0L, 0L, byteArrayOf(index.toByte())))
            }
        }
        require(unsigned.all { it.isNotEmpty() }) { "Failed to build voucher simulation payments" }
        val grouped = transactions.group(unsigned)
        require(grouped.size == count && grouped.all { it.isNotEmpty() }) { "Failed to group voucher simulation payments" }
        require(grouped.indices.all { !grouped[it].contentEquals(unsigned[it]) }) { "Voucher simulation group ID was not assigned" }
        val signedVerifier = transactions.logicSign(program, signature, grouped[VOUCHER_VERIFIER_INDEX])
        require(signedVerifier.isNotEmpty()) { "Missing voucher verification LogicSig" }
        val envelopes = grouped.mapIndexed { index, transaction ->
            if (index == VOUCHER_VERIFIER_INDEX) signedVerifier else unsignedVoucherEnvelope(transaction)
        }
        val requiredFee = envelopes.mapIndexed { index, bytes ->
            val encodedSize = bytes.size.toLong() + if (index != VOUCHER_VERIFIER_INDEX) VOUCHER_UNSIGNED_SIGNATURE_FEE_BYTES else 0L
            val minimum = minFee * if (index == VOUCHER_VERIFIER_INDEX) verifierFeeUnits else 1L
            maxOf(minimum, encodedSize * feePerByte)
        }.sum()
        if (pooledFee >= requiredFee) return envelopes
        pooledFee = requiredFee
    }
    error("Voucher simulation fee calculation did not converge")
}

private fun voucherMsgpackString(value: String): ByteArray {
    val bytes = value.encodeToByteArray()
    require(bytes.size < 32)
    return byteArrayOf((0xa0 or bytes.size).toByte()) + bytes
}

private fun unsignedVoucherEnvelope(transaction: ByteArray): ByteArray =
    byteArrayOf(0x81.toByte()) + voucherMsgpackString("txn") + transaction

internal fun buildVoucherValidationRequest(envelopes: List<ByteArray>): ByteArray {
    require(envelopes.size in 2..VOUCHER_MAX_GROUP_SIZE && envelopes.all { it.isNotEmpty() })
    val arrayHeader = if (envelopes.size < 16) {
        byteArrayOf((0x90 or envelopes.size).toByte())
    } else {
        byteArrayOf(0xdc.toByte(), 0, 16)
    }
    return byteArrayOf(0x83.toByte()) +
        voucherMsgpackString("allow-empty-signatures") + byteArrayOf(0xc3.toByte()) +
        voucherMsgpackString("exec-trace-config") + byteArrayOf(0x81.toByte()) +
        voucherMsgpackString("enable") + byteArrayOf(0xc3.toByte()) +
        voucherMsgpackString("txn-groups") + byteArrayOf(0x91.toByte(), 0x81.toByte()) +
        voucherMsgpackString("txns") + arrayHeader +
        envelopes.fold(ByteArray(0)) { bytes, envelope -> bytes + envelope }
}

internal fun requireVerifiedVoucherSimulation(response: String, transactionCount: Int, signatureSize: Int) {
    require(transactionCount in 2..VOUCHER_MAX_GROUP_SIZE && signatureSize in 1..4096)
    require(!response.startsWith("SIMULATE_ERROR:")) { "Voucher simulation unavailable: ${response.take(300)}" }
    val root = Json.parseToJsonElement(response).jsonObject
    require(root["error"]?.jsonPrimitive?.content.orEmpty().isEmpty()) { "Voucher simulation failed" }
    require(root["eval-overrides"]?.jsonObject?.get("fix-signers")?.jsonPrimitive?.booleanOrNull != true) {
        "Voucher simulation must not fix signers"
    }
    val groups = root.getValue("txn-groups").jsonArray
    require(groups.size == 1) { "Missing voucher simulation group" }
    val group = groups.single().jsonObject
    val failure = group["failure-message"]?.jsonPrimitive?.content.orEmpty()
    require(failure.isEmpty()) { "Voucher simulation failed: ${failure.take(300)}" }
    require("failed-at" !in group) { "Voucher simulation rejected" }
    val results = group.getValue("txn-results").jsonArray
    require(results.size == transactionCount) { "Missing voucher simulation results" }
    results.forEachIndexed { index, result ->
        val fields = result.jsonObject
        require("fixed-signer" !in fields) { "Voucher simulation changed a signer" }
        require("failed-at" !in fields && fields["error"]?.jsonPrimitive?.content.orEmpty().isEmpty()) {
            "Voucher simulation transaction failed"
        }
        val pending = fields.getValue("txn-result").jsonObject
        require(pending["pool-error"]?.jsonPrimitive?.content.orEmpty().isEmpty()) { "Voucher simulation transaction failed" }
        val cost = fields["logic-sig-budget-consumed"]?.jsonPrimitive?.longOrNull ?: 0L
        if (index == VOUCHER_VERIFIER_INDEX) {
            require(cost > 0L) {
                "Voucher simulation did not execute the signature verifier"
            }
            val trace = fields.getValue("exec-trace").jsonObject.getValue("logic-sig-trace").jsonArray
            require(trace.isNotEmpty()) { "Voucher simulation returned no LogicSig trace" }
            require(trace.all {
                val step = it.jsonObject
                (step["pc"]?.jsonPrimitive?.longOrNull ?: -1L) >= 0L &&
                    step["error"]?.jsonPrimitive?.content.orEmpty().isEmpty()
            }) { "Voucher simulation returned an invalid LogicSig trace" }
        } else {
            require(cost == 0L) { "Voucher sponsor must remain unsigned" }
        }
    }
}
