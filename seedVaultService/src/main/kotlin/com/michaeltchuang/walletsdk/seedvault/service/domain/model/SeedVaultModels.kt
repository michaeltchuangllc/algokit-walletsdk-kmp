package com.michaeltchuang.walletsdk.seedvault.service.domain.model

/**
 * A seed stored in the vault, as shown on the Seed Vault settings screens.
 * Only display data lives here: no seed phrase, keys or derivation material.
 */
data class VaultSeed(
    val id: Long,
    val name: String,
    val isBackedUp: Boolean,
    val authorizedApps: List<AuthorizedApp>,
)

/**
 * An app that is allowed to request signatures from a seed.
 *
 * @param isPrivileged privileged wallets (e.g. holding ACCESS_SEED_VAULT_PRIVILEGED) cannot be
 * revoked from the settings screen, which matches the disabled "Authorized" button in the design.
 */
data class AuthorizedApp(
    val packageName: String,
    val label: String,
    val isPrivileged: Boolean = false,
)
