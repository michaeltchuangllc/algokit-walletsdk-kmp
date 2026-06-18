package com.michaeltchuang.walletsdk.core.railmpp.domain.usecase

import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAlgo25SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetFalcon24SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetHdSeed
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccount
import com.michaeltchuang.walletsdk.core.railmpp.data.MppWalletSignerImpl
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner

class MppWalletSignerUseCase(
    private val getLocalAccount: GetLocalAccount,
    private val getAlgo25SecretKey: GetAlgo25SecretKey,
    private val getFalcon24SecretKey: GetFalcon24SecretKey,
    private val getHdSeed: GetHdSeed,
) {
    suspend operator fun invoke(address: String): MppWalletSigner? {
        val localAccount = getLocalAccount(address) ?: return null
        if (localAccount is LocalAccount.SeedVault) return null

        val authorizedSignerPublicKey: ByteArray =
            when (localAccount) {
                is LocalAccount.HdKey -> localAccount.publicKey
                is LocalAccount.Falcon24 -> localAccount.publicKey
                is LocalAccount.Algo25 -> {
                    val secretKey = getAlgo25SecretKey(address)
                    if (secretKey != null && secretKey.size == 64) secretKey.copyOfRange(32, 64) else ByteArray(0)
                }
                else -> ByteArray(0)
            }

        val signerType = if (localAccount is LocalAccount.Falcon24) 1L else 0L

        return MppWalletSignerImpl(
            address = address,
            authorizedSignerPublicKey = authorizedSignerPublicKey,
            signerType = signerType,
            localAccount = localAccount,
            getAlgo25SecretKey = getAlgo25SecretKey,
            getFalcon24SecretKey = getFalcon24SecretKey,
            getHdSeed = getHdSeed,
        )
    }
}
