package com.michaeltchuang.seedvault.sdk

/**
 * Names for reaching the Seed Vault Service app. Must match SeedVaultContract in seedVaultService.
 */
object SeedVaultServiceConstants {
    const val PACKAGE = "com.michaeltchuang.seedvault.service"

    /** Permission needed to bind to the service. */
    const val PERMISSION_ACCESS_SEED_VAULT = "$PACKAGE.ACCESS_SEED_VAULT"

    /** Action to bind to the ISeedVaultService AIDL service. */
    const val ACTION_SEED_VAULT_SERVICE = "$PACKAGE.SEED_VAULT_SERVICE"

    /** Action of the activity where the user authorizes this app. Started with startActivityForResult. */
    const val ACTION_AUTHORIZE_APP = "$PACKAGE.AUTHORIZE_APP"

    /** Action of the activity where the user approves a signature. Started with startActivityForResult. */
    const val ACTION_CONFIRM_SIGN = "$PACKAGE.CONFIRM_SIGN"
    const val EXTRA_SEED_ID = "seed_id"
    const val EXTRA_DATA = "data"
    const val EXTRA_RECEIVER = "receiver"
    const val EXTRA_AMOUNT_MICRO_ALGOS = "amount_micro_algos"
    const val EXTRA_PARAMS_JSON = "params_json"
}
