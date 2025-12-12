package com.michaeltchuang.walletsdk.core.passkeys.domain

import java.security.MessageDigest

internal object PeraMessageDigest {

    fun getInstance(): MessageDigest {
        return MessageDigest.getInstance("SHA-256")
    }
}
