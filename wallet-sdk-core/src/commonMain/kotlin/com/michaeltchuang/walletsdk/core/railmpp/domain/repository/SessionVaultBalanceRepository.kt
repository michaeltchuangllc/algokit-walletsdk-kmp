package com.michaeltchuang.walletsdk.core.railmpp.domain.repository

interface SessionVaultBalanceRepository {
    suspend fun getRemainingBalance(params: GetRemainingBalanceParams): Result<Long>

    class GetRemainingBalanceParams(
        val viewerAddress: String,
        val hostAddress: String,
        val appId: Long,
        val algodUrl: String? = null,
        val authorizedSignerPublicKey: ByteArray? = null,
    )
}
