package com.michaeltchuang.walletsdk.ui.base.utils

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun plainTextClipEntry(text: String): ClipEntry = ClipEntry.withPlainText(text)

@OptIn(ExperimentalComposeUiApi::class)
internal actual suspend fun ClipEntry.readPlainText(): String? = getPlainText()
