package com.michaeltchuang.seedvault.sdk.client

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.util.Base64
import com.michaeltchuang.seedvault.sdk.domain.model.SeedVaultLimits
import com.michaeltchuang.seedvault.sdk.domain.model.SolanaAccount
import com.michaeltchuang.seedvault.sdk.domain.model.SolanaSeed
import com.michaeltchuang.seedvault.sdk.domain.model.SolanaVaultSnapshot
import com.solanamobile.seedvault.Bip32DerivationPath
import com.solanamobile.seedvault.Bip44DerivationPath
import com.solanamobile.seedvault.BipLevel
import com.solanamobile.seedvault.Wallet
import com.solanamobile.seedvault.WalletContractV1
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Headless Solana Seed Vault access. Callers launch the returned intents and pass back their results.
 * Provider work runs on [ioDispatcher]; failures and coroutine cancellation propagate to the caller.
 */
class SolanaSeedVaultClient(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val context = context.applicationContext ?: context

    /** Reads authorized seeds, user-wallet accounts, and limits without fetching network balances. */
    suspend fun getSnapshot(): SolanaVaultSnapshot =
        withContext(ioDispatcher) {
            val hasUnauthorizedSeeds = hasUnauthorizedSeeds()
            currentCoroutineContext().ensureActive()
            val seeds =
                checkNotNull(
                    Wallet.getAuthorizedSeeds(context, WalletContractV1.AUTHORIZED_SEEDS_ALL_COLUMNS),
                ) { "Seed Vault returned no authorized-seeds cursor" }.use { cursor ->
                    val tokenColumn = cursor.getColumnIndexOrThrow(WalletContractV1.AUTHORIZED_SEEDS_AUTH_TOKEN)
                    val purposeColumn = cursor.getColumnIndexOrThrow(WalletContractV1.AUTHORIZED_SEEDS_AUTH_PURPOSE)
                    val nameColumn = cursor.getColumnIndexOrThrow(WalletContractV1.AUTHORIZED_SEEDS_SEED_NAME)
                    val backedUpColumn = cursor.getColumnIndex(WalletContractV1.AUTHORIZED_SEEDS_IS_BACKED_UP)
                    buildList {
                        while (cursor.moveToNext()) {
                            currentCoroutineContext().ensureActive()
                            val authToken = cursor.getLong(tokenColumn)
                            add(
                                SolanaSeed(
                                    authToken = authToken,
                                    name = cursor.getString(nameColumn).orEmpty().ifBlank { authToken.toString() },
                                    authPurpose = cursor.getInt(purposeColumn),
                                    isBackedUp = backedUpColumn >= 0 && cursor.getInt(backedUpColumn) == 1,
                                    accounts = getAccounts(authToken),
                                ),
                            )
                        }
                    }
                }
            currentCoroutineContext().ensureActive()
            SolanaVaultSnapshot(seeds, hasUnauthorizedSeeds, getLimits())
        }

    /**
     * Marks the selected hardened BIP44 accounts as user wallets, resolving each to a BIP32 path.
     * Missing accounts fail rather than silently succeeding; earlier updates are not rolled back.
     */
    suspend fun markAccountsAsUserWallets(
        authToken: Long,
        accountIndices: IntRange = 0..1,
    ) {
        require(accountIndices.isEmpty() || accountIndices.first >= 0) { "Account indices must be non-negative" }
        withContext(ioDispatcher) {
            for (index in accountIndices) {
                currentCoroutineContext().ensureActive()
                val path =
                    Bip44DerivationPath
                        .newBuilder()
                        .setAccount(BipLevel(index, true))
                        .build()
                        .toUri()
                val resolvedPath =
                    Wallet.resolveDerivationPath(
                        context,
                        path,
                        WalletContractV1.PURPOSE_SIGN_SOLANA_TRANSACTION,
                    )
                currentCoroutineContext().ensureActive()
                checkNotNull(
                    Wallet.getAccounts(
                        context,
                        authToken,
                        arrayOf(
                            WalletContractV1.ACCOUNTS_ACCOUNT_ID,
                            WalletContractV1.ACCOUNTS_ACCOUNT_IS_USER_WALLET,
                        ),
                        WalletContractV1.ACCOUNTS_BIP32_DERIVATION_PATH,
                        resolvedPath.toString(),
                    ),
                ) { "Seed Vault returned no account cursor" }.use { cursor ->
                    check(cursor.moveToNext()) { "Seed Vault account was not found" }
                    val accountId = cursor.getLong(cursor.getColumnIndexOrThrow(WalletContractV1.ACCOUNTS_ACCOUNT_ID))
                    val isUserWallet =
                        cursor.getInt(
                            cursor.getColumnIndexOrThrow(WalletContractV1.ACCOUNTS_ACCOUNT_IS_USER_WALLET),
                        ) == 1
                    currentCoroutineContext().ensureActive()
                    if (!isUserWallet) {
                        vendorCall { Wallet.updateAccountIsUserWallet(context, authToken, accountId, true) }
                    }
                }
            }
        }
    }

    /** Cold, conflated invalidations; collection registers an observer and cancellation removes it. */
    fun observeChanges(): Flow<Unit> =
        callbackFlow {
            val observer =
                object : ContentObserver(null) {
                    override fun onChange(selfChange: Boolean) {
                        trySend(Unit)
                    }

                    override fun onChange(
                        selfChange: Boolean,
                        uri: Uri?,
                    ) {
                        trySend(Unit)
                    }

                    override fun onChange(
                        selfChange: Boolean,
                        uri: Uri?,
                        flags: Int,
                    ) {
                        trySend(Unit)
                    }

                    override fun onChange(
                        selfChange: Boolean,
                        uris: Collection<Uri>,
                        flags: Int,
                    ) {
                        trySend(Unit)
                    }
                }
            val resolver = context.contentResolver
            resolver.registerContentObserver(WalletContractV1.WALLET_PROVIDER_CONTENT_URI_BASE, true, observer)
            awaitClose { resolver.unregisterContentObserver(observer) }
        }.conflate().flowOn(ioDispatcher)

    /** Creates an authorization intent without launching it. */
    fun authorizeSeedIntent(): Intent = Wallet.authorizeSeed(context, WalletContractV1.PURPOSE_SIGN_SOLANA_TRANSACTION)

    /** Creates a single-account transaction-signing intent from a base64 serialized Solana message. */
    fun signingIntent(
        messageBase64: String,
        authToken: Long,
        derivationPath: Uri,
    ): Intent {
        val message = Base64.decode(messageBase64, Base64.DEFAULT)
        require(message.isNotEmpty()) { "Signing message must not be empty" }
        return Wallet.signTransaction(context, authToken, derivationPath, message)
    }

    /** Returns the authorization token or throws if authorization failed or returned invalid data. */
    fun authorizationResult(
        resultCode: Int,
        data: Intent?,
    ): Long = vendorCall { Wallet.onAuthorizeSeedResult(resultCode, data) }

    /** Returns one 64-byte signature as unwrapped base64; rejects missing or multiple results. */
    fun signatureResult(
        resultCode: Int,
        data: Intent?,
    ): String {
        val responses = vendorCall { Wallet.onSignTransactionsResult(resultCode, data) }
        check(responses.size == 1) { "Expected exactly one signing response" }
        val response = checkNotNull(responses.single()) { "Missing signing response" }
        val signatures = checkNotNull(response.signatures) { "Missing signatures" }
        check(signatures.size == 1) { "Expected exactly one signature" }
        val signature = checkNotNull(signatures.single()) { "Missing signature" }
        check(signature.size == SOLANA_SIGNATURE_BYTES) { "Expected a 64-byte Solana signature" }
        return Base64.encodeToString(signature, Base64.NO_WRAP)
    }

    private suspend fun getAccounts(authToken: Long): List<SolanaAccount> =
        checkNotNull(
            Wallet.getAccounts(
                context,
                authToken,
                WalletContractV1.ACCOUNTS_ALL_COLUMNS,
                WalletContractV1.ACCOUNTS_ACCOUNT_IS_USER_WALLET,
                "1",
            ),
        ) { "Seed Vault returned no accounts cursor" }.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(WalletContractV1.ACCOUNTS_ACCOUNT_ID)
            val pathColumn = cursor.getColumnIndexOrThrow(WalletContractV1.ACCOUNTS_BIP32_DERIVATION_PATH)
            val keyColumn = cursor.getColumnIndexOrThrow(WalletContractV1.ACCOUNTS_PUBLIC_KEY_ENCODED)
            val nameColumn = cursor.getColumnIndexOrThrow(WalletContractV1.ACCOUNTS_ACCOUNT_NAME)
            buildList {
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val publicKey = checkNotNull(cursor.getString(keyColumn)) { "Account has no public key" }
                    add(
                        SolanaAccount(
                            accountId = cursor.getLong(idColumn),
                            name = cursor.getString(nameColumn).orEmpty().ifBlank { publicKey.take(10) },
                            derivationPath =
                                Uri.parse(
                                    checkNotNull(cursor.getString(pathColumn)) { "Account has no derivation path" },
                                ),
                            publicKeyEncoded = publicKey,
                        ),
                    )
                }
            }
        }

    private fun hasUnauthorizedSeeds(): Boolean =
        checkNotNull(
            context.contentResolver.query(
                ContentUris.withAppendedId(
                    WalletContractV1.UNAUTHORIZED_SEEDS_CONTENT_URI,
                    WalletContractV1.PURPOSE_SIGN_SOLANA_TRANSACTION.toLong(),
                ),
                WalletContractV1.UNAUTHORIZED_SEEDS_ALL_COLUMNS,
                null,
                null,
                null,
            ),
        ) { "Seed Vault returned no unauthorized-seeds cursor" }.use { cursor ->
            check(cursor.moveToFirst()) { "Seed Vault returned no unauthorized-seeds status" }
            val column = cursor.getColumnIndexOrThrow(WalletContractV1.UNAUTHORIZED_SEEDS_HAS_UNAUTHORIZED_SEEDS)
            check(!cursor.isNull(column)) { "Missing unauthorized-seeds status" }
            cursor.getInt(column) != 0
        }

    private fun getLimits(): SeedVaultLimits {
        // Query directly so the cursor is also closed for empty or malformed provider results.
        return checkNotNull(
            context.contentResolver.query(
                ContentUris.withAppendedId(
                    WalletContractV1.IMPLEMENTATION_LIMITS_CONTENT_URI,
                    WalletContractV1.PURPOSE_SIGN_SOLANA_TRANSACTION.toLong(),
                ),
                WalletContractV1.IMPLEMENTATION_LIMITS_ALL_COLUMNS,
                null,
                null,
                null,
            ),
        ) { "Seed Vault returned no implementation-limits cursor" }.use { cursor ->
            check(cursor.moveToNext()) { "Seed Vault returned no implementation limits" }

            fun limit(column: String): Int {
                val index = cursor.getColumnIndexOrThrow(column)
                check(!cursor.isNull(index)) { "Missing Seed Vault limit: $column" }
                val value = cursor.getLong(index)
                check(value in 0..Int.MAX_VALUE.toLong()) { "Invalid Seed Vault limit: $column" }
                return value.toInt()
            }
            val maxSigningRequests = limit(WalletContractV1.IMPLEMENTATION_LIMITS_MAX_SIGNING_REQUESTS)
            val maxRequestedSignatures = limit(WalletContractV1.IMPLEMENTATION_LIMITS_MAX_REQUESTED_SIGNATURES)
            val maxRequestedPublicKeys = limit(WalletContractV1.IMPLEMENTATION_LIMITS_MAX_REQUESTED_PUBLIC_KEYS)
            val lastIndex = FIRST_REQUESTED_PUBLIC_KEY_INDEX.toLong() + maxRequestedPublicKeys - 1
            check(lastIndex <= Int.MAX_VALUE) { "Public-key request range exceeds the BIP32 index limit" }
            SeedVaultLimits(
                maxSigningRequests = maxSigningRequests,
                maxRequestedSignatures = maxRequestedSignatures,
                maxRequestedPublicKeys = maxRequestedPublicKeys,
                firstRequestedPublicKey = if (maxRequestedPublicKeys == 0) "" else publicKeyPath(FIRST_REQUESTED_PUBLIC_KEY_INDEX),
                lastRequestedPublicKey = if (maxRequestedPublicKeys == 0) "" else publicKeyPath(lastIndex.toInt()),
            )
        }
    }

    private fun publicKeyPath(index: Int): String =
        Bip32DerivationPath
            .newBuilder()
            .appendLevel(BipLevel(index, true))
            .build()
            .toUri()
            .toString()

    private inline fun <T> vendorCall(block: () -> T): T =
        try {
            block()
        } catch (error: Wallet.ActionFailedException) {
            throw IllegalStateException("Seed Vault action failed", error)
        } catch (error: Wallet.NotModifiedException) {
            throw IllegalStateException("Seed Vault account update failed", error)
        }

    companion object {
        const val PERMISSION_ACCESS_SEED_VAULT: String = WalletContractV1.PERMISSION_ACCESS_SEED_VAULT
        const val PACKAGE: String = WalletContractV1.PACKAGE_SEED_VAULT

        private const val FIRST_REQUESTED_PUBLIC_KEY_INDEX = 1000
        private const val SOLANA_SIGNATURE_BYTES = 64

        /** Checks installation on a supported OS; callers must declare package visibility for [PACKAGE]. */
        fun isAvailable(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
            return try {
                context.packageManager.getPackageInfo(PACKAGE, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }

        /** Accepts either the regular or privileged Seed Vault permission. */
        fun hasPermission(context: Context): Boolean =
            context.checkSelfPermission(PERMISSION_ACCESS_SEED_VAULT) == PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(
                    WalletContractV1.PERMISSION_ACCESS_SEED_VAULT_PRIVILEGED,
                ) == PackageManager.PERMISSION_GRANTED
    }
}
