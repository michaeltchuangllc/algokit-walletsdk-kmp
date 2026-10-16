package com.michaeltchuang.walletsdk.service.demo.data.model

import android.net.Uri

/**
 * Represents a Solana account derived from a seed.
 */
data class SolanaAccount(
    val accountId: Long,
    val name: String,
    val derivationPath: Uri,
    val publicKeyEncoded: String,
    val balance: Double? = null,  // Balance in SOL, null if not fetched
    val isBalanceLoading: Boolean = false
)

/**
 * Represents an authorized seed in the Seed Vault with its derived accounts.
 */
data class SolanaSeed(
    val authToken: Long,
    val name: String,
    val authPurpose: Int,
    val isBackedUp: Boolean,
    val accounts: List<SolanaAccount>
)

/**
 * Implementation limits for the Seed Vault.
 */
data class SeedVaultLimits(
    val maxSigningRequests: Int,
    val maxRequestedSignatures: Int,
    val maxRequestedPublicKeys: Int,
    val firstRequestedPublicKey: String,
    val lastRequestedPublicKey: String
)
