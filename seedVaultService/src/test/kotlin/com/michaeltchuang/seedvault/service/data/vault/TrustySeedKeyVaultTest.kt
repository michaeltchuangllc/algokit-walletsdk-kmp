package com.michaeltchuang.seedvault.service.data.vault

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.seed_vault_ffi.Account
import uniffi.seed_vault_ffi.AppletErrorCode
import uniffi.seed_vault_ffi.KeyAlgorithm
import uniffi.seed_vault_ffi.ListedAccount
import uniffi.seed_vault_ffi.ProtocolVersion
import uniffi.seed_vault_ffi.SeedVaultClientInterface
import uniffi.seed_vault_ffi.SeedVaultException
import uniffi.seed_vault_ffi.SeedVaultStatus
import uniffi.seed_vault_ffi.SignedArbitraryData
import uniffi.seed_vault_ffi.SignedTransaction

class TrustySeedKeyVaultTest {
    private val applet = FakeApplet()
    private val vault = TrustySeedKeyVault(clientFactory = { applet }, addressIndexProvider = { INDEX })
    private val seedPhrase = List(25) { "word$it" }

    private fun storedSeed(address: String = ADDRESS) =
        StoredSeed(address = address, publicKey = PUBLIC_KEY, encryptedEntropy = ByteArray(0), encryptedPrivateKey = ByteArray(0))

    @Test
    fun isAvailableWhenTheAppletIsThere() =
        runTest {
            assertTrue(vault.isAvailable())
        }

    @Test
    fun isNotAvailableWithoutTheApplet() =
        runTest {
            applet.status = SeedVaultStatus.ServiceNotFound("No applet on this device")
            assertFalse(vault.isAvailable())
        }

    @Test
    fun isNotAvailableWhenTheClientFails() =
        runTest {
            val broken = TrustySeedKeyVault(clientFactory = { error("Native library did not load") }, addressIndexProvider = { INDEX })
            assertFalse(broken.isAvailable())
        }

    @Test
    fun addSeedImportsAFalconAccountAndKeepsOnlyPublicValues() =
        runTest {
            val stored = vault.addSeed(seedPhrase)

            assertEquals(seedPhrase.joinToString(" "), applet.importedMnemonic)
            assertEquals(KeyAlgorithm.FALCON1024, applet.importedAlgorithm)
            assertEquals(INDEX, applet.importedIndex)
            assertEquals(ADDRESS, stored.address)
            assertArrayEquals(PUBLIC_KEY, stored.publicKey)
            assertEquals(0, stored.encryptedEntropy.size)
            assertEquals(0, stored.encryptedPrivateKey.size)
        }

    @Test
    fun revealSeedPhraseReturnsTheWords() =
        runTest {
            applet.mnemonic = seedPhrase.joinToString(" ")

            assertEquals(seedPhrase, vault.revealSeedPhrase(storedSeed()))
        }

    @Test
    fun signBytesReturnsTheAppletSignature() =
        runTest {
            val data = byteArrayOf(9, 8, 7)

            val signature = vault.signBytes(storedSeed(), data)

            assertArrayEquals(data.reversedArray(), signature)
            assertEquals(ADDRESS, applet.signedAddress)
            assertEquals(INDEX, applet.signedIndex)
        }

    @Test
    fun signBytesTurnsAReservedPrefixIntoAnIllegalArgument() =
        runTest {
            applet.signError = SeedVaultException.Applet(AppletErrorCode.RESERVED_DOMAIN_PREFIX)

            assertThrows(IllegalArgumentException::class.java) {
                kotlinx.coroutines.runBlocking { vault.signBytes(storedSeed(), byteArrayOf(1)) }
            }
        }

    @Test
    fun signBytesKeepsOtherAppletErrors() =
        runTest {
            applet.signError = SeedVaultException.Applet(AppletErrorCode.SIGNING_FAILED)

            assertThrows(SeedVaultException.Applet::class.java) {
                kotlinx.coroutines.runBlocking { vault.signBytes(storedSeed(), byteArrayOf(1)) }
            }
        }

    @Test
    fun deleteSeedRemovesTheAccount() =
        runTest {
            vault.deleteSeed(storedSeed())

            assertEquals(listOf(ADDRESS), applet.deleted)
        }

    @Test
    fun deleteSeedIgnoresAnAccountThatIsAlreadyGone() =
        runTest {
            applet.deleteError = SeedVaultException.Applet(AppletErrorCode.ACCOUNT_NOT_FOUND)

            vault.deleteSeed(storedSeed())
        }

    @Test
    fun deleteSeedFailsForOtherAppletErrors() =
        runTest {
            applet.deleteError = SeedVaultException.Applet(AppletErrorCode.STORE_FAILURE)

            assertThrows(SeedVaultException.Applet::class.java) {
                kotlinx.coroutines.runBlocking { vault.deleteSeed(storedSeed()) }
            }
        }

    private class FakeApplet : SeedVaultClientInterface {
        var status: SeedVaultStatus = SeedVaultStatus.Available(ProtocolVersion(1u, 0u))
        var mnemonic = ""
        var deleteError: SeedVaultException? = null
        var signError: SeedVaultException? = null
        var signedAddress: String? = null
        var signedIndex: UByte? = null
        var importedMnemonic: String? = null
        var importedAlgorithm: KeyAlgorithm? = null
        var importedIndex: UByte? = null
        val deleted = mutableListOf<String>()

        private val account = Account(KeyAlgorithm.FALCON1024, INDEX.toUInt(), ADDRESS)

        override fun status() = status

        override fun importAccount(
            mnemonic: String,
            algorithm: KeyAlgorithm,
            addressIndex: UByte,
        ): Account {
            importedMnemonic = mnemonic
            importedAlgorithm = algorithm
            importedIndex = addressIndex
            return account
        }

        override fun listAccounts() = listOf(ListedAccount(account, PUBLIC_KEY))

        override fun revealMnemonic(address: String) = mnemonic

        override fun deleteAccount(address: String): Account {
            deleteError?.let { throw it }
            deleted += address
            return account
        }

        override fun signTransaction(
            address: String,
            addressIndex: UByte,
            txn: ByteArray,
        ) = SignedTransaction(KeyAlgorithm.FALCON1024, txn, ByteArray(0))

        override fun signArbitraryData(
            address: String,
            addressIndex: UByte,
            data: ByteArray,
        ): SignedArbitraryData {
            signError?.let { throw it }
            signedAddress = address
            signedIndex = addressIndex
            return SignedArbitraryData(KeyAlgorithm.FALCON1024, data.reversedArray())
        }

        override fun createAccount(
            algorithm: KeyAlgorithm,
            addressIndex: UByte,
        ) = account

        override fun devicePath() = "/dev/trusty-ipc-dev0"

        override fun protocolVersion() = ProtocolVersion(1u, 0u)

        override fun serviceName() = "seed-vault"
    }

    private companion object {
        const val ADDRESS = "FALCONADDRESS"
        val PUBLIC_KEY = byteArrayOf(1, 2, 3)
        const val INDEX: UByte = 1u
    }
}
