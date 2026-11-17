package com.michaeltchuang.walletsdk.ui.base.utils

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry

internal actual fun plainTextClipEntry(text: String): ClipEntry = ClipEntry(ClipData.newPlainText("text", text))

internal actual suspend fun ClipEntry.readPlainText(): String? =
    clipData
        .takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.text
        ?.toString()
