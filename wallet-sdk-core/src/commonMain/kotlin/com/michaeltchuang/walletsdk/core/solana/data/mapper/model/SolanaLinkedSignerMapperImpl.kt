package com.michaeltchuang.walletsdk.core.solana.data.mapper.model

import com.michaeltchuang.walletsdk.core.solana.data.database.SolanaLinkedSignerEntity
import com.michaeltchuang.walletsdk.core.solana.domain.SolanaLinkedSigner
import com.michaeltchuang.walletsdk.core.solana.domain.SolanaLinkedSignerSource

internal class SolanaLinkedSignerMapperImpl : SolanaLinkedSignerMapper {
    override fun invoke(entity: SolanaLinkedSignerEntity): SolanaLinkedSigner =
        SolanaLinkedSigner(
            ownerAddress = entity.ownerAddress,
            signerAddress = entity.signerAddress,
            source =
                SolanaLinkedSignerSource.entries.firstOrNull { it.name == entity.source }
                    ?: SolanaLinkedSignerSource.IMPORTED,
            derivationPath = entity.derivationPath,
            createdAtMs = entity.createdAtMs,
        )
}
