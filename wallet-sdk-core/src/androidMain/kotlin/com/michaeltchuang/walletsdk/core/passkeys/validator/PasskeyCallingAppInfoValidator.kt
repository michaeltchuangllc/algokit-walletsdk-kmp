
package com.michaeltchuang.walletsdk.core.passkeys.validator

import androidx.credentials.provider.CallingAppInfo
import com.michaeltchuang.walletsdk.core.passkeys.domain.WebAuthnUtils
import com.michaeltchuang.walletsdk.core.passkeys.validator.domain.usecase.GetCallingAppOriginCheckingGpmAllowlist
import com.michaeltchuang.walletsdk.core.passkeys.validator.domain.usecase.IsAssetLinksValid

internal class PasskeyCallingAppInfoValidator(
    private val isAssetLinksValid: IsAssetLinksValid,
    private val getCallingAppOriginCheckingGpmAllowlist: GetCallingAppOriginCheckingGpmAllowlist,
) : CallingAppInfoValidator {
    override suspend fun validateCallingApp(
        rpId: String,
        callingAppInfo: CallingAppInfo?,
    ): AppInfoValidationResult {
        if (callingAppInfo == null) return AppInfoValidationResult.AppInfoNotFound
        return if (isWebRequest(callingAppInfo)) {
            val result = getCallingAppOriginCheckingGpmAllowlist(callingAppInfo)
            result.use(
                onSuccess = { origin: String ->
                    AppInfoValidationResult.Success(origin)
                },
                onFailed = { exception: Exception, _: Int? ->
                    AppInfoValidationResult.FailedToValidateOrigin(exception)
                },
            )
        } else {
            val result = isAssetLinksValid(rpId, callingAppInfo)
            result.use(
                onSuccess = { isValid: Boolean ->
                    val origin = WebAuthnUtils.appInfoToOrigin(callingAppInfo)
                    if (isValid) AppInfoValidationResult.Success(origin) else AppInfoValidationResult.FailedToValidateRP
                },
                onFailed = { _: Exception, _: Int? ->
                    AppInfoValidationResult.FailedToValidateRP
                },
            )
        }
    }

    private fun isWebRequest(callingAppInfo: CallingAppInfo): Boolean {
        try {
            callingAppInfo.getOrigin(INVALID_ALLOWLIST)
        } catch (_: IllegalStateException) {
            return true
        }
        return false
    }

    private companion object {
        const val INVALID_ALLOWLIST =
            "{\"apps\": [\n" +
                "   {\n" +
                "      \"type\": \"android\", \n" +
                "      \"info\": {\n" +
                "         \"package_name\": \"androidx.credentials.test\",\n" +
                "         \"signatures\" : [\n" +
                "         {\"build\": \"release\",\n" +
                "             \"cert_fingerprint_sha256\": \"HELLO\"\n" +
                "         },\n" +
                "         {\"build\": \"ud\",\n" +
                "         \"cert_fingerprint_sha256\": \"YELLOW\"\n" +
                "         }]\n" +
                "      }\n" +
                "    }\n" +
                "]}\n" +
                "\n"
    }
}
