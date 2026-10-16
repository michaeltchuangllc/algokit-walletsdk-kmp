package com.michaeltchuang.walletsdk.core.solana.data.mapper.entity

import com.michaeltchuang.walletsdk.core.solana.data.database.SolanaLinkedSignerEntity
import com.michaeltchuang.walletsdk.core.solana.domain.SolanaLinkedSigner

internal interface SolanaLinkedSignerEntityMapper {
    operator fun invoke(
        linkedSigner: SolanaLinkedSigner,
        privateSeed: ByteArray,
    ): SolanaLinkedSignerEntity
}
