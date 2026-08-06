package com.michaeltchuang.walletsdk.core.account.domain.usecase.core

import com.michaeltchuang.walletsdk.core.account.domain.model.core.AccountCreation
import com.michaeltchuang.walletsdk.core.account.domain.model.core.CreateAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.CreateWatchAccountUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.DeleteNoAuthAccountUseCase
import com.michaeltchuang.walletsdk.core.encryption.decryptByteArray

@Suppress("LongParameterList")
class AccountAdditionUseCase(
    private val addNoAuthAccount: CreateWatchAccountUseCase,
    private val addHdKeyAccount: AddHdKeyAccount,
    private val addHdSeed: AddHdSeed,
    private val addAlgo25Account: AddAlgo25Account,
    private val addFalcon24Account: AddFalcon24Account,
    private val addFalcon25Account: AddFalcon25Account,
    private val deleteNoAuthAccountUseCase: DeleteNoAuthAccountUseCase,
) {
    suspend fun addNewAccount(accountCreation: AccountCreation) {
        addAccount(accountCreation.toCreateAccount())
    }

    private suspend fun addAccount(createAccount: CreateAccount) {
        when (createAccount.type) {
            is CreateAccount.Type.HdKey -> {
                // Create the account first, then delete watch account only if successful
                val success = createHdKeyAccount(createAccount, createAccount.type)
                if (success) {
                    deleteWatchAccountIfExists(createAccount.address)
                }
            }

            is CreateAccount.Type.Falcon25 -> {
                if (createFalcon25Account(createAccount, createAccount.type)) deleteWatchAccountIfExists(createAccount.address)
            }

            is CreateAccount.Type.Falcon24 -> {
                // Create the account first, then delete watch account only if successful
                val success =
                    createFalcon24Account(
                        createAccount,
                        createAccount.type,
                    )
                if (success) {
                    deleteWatchAccountIfExists(createAccount.address)
                }
            }

            is CreateAccount.Type.Algo25 -> {
                // Create the account first, then delete watch account only if successful
                val success = createAlgo25Account(createAccount, createAccount.type)
                if (success) {
                    deleteWatchAccountIfExists(createAccount.address)
                }
            }
            is CreateAccount.Type.LedgerBle -> {}
            is CreateAccount.Type.NoAuth -> {
                createNoAuthAccount(createAccount)
            }
        }
    }

    private suspend fun deleteWatchAccountIfExists(address: String) {
        deleteNoAuthAccountUseCase(address)
    }

    private suspend fun createHdKeyAccount(
        createAccount: CreateAccount,
        type: CreateAccount.Type.HdKey,
    ): Boolean =
        try {
            with(createAccount) {
                decryptByteArray(type.encryptedPrivateKey).let { privateKey ->

                    decryptByteArray(type.encryptedEntropy).let { entropy ->

                        val seedIdResult = addHdSeed(entropy)
                        val seedId = seedIdResult.getDataOrNull()
                        if (seedIdResult.isSuccess && seedId != null) {
                            addHdKeyAccount(
                                address,
                                type.publicKey,
                                privateKey,
                                seedId,
                                type.account,
                                type.change,
                                type.keyIndex,
                                type.derivationType,
                                isBackedUp,
                                customName,
                                createAccount.orderIndex,
                            )
                            true
                        } else {
                            false
                        }
                    }
                }
            }
        } catch (e: Exception) {
            false
        }

    private suspend fun createFalcon25Account(
        createAccount: CreateAccount,
        type: CreateAccount.Type.Falcon25,
    ): Boolean =
        try {
            addFalcon25Account(
                address = createAccount.address,
                publicKey = type.publicKey,
                privateKey = decryptByteArray(type.encryptedPrivateKey),
                entropy = decryptByteArray(type.encryptedEntropy),
                seed = decryptByteArray(type.encryptedSeed),
                isBackedUp = createAccount.isBackedUp,
                customName = createAccount.customName,
                orderIndex = createAccount.orderIndex,
            )
            true
        } catch (_: Exception) {
            false
        }

    private suspend fun createFalcon24Account(
        createAccount: CreateAccount,
        type: CreateAccount.Type.Falcon24,
    ): Boolean =
        try {
            with(createAccount) {
                decryptByteArray(type.encryptedPrivateKey).let { privateKey ->
                    decryptByteArray(type.encryptedEntropy).let { entropy ->

                        val seedIdResult = addHdSeed(entropy)
                        val seedId = seedIdResult.getDataOrNull()
                        if (seedIdResult.isSuccess && seedId != null) {
                            addFalcon24Account(
                                address,
                                type.publicKey,
                                privateKey,
                                seedId,
                                isBackedUp,
                                customName,
                                createAccount.orderIndex,
                            )
                            true
                        } else {
                            false
                        }
                    }
                }
            }
        } catch (e: Exception) {
            false
        }

    private suspend fun createAlgo25Account(
        createAccount: CreateAccount,
        type: CreateAccount.Type.Algo25,
    ): Boolean =
        try {
            with(createAccount) {
                val secretKey = decryptByteArray(type.encryptedSecretKey)
                addAlgo25Account(
                    address,
                    secretKey,
                    isBackedUp,
                    customName,
                    createAccount.orderIndex,
                )
                // type.clearFromMemory()
                true
            }
        } catch (e: Exception) {
            false
        }

    private suspend fun createNoAuthAccount(createAccount: CreateAccount) {
        addNoAuthAccount(
            createAccount,
        )
    }
}
