package com.michaeltchuang.walletsdk.core.railmpp.data.local

import com.michaeltchuang.walletsdk.core.network.domain.AndroidContextHolder

internal actual fun railMppDataStorePath(fileName: String): String? =
    AndroidContextHolder.applicationContext
        ?.filesDir
        ?.resolve("datastore/$fileName")
        ?.absolutePath
