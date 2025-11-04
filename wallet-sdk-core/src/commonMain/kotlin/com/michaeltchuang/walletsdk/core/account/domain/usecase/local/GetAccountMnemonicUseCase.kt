package com.michaeltchuang.walletsdk.core.account.domain.usecase.local

import com.michaeltchuang.walletsdk.core.account.domain.model.local.AccountMnemonic
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.GetLocalAccountUseCase
import com.michaeltchuang.walletsdk.core.algosdk.AlgoKitBip39.getMnemonicFromEntropy
import com.michaeltchuang.walletsdk.core.algosdk.getMnemonicFromAlgo25SecretKey
import com.michaeltchuang.walletsdk.core.foundation.WalletSdkResult
import com.michaeltchuang.walletsdk.core.foundation.utils.splitMnemonic

internal class GetAccountMnemonicUseCase(
    private val getLocalAccount: GetLocalAccountUseCase,
    private val getAlgo25SecretKey: GetAlgo25SecretKey,
    private val getHdEntropy: GetHdEntropy,
) : GetAccountMnemonic {
    override suspend fun invoke(address: String): WalletSdkResult<AccountMnemonic> {
        val localAccount = getLocalAccount(address)
        return when (localAccount) {
            is LocalAccount.Algo25 -> getAlgo25Mnemonic(address)
            is LocalAccount.HdKey ->
                getHdBasedMnemonic(
                    localAccount = localAccount,
                )
            is LocalAccount.Falcon24 ->
                getHdBasedMnemonic(
                    localAccount = localAccount,
                )
            else -> WalletSdkResult.Error(IllegalArgumentException())
        }
    }

    private suspend fun getAlgo25Mnemonic(address: String): WalletSdkResult<AccountMnemonic> {
        val secretKey =
            getAlgo25SecretKey(address) ?: return WalletSdkResult.Error(IllegalArgumentException())
        val mnemonic =
            getMnemonicFromAlgo25SecretKey(secretKey) ?: return WalletSdkResult.Error(
                IllegalArgumentException(),
            )
        return getAccountMnemonic(mnemonic, AccountMnemonic.AccountType.Algo25)
    }

    private suspend fun getHdBasedMnemonic(localAccount: LocalAccount): WalletSdkResult<AccountMnemonic> {
        val (seedId, accountType) =
            when (localAccount) {
                is LocalAccount.HdKey -> localAccount.seedId to AccountMnemonic.AccountType.HdKey
                is LocalAccount.Falcon24 -> localAccount.seedId to AccountMnemonic.AccountType.Falcon24
                else -> {
                    return WalletSdkResult.Error(
                        IllegalArgumentException("LocalAccount type not supported by getHdBasedMnemonic"),
                    )
                }
            }

        val entropy =
            getHdEntropy(seedId) ?: return WalletSdkResult.Error(
                IllegalArgumentException("HD entropy not found for seed $seedId"),
            )

        val mnemonic = getMnemonicFromEntropy(entropy)
        return getAccountMnemonic(mnemonic, accountType)
    }

    private fun getAccountMnemonic(
        mnemonic: String?,
        type: AccountMnemonic.AccountType,
    ): WalletSdkResult<AccountMnemonic> =
        if (mnemonic.isNullOrBlank()) {
            WalletSdkResult.Error(IllegalArgumentException())
        } else {
            val mnemonicWords = mnemonic.splitMnemonic()
            WalletSdkResult.Success(AccountMnemonic(mnemonicWords, type))
        }
}
