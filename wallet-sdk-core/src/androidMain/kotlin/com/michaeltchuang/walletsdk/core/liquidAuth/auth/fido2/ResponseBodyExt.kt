package com.michaeltchuang.walletsdk.core.liquidAuth.auth.fido2

import android.util.JsonReader
import android.util.Log
import com.google.android.gms.fido.fido2.api.common.*
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.crypto.decodeBase64
import okhttp3.ResponseBody

@Deprecated("Use the new CredentialManager API")
fun ResponseBody.toPublicKeyCredentialRequestOptions(): PublicKeyCredentialRequestOptions{
    val builder = PublicKeyCredentialRequestOptions.Builder()
    JsonReader(this.byteStream().bufferedReader()).use { reader ->
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "challenge" -> builder.setChallenge(reader.nextString().decodeBase64())
                "userVerification" -> reader.skipValue()
                "allowCredentials" -> builder.setAllowList(parseCredentialDescriptors(reader))
                "rpId" -> builder.setRpId(reader.nextString())
                "timeout" -> builder.setTimeoutSeconds(reader.nextDouble())

                else -> reader.skipValue()
            }
        }
        reader.endObject()
    }
    return builder.build()
}

@Deprecated("Use the new CredentialManager API")
fun ResponseBody.toPublicKeyCredentialCreationOptions(overrideRpId: String? = null): PublicKeyCredentialCreationOptions {
    val builder = PublicKeyCredentialCreationOptions.Builder()
    var parsedRpEntity: PublicKeyCredentialRpEntity? = null
    
    JsonReader(this.byteStream().bufferedReader()).use { reader ->
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "user" -> builder.setUser(parseUser(reader))
                "challenge" -> builder.setChallenge(reader.nextString().decodeBase64())
                "pubKeyCredParams" -> builder.setParameters(parseParameters(reader))
                "timeout" -> builder.setTimeoutSeconds(reader.nextDouble())
                "attestation" -> {
                    val attestation = reader.nextString()
                    try {
                        builder.setAttestationConveyancePreference(
                            AttestationConveyancePreference.fromString(attestation)
                        )
                    } catch (e: Exception) {
                        Log.w("FIDO2", "Unknown attestation value: $attestation", e)
                    }
                }
                "excludeCredentials" -> builder.setExcludeList(
                    parseCredentialDescriptors(reader)
                )

                "authenticatorSelection" -> builder.setAuthenticatorSelection(
                    parseSelection(reader)
                )

                "rp" -> {
                    parsedRpEntity = parseRp(reader)
                    // Don't set it yet if we have an override
                    if (overrideRpId == null) {
                        builder.setRp(parsedRpEntity!!)
                    }
                }
                "extensions" -> {
                    // Parse extensions but don't apply them yet as they may need special handling
                    // Skip for now to avoid parsing errors
                    reader.skipValue()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
    }
    
    // Apply RP ID override if provided
    if (overrideRpId != null && parsedRpEntity != null) {
        Log.w("FIDO2", "Overriding server RP ID '${parsedRpEntity!!.id}' with '$overrideRpId'")
        val overriddenRp = PublicKeyCredentialRpEntity(
            overrideRpId,
            parsedRpEntity!!.name,
            parsedRpEntity!!.icon
        )
        builder.setRp(overriddenRp)
    }
    
    Log.d("FIDO2", "Parsed PublicKeyCredentialCreationOptions successfully")
    return builder.build()
}

@Deprecated("Use the new CredentialManager API")
fun parseRp(reader: JsonReader): PublicKeyCredentialRpEntity {
    var id: String? = null
    var name: String? = null
    reader.beginObject()
    while (reader.hasNext()) {
        when (reader.nextName()) {
            "id" -> id = reader.nextString()
            "name" -> name = reader.nextString()
            else -> reader.skipValue()
        }
    }
    reader.endObject()
    
    if (id.isNullOrEmpty()) {
        Log.e("FIDO2", "RP ID is null or empty! This will cause validation errors.")
        throw IllegalArgumentException("RP ID is required for FIDO2 operations")
    }
    if (name.isNullOrEmpty()) {
        Log.e("FIDO2", "RP name is null or empty! This will cause validation errors.")
        throw IllegalArgumentException("RP name is required for FIDO2 operations")
    }
    
    Log.d("FIDO2", "Parsed RP - ID: $id, Name: $name")
    return PublicKeyCredentialRpEntity(id, name, /* icon */ null)
}

@Deprecated("Use the new CredentialManager API")
private fun parseSelection(reader: JsonReader): AuthenticatorSelectionCriteria {
    val builder = AuthenticatorSelectionCriteria.Builder()
    reader.beginObject()
    while (reader.hasNext()) {
        when (reader.nextName()) {
            "authenticatorAttachment" -> builder.setAttachment(
                Attachment.fromString(reader.nextString())
            )

            "userVerification" -> reader.skipValue()
            else -> reader.skipValue()
        }
    }
    reader.endObject()
    return builder.build()
}

@Deprecated("Use the new CredentialManager API")
private fun parseCredentialDescriptors(
    reader: JsonReader
): List<PublicKeyCredentialDescriptor> {
    val list = mutableListOf<PublicKeyCredentialDescriptor>()
    reader.beginArray()
    while (reader.hasNext()) {
        var id: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = reader.nextString()
                "type" -> reader.skipValue()
                "transports" -> reader.skipValue()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        list.add(
            PublicKeyCredentialDescriptor(
                PublicKeyCredentialType.PUBLIC_KEY.toString(),
                id!!.decodeBase64(),
                /* transports */ null
            )
        )
    }
    reader.endArray()
    return list
}

@Deprecated("Use the new CredentialManager API")
private fun parseParameters(reader: JsonReader): List<PublicKeyCredentialParameters> {
    val parameters = mutableListOf<PublicKeyCredentialParameters>()
    reader.beginArray()
    while (reader.hasNext()) {
        reader.beginObject()
        var type: String? = null
        var alg = 0
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "type" -> type = reader.nextString()
                "alg" -> alg = reader.nextInt()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        parameters.add(PublicKeyCredentialParameters(type!!, alg))
    }
    reader.endArray()
    return parameters
}
@Deprecated("Use the new CredentialManager API")
private fun parseUser(reader: JsonReader): PublicKeyCredentialUserEntity {
    reader.beginObject()
    var id: String? = null
    var name: String? = null
    var displayName = ""
    while (reader.hasNext()) {
        when (reader.nextName()) {
            "id" -> id = reader.nextString()
            "name" -> name = reader.nextString()
            "displayName" -> displayName = reader.nextString()
            else -> reader.skipValue()
        }
    }
    reader.endObject()
    return PublicKeyCredentialUserEntity(
        id!!.decodeBase64(),
        name!!,
        "", // icon
        displayName
    )
}
