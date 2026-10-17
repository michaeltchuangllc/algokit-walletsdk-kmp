package com.michaeltchuang.walletsdk.service.demo

/**
 * Names for reaching the Seed Vault Service app. Must match SeedVaultContract in seedVaultService.
 */
object SeedVaultServiceConstants {
    const val PACKAGE = "com.michaeltchuang.walletsdk.seedvault.service"

    /** Permission needed to bind to the service. */
    const val PERMISSION_ACCESS_SEED_VAULT = "$PACKAGE.ACCESS_SEED_VAULT"

    /** Action to bind to the ISeedVaultService AIDL service. */
    const val ACTION_SEED_VAULT_SERVICE = "$PACKAGE.SEED_VAULT_SERVICE"

    /** Action of the activity where the user authorizes this app. Started with startActivityForResult. */
    const val ACTION_AUTHORIZE_APP = "$PACKAGE.AUTHORIZE_APP"
}
