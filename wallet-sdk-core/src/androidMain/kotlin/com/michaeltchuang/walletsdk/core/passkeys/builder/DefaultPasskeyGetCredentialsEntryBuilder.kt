package com.michaeltchuang.walletsdk.core.passkeys.builder

import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import com.michaeltchuang.walletsdk.core.foundation.utils.AlgoKitResult
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.Passkey
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.PublicKeyCredentialRequestOptions
import com.michaeltchuang.walletsdk.core.passkeys.domain.usecase.GetSitePasskeys
import com.michaeltchuang.walletsdk.ui.passkeys.model.GetPasskeyCredentialEntry

class DefaultPasskeyGetCredentialsEntryBuilder constructor(
    private val getSitePasskeys: GetSitePasskeys,
) : PasskeyGetCredentialsEntryBuilder {
    override suspend fun buildEntries(request: BeginGetCredentialRequest): AlgoKitResult<List<GetPasskeyCredentialEntry>> {
        val entries =
            request.beginGetCredentialOptions
                .filterIsInstance<BeginGetPublicKeyCredentialOption>()
                .mapNotNull { option -> getEntries(option) }
                .flatten()
        return if (entries.isEmpty()) {
            AlgoKitResult.Error(NoCredentialException())
        } else {
            AlgoKitResult.Success(entries)
        }
    }

    private suspend fun getEntries(option: BeginGetPublicKeyCredentialOption): List<GetPasskeyCredentialEntry>? {
        val siteUrl = PublicKeyCredentialRequestOptions(option.requestJson).rpId
        if (siteUrl.isBlank()) {
            return null
        }
        val passkeys = getSitePasskeys(siteUrl)
        return if (passkeys.isEmpty()) {
            null
        } else {
            mapToEntries(option, passkeys)
        }
    }

    private fun mapToEntries(
        option: BeginGetPublicKeyCredentialOption,
        passkeys: List<Passkey>,
    ): List<GetPasskeyCredentialEntry> =
        passkeys.map { passkey ->
            GetPasskeyCredentialEntry(
                option = option,
                credentialId = passkey.credId,
                username = passkey.username,
                userDisplayName = passkey.displayName,
            )
        }
}
