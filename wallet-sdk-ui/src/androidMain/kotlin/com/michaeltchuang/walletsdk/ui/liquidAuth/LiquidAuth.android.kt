package com.michaeltchuang.walletsdk.ui.liquidAuth

import android.content.Intent
import androidx.compose.runtime.Composable
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.AuthMessage

const val EXTRA_ALGO_ADDRESS = "EXTRA_ALGO_ADDRESS"
object AuthMessageStorage {
    var AuthMessage =
        com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect
            .AuthMessage("", "")
}

@Composable
actual fun connect(authMessage: AuthMessage,algoAddress: String) {
    AuthMessageStorage.AuthMessage =
        com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect.AuthMessage(
            authMessage.origin,
            authMessage.requestId,
        )
    val context = androidx.compose.ui.platform.LocalContext.current
    val intent = Intent(context, AnswerActivity::class.java)
    intent.putExtra(EXTRA_ALGO_ADDRESS, algoAddress)
    context.startActivity(intent)
}
