package com.michaeltchuang.walletsdk.core.passkeys.domain.usecase

import com.michaeltchuang.walletsdk.core.passkeys.domain.model.PublicKeyCredentialCreationOptions
import com.michaeltchuang.walletsdk.core.passkeys.model.Passkey
import kotlinx.coroutines.flow.Flow

fun interface GetAllPasskeysAsFlow {
    operator fun invoke(): Flow<List<Passkey>>
}

fun interface RemovePasskeyByCredentialId {
    suspend operator fun invoke(credId: String)
}

fun interface ClearAllPasskeys {
    suspend operator fun invoke()
}

fun interface GetSitePasskeyCount {
    suspend operator fun invoke(url: String): Int
}

fun interface GetSitePasskeys {
    suspend operator fun invoke(url: String): List<Passkey>
}

fun interface AddNewPasskey {
    suspend operator fun invoke(
        algoAddress: String,
        requestOptions: PublicKeyCredentialCreationOptions,
        credId: ByteArray
    )
}

fun interface GetCredentialBySiteUseCase {
    suspend operator fun invoke(site: String): String?
}

fun interface GetPasskeyByCredentialId {
    suspend operator fun invoke(credentialId: String): Passkey?
}

fun interface SetPasskeyLastUsedTime {
    suspend operator fun invoke(credId: String, lastUsed: Long)
}

fun interface DoesPasskeyExist {
    suspend operator fun invoke(rpId: String, username: String, algoAddress: String): Boolean
}
