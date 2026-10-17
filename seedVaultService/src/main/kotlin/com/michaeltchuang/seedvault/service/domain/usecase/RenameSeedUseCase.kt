package com.michaeltchuang.seedvault.service.domain.usecase

import com.michaeltchuang.seedvault.service.domain.repository.SeedVaultRepository

/** Trims the new name and ignores blank names. */
internal class RenameSeedUseCase(
    private val seedVaultRepository: SeedVaultRepository,
) : RenameSeed {
    override suspend fun invoke(
        seedId: Long,
        name: String,
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        seedVaultRepository.renameSeed(seedId, trimmed)
    }
}
