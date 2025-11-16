package com.michaeltchuang.walletsdk.core.account.domain.usecase.recoverypassphrase

import com.michaeltchuang.walletsdk.core.account.data.mapper.model.HdAccountAddressMapper
import com.michaeltchuang.walletsdk.core.account.domain.model.local.ActiveHdAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAccountFastLookupBatch
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetActiveHdAccounts
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.HdKeyAddressIndex
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.HdKeyAddressLite
import com.michaeltchuang.walletsdk.core.algosdk.bip39.sdk.Bip39Wallet
import com.michaeltchuang.walletsdk.core.algosdk.getBip39Wallet
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

internal class GetActiveHdAccountsUseCase(
    private val getAccountFastLookupBatch: GetAccountFastLookupBatch,
    private val hdAccountAddressMapper: HdAccountAddressMapper
) : GetActiveHdAccounts {

    override suspend fun invoke(entropy: ByteArray): List<ActiveHdAccount> {
        val activeHdAccounts = mutableListOf<ActiveHdAccount>()
        var accountIndex = 0
        val bip39Api = getBip39Wallet(entropy)
        while (true) {
            val activeAccounts = getActiveAccountsBatchDeferred(accountIndex, entropy, bip39Api)
            if (activeAccounts.isEmpty()) {
                break
            } else {
                activeHdAccounts.addAll(activeAccounts)
                accountIndex += SEARCH_BATCH_COUNT
            }
        }
        bip39Api.invalidate()
        return activeHdAccounts
    }

    private suspend fun getActiveAccountsBatchDeferred(
        accountIndex: Int,
        entropy: ByteArray,
        bip39Wallet: Bip39Wallet
    ): List<ActiveHdAccount> {
        return supervisorScope {
            (accountIndex until accountIndex + SEARCH_BATCH_COUNT).map { index ->
                async {
                    getActiveHdAccountIfExist(index, entropy, bip39Wallet)
                }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun getActiveHdAccountIfExist(
        accountIndex: Int,
        entropy: ByteArray,
        bip39Wallet: Bip39Wallet
    ): ActiveHdAccount? {
        val firstBatchHdKeyDetails = getFirstHdKeyDetailsBatch(accountIndex, bip39Wallet)
        val addresses = firstBatchHdKeyDetails.map { it.address }
        val firstBatchAccountFastLookup = getAccountFastLookupBatch(addresses)
        val isAccountActive = firstBatchAccountFastLookup.any { it.value?.accountExists == true }
        return if (isAccountActive) {
            val hdAccountAddresses = hdAccountAddressMapper(firstBatchHdKeyDetails, firstBatchAccountFastLookup)
            ActiveHdAccount(accountIndex, entropy, hdAccountAddresses)
        } else {
            null
        }
    }

    private fun getFirstHdKeyDetailsBatch(accountIndex: Int, bip39Wallet: Bip39Wallet): List<HdKeyAddressLite> {
        val range = 0 until SEARCH_BATCH_COUNT
        return range.map { keyIndex ->
            val index = HdKeyAddressIndex(accountIndex, 0, keyIndex)
            bip39Wallet.generateAddressLite(index)
        }
    }

    private companion object {
        const val SEARCH_BATCH_COUNT = 5
    }
}
