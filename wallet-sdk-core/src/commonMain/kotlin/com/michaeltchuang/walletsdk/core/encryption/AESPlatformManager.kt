package com.michaeltchuang.walletsdk.core.encryption

expect fun encryptByteArray(data: ByteArray): ByteArray

expect fun decryptByteArray(encryptedData: ByteArray): ByteArray

expect fun encryptString(data: String): String

expect fun decryptString(encryptedData: String): String

expect suspend fun initializeEncryptionManager()