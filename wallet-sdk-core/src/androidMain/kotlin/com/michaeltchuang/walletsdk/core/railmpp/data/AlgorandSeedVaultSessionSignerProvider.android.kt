@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package com.michaeltchuang.walletsdk.core.railmpp.data

import com.michaeltchuang.seedvault.sdk.client.SeedVaultServiceClient
import com.michaeltchuang.seedvault.sdk.domain.model.SeedVaultAccount
import com.michaeltchuang.walletsdk.core.network.domain.AndroidContextHolder
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSignerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.io.encoding.Base64

internal actual fun createAlgorandSeedVaultSessionSignerProvider(): AlgorandSeedVaultSessionSignerProvider =
    AlgorandSeedVaultSessionSignerProvider { ownerAddress ->
        withSeedVaultClient { client ->
            val account = client.sessionAccount(ownerAddress) ?: return@withSeedVaultClient null
            val publicKey = Base64.decode(account.publicKey)
            check(publicKey.size == 1793) { "Invalid Seed Vault Falcon session public key" }
            check(account.address != ownerAddress) { "Session key must differ from its owner" }
            object : MppWalletSigner {
                override val address: String = account.address
                override val authorizedSignerPublicKey: ByteArray
                    get() = publicKey.copyOf()
                override val signerType: MppWalletSignerType = MppWalletSignerType.FALCON_NATIVE

                override suspend fun signMessage(message: ByteArray): ByteArray = sign { it.signBytes(address, message) }

                override suspend fun signTransactionBytes(txnMsgpack: ByteArray): ByteArray =
                    sign { it.signTransaction(address, txnMsgpack) }

                private suspend fun sign(operation: (SeedVaultServiceClient) -> ByteArray?): ByteArray =
                    withSeedVaultClient { signingClient ->
                        val current =
                            checkNotNull(signingClient.sessionAccount(ownerAddress)) {
                                "Seed Vault session account is no longer authorized"
                            }
                        current.requireAutosign()
                        check(current.address == address && current.publicKey == account.publicKey) {
                            "Seed Vault session account changed"
                        }
                        checkNotNull(operation(signingClient)) { "Seed Vault session signing failed" }.also {
                            check(it.isNotEmpty()) { "Seed Vault session signing returned empty bytes" }
                        }
                    }
            }
        }
    }

private fun SeedVaultServiceClient.sessionAccount(ownerAddress: String): SeedVaultAccount? =
    getAuthorizedSeeds()
        .firstOrNull { seed -> seed.accounts.any { it.index == 0 && it.address == ownerAddress } }
        ?.accounts
        ?.firstOrNull { it.index == 1 }

private fun SeedVaultAccount.requireAutosign() {
    check(isAutosign && !requiresApproval) {
        "Enable index-1 autosigning in Seed Vault to use its session key without approval"
    }
}

private suspend fun <T> withSeedVaultClient(block: (SeedVaultServiceClient) -> T): T =
    withContext(Dispatchers.IO) {
        val context = checkNotNull(AndroidContextHolder.applicationContext) { "Android application context is unavailable" }
        val client = SeedVaultServiceClient(context)
        try {
            checkNotNull(withTimeoutOrNull(10_000) { client.bindAsync() }) { "Timed out connecting to Seed Vault" }
            block(client)
        } finally {
            client.unbind()
        }
    }
