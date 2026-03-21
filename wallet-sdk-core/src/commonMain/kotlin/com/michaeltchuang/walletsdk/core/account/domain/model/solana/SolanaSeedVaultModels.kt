package com.michaeltchuang.walletsdk.core.account.domain.model.solana

/**
 * Represents a Solana account from Seed Vault.
 */
data class SolanaSeedVaultAccount(
    val address: String,
    val accountName: String?,
    val derivationPath: String,
    val accountId: Long,
)

/**
 * Represents a seed containing Solana accounts from Seed Vault.
 */
data class SolanaSeedInfo(
    val authToken: Long,
    val name: String,
    val accounts: List<SolanaSeedVaultAccount>,
)
