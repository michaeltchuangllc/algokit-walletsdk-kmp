package com.michaeltchuang.walletsdk.core.account.domain.usecase.local

import com.michaeltchuang.walletsdk.core.account.domain.repository.local.Algo25AccountRepository
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.HdKeyAccountRepository
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.NoAuthAccountRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

internal class GetLocalAccountsAddressesUseCase(
    private val hdKeyAccountRepository: HdKeyAccountRepository,
    private val algo25AccountRepository: Algo25AccountRepository,
    // private val ledgerBleAccountRepository: LedgerBleAccountRepository,
    private val noAuthAccountRepository: NoAuthAccountRepository,
    private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : GetLocalAccountsAddresses {
    override suspend fun invoke(): List<String> =
        withContext(coroutineDispatcher) {
            val deferredHdKeyAccountsAddresses = async { hdKeyAccountRepository.getAllAddresses() }
            val deferredAlgo25Accounts = async { algo25AccountRepository.getAllAddresses() }
            // val deferredLedgerBleAccounts = async { ledgerBleAccountRepository.getAllAddresses() }
            val deferredNoAuthAccounts = async { noAuthAccountRepository.getAllAddresses() }
            awaitAll(
                deferredHdKeyAccountsAddresses,
                deferredAlgo25Accounts,
                // deferredLedgerBleAccounts,
                deferredNoAuthAccounts,
            ).flatten()
        }
}
