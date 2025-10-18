package com.michaeltchuang.walletsdk.core.account.domain.usecase.core

import com.michaeltchuang.walletsdk.core.account.domain.model.core.AccountCreation
import com.michaeltchuang.walletsdk.core.account.domain.model.core.CreateAccount
import com.michaeltchuang.walletsdk.core.encryption.decryptByteArray

@Suppress("LongParameterList")
class AccountAdditionUseCase(
    private val addHdKeyAccount: AddHdKeyAccount,
    private val addHdSeed: AddHdSeed,
    private val addAlgo25Account: AddAlgo25Account,
    private val addFalcon24Account: AddFalcon24Account,
) {
    suspend fun addNewAccount(accountCreation: AccountCreation) {
        addAccount(accountCreation.toCreateAccount())
    }

    private suspend fun addAccount(createAccount: CreateAccount) {
        when (createAccount.type) {
            is CreateAccount.Type.HdKey -> {
                createHdKeyAccount(createAccount, createAccount.type)
            }

            is CreateAccount.Type.Falcon24 -> createFalcon24Account(
                createAccount,
                createAccount.type
            )

            is CreateAccount.Type.Algo25 -> createAlgo25Account(createAccount, createAccount.type)
            is CreateAccount.Type.LedgerBle -> {}
            is CreateAccount.Type.NoAuth -> {}
        }
    }

    private suspend fun createHdKeyAccount(
        createAccount: CreateAccount,
        type: CreateAccount.Type.HdKey,
    ) {
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
                    }

                }
            }
        }
    }

    private suspend fun createFalcon24Account(
        createAccount: CreateAccount,
        type: CreateAccount.Type.Falcon24,
    ) {
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
                    }
                }
            }
        }
    }

    private suspend fun createAlgo25Account(
        createAccount: CreateAccount,
        type: CreateAccount.Type.Algo25,
    ) {
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
        }
    }
}
