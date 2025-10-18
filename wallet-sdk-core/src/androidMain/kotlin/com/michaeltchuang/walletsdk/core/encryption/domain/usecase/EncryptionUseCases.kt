package com.michaeltchuang.walletsdk.core.encryption.domain.usecase

import javax.crypto.SecretKey

fun interface GetStrongBoxUsedCheck {
    suspend operator fun invoke(): Boolean
}

fun interface SaveStrongBoxUsedCheck {
    suspend operator fun invoke(check: Boolean)
}

fun interface GetEncryptionSecretKey {
    operator fun invoke(): SecretKey
}
