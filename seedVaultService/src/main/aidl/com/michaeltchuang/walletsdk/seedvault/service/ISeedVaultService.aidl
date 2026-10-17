package com.michaeltchuang.walletsdk.seedvault.service;

/**
 * AIDL interface of the Seed Vault Service. Client apps bind to the service with the
 * SEED_VAULT_SERVICE action and need the ACCESS_SEED_VAULT permission.
 *
 * A seed can only be used by an app the user has authorized for it. Apps get authorized by
 * launching the AUTHORIZE_APP activity with startActivityForResult. Seeds are never returned,
 * only their public data and signatures.
 *
 * Keep a copy of this file in sync in every client app.
 */
interface ISeedVaultService {

    /** Check if the service is ready to handle requests. */
    boolean isServiceReady();

    /**
     * Get the seeds the calling app is authorized to use.
     * Returns: JSON array of {id, name, address, publicKey} where publicKey is Base64 encoded
     * and address is the Falcon-1024 account address.
     */
    String getAuthorizedSeeds();

    /**
     * Sign a byte array with the Falcon-1024 private key of a seed.
     * @param seedId id of a seed returned by getAuthorizedSeeds
     * @param data the bytes to sign
     * @return the signature, or null if signing failed
     * @throws SecurityException if the calling app is not authorized for the seed
     */
    byte[] signBytes(long seedId, in byte[] data);

    /**
     * Build and sign an Algorand payment from a seed's Falcon-1024 account. The vault builds the
     * transaction itself, with the seed as sender and its own fee, so the caller cannot get anything
     * else signed.
     * @param seedId id of a seed returned by getAuthorizedSeeds
     * @param receiver Algorand address to pay
     * @param amountMicroAlgos amount in microAlgos, 0 is allowed
     * @param paramsJson JSON {"genesisId", "genesisHash" (Base64), "firstValid", "lastValid"} from algod
     * @return the signed transaction, ready to send to algod, or null if signing failed
     * @throws SecurityException if the calling app is not authorized for the seed
     */
    byte[] signAlgorandPayment(long seedId, String receiver, long amountMicroAlgos, String paramsJson);
}
