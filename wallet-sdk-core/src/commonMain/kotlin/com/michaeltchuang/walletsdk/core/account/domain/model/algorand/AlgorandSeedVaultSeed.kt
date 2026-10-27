package com.michaeltchuang.walletsdk.core.account.domain.model.algorand

/**
 * An authorized Algorand seed from the Seed Vault Service with all of its derived accounts.
 *
 * Index 0 is the primary account and the only one users can import. Other indexes (e.g. 1)
 * are kept for internal uses such as session keys.
 */
data class AlgorandSeedVaultSeed(
    val id: String,
    val name: String,
    val accounts: List<AlgorandSeedVaultSeedAccount>,
) {
    val primaryAccount: AlgorandSeedVaultSeedAccount?
        get() = accountAt(PRIMARY_ACCOUNT_INDEX)

    fun accountAt(index: Int): AlgorandSeedVaultSeedAccount? = accounts.firstOrNull { it.index == index }

    companion object {
        const val PRIMARY_ACCOUNT_INDEX = 0
    }
}

data class AlgorandSeedVaultSeedAccount(
    val index: Int,
    val address: String,
    val publicKey: String,
)
