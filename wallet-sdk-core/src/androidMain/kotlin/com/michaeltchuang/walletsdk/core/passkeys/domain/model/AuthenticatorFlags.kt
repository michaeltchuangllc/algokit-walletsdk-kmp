package com.michaeltchuang.walletsdk.core.passkeys.domain.model

data class AuthenticatorFlags(
    val userPresent: Boolean = true,
    val userVerified: Boolean = true,
    val backupEligibility: Boolean = true,
    val backupState: Boolean = true
)
