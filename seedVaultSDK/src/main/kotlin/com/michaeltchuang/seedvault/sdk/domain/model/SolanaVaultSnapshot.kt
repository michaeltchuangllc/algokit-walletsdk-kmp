package com.michaeltchuang.seedvault.sdk.domain.model

/** Provider metadata only; balances are not fetched by the Seed Vault client. */
data class SolanaVaultSnapshot(
    val seeds: List<SolanaSeed>,
    val hasUnauthorizedSeeds: Boolean,
    val limits: SeedVaultLimits,
)
