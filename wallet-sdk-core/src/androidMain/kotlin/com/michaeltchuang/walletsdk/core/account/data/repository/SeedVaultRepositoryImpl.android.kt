package com.michaeltchuang.walletsdk.core.account.data.repository

import android.content.Context
import android.database.Cursor
import android.util.Log
import com.michaeltchuang.walletsdk.core.account.domain.model.solana.SolanaSeedInfo
import com.michaeltchuang.walletsdk.core.account.domain.model.solana.SolanaSeedVaultAccount
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.SolanaAccountRepository
import com.michaeltchuang.walletsdk.core.account.domain.repository.solana.SeedVaultRepository
import com.michaeltchuang.walletsdk.core.network.domain.AndroidContextHolder
import com.solanamobile.seedvault.Bip44DerivationPath
import com.solanamobile.seedvault.BipLevel
import com.solanamobile.seedvault.Wallet
import com.solanamobile.seedvault.WalletContractV1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android implementation of SeedVaultRepository that interacts with the Solana Mobile Seed Vault.
 */
class SeedVaultRepositoryImpl(
    private val solanaAccountRepository: SolanaAccountRepository,
) : SeedVaultRepository {
    companion object {
        private const val TAG = "SeedVaultRepository"
        private const val FIRST_ACCOUNT_INDEX = 0
        private const val MAX_ACCOUNTS_TO_MARK = 2 // Mark first 2 accounts as user wallets
    }

    override suspend fun getSolanaSeeds(): List<SolanaSeedInfo> {
        val context =
            AndroidContextHolder.applicationContext
                ?: throw IllegalStateException("Application context not available")

        return withContext(Dispatchers.IO) {
            val seeds = mutableListOf<SolanaSeedInfo>()

            // Get authorized seeds
            val authorizedSeedsCursor: Cursor? =
                try {
                    Wallet.getAuthorizedSeeds(
                        context,
                        WalletContractV1.AUTHORIZED_SEEDS_ALL_COLUMNS,
                    )
                } catch (e: SecurityException) {
                    Log.w(TAG, "Seed Vault permission not granted - skipping Solana seed fetch", e)
                    return@withContext emptyList()
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "Seed Vault not available on this device - skipping Solana seed fetch", e)
                    return@withContext emptyList()
                }

            authorizedSeedsCursor?.use { cursor ->
                while (cursor.moveToNext()) {
                    val authToken = cursor.getLong(0)
                    val seedName = cursor.getString(2)
                    Log.d(TAG, "Processing seed: authToken=$authToken, name=$seedName")

                    // Mark accounts as user wallets before querying
                    markAccountsAsUserWallets(context, authToken)

                    val accounts = mutableListOf<SolanaSeedVaultAccount>()

                    // Get accounts for this seed - only user wallet accounts
                    val accountsCursor: Cursor? =
                        Wallet.getAccounts(
                            context,
                            authToken,
                            WalletContractV1.ACCOUNTS_ALL_COLUMNS,
                            WalletContractV1.ACCOUNTS_ACCOUNT_IS_USER_WALLET,
                            "1",
                        )

                    accountsCursor?.use { acCursor ->
                        Log.d(TAG, "Found ${acCursor.count} accounts for seed: $seedName")
                        while (acCursor.moveToNext()) {
                            val accountId = acCursor.getLong(0)
                            val derivationPath = acCursor.getString(1)
                            val publicKeyEncoded = acCursor.getString(3)
                            val accountName = acCursor.getString(4)

                            Log.d(TAG, "Account: id=$accountId, name=$accountName, key=${publicKeyEncoded.take(10)}...")

                            accounts.add(
                                SolanaSeedVaultAccount(
                                    address = publicKeyEncoded,
                                    accountName = accountName.ifBlank { null },
                                    derivationPath = derivationPath,
                                    accountId = accountId,
                                ),
                            )
                        }
                    }

                    seeds.add(
                        SolanaSeedInfo(
                            authToken = authToken,
                            name = seedName.ifBlank { "Seed $authToken" },
                            accounts = accounts,
                        ),
                    )
                }
            }

            Log.d(TAG, "Total seeds loaded: ${seeds.size}")
            seeds
        }
    }

    override suspend fun getImportedAddresses(addresses: List<String>): Set<String> {
        val importedAddresses = mutableSetOf<String>()
        for (address in addresses) {
            if (solanaAccountRepository.isAddressExists(address)) {
                importedAddresses.add(address)
            }
        }
        return importedAddresses
    }

    /**
     * Mark accounts as user wallets after a seed is authorized.
     * This is REQUIRED for accounts to appear in getAccounts() queries with
     * ACCOUNTS_ACCOUNT_IS_USER_WALLET filter.
     *
     * This replicates the functionality from fakewallet's MainViewModel.
     *
     * @param context The application context
     * @param authToken The authorization token of the seed
     */
    private suspend fun markAccountsAsUserWallets(
        context: Context,
        authToken: Long,
    ) {
        Log.d(TAG, "Marking accounts as user wallets for authToken: $authToken")

        for (i in FIRST_ACCOUNT_INDEX until MAX_ACCOUNTS_TO_MARK) {
            try {
                val derivationPath =
                    Bip44DerivationPath
                        .newBuilder()
                        .setAccount(BipLevel(i, true))
                        .build()

                val resolvedDerivationPath =
                    Wallet.resolveDerivationPath(
                        context,
                        derivationPath.toUri(),
                        WalletContractV1.PURPOSE_SIGN_SOLANA_TRANSACTION,
                    )

                Log.d(TAG, "Resolved BIP44 path '$derivationPath' to BIP32 path '$resolvedDerivationPath'")

                val cursor: Cursor? =
                    withContext(Dispatchers.IO) {
                        Wallet.getAccounts(
                            context,
                            authToken,
                            arrayOf(
                                WalletContractV1.ACCOUNTS_ACCOUNT_ID,
                                WalletContractV1.ACCOUNTS_ACCOUNT_IS_USER_WALLET,
                            ),
                            WalletContractV1.ACCOUNTS_BIP32_DERIVATION_PATH,
                            resolvedDerivationPath.toString(),
                        )
                    }

                cursor?.use { c ->
                    if (c.moveToNext()) {
                        val accountId = c.getLong(0)
                        val isUserWallet = (c.getShort(1) == 1.toShort())

                        if (!isUserWallet) {
                            Wallet.updateAccountIsUserWallet(context, authToken, accountId, true)
                            Log.d(TAG, "Marked account '$resolvedDerivationPath' as user wallet")
                        } else {
                            Log.d(TAG, "Account '$resolvedDerivationPath' is already marked as user wallet")
                        }
                    } else {
                        Log.w(TAG, "Failed to find expected account '$resolvedDerivationPath'")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to mark account $i as user wallet", e)
            }
        }
    }

    override fun hasUnauthorizedSeeds(): Boolean {
        val context =
            AndroidContextHolder.applicationContext
                ?: return false

        return try {
            Wallet.hasUnauthorizedSeedsForPurpose(
                context,
                WalletContractV1.PURPOSE_SIGN_SOLANA_TRANSACTION,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException checking unauthorized seeds - permission not granted", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for unauthorized seeds", e)
            false
        }
    }
}
