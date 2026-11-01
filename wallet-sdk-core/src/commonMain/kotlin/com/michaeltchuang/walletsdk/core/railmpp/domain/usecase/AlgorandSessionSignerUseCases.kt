package com.michaeltchuang.walletsdk.core.railmpp.domain.usecase

import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner

fun interface GetAlgorandSessionSigner {
    suspend operator fun invoke(ownerAddress: String): MppWalletSigner?
}
