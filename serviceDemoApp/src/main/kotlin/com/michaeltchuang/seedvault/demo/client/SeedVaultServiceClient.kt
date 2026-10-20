package com.michaeltchuang.seedvault.demo.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.michaeltchuang.seedvault.service.ISeedVaultService
import com.michaeltchuang.seedvault.demo.SeedVaultServiceConstants
import com.michaeltchuang.seedvault.demo.data.model.SeedVaultSeed
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Client helper for binding to Seed Vault Service over AIDL.
 */
class SeedVaultServiceClient(
    private val context: Context,
) {
    private var service: ISeedVaultService? = null
    private var connection: ServiceConnection? = null

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Bind to the service, or return the existing connection.
     * Throws if the service app is not installed or this app lacks the ACCESS_SEED_VAULT permission.
     */
    suspend fun bindAsync(): ISeedVaultService =
        suspendCancellableCoroutine { continuation ->
            service?.let {
                continuation.resume(it)
                return@suspendCancellableCoroutine
            }

            val newConnection =
                object : ServiceConnection {
                    override fun onServiceConnected(
                        name: ComponentName?,
                        binder: IBinder?,
                    ) {
                        Log.d(TAG, "Service connected")
                        val svc = ISeedVaultService.Stub.asInterface(binder)
                        service = svc
                        if (continuation.isActive) continuation.resume(svc)
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        Log.d(TAG, "Service disconnected")
                        service = null
                    }
                }

            val intent =
                Intent(SeedVaultServiceConstants.ACTION_SEED_VAULT_SERVICE).apply {
                    setPackage(SeedVaultServiceConstants.PACKAGE)
                }
            try {
                if (context.bindService(intent, newConnection, Context.BIND_AUTO_CREATE)) {
                    connection = newConnection
                    continuation.invokeOnCancellation { unbind() }
                } else {
                    // bindService keeps a pending connection even when it returns false
                    context.unbindService(newConnection)
                    continuation.resumeWithException(
                        IllegalStateException("Could not bind to Seed Vault Service"),
                    )
                }
            } catch (e: SecurityException) {
                continuation.resumeWithException(e)
            }
        }

    fun unbind() {
        val current = connection ?: return
        try {
            context.unbindService(current)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Not bound to service", e)
        }
        connection = null
        service = null
    }

    fun isConnected(): Boolean = service != null

    private fun requireService(): ISeedVaultService = service ?: throw IllegalStateException("Not connected to Seed Vault Service. Call bindAsync() first.")

    /** Seeds the user has authorized this app to use. */
    fun getAuthorizedSeeds(): List<SeedVaultSeed> = json.decodeFromString(requireService().authorizedSeeds)

    /**
     * Sign [data] with the Falcon-1024 key of a seed. Throws SecurityException if this app is not
     * authorized for the seed.
     */
    fun signBytes(
        seedId: Long,
        data: ByteArray,
    ): ByteArray? = requireService().signBytes(seedId, data)

    /**
     * Ask the vault to build and sign an Algorand payment from a seed's account. [paramsJson] is the
     * network values from algod. Throws SecurityException if this app is not authorized for the seed.
     */
    fun signAlgorandPayment(
        seedId: Long,
        receiver: String,
        amountMicroAlgos: Long,
        paramsJson: String,
    ): ByteArray? = requireService().signAlgorandPayment(seedId, receiver, amountMicroAlgos, paramsJson)

    /** Intent for the authorization screen. Start it with startActivityForResult. */
    fun authorizeAppIntent(): Intent =
        Intent(SeedVaultServiceConstants.ACTION_AUTHORIZE_APP).apply {
            setPackage(SeedVaultServiceConstants.PACKAGE)
        }

    /**
     * Intent for the screen where the user approves signing [data] and verifies themselves. Start it
     * with startActivityForResult and call [signBytes] with the same values after RESULT_OK.
     */
    fun confirmSignBytesIntent(
        seedId: Long,
        data: ByteArray,
    ): Intent =
        confirmSignIntent(seedId).putExtra(SeedVaultServiceConstants.EXTRA_DATA, data)

    /** Same for [signAlgorandPayment]: pass the same values to it after RESULT_OK. */
    fun confirmSignPaymentIntent(
        seedId: Long,
        receiver: String,
        amountMicroAlgos: Long,
        paramsJson: String,
    ): Intent =
        confirmSignIntent(seedId)
            .putExtra(SeedVaultServiceConstants.EXTRA_RECEIVER, receiver)
            .putExtra(SeedVaultServiceConstants.EXTRA_AMOUNT_MICRO_ALGOS, amountMicroAlgos)
            .putExtra(SeedVaultServiceConstants.EXTRA_PARAMS_JSON, paramsJson)

    private fun confirmSignIntent(seedId: Long): Intent =
        Intent(SeedVaultServiceConstants.ACTION_CONFIRM_SIGN)
            .setPackage(SeedVaultServiceConstants.PACKAGE)
            .putExtra(SeedVaultServiceConstants.EXTRA_SEED_ID, seedId)

    private companion object {
        const val TAG = "SeedVaultServiceClient"
    }
}
