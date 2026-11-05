package com.michaeltchuang.walletsdk.core.passkeys.domain

import android.content.Context
import android.content.Intent
import app.perawallet.deterministicP256.DeterministicP256
import com.michaeltchuang.seedvault.sdk.SeedVaultServiceConstants
import com.michaeltchuang.seedvault.sdk.client.SeedVaultServiceClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.KeyPair

/**
 * Starts a Seed Vault approval screen and returns whether the user approved. Set by the screen that
 * is showing (it needs an activity result launcher); null when none is.
 */
fun interface SeedVaultApprover {
    suspend fun approve(intent: Intent): Boolean
}

/** Holds the [SeedVaultApprover] of the visible passkey screen. */
class SeedVaultApproverHolder {
    @Volatile
    var approver: SeedVaultApprover? = null
}

/**
 * Passkey keys for Algorand Seed Vault accounts. The Seed Vault derives secret bytes for the account
 * (see `deriveKeyMaterial` in the Seed Vault SDK) and the P-256 key comes from them with the same
 * [DeterministicP256] step as the other accounts. The derived bytes and the key are never stored, so
 * every sign-in asks Seed Vault again (and the user, unless the account is an autosign account).
 */
class SeedVaultPasskeyKeyManager(
    context: Context,
    private val approverHolder: SeedVaultApproverHolder,
    private val deterministicSigner: DeterministicP256,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val applicationContext = context.applicationContext

    suspend fun sign(
        address: String,
        origin: String,
        userHandle: String,
        payload: ByteArray,
    ): ByteArray? = deriveKeyPair(address, origin, userHandle)?.let { deterministicSigner.signWithDomainSpecificKeyPair(it, payload) }

    suspend fun deriveKeyPair(
        address: String,
        origin: String,
        userHandle: String,
    ): KeyPair? =
        withContext(dispatcher) {
            val client = SeedVaultServiceClient(applicationContext)
            try {
                checkNotNull(withTimeoutOrNull(BIND_TIMEOUT_MILLIS) { client.bindAsync() }) {
                    "Timed out connecting to Seed Vault."
                }
                val account =
                    checkNotNull(client.getAuthorizedSeeds().flatMap { it.accounts }.firstOrNull { it.address == address }) {
                        "This account is no longer authorized in Seed Vault."
                    }
                check(account.supportsKeyDerivation) {
                    "Update the Seed Vault app to use passkeys with this account."
                }
                val info = info(origin, userHandle)
                if (!(account.isAutosign && !account.requiresApproval)) {
                    val approver = checkNotNull(approverHolder.approver) { "No screen is available to ask for Seed Vault approval." }
                    check(approver.approve(client.confirmDeriveKeyMaterialIntent(address, DOMAIN, info, LENGTH))) {
                        "Seed Vault approval was cancelled."
                    }
                }
                val material =
                    checkNotNull(client.deriveKeyMaterial(address, DOMAIN, info, LENGTH)) { "Seed Vault could not derive the key." }
                try {
                    deterministicSigner.genDomainSpecificKeypair(material, origin, userHandle)
                } finally {
                    material.fill(0)
                }
            } finally {
                client.unbind()
            }
        }

    private fun info(
        origin: String,
        userHandle: String,
    ) = (origin + "\u0000" + userHandle).encodeToByteArray()

    private companion object {
        const val DOMAIN = SeedVaultServiceConstants.DERIVE_DOMAIN_PREFIX + "v1"
        const val LENGTH = 64
        const val BIND_TIMEOUT_MILLIS = 10_000L
    }
}
