package com.michaeltchuang.walletsdk.core.passkeys.builder

import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import com.michaeltchuang.walletsdk.core.foundation.utils.AlgoKitResult
import com.michaeltchuang.walletsdk.ui.passkeys.model.CreatePasskeyCredentialCreateEntry
import com.michaeltchuang.walletsdk.ui.passkeys.model.GetPasskeyCredentialEntry

interface PasskeyCreateCredentialEntryBuilder {
    suspend fun buildEntries(
        request: BeginCreateCredentialRequest
    ): AlgoKitResult<List<CreatePasskeyCredentialCreateEntry>>
}

interface PasskeyGetCredentialsEntryBuilder {
    suspend fun buildEntries(request: BeginGetCredentialRequest): AlgoKitResult<List<GetPasskeyCredentialEntry>>
}
