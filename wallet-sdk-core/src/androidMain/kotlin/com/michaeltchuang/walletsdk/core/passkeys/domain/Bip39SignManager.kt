package com.michaeltchuang.walletsdk.core.passkeys.domain

import java.security.KeyPair

interface Bip39SignManager {
    suspend fun sign(address: String, origin: String, userHandle: String, payload: ByteArray): ByteArray?
    suspend fun deriveKeyPair(address: String, origin: String, userHandle: String): KeyPair?
    fun deriveCredentialId(keyPair: KeyPair): ByteArray
}
