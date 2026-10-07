package com.michaeltchuang.walletsdk.core.solana.data

import com.michaeltchuang.walletsdk.core.encryption.decryptByteArray
import com.michaeltchuang.walletsdk.core.solana.crypto.SolanaKeypair
import com.michaeltchuang.walletsdk.core.solana.data.database.SolanaLinkedSignerDao
import com.michaeltchuang.walletsdk.core.solana.data.mapper.entity.SolanaLinkedSignerEntityMapper
import com.michaeltchuang.walletsdk.core.solana.data.mapper.model.SolanaLinkedSignerMapper
import com.michaeltchuang.walletsdk.core.solana.domain.SolanaLinkedSigner
import com.michaeltchuang.walletsdk.core.solana.domain.SolanaLinkedSignerSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

interface SolanaLinkedSignerRepository {
    suspend fun get(ownerAddress: String): SolanaLinkedSigner?

    fun observe(ownerAddress: String): Flow<SolanaLinkedSigner?>

    /**
     * Stores [keypair] as the owner's session key only if none exists yet, and returns whichever
     * key is stored afterwards. Safe against concurrent callers: an existing key is never replaced.
     */
    suspend fun createIfAbsent(
        ownerAddress: String,
        keypair: SolanaKeypair,
    ): SolanaLinkedSigner

    /** Replaces the owner's session key with [keypair]. The previous key is permanently discarded. */
    suspend fun replace(
        ownerAddress: String,
        keypair: SolanaKeypair,
    ): SolanaLinkedSigner

    /** Decrypts the signer's 32-byte private seed. Callers must zero it after use. */
    suspend fun getPrivateSeed(ownerAddress: String): ByteArray?
}

internal class SolanaLinkedSignerRepositoryImpl(
    private val dao: SolanaLinkedSignerDao,
    private val solanaLinkedSignerEntityMapper: SolanaLinkedSignerEntityMapper,
    private val solanaLinkedSignerMapper: SolanaLinkedSignerMapper,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SolanaLinkedSignerRepository {
    override suspend fun get(ownerAddress: String): SolanaLinkedSigner? =
        withContext(dispatcher) { dao.get(ownerAddress)?.let(solanaLinkedSignerMapper::invoke) }

    override fun observe(ownerAddress: String): Flow<SolanaLinkedSigner?> =
        dao.observe(ownerAddress).map { entity -> entity?.let(solanaLinkedSignerMapper::invoke) }

    override suspend fun createIfAbsent(
        ownerAddress: String,
        keypair: SolanaKeypair,
    ): SolanaLinkedSigner =
        withContext(dispatcher) {
            dao.insertIfAbsent(solanaLinkedSignerEntityMapper(newSigner(ownerAddress, keypair), keypair.privateSeed))
            dao.get(ownerAddress)?.let(solanaLinkedSignerMapper::invoke)
                ?: error("Failed to store session key")
        }

    override suspend fun replace(
        ownerAddress: String,
        keypair: SolanaKeypair,
    ): SolanaLinkedSigner =
        withContext(dispatcher) {
            val signer = newSigner(ownerAddress, keypair)
            dao.upsert(solanaLinkedSignerEntityMapper(signer, keypair.privateSeed))
            signer
        }

    override suspend fun getPrivateSeed(ownerAddress: String): ByteArray? =
        withContext(dispatcher) { dao.get(ownerAddress)?.let { decryptByteArray(it.encryptedPrivateSeed) } }

    @OptIn(ExperimentalTime::class)
    private fun newSigner(
        ownerAddress: String,
        keypair: SolanaKeypair,
    ): SolanaLinkedSigner {
        require(keypair.address != ownerAddress) { "The session key must differ from the Seed Vault account" }
        return SolanaLinkedSigner(
            ownerAddress = ownerAddress,
            signerAddress = keypair.address,
            source = SolanaLinkedSignerSource.GENERATED,
            derivationPath = null,
            createdAtMs = Clock.System.now().toEpochMilliseconds(),
        )
    }
}
