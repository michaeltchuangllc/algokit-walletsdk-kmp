package com.michaeltchuang.walletsdk.ui.liquidAuth

import android.content.Intent
import androidx.compose.runtime.Composable
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.AuthMessage

object AuthMessageStorage {
    var authMessage =
        com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect
            .AuthMessage("", "")
}

@Composable
actual fun connect(
    authMessage: AuthMessage,
    accountAddress: String,
) {
    AuthMessageStorage.authMessage =
        com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect.AuthMessage(
            authMessage.origin,
            authMessage.requestId,
        )
    val context = androidx.compose.ui.platform.LocalContext.current
    val intent = Intent(context, AnswerActivity::class.java)
    intent.putExtra(AnswerActivity.EXTRA_ACCOUNT_ADDRESS, accountAddress)
    context.startActivity(intent)
}
