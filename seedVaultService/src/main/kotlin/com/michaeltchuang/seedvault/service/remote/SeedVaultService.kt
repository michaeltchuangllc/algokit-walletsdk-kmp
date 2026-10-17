package com.michaeltchuang.seedvault.service.remote

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.util.Base64
import com.michaeltchuang.seedvault.service.ISeedVaultService
import com.michaeltchuang.seedvault.service.domain.model.VaultSeed
import com.michaeltchuang.seedvault.service.domain.repository.SeedVaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.android.ext.android.inject

/**
 * Bound service behind [ISeedVaultService]. Every call is checked against the app that made it:
 * it needs the ACCESS_SEED_VAULT permission, and can only see and sign with seeds it was authorized
 * for by the user. The app is identified by its Binder uid, never by anything it passes in.
 */
class SeedVaultService : Service() {
    private val seedVaultRepository: SeedVaultRepository by inject()

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder =
        object : ISeedVaultService.Stub() {
            override fun isServiceReady(): Boolean {
                enforceCallerPermission()
                return true
            }

            override fun getAuthorizedSeeds(): String {
                val packages = callerPackages()
                return runBlocking(Dispatchers.IO) {
                    val seeds = authorizedSeeds(packages).map { it.toRemoteSeed() }
                    Json.encodeToString(seeds)
                }
            }

            override fun signBytes(
                seedId: Long,
                data: ByteArray,
            ): ByteArray? {
                val packages = callerPackages()
                return runBlocking(Dispatchers.IO) {
                    requireAuthorized(packages, seedId)
                    seedVaultRepository.signBytes(seedId, data)
                }
            }

            override fun signAlgorandPayment(
                seedId: Long,
                receiver: String,
                amountMicroAlgos: Long,
                paramsJson: String,
            ): ByteArray? {
                val packages = callerPackages()
                // A malformed request is the caller's mistake, not a signing failure
                val params =
                    runCatching { Json.decodeFromString<RemoteAlgorandParams>(paramsJson).toPaymentParams() }
                        .getOrElse { throw IllegalArgumentException("Invalid Algorand parameters", it) }
                return runBlocking(Dispatchers.IO) {
                    requireAuthorized(packages, seedId)
                    seedVaultRepository.signAlgorandPayment(seedId, receiver, amountMicroAlgos, params)
                }
            }
        }

    private fun enforceCallerPermission() {
        if (checkCallingPermission(SeedVaultContract.PERMISSION_ACCESS_SEED_VAULT) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Requires ${SeedVaultContract.PERMISSION_ACCESS_SEED_VAULT}")
        }
    }

    /** Packages that share the uid of the caller. Only valid on the binder thread that was called. */
    private fun callerPackages(): Set<String> {
        enforceCallerPermission()
        return packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty().toSet()
    }

    private suspend fun requireAuthorized(
        packages: Set<String>,
        seedId: Long,
    ) {
        if (authorizedSeeds(packages).none { it.id == seedId }) {
            throw SecurityException("The calling app is not authorized for seed $seedId")
        }
    }

    private suspend fun authorizedSeeds(packages: Set<String>): List<VaultSeed> =
        seedVaultRepository
            .getSeeds()
            .first()
            .filter { seed -> seed.authorizedApps.any { it.packageName in packages } }

    private suspend fun VaultSeed.toRemoteSeed() =
        RemoteSeed(
            id = id,
            name = name,
            address = address,
            publicKey = Base64.encodeToString(checkNotNull(seedVaultRepository.getPublicKey(id)), Base64.NO_WRAP),
        )
}
