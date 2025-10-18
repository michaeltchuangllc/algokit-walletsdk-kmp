package com.michaeltchuang.walletsdk.core.account.data.mapper.entity

import com.michaeltchuang.walletsdk.core.account.data.database.model.HdSeedEntity
import com.michaeltchuang.walletsdk.core.encryption.encryptByteArray

internal class HdSeedEntityMapperImpl() : HdSeedEntityMapper {
    override fun invoke(
        seedId: Int,
        entropy: ByteArray,
        seed: ByteArray,
    ): HdSeedEntity =
        HdSeedEntity(
            seedId = 0, // Let Room auto-generate the ID
            encryptedEntropy = encryptByteArray(entropy),
            encryptedSeed = encryptByteArray(seed),
        )
}
