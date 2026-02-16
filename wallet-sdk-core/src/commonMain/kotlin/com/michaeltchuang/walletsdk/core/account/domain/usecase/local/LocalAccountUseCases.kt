package com.michaeltchuang.walletsdk.core.account.domain.usecase.local

import com.ionspin.kotlin.bignum.integer.BigInteger
import com.michaeltchuang.walletsdk.core.account.domain.model.core.AccountRegistrationType
import com.michaeltchuang.walletsdk.core.account.domain.model.local.AccountFastLookup
import com.michaeltchuang.walletsdk.core.account.domain.model.local.AccountMnemonic
import com.michaeltchuang.walletsdk.core.account.domain.model.local.ActiveHdAccount
import com.michaeltchuang.walletsdk.core.account.domain.model.local.HdSeedFirstAddress
import com.michaeltchuang.walletsdk.core.account.domain.model.local.HdWalletSummary
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.model.local.RegisteredHdKey
import com.michaeltchuang.walletsdk.core.account.domain.model.local.TransactionFee
import com.michaeltchuang.walletsdk.core.foundation.WalletSdkResult
import com.michaeltchuang.walletsdk.core.foundation.utils.AlgoKitResult
import com.michaeltchuang.walletsdk.core.network.model.AccountInformation
import com.michaeltchuang.walletsdk.core.network.model.TransactionSigner

internal fun interface SaveAlgo25Account {
    suspend operator fun invoke(
        account: LocalAccount.Algo25,
        privateKey: ByteArray,
    )
}

internal fun interface DeleteAlgo25Account {
    suspend operator fun invoke(address: String)
}

internal fun interface SaveFalcon24Account {
    suspend operator fun invoke(
        account: LocalAccount.Falcon24,
        seedId: Int,
        privateKey: ByteArray,
    )
}

internal fun interface DeleteFalcon24Account {
    suspend operator fun invoke(address: String)
}

internal fun interface DeleteHdKeyAccount {
    suspend operator fun invoke(address: String)
}

fun interface GetLocalAccounts {
    suspend operator fun invoke(): List<LocalAccount>
}

fun interface GetLocalAccount {
    suspend operator fun invoke(address: String): LocalAccount?
}

internal fun interface SaveHdKeyAccount {
    suspend operator fun invoke(
        account: LocalAccount.HdKey,
        privateKey: ByteArray,
    )
}

fun interface GetSeedIdIfExistingEntropy {
    suspend operator fun invoke(entropy: ByteArray): Int?
}

interface GetAccountRegistrationType {
    suspend operator fun invoke(address: String): AccountRegistrationType?

    operator fun invoke(account: LocalAccount): AccountRegistrationType
}

fun interface GetHdWalletSummaries {
    suspend operator fun invoke(): List<HdWalletSummary>?
}

fun interface GetMaxHdSeedId {
    suspend operator fun invoke(): Int?
}

fun interface GetHdEntropy {
    suspend operator fun invoke(seedId: Int): ByteArray?
}

fun interface GetAccountMnemonic {
    suspend operator fun invoke(address: String): WalletSdkResult<AccountMnemonic>
}

fun interface GetAlgo25SecretKey {
    suspend operator fun invoke(address: String): ByteArray?
}

fun interface GetHdKeyPrivateKey {
    suspend operator fun invoke(address: String): ByteArray?
}

fun interface GetFalcon24SecretKey {
    suspend operator fun invoke(address: String): ByteArray?
}

fun interface GetFalcon24WalletSummaries {
    suspend operator fun invoke(): List<HdWalletSummary>?
}

fun interface IsAccountRekeyedToAnotherAccount {
    suspend operator fun invoke(address: String): Boolean
}

fun interface GetAccountRekeyAdminAddress {
    suspend operator fun invoke(address: String): String?
}

fun interface GetTransactionSigner {
    suspend operator fun invoke(address: String): TransactionSigner
}

fun interface GetHdSeed {
    suspend operator fun invoke(seedId: Int): ByteArray?
}

fun interface GetAccountAlgoBalance {
    suspend operator fun invoke(address: String): BigInteger?
}

fun interface GetAccountMinimumBalance {
    suspend operator fun invoke(address: String): Long?
}

fun interface GetTransactionFeeForAccount {
    suspend operator fun invoke(address: String): TransactionFee
}

fun interface GetBasicAccountInformationUseCase {
    suspend operator fun invoke(address: String): AccountInformation?
}

internal fun interface GetActiveHdAccounts {
    suspend operator fun invoke(entropy: ByteArray): List<ActiveHdAccount>
}

fun interface GetRegisteredHdKeys {
    suspend operator fun invoke(entropy: ByteArray): List<RegisteredHdKey>
}

internal fun interface GetActiveHdAccountAddresses {
    suspend operator fun invoke(activeHdAccount: ActiveHdAccount): List<ActiveHdAccount.HdAccountAddress>
}

internal fun interface GetAccountFastLookupBatch {
    suspend operator fun invoke(addresses: List<String>): Map<String, AccountFastLookup?>
}

fun interface GetAccountFastLookup {
    suspend operator fun invoke(address: String): AlgoKitResult<AccountFastLookup>
}

fun interface GetLocalAccountsAddresses {
    suspend operator fun invoke(): List<String>
}

fun interface GetAllHdSeedFirstAddresses {
    suspend operator fun invoke(): List<HdSeedFirstAddress>
}
