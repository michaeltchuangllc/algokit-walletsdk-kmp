package com.michaeltchuang.walletsdk.core.passkeys.validator.domain.usecase

import android.content.pm.SigningInfo
import androidx.credentials.provider.CallingAppInfo
import com.michaeltchuang.walletsdk.core.passkeys.domain.PeraMessageDigest
import com.michaeltchuang.walletsdk.core.passkeys.validator.domain.repository.AppInfoValidationRepository
import com.michaeltchuang.walletsdk.core.foundation.utils.AlgoKitResult


internal class IsAssetLinksValidUseCase constructor(
    private val appInfoValidationRepository: AppInfoValidationRepository
) : IsAssetLinksValid {

    override suspend fun invoke(rpId: String, callingAppInfo: CallingAppInfo): AlgoKitResult<Boolean> {
        val websiteUrl = getWebsiteUrl(rpId)
        return appInfoValidationRepository.getAssetLinkCheckResult(
            url = websiteUrl,
            pkgName = callingAppInfo.packageName,
            certId = computeLatestCertification(callingAppInfo.signingInfo).orEmpty()
        ).map { it.isLinked }
    }

    private fun getWebsiteUrl(rpId: String): String {
        val protocol = "https://"
        return if (rpId.startsWith(protocol)) rpId else "${protocol}$rpId"
    }

    private fun computeLatestCertification(callerSigningInfo: SigningInfo): String? {
        if (callerSigningInfo.hasMultipleSigners()) {
            return null
        }
        return computeNormalizedSha256Fingerprint(callerSigningInfo.signingCertificateHistory[0].toByteArray())
    }

    private fun computeNormalizedSha256Fingerprint(signature: ByteArray): String {
        val md = PeraMessageDigest.getInstance()
        return bytesToHexString(md.digest(signature))
    }

    private fun bytesToHexString(bytes: ByteArray): String {
        return bytes.joinToString(":") { "%02X".format(it) }
    }
}
