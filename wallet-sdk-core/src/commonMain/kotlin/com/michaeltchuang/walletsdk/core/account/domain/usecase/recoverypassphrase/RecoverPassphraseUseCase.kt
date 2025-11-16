package com.michaeltchuang.walletsdk.core.account.domain.usecase.recoverypassphrase

import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.AccountCreationFalcon24TypeMapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.AccountCreationHdKeyTypeMapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.Algo25AccountTypeMapper
import com.michaeltchuang.walletsdk.core.account.domain.model.core.AccountCreation
import com.michaeltchuang.walletsdk.core.account.domain.model.core.OnboardingAccountType
import com.michaeltchuang.walletsdk.core.algosdk.AlgoKitBip39.getEntropyFromMnemonic
import com.michaeltchuang.walletsdk.core.algosdk.getBip39Wallet
import com.michaeltchuang.walletsdk.core.algosdk.recoverAlgo25Account
import com.michaeltchuang.walletsdk.core.encryption.encryptByteArray
import com.michaeltchuang.walletsdk.core.foundation.utils.CreationType
import com.michaeltchuang.walletsdk.core.foundation.utils.toShortenedAddress
import kotlinx.coroutines.flow.flow

@Suppress("LongParameterList")
class RecoverPassphraseUseCase(
    private val accountCreationFalcon24TypeMapper: AccountCreationFalcon24TypeMapper,
    private val Algo25AccountTypeMapper: Algo25AccountTypeMapper,
    private val accountCreationHdKeyTypeMapper: AccountCreationHdKeyTypeMapper,
) {
    fun validateEnteredMnemonics(
        mnemonics: String,
        onboardingAccountType: OnboardingAccountType,
    ) = flow {
        try {
            val recoveredAccount = getAccount(onboardingAccountType, mnemonics)
            emit(recoveredAccount)
        } catch (e: Exception) {
            // Catch any exception (IllegalArgumentException, etc.) and return null
            emit(null)
        }
    }

    private fun getAccount(
        accountType: OnboardingAccountType,
        mnemonics: String,
    ): AccountCreation? {
        return when (accountType) {
            OnboardingAccountType.Algo25 -> {
                val algo25account =
                    recoverAlgo25Account(
                        mnemonics.lowercase(),
                    ) ?: return null
                AccountCreation(
                    address = algo25account.address,
                    customName = algo25account.address.toShortenedAddress(),
                    isBackedUp = false,
                    type =
                        Algo25AccountTypeMapper(
                            algo25account.secretKey,
                        ),
                    creationType = CreationType.RECOVER,
                )
            }

            OnboardingAccountType.Falcon24 -> {
                val entropy = getEntropyFromMnemonic(mnemonics)
                val wallet = getBip39Wallet(entropy)
                val falcon24 = wallet.generateFalcon24Address(mnemonics)
                AccountCreation(
                    address = falcon24.address,
                    customName = falcon24.address.toShortenedAddress(),
                    isBackedUp = false,
                    type = accountCreationFalcon24TypeMapper(entropy, falcon24, null),
                    creationType = CreationType.RECOVER,
                )
            }

            OnboardingAccountType.HdKey -> {
                // only entropy is needed for next screen (importing registered addresses)
                val entropy = getEntropyFromMnemonic(mnemonics)
                AccountCreation(
                    address = "",
                    customName = "",
                    isBackedUp = true,
                    type = AccountCreation.Type.HdKey(
                        publicKey = ByteArray(0),
                        encryptedPrivateKey = ByteArray(0),
                        encryptedEntropy = encryptByteArray(entropy),
                        account = 0,
                        change = 0,
                        keyIndex = 0,
                        derivationType = 0
                    ),
                    creationType = CreationType.RECOVER
                )
            }
        }
    }
}
