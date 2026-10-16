package com.michaeltchuang.walletsdk.core.account.domain.usecase.local

import com.michaeltchuang.walletsdk.core.account.domain.model.local.AccountMnemonic
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.GetLocalAccountUseCase
import com.michaeltchuang.walletsdk.core.algosdk.AlgoKitBip39.getMnemonicFromEntropy
import com.michaeltchuang.walletsdk.core.algosdk.getFalcon25MnemonicFromEntropy
import com.michaeltchuang.walletsdk.core.algosdk.getMnemonicFromAlgo25SecretKey
import com.michaeltchuang.walletsdk.core.foundation.WalletSdkResult
import com.michaeltchuang.walletsdk.core.foundation.utils.splitMnemonic

internal class GetAccountMnemonicUseCase(
    private val getLocalAccount: GetLocalAccountUseCase,
    private val getAlgo25SecretKey: GetAlgo25SecretKey,
    private val getFalcon25Entropy: GetFalcon25Entropy,
    private val getHdEntropy: GetHdEntropy,
) : GetAccountMnemonic {
    override suspend fun invoke(address: String): WalletSdkResult<AccountMnemonic> =
        when (val account = getLocalAccount(address)) {
            is LocalAccount.Algo25 ->
                mnemonic(
                    getMnemonicFromAlgo25SecretKey(
                        getAlgo25SecretKey(address) ?: return WalletSdkResult.Error(IllegalArgumentException()),
                    ),
                    AccountMnemonic.AccountType.Algo25,
                )
            is LocalAccount.Falcon25 ->
                mnemonic(
                    getFalcon25MnemonicFromEntropy(
                        getFalcon25Entropy(address) ?: return WalletSdkResult.Error(IllegalArgumentException()),
                    ),
                    AccountMnemonic.AccountType.Falcon25,
                )
            is LocalAccount.HdKey -> hdMnemonic(account.seedId, AccountMnemonic.AccountType.HdKey)
            is LocalAccount.Falcon24 -> hdMnemonic(account.seedId, AccountMnemonic.AccountType.Falcon24)
            else -> WalletSdkResult.Error(IllegalArgumentException())
        }

    private suspend fun hdMnemonic(
        seedId: Int,
        type: AccountMnemonic.AccountType,
    ): WalletSdkResult<AccountMnemonic> {
        val entropy = getHdEntropy(seedId) ?: return WalletSdkResult.Error(IllegalArgumentException())
        return mnemonic(getMnemonicFromEntropy(entropy), type)
    }

    private fun mnemonic(
        value: String?,
        type: AccountMnemonic.AccountType,
    ): WalletSdkResult<AccountMnemonic> =
        if (value.isNullOrBlank()) {
            WalletSdkResult.Error(
                IllegalArgumentException(),
            )
        } else {
            WalletSdkResult.Success(AccountMnemonic(value.splitMnemonic(), type))
        }
}
