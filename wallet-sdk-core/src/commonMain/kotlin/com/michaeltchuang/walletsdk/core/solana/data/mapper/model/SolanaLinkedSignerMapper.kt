package com.michaeltchuang.walletsdk.core.solana.data.mapper.model

import com.michaeltchuang.walletsdk.core.solana.data.database.SolanaLinkedSignerEntity
import com.michaeltchuang.walletsdk.core.solana.domain.SolanaLinkedSigner

internal interface SolanaLinkedSignerMapper {
    operator fun invoke(entity: SolanaLinkedSignerEntity): SolanaLinkedSigner
}
