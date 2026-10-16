package com.michaeltchuang.walletsdk.core.railmpp.domain.model

data class ConsentApproval(
    val approved: Boolean,
    val autoPaySegments: Boolean,
    val budgetCap: BudgetCap? = null,
    val maxAutoPaySegments: Int? = null,
    val voucherSignature: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ConsentApproval

        if (approved != other.approved) return false
        if (autoPaySegments != other.autoPaySegments) return false
        if (maxAutoPaySegments != other.maxAutoPaySegments) return false
        if (budgetCap != other.budgetCap) return false
        if (!voucherSignature.contentEquals(other.voucherSignature)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = approved.hashCode()
        result = 31 * result + autoPaySegments.hashCode()
        result = 31 * result + (maxAutoPaySegments ?: 0)
        result = 31 * result + (budgetCap?.hashCode() ?: 0)
        result = 31 * result + (voucherSignature?.contentHashCode() ?: 0)
        return result
    }
}
