package com.michaeltchuang.walletsdk.core.account.domain.model.local

/**
 * Represents a Seed Vault account in the local database.
 * Contains public_key as primary key (address), address string, chainId (format like "501"),
 * and optional accountName from Seed Vault.
 */
data class SolanaAccount(
    val publicKey: String,
    val address: String,
    val chainId: String,
    val accountName: String? = null,
)
