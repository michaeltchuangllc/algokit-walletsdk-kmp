package com.michaeltchuang.walletsdk.core.passkeys.builder

import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.provider.BeginCreateCredentialRequest
import com.michaeltchuang.walletsdk.core.account.domain.model.local.HdSeedFirstAddress
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.custom.GetHdSeedCustomName
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAllHdSeedFirstAddresses
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccounts
import com.michaeltchuang.walletsdk.core.foundation.utils.AlgoKitResult
import com.michaeltchuang.walletsdk.core.foundation.utils.Chain
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.PublicKeyCredentialCreationOptions
import com.michaeltchuang.walletsdk.core.passkeys.domain.usecase.GetSitePasskeyCount
import com.michaeltchuang.walletsdk.core.passkeys.model.CreatePasskeyCredentialCreateEntry
import com.michaeltchuang.walletsdk.core.passkeys.model.PasskeySigningProvider
import io.github.aakira.napier.Napier

class DefaultPasskeyCreateCredentialEntryBuilder(
    private val getAllHdSeedFirstAddresses: GetAllHdSeedFirstAddresses,
    private val getLocalAccounts: GetLocalAccounts,
    private val getSitePasskeyCount: GetSitePasskeyCount,
    private val getHdSeedCustomName: GetHdSeedCustomName,
) : PasskeyCreateCredentialEntryBuilder {
    override suspend fun buildEntries(request: BeginCreateCredentialRequest): AlgoKitResult<List<CreatePasskeyCredentialCreateEntry>> {
        val hdSeedsAddresses = getAllHdSeedFirstAddresses()
        val seedVaultAccounts = getLocalAccounts().filterIsInstance<LocalAccount.SeedVault>()

        val requestedAddress = getRequestedAddress(request)
        val keystoreAccounts =
            getLocalAccounts().filter {
                it is LocalAccount.Falcon24 || it is LocalAccount.Falcon25 || it is LocalAccount.Algo25
            }

        Napier.d(
            "requested user.name=$requestedAddress, hd=${hdSeedsAddresses.size}, seedVault=${seedVaultAccounts.size}, " +
                "keystoreAccounts=${keystoreAccounts.size}",
            tag = TAG,
        )

        val entries =
            (createEntries(request, hdSeedsAddresses, seedVaultAccounts) + keystoreAccounts.map { keystoreEntry(request, it) })
                .distinctBy { it.address }
                .let { all ->
                    // Liquid Auth sets user.name to the account being registered: offer only that account.
                    val match = requestedAddress?.let { address -> all.firstOrNull { it.address == address } }
                    when {
                        match != null -> listOf(match)
                        else -> all
                    }
                }
        return if (entries.isEmpty()) {
            AlgoKitResult.Error(CreateCredentialNoCreateOptionException("No HD or Seed Vault signing account is available"))
        } else {
            AlgoKitResult.Success(entries)
        }
    }

    private suspend fun createEntries(
        request: BeginCreateCredentialRequest,
        hdSeedsAddresses: List<HdSeedFirstAddress>,
        seedVaultAccounts: List<LocalAccount.SeedVault>,
    ): List<CreatePasskeyCredentialCreateEntry> {
        val registeredRelyingPartyPasskeyCount = getPasskeyCount(request)

        val hdEntries =
            hdSeedsAddresses.map { hdSeed ->
                CreatePasskeyCredentialCreateEntry(
                    accountName = getHdSeedCustomName(hdSeed.seedId)?.takeIf { it.isNotBlank() } ?: "HD Wallet ${hdSeed.seedId}",
                    passkeyCount = registeredRelyingPartyPasskeyCount,
                    address = hdSeed.firstAddress,
                    signingProvider = PasskeySigningProvider.BIP39_DETERMINISTIC,
                )
            }

        val seedVaultEntries =
            seedVaultAccounts.map { seedVault ->
                CreatePasskeyCredentialCreateEntry(
                    accountName = seedVault.accountName?.takeIf { it.isNotBlank() } ?: seedVault.address,
                    passkeyCount = registeredRelyingPartyPasskeyCount,
                    address = seedVault.address,
                    signingProvider =
                        if (seedVault.chainId == Chain.ALGORAND.id) {
                            PasskeySigningProvider.SEED_VAULT_DERIVED
                        } else {
                            PasskeySigningProvider.ANDROID_KEYSTORE_SECP256R1
                        },
                )
            }

        return (hdEntries + seedVaultEntries).distinctBy { it.address }
    }

    private suspend fun keystoreEntry(
        request: BeginCreateCredentialRequest,
        account: LocalAccount,
    ) = CreatePasskeyCredentialCreateEntry(
        accountName = account.address,
        passkeyCount = getPasskeyCount(request),
        address = account.address,
        signingProvider = PasskeySigningProvider.BIP39_DETERMINISTIC,
    )

    private fun getRequestedAddress(request: BeginCreateCredentialRequest): String? = getCredentialOptions(request)?.user?.name

    private suspend fun getPasskeyCount(request: BeginCreateCredentialRequest): Int {
        val credentialOptions = getCredentialOptions(request)
        val relyingPartyUrl = credentialOptions?.rp?.id ?: return 0
        return getSitePasskeyCount(relyingPartyUrl)
    }

    private fun getCredentialOptions(request: BeginCreateCredentialRequest): PublicKeyCredentialCreationOptions? =
        try {
            val requestJson = request.candidateQueryData.getString(BUNDLE_KEY)
            PublicKeyCredentialCreationOptions(requestJson.orEmpty())
        } catch (_: Exception) {
            null
        }

    private companion object {
        const val TAG = "PasskeyCreateEntries"
        const val BUNDLE_KEY = "androidx.credentials.BUNDLE_KEY_REQUEST_JSON"
    }
}
