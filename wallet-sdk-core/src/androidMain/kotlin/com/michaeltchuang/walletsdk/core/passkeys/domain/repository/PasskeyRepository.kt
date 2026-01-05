
package com.michaeltchuang.walletsdk.core.passkeys.domain.repository

import org.koin.core.context.GlobalContext

actual fun getPasskeyRepository(): PasskeyRepository = GlobalContext.get().get<PasskeyRepository>()
