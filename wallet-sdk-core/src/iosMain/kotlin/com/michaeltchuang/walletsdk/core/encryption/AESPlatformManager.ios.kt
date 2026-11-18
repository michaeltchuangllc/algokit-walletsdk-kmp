package com.michaeltchuang.walletsdk.core.encryption

import AlgorandIosSdk.IosEncryptionManager
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private var isEncryptionManagerCreated = false

@OptIn(ExperimentalForeignApi::class)
private val encryptionManager: IosEncryptionManager by lazy {
    isEncryptionManagerCreated = true
    IosEncryptionManager()
}

/**
 * Sets the keychain access group that holds the key encrypting stored accounts. Defaults to
 * `group.com.michaeltchuang.walletsdk.demo`. Pass an App Group the app (and any extension that
 * reads accounts) lists under `com.apple.security.application-groups`, or a full keychain group
 * including the team ID prefix from `keychain-access-groups`. Call once at launch, before
 * any account is created, restored or read. Changing it for an existing install loses access to
 * the accounts already stored.
 */
@OptIn(ExperimentalForeignApi::class)
fun setKeychainAccessGroup(group: String) {
    check(!isEncryptionManagerCreated) { "setKeychainAccessGroup must be called before any encryption" }
    IosEncryptionManager.setKeychainAccessGroup(group)
}

class EncryptionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
actual fun encryptByteArray(data: ByteArray): ByteArray {
    val dataBase64 = Base64.encode(data)
    val encryptedBase64 = encryptionManager.encryptString(dataBase64)

    if (encryptedBase64.isEmpty()) {
        throw EncryptionException("Encryption failed: returned empty string")
    }

    return try {
        Base64.decode(encryptedBase64)
    } catch (e: Exception) {
        throw EncryptionException("Failed to decode encrypted data", e)
    }
}

@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
actual fun decryptByteArray(encryptedData: ByteArray): ByteArray {
    val encryptedBase64 = Base64.encode(encryptedData)
    val decryptedBase64 = encryptionManager.decryptString(encryptedBase64)

    if (decryptedBase64.isEmpty()) {
        throw EncryptionException("Decryption failed: returned empty string")
    }
    return try {
        Base64.decode(decryptedBase64)
    } catch (e: Exception) {
        throw EncryptionException("Failed to decode decrypted data", e)
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun encryptString(data: String): String {
    val encrypted = encryptionManager.encryptString(data)

    if (encrypted.isEmpty()) {
        throw EncryptionException("String encryption failed: returned empty string")
    }

    return encrypted
}

@OptIn(ExperimentalForeignApi::class)
actual fun decryptString(encryptedData: String): String {
    val decrypted = encryptionManager.decryptString(encryptedData)

    if (decrypted.isEmpty() && encryptedData.isNotEmpty()) {
        throw EncryptionException("String decryption failed: returned empty string for non-empty input")
    }

    return decrypted
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun initializeEncryptionManager() {
    try {
        encryptionManager.initializeEncryptionManager()
    } catch (e: Exception) {
        throw EncryptionException("Failed to initialize encryption manager", e)
    }
}
