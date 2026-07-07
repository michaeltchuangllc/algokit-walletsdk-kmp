package com.michaeltchuang.walletsdk.core.railmpp.data.local

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
internal actual fun railMppDataStorePath(fileName: String): String? {
    val directoryUrl =
        NSFileManager.defaultManager
            .URLForDirectory(
                directory = NSApplicationSupportDirectory,
                inDomain = NSUserDomainMask,
                appropriateForURL = null,
                create = true,
                error = null,
            ) ?: return null

    return directoryUrl
        .URLByAppendingPathComponent("datastore", isDirectory = true)
        ?.also { createDirectoryIfNeeded(it) }
        ?.URLByAppendingPathComponent(fileName)
        ?.path
}

@OptIn(ExperimentalForeignApi::class)
private fun createDirectoryIfNeeded(directoryUrl: NSURL) {
    NSFileManager.defaultManager.createDirectoryAtURL(
        url = directoryUrl,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
}
