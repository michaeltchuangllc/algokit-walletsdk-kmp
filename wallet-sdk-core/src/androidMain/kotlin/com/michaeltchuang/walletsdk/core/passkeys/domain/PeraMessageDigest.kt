package com.michaeltchuang.walletsdk.core.passkeys.domain

import java.security.MessageDigest

internal object PeraMessageDigest {
    fun getInstance(): MessageDigest = MessageDigest.getInstance("SHA-256")
}
