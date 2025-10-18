package com.michaeltchuang.walletsdk.core.encryption

actual fun encryptByteArray(data: ByteArray): ByteArray {
    return data
}

actual fun decryptByteArray(encryptedData: ByteArray): ByteArray {
    return encryptedData
}

actual fun encryptString(data: String): String {
    return data
}

actual fun decryptString(encryptedData: String): String {
    return encryptedData
}
actual suspend fun initializeEncryptionManager(){}