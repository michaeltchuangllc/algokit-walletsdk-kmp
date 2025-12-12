package com.michaeltchuang.walletsdk.ui.passkeys

import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.GetCredentialResponse
import androidx.fragment.app.FragmentActivity

internal interface ActivityCredentialRequestResolver {
    fun initializeRequestResolver(activity: FragmentActivity)

    fun finishWithCreateCredentialError(message: String)

    fun finishWithGetCredentialError(message: String)

    fun finishWithCreateCredentialResponse(response: CreatePublicKeyCredentialResponse)

    fun finishWithGetCredentialResponse(response: GetCredentialResponse)
}
