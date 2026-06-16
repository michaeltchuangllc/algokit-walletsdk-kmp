package com.michaeltchuang.walletsdk.core.railmpp.internal

internal actual fun ensureCryptoProvider() = BouncyCastleProviderSetup.ensure()
