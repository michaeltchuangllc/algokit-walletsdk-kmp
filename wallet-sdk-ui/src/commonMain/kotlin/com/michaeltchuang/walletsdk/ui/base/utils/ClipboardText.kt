package com.michaeltchuang.walletsdk.ui.base.utils

import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard

internal expect fun plainTextClipEntry(text: String): ClipEntry

internal expect suspend fun ClipEntry.readPlainText(): String?

internal suspend fun Clipboard.setPlainText(text: String) = setClipEntry(plainTextClipEntry(text))

internal suspend fun Clipboard.getPlainText(): String? = getClipEntry()?.readPlainText()
