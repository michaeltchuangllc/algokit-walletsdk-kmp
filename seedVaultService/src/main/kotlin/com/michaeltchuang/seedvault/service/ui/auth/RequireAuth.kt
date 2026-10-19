package com.michaeltchuang.seedvault.service.ui.auth

import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import com.michaeltchuang.seedvault.service.R
import com.michaeltchuang.seedvault.service.data.auth.AuthResult
import com.michaeltchuang.seedvault.service.data.auth.UserAuthenticators
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Returns a function that verifies the user (fingerprint or screen lock) and only then runs the
 * action it is given. A denial or a device with no way to verify the user runs nothing.
 */
@Composable
internal fun rememberRequireAuth(
    title: String,
    subtitle: String? = null,
): (onVerified: () -> Unit) -> Unit {
    val context = LocalContext.current
    val authenticators: UserAuthenticators = koinInject()
    val scope = rememberCoroutineScope()
    return remember(context, authenticators, title, subtitle) {
        { onVerified ->
            val activity = context.findFragmentActivity()
            if (activity == null) {
                Toast.makeText(context, R.string.generic_error, Toast.LENGTH_SHORT).show()
            } else {
                scope.launch {
                    when (val result = authenticators.authenticate(activity, title, subtitle)) {
                        AuthResult.Success -> onVerified()
                        AuthResult.Unavailable ->
                            Toast.makeText(context, R.string.auth_unavailable, Toast.LENGTH_LONG).show()
                        is AuthResult.Denied ->
                            result.message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                    }
                }
            }
        }
    }
}

private fun Context.findFragmentActivity(): FragmentActivity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return null
}
