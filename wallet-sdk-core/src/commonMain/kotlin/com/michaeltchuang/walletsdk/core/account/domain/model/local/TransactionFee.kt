package com.michaeltchuang.walletsdk.core.account.domain.model.local

data class TransactionFee(
    val feeInMicroAlgos: Long,
    val feeInAlgos: String,
) {
    companion object {
        const val FALCON24_FEE_MICRO_ALGOS = 4000L // 0.004 ALGO (LogicSig bundle)
        const val FALCON25_FEE_MICRO_ALGOS = 3000L // 0.003 ALGO (native account)
        const val STANDARD_FEE_MICRO_ALGOS = 1000L // 0.001 ALGO
        const val FALCON24_FEE_ALGOS = "0.004"
        const val FALCON25_FEE_ALGOS = "0.003"
        const val STANDARD_FEE_ALGOS = "0.001"
    }
}
