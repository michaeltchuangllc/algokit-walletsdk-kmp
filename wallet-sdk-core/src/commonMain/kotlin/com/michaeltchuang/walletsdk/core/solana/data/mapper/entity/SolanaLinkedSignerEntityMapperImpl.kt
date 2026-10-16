package com.michaeltchuang.walletsdk.core.solana.data.mapper.entity

import com.michaeltchuang.walletsdk.core.encryption.encryptByteArray
import com.michaeltchuang.walletsdk.core.solana.data.database.SolanaLinkedSignerEntity
import com.michaeltchuang.walletsdk.core.solana.domain.SolanaLinkedSigner

internal class SolanaLinkedSignerEntityMapperImpl : SolanaLinkedSignerEntityMapper {
    override fun invoke(
        linkedSigner: SolanaLinkedSigner,
        privateSeed: ByteArray,
    ): SolanaLinkedSignerEntity =
        SolanaLinkedSignerEntity(
            ownerAddress = linkedSigner.ownerAddress,
            signerAddress = linkedSigner.signerAddress,
            encryptedPrivateSeed = encryptByteArray(privateSeed),
            source = linkedSigner.source.name,
            derivationPath = linkedSigner.derivationPath,
            createdAtMs = linkedSigner.createdAtMs,
        )
}
