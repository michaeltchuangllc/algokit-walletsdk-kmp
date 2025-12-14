package com.michaeltchuang.walletsdk.ui.qrscanner

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun launchIntentWithUri(uri: String) {
    val context = LocalContext.current
    val intent = Intent(Intent.ACTION_VIEW)
    intent.data = Uri.parse(uri)
    if (intent.resolveActivity(context.packageManager) != null) {
        context.startActivity(intent)
    }
}
