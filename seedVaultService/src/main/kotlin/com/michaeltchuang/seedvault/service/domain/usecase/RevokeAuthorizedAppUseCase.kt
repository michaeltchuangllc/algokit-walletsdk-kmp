package com.michaeltchuang.seedvault.service.domain.usecase

import com.michaeltchuang.seedvault.service.domain.repository.SeedVaultRepository
import kotlinx.coroutines.flow.first

/** Privileged apps cannot be revoked from the settings screen, so they are skipped. */
internal class RevokeAuthorizedAppUseCase(
    private val seedVaultRepository: SeedVaultRepository,
) : RevokeAuthorizedApp {
    override suspend fun invoke(
        seedId: Long,
        packageName: String,
    ) {
        val app =
            seedVaultRepository
                .getSeed(seedId)
                .first()
                ?.authorizedApps
                ?.firstOrNull { it.packageName == packageName }
        if (app == null || app.isPrivileged) return
        seedVaultRepository.revokeApp(seedId, packageName)
    }
}
