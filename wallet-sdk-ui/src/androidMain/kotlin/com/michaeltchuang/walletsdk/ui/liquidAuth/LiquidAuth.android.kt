package com.michaeltchuang.walletsdk.ui.liquidAuth

import android.content.Intent
import androidx.compose.runtime.Composable
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.AuthMessage

object AuthMessageStorage {
    var AuthMessage = com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect.AuthMessage("", "")
}

@Composable
actual fun connect(
    authMessage: AuthMessage
) {/* val context = androidx.compose.ui.platform.LocalContext.current
     val activity = context as? ComponentActivity
         ?: throw IllegalArgumentException("Context must be a ComponentActivity for Android")
     val answer = Answer(activity)
     LaunchedEffect(Unit) {
         answer.register(authMessage)
     }*/
    AuthMessageStorage.AuthMessage = com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect.AuthMessage(
        authMessage.origin, authMessage.requestId
    )
    val context = androidx.compose.ui.platform.LocalContext.current
    context.startActivity(Intent(context, AnswerActivity::class.java))
}

