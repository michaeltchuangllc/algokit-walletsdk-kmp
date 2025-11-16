package com.michaeltchuang.walletsdk.core.account.domain.usecase.recoverypassphrase


import com.michaeltchuang.walletsdk.core.account.data.mapper.model.HdAccountAddressMapper
import com.michaeltchuang.walletsdk.core.account.domain.model.local.AccountFastLookup
import com.michaeltchuang.walletsdk.core.account.domain.model.local.ActiveHdAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAccountFastLookupBatch
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetActiveHdAccountAddresses
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.HdKeyAddressIndex
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.HdKeyAddressLite
import com.michaeltchuang.walletsdk.core.algosdk.bip39.sdk.Bip39Wallet
import com.michaeltchuang.walletsdk.core.algosdk.getBip39Wallet


internal class GetActiveHdAccountAddressesUseCase(
  /*  private val bip39WalletProvider: Bip39WalletProvider,*/
    private val getAccountFastLookupBatch: GetAccountFastLookupBatch,
    private val hdAccountAddressMapper: HdAccountAddressMapper
) : GetActiveHdAccountAddresses {

    override suspend fun invoke(activeHdAccount: ActiveHdAccount): List<ActiveHdAccount.HdAccountAddress> {
        val accountIndex = activeHdAccount.accountIndex
        val hdKeyDetailsList = mutableListOf<ActiveHdAccount.HdAccountAddress>().apply {
            addAll(activeHdAccount.firstBatchHdAccountAddress)
        }
        var rangeStart = SEARCH_BATCH_COUNT
        val bip39Api = getBip39Wallet(activeHdAccount.entropy)
        while (true) {
            val hdKeyDetailsBatch =
                createHdKeyDetailBatch(bip39Api, accountIndex, getSearchBatchRange(rangeStart))
            val addresses = hdKeyDetailsBatch.map { it.address }
            val accountFastLookupBatch = getAccountFastLookupBatch(addresses)
            if (shouldContinueSearching(accountFastLookupBatch)) {
                val hdAccountAddresses =
                    hdAccountAddressMapper(hdKeyDetailsBatch, accountFastLookupBatch)
                hdKeyDetailsList.addAll(hdAccountAddresses)
                rangeStart += SEARCH_BATCH_COUNT
            } else {
                break
            }
        }
        bip39Api.invalidate()
        return hdKeyDetailsList
    }

    private fun shouldContinueSearching(accountFastLookupBatch: Map<String, AccountFastLookup?>): Boolean {
        return accountFastLookupBatch.values.any { it?.accountExists == true }
    }

    private fun createHdKeyDetailBatch(
        bip39Wallet: Bip39Wallet,
        accountIndex: Int,
        range: IntRange
    ): List<HdKeyAddressLite> {
        return range.map { keyIndex ->
            val index = HdKeyAddressIndex(accountIndex, 0, keyIndex)
            bip39Wallet.generateAddressLite(index)
        }
    }

    private fun getSearchBatchRange(rangeStart: Int): IntRange {
        return rangeStart until rangeStart + SEARCH_BATCH_COUNT
    }

    private companion object {
        const val SEARCH_BATCH_COUNT = 5
    }
}
