package com.michaeltchuang.seedvault.service.domain.model

/**
 * A seed stored in the vault, as shown on the Seed Vault settings screens.
 * Only display data lives here: no seed phrase, private key or derivation material.
 *
 * @param address the Falcon-1024 account address, or empty for a seed that has not been used yet
 */
data class VaultSeed(
    val id: Long,
    val name: String,
    val isBackedUp: Boolean,
    val authorizedApps: List<AuthorizedApp>,
    val address: String = "",
)

/** Network values from algod that go into an Algorand payment. */
class AlgorandPaymentParams(
    val genesisId: String,
    val genesisHash: ByteArray,
    val firstValid: Long,
    val lastValid: Long,
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
