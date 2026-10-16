package com.michaeltchuang.walletsdk.core.passkeys.model

import com.michaeltchuang.walletsdk.core.passkeys.domain.model.PublicKeyCredentialRequestOptions

data class GetCredentialsParams(
    val address: String,
    val credId: String,
    val origin: String,
    val request: PublicKeyCredentialRequestOptions,
    val userId: String,
    val username: String,
    val packageName: String,
    val callingAppInfo: String?,
    val clientDataHash: ByteArray?,
    val signingProvider: PasskeySigningProvider,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as GetCredentialsParams

        if (address != other.address) return false
        if (credId != other.credId) return false
        if (origin != other.origin) return false
        if (request != other.request) return false
        if (userId != other.userId) return false
        if (username != other.username) return false
        if (packageName != other.packageName) return false
        if (callingAppInfo != other.callingAppInfo) return false
        if (!clientDataHash.contentEquals(other.clientDataHash)) return false
        if (signingProvider != other.signingProvider) return false

        return true
    }

    override fun hashCode(): Int {
        var result = address.hashCode()
        result = 31 * result + credId.hashCode()
        result = 31 * result + origin.hashCode()
        result = 31 * result + request.hashCode()
        result = 31 * result + userId.hashCode()
        result = 31 * result + username.hashCode()
        result = 31 * result + packageName.hashCode()
        result = 31 * result + (callingAppInfo?.hashCode() ?: 0)
        result = 31 * result + (clientDataHash?.contentHashCode() ?: 0)
        result = 31 * result + signingProvider.hashCode()
        return result
    }
}
