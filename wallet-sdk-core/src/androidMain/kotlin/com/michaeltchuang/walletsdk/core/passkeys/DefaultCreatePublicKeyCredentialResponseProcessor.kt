package com.michaeltchuang.walletsdk.core.passkeys

import androidx.credentials.CreatePublicKeyCredentialResponse
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.AuthenticatorAttestationResponse
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.AuthenticatorFlags
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.CreatePublicKeyCredentialResponseArgs
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.CreatePublicKeyCredentialResponseData
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.FidoPublicKeyCredential
import com.michaeltchuang.walletsdk.core.passkeys.foundation.Cbor
import com.michaeltchuang.walletsdk.core.passkeys.foundation.CoseMapper
import java.security.interfaces.ECPublicKey
import java.util.UUID

val PERA_AAGUID = "418a66da-f981-47e8-814f-19c97f97bd4d"

internal class DefaultCreatePublicKeyCredentialResponseProcessor(
    private val coseMapper: CoseMapper,
) : CreatePublicKeyCredentialResponseProcessor {
    override fun invoke(args: CreatePublicKeyCredentialResponseArgs): CreatePublicKeyCredentialResponseData {
        val response = constructWebAuthnResponse(args)
        val credential = FidoPublicKeyCredential(args.credentialId, response)
        return CreatePublicKeyCredentialResponseData(
            credentialId = args.credentialId,
            response = CreatePublicKeyCredentialResponse(credential.json()),
        )
    }

    private fun constructWebAuthnResponse(args: CreatePublicKeyCredentialResponseArgs): AuthenticatorAttestationResponse =
        with(args) {
            val coseKey = coseMapper.mapPublicKeyToCose(keyPair.public as ECPublicKey)
            val spki = coseMapper.mapCoseKeyToSpki(coseKey)

            AuthenticatorAttestationResponse(
                aaguid = UUID.fromString(PERA_AAGUID),
                requestOptions = request,
                credentialId = credentialId,
                credentialPublicKey = Cbor().encode(coseKey),
                origin = appInfoOrigin,
                authFlags = AuthenticatorFlags(),
                packageName = appInfo.packageName,
                clientDataHash = clientDataHash,
                spki = spki,
            )
        }
}
