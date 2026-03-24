package com.michaeltchuang.walletsdk.core.passkeys.builder

import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.provider.BeginCreateCredentialRequest
import com.michaeltchuang.walletsdk.core.account.domain.model.local.HdSeedFirstAddress
import com.michaeltchuang.walletsdk.core.account.domain.usecase.custom.GetHdSeedCustomName
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAllHdSeedFirstAddresses
import com.michaeltchuang.walletsdk.core.foundation.utils.AlgoKitResult
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.PublicKeyCredentialCreationOptions
import com.michaeltchuang.walletsdk.core.passkeys.domain.usecase.GetSitePasskeyCount
import com.michaeltchuang.walletsdk.core.passkeys.model.CreatePasskeyCredentialCreateEntry
import com.michaeltchuang.walletsdk.core.passkeys.model.PasskeySigningProvider

class DefaultPasskeyCreateCredentialEntryBuilder(
    private val getAllHdSeedFirstAddresses: GetAllHdSeedFirstAddresses,
    private val getSitePasskeyCount: GetSitePasskeyCount,
    private val getHdSeedCustomName: GetHdSeedCustomName,
) : PasskeyCreateCredentialEntryBuilder {
    override suspend fun buildEntries(request: BeginCreateCredentialRequest): AlgoKitResult<List<CreatePasskeyCredentialCreateEntry>> {
        val hdSeedsAddresses = getAllHdSeedFirstAddresses()
        return if (hdSeedsAddresses.isEmpty()) {
            AlgoKitResult.Error(CreateCredentialNoCreateOptionException())
        } else {
            AlgoKitResult.Success(createEntries(request, hdSeedsAddresses))
        }
    }

    private suspend fun createEntries(
        request: BeginCreateCredentialRequest,
        hdSeedsAddresses: List<HdSeedFirstAddress>,
    ): List<CreatePasskeyCredentialCreateEntry> {
        val registeredRelyingPartyPasskeyCount = getPasskeyCount(request)
        return hdSeedsAddresses.map { hdSeed ->
            CreatePasskeyCredentialCreateEntry(
                accountName = getHdSeedCustomName(hdSeed.seedId).orEmpty(),
                passkeyCount = registeredRelyingPartyPasskeyCount,
                address = hdSeed.firstAddress,
                signingProvider = PasskeySigningProvider.BIP39_DETERMINISTIC,
            )
        }
    }

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
        const val BUNDLE_KEY = "androidx.credentials.BUNDLE_KEY_REQUEST_JSON"
    }
}
