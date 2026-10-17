package com.michaeltchuang.walletsdk.seedvault.service.remote

import android.util.Base64
import com.michaeltchuang.walletsdk.seedvault.service.domain.model.AlgorandPaymentParams
import kotlinx.serialization.Serializable

/** Names client apps use to reach the Seed Vault Service. */
object SeedVaultContract {
    const val PACKAGE = "com.michaeltchuang.walletsdk.seedvault.service"

    /** Permission a client app must hold to bind to the service. */
    const val PERMISSION_ACCESS_SEED_VAULT = "$PACKAGE.ACCESS_SEED_VAULT"

    /** Action to bind to the [ISeedVaultService] AIDL service. */
    const val ACTION_SEED_VAULT_SERVICE = "$PACKAGE.SEED_VAULT_SERVICE"

    /** Action of the activity where the user authorizes the calling app. Start it with startActivityForResult. */
    const val ACTION_AUTHORIZE_APP = "$PACKAGE.AUTHORIZE_APP"
}

/** Algod values for `signAlgorandPayment`. [genesisHash] is Base64 encoded. */
@Serializable
data class RemoteAlgorandParams(
    val genesisId: String,
    val genesisHash: String,
    val firstValid: Long,
    val lastValid: Long,
) {
    internal fun toPaymentParams() =
        AlgorandPaymentParams(
            genesisId = genesisId,
            genesisHash = Base64.decode(genesisHash, Base64.DEFAULT),
            firstValid = firstValid,
            lastValid = lastValid,
        )
}

/** A seed as returned by `getAuthorizedSeeds`. [publicKey] is Base64 encoded. */
@Serializable
data class RemoteSeed(
    val id: Long,
    val name: String,
    val address: String,
    val publicKey: String,
)
