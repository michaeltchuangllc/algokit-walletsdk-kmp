package com.michaeltchuang.walletsdk.core.account.domain.usecase.core

import com.michaeltchuang.walletsdk.core.account.domain.model.custom.CustomHdSeedInfo
import com.michaeltchuang.walletsdk.core.account.domain.repository.custom.CustomHdSeedInfoRepository
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.HdSeedRepository
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetSeedIdIfExistingEntropy
import com.michaeltchuang.walletsdk.core.algosdk.getSeedFromEntropy
import com.michaeltchuang.walletsdk.core.foundation.WalletSdkResult
import com.michaeltchuang.walletsdk.core.foundation.utils.clearFromMemory

internal class AddHdSeedUseCase(
    private val hdSeedRepository: HdSeedRepository,
    private val customHdSeedInfoRepository: CustomHdSeedInfoRepository,
    private val getSeedIdIfExistingEntropy: GetSeedIdIfExistingEntropy,
) : AddHdSeed {
    override suspend fun invoke(entropy: ByteArray): WalletSdkResult<Int> {
        val existingSeedId = getSeedIdIfExistingEntropy.invoke(entropy)
        return if (existingSeedId != null) {
            WalletSdkResult.Success(existingSeedId)
        } else {
            createNewSeed(entropy)
        }
    }

    private suspend fun createNewSeed(entropy: ByteArray): WalletSdkResult<Int> {
        val seed =
            getSeedFromEntropy(entropy)
                ?: return WalletSdkResult.Error(Exception("Failed to generate seed from entropy"))
        val newSeedId = addHdSeed(seed.copyOf(), entropy)
        setCustomInfo(newSeedId)
        seed.clearFromMemory()
        return WalletSdkResult.Success(newSeedId)
    }

    private suspend fun addHdSeed(
        seed: ByteArray,
        entropy: ByteArray,
    ): Int =
        hdSeedRepository
            .addHdSeed(
                seedId = 0, // ID will be auto-generated
                seed = seed,
                entropy = entropy,
            ).toInt()

    private suspend fun setCustomInfo(seedId: Int) {
        customHdSeedInfoRepository.setCustomInfo(
            CustomHdSeedInfo(
                seedId = seedId,
                entropyCustomName = "Wallet #$seedId",
                orderIndex = seedId,
                isBackedUp = false,
            ),
        )
    }
}
