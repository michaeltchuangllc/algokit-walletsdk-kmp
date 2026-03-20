package com.michaeltchuang.walletsdk.core.account.domain.usecase.core

import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.Algo25AccountRepository
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.Falcon24AccountRepository
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.HdKeyAccountRepository
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.NoAuthAccountRepository
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.SolanaAccountRepository
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccounts
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

class GetLocalAccountsUseCase(
    private val falcon24AccountRepository: Falcon24AccountRepository,
    private val hdKeyAccountRepository: HdKeyAccountRepository,
    private val algo25AccountRepository: Algo25AccountRepository,
    private val noAuthAccountRepository: NoAuthAccountRepository,
    private val solanaAccountRepository: SolanaAccountRepository,
    private val dispatcher: CoroutineDispatcher,
) : GetLocalAccounts {
    override suspend fun invoke(): List<LocalAccount> =
        withContext(dispatcher) {
            val deferredFalcon24Accounts = async { falcon24AccountRepository.getAll() }
            val deferredHdKeyAccounts = async { hdKeyAccountRepository.getAll() }
            val deferredAlgo25Accounts = async { algo25AccountRepository.getAll() }
            val deferredNoAuthAccounts = async { noAuthAccountRepository.getAll() }
            val deferredSeedVaultAccounts = async {
                solanaAccountRepository
                    .getAll()
                    .map { account ->
                        LocalAccount.SeedVault(
                            algoAddress = account.address,
                            chainId = account.chainId,
                            accountName = account.accountName,
                        )
                    }
            }
            awaitAll(
                deferredFalcon24Accounts,
                deferredHdKeyAccounts,
                deferredAlgo25Accounts,
                deferredNoAuthAccounts,
                deferredSeedVaultAccounts,
            ).flatten()
        }
}
