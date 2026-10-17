package com.michaeltchuang.seedvault.service.data.vault

import com.michaeltchuang.seedvault.service.domain.model.AlgorandPaymentParams
import com.michaeltchuang.walletsdk.core.algosdk.recoverFalcon25Account
import uniffi.seed_vault_ffi.AppletErrorCode
import uniffi.seed_vault_ffi.KeyAlgorithm
import uniffi.seed_vault_ffi.ListedAccount
import uniffi.seed_vault_ffi.SeedVaultClient
import uniffi.seed_vault_ffi.SeedVaultClientInterface
import uniffi.seed_vault_ffi.SeedVaultException
import uniffi.seed_vault_ffi.SeedVaultStatus
import uniffi.seed_vault_ffi.humanSignIndex

/**
 * Keeps Falcon-1024 keys in the Trusty applet in the TEE, through the algokit seed vault client.
 * The private keys never leave the TEE, so the seed row only holds the address and public key.
 * It is only available on devices that have the applet and let this app talk to it.
 *
 * The applet signs transactions, not raw bytes, so [signBytes] is not supported.
 *
 * The applet keeps one key per address index: the human sign index needs the user to approve each
 * signature in the TEE, the autosign index does not. A seed vault wants approval, so seeds use the
 * human sign index.
 *
 * @param clientFactory creates the client lazily, it loads a native library
 * @param addressIndexProvider the address index of the keys, read lazily for the same reason
 */
internal class TrustySeedKeyVault(
    clientFactory: () -> SeedVaultClientInterface = { SeedVaultClient() },
    addressIndexProvider: () -> UByte = { humanSignIndex() },
) : SeedKeyVault {
    override val storage = SeedStorage.TRUSTY

    private val client by lazy(clientFactory)
    private val addressIndex by lazy(addressIndexProvider)

    /**
     * Any failure counts as unavailable: no applet, no permission to reach it, an incompatible
     * version, or the native library not loading on this device.
     */
    override suspend fun isAvailable(): Boolean = runCatching { client.status() is SeedVaultStatus.Available }.getOrDefault(false)

    override suspend fun addSeed(seedPhrase: List<String>): StoredSeed {
        val mnemonic = seedPhrase.joinToString(" ")
        val listed =
            try {
                val account = client.importAccount(mnemonic, KeyAlgorithm.FALCON1024, addressIndex)
                listAccount(account.address)
            } catch (e: SeedVaultException.Applet) {
                // The TEE keeps its accounts when the app's data is cleared, so the phrase may be there already
                if (e.code != AppletErrorCode.ACCOUNT_ALREADY_EXISTS) throw e
                listAccount(addressOf(mnemonic))
            }
        return StoredSeed(
            address = listed.account.address,
            publicKey = listed.publicKey,
            encryptedEntropy = ByteArray(0),
            encryptedPrivateKey = ByteArray(0),
        )
    }

    override suspend fun revealSeedPhrase(seed: StoredSeed): List<String> = client.revealMnemonic(seed.address).split(" ")

    override suspend fun signBytes(
        seed: StoredSeed,
        data: ByteArray,
    ): ByteArray? = throw UnsupportedOperationException("The Trusty applet signs transactions only, not raw bytes")

    override suspend fun signAlgorandPayment(
        seed: StoredSeed,
        receiver: String,
        amountMicroAlgos: Long,
        params: AlgorandPaymentParams,
    ): ByteArray? {
        val transaction = buildFalconPayment(seed.address, receiver, amountMicroAlgos, params) ?: return null
        val listed = listAccount(seed.address)
        return client.signTransaction(listed.account.address, listed.account.addressIndex.toUByte(), transaction).signedTxn
    }

    override suspend fun deleteSeed(seed: StoredSeed) {
        try {
            client.deleteAccount(seed.address)
        } catch (e: SeedVaultException.Applet) {
            // Already gone, which is what was asked for
            if (e.code != AppletErrorCode.ACCOUNT_NOT_FOUND) throw e
        }
    }

    private fun listAccount(address: String): ListedAccount =
        client.listAccounts().firstOrNull { it.account.address == address }
            ?: throw IllegalStateException("The Trusty applet has no account $address")

    /** Address of a seed phrase, for a phrase that is already in the applet. */
    private fun addressOf(mnemonic: String): String {
        val account = checkNotNull(recoverFalcon25Account(mnemonic)) { "Could not derive the Falcon-1024 account" }
        account.entropy.fill(0)
        account.seed.fill(0)
        account.privateKey.fill(0)
        return account.address
    }
}
