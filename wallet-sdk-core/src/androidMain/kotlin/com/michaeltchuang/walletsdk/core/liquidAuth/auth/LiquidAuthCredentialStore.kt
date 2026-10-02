package com.michaeltchuang.walletsdk.core.liquidAuth.auth

import android.content.Context
import androidx.core.content.edit

class LiquidAuthCredentialStore(
    context: Context,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences("liquid_auth_credential_bindings", Context.MODE_PRIVATE)

    fun getCredentialId(
        rpId: String,
        address: String,
    ): String? = preferences.getString(bindingKey(rpId, address), null)

    fun saveCredentialId(
        rpId: String,
        address: String,
        credentialId: String,
    ) {
        preferences.edit { putString(bindingKey(rpId, address), credentialId) }
    }

    fun removeCredentialId(
        rpId: String,
        address: String,
    ) {
        preferences.edit { remove(bindingKey(rpId, address)) }
    }

    // Length-prefix both components so separators within either value cannot cause collisions.
    private fun bindingKey(
        rpId: String,
        address: String,
    ): String = "${rpId.length}:$rpId${address.length}:$address"
}
