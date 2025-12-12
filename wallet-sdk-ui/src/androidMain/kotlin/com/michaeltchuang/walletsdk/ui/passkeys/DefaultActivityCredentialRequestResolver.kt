package com.michaeltchuang.walletsdk.ui.passkeys

import android.app.Activity.RESULT_OK
import android.content.Intent
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity

internal class DefaultActivityCredentialRequestResolver : ActivityCredentialRequestResolver {

    private var activity: FragmentActivity? = null

    override fun initializeRequestResolver(activity: FragmentActivity) {
        this.activity = activity
    }

    override fun finishWithCreateCredentialError(message: String) {
        val intent = Intent()
        PendingIntentHandler.setCreateCredentialException(intent, CreateCredentialUnknownException(message))
        finishActivity(intent)
    }

    override fun finishWithGetCredentialError(message: String) {
        val intent = Intent()
        PendingIntentHandler.setGetCredentialException(intent, GetCredentialUnknownException(message))
        finishActivity(intent)
    }

    override fun finishWithCreateCredentialResponse(response: CreatePublicKeyCredentialResponse) {
        val intent = Intent()
        PendingIntentHandler.setCreateCredentialResponse(intent, response)
        finishActivity(intent)
    }

    override fun finishWithGetCredentialResponse(response: GetCredentialResponse) {
        val intent = Intent()
        PendingIntentHandler.setGetCredentialResponse(intent, response)
        finishActivity(intent)
    }

    private fun finishActivity(intent: Intent) {
        activity?.setResult(RESULT_OK, intent)
        activity?.finish()
        activity = null
    }
}
