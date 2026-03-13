package com.michaeltchuang.walletsdk.core.account.domain.model.local

/**
 * Represents a Seed Vault account in the local database.
 * Contains public_key as primary key (address), address string, and chainId (format like "501").
 */
data class SolanaAccount(
    val publicKey: String,
    val address: String,
    val chainId: String,
)
