package com.michaeltchuang.walletsdk.core.account.domain.usecase.recoverypassphrase
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.RegisteredHdKeyMapper
import com.michaeltchuang.walletsdk.core.account.domain.model.local.ActiveHdAccount
import com.michaeltchuang.walletsdk.core.account.domain.model.local.RegisteredHdKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetActiveHdAccountAddresses
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetActiveHdAccounts
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccountsAddresses
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetRegisteredHdKeys
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.HdKeyAddressIndex
import com.michaeltchuang.walletsdk.core.algosdk.bip39.sdk.Bip39Wallet
import com.michaeltchuang.walletsdk.core.algosdk.getBip39Wallet
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

internal class GetRegisteredHdKeysUseCase(
    private val getLocalAccountsAddresses: GetLocalAccountsAddresses,
    private val getActiveHdAccounts: GetActiveHdAccounts,
    private val getActiveHdAccountAddresses: GetActiveHdAccountAddresses,
    private val registeredHdKeyMapper: RegisteredHdKeyMapper,
) : GetRegisteredHdKeys {
    override suspend fun invoke(entropy: ByteArray): List<RegisteredHdKey> {
        val localAccountAddresses = getLocalAccountsAddresses()
        val activeHdAccounts = getActiveHdAccounts(entropy)
        val walletApi = getBip39Wallet(entropy)

        if (activeHdAccounts.isEmpty()) {
            return getFirstAccountFirstAddress(walletApi, localAccountAddresses)
        }

        val activeHdAccountAddresses =
            supervisorScope {
                activeHdAccounts
                    .map { activeHdAccount ->
                        async {
                            getActiveHdAccountAddresses(activeHdAccount)
                        }
                    }.awaitAll()
                    .flatten()
            }

        return activeHdAccountAddresses
            .mapNotNull { hdAccountAddress ->
                if (hdAccountAddress.fastLookup == null || !hdAccountAddress.fastLookup.accountExists) {
                    return@mapNotNull null
                }
                val isAlreadyImported = localAccountAddresses.contains(hdAccountAddress.address)
                registeredHdKeyMapper(hdAccountAddress, hdAccountAddress.fastLookup, isAlreadyImported)
            }.ifEmpty {
                getFirstAccountFirstAddress(walletApi, localAccountAddresses)
            }.also {
                walletApi.invalidate()
            }
    }

    private fun getFirstAccountFirstAddress(
        bip39Wallet: Bip39Wallet,
        localAccountAddresses: List<String>,
    ): List<RegisteredHdKey> {
        val index = HdKeyAddressIndex(accountIndex = 0, changeIndex = 0, keyIndex = 0)
        val address = bip39Wallet.generateAddressLite(index).address
        val hdAccountAddress = ActiveHdAccount.HdAccountAddress(address, 0, 0, 0, null)
        val isAlreadyImported = localAccountAddresses.contains(address)
        return listOf(registeredHdKeyMapper(hdAccountAddress, null, isAlreadyImported))
    }
}
