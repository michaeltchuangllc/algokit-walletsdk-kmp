package com.michaeltchuang.walletsdk.core.railmpp.smartcontract

import com.michaeltchuang.walletsdk.core.deeplink.utils.AssetConstants
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.NODE_FUTURENET_BASE_URL
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.NODE_MAINNET_BASE_URL
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.NODE_TESTNET_BASE_URL
import com.michaeltchuang.walletsdk.core.railmpp.MppNetworks
import com.michaeltchuang.walletsdk.core.railmpp.internal.decodeAlgorandAddressPublicKey
import com.michaeltchuang.walletsdk.core.railmpp.internal.encodeAlgorandAddress
import com.michaeltchuang.walletsdk.core.railmpp.internal.encodeArc4DynamicBytes
import com.michaeltchuang.walletsdk.core.railmpp.internal.encodeUint64
import com.michaeltchuang.walletsdk.core.railmpp.internal.getSessionBoxBytesInternal
import com.michaeltchuang.walletsdk.core.railmpp.internal.sha256
import com.michaeltchuang.walletsdk.core.railmpp.internal.sha512_256
import com.michaeltchuang.walletsdk.core.railmpp.internal.simulateReadonlyMethodInternal
import com.michaeltchuang.walletsdk.core.railmpp.utils.RailMppConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Stateless, read-only host lookup. Never consults or configures the shared escrow client. */
object HostViewerVaultReader {
    data class Snapshot(
        val remainingBalanceMicroUsdc: Long,
        /** Cumulative on-chain settlement, not the amount of the last receipt. */
        val lastSettledMicroUsdc: Long,
        val progressBalanceMicroUsdc: Long,
        val totalDepositMicroUsdc: Long,
    )

    /**
     * Reads one viewer's on-chain snapshot using an explicit [MppNetworks] Algorand network.
     * Missing channels, failed simulations and invalid data are failures, not zero balances.
     * Progress accounts for the on-chain voucher watermark, not unsubmitted off-chain vouchers.
     */
    suspend fun read(
        viewerAddress: String,
        creatorAddress: String,
        authorizedSignerPublicKey: ByteArray,
        network: String,
        salt: ByteArray,
    ): Result<Snapshot> =
        read(
            viewerAddress,
            creatorAddress,
            authorizedSignerPublicKey,
            network,
            salt,
            ::simulateReadonlyMethodInternal,
        )

    /**
     * Reads an untrusted channel hint without needing its salt. Validates the session box's
     * payer, payee and current signer hash first. This lookup does not authorize payments.
     * Identity and dynamic data are separate on-chain reads, not an atomic authorization proof.
     */
    suspend fun readChannel(
        channelId: ByteArray,
        viewerAddress: String,
        creatorAddress: String,
        authorizedSignerPublicKey: ByteArray,
        network: String,
    ): Result<Snapshot> =
        readChannel(
            channelId,
            viewerAddress,
            creatorAddress,
            authorizedSignerPublicKey,
            network,
            ::getSessionBoxBytesInternal,
            ::simulateReadonlyMethodInternal,
        )

    internal suspend fun readChannel(
        channelId: ByteArray,
        viewerAddress: String,
        creatorAddress: String,
        authorizedSignerPublicKey: ByteArray,
        network: String,
        readBox: (Long, ByteArray, String) -> ByteArray,
        simulate: (Long, String, ByteArray, List<ByteArray>, List<Pair<Long, ByteArray>>) -> ByteArray?,
    ): Result<Snapshot> {
        val channel = channelId.copyOf()
        val signerKey = authorizedSignerPublicKey.copyOf()
        return withContext(Dispatchers.Default) {
            try {
                require(channel.size == 32) { "channelId must be 32 bytes" }
                require(signerKey.isNotEmpty()) { "authorizedSignerPublicKey must not be empty" }
                val payer = decodeAddress(viewerAddress)
                val payee = decodeAddress(creatorAddress)
                val config = networkConfig(network)
                val box = readBox(config.appId, channel, config.algodUrl)
                currentCoroutineContext().ensureActive()
                validateSessionIdentity(box, payer, payee, signerKey)
                val storedSigner = readBox(config.appId, "p".encodeToByteArray() + channel, config.algodUrl)
                currentCoroutineContext().ensureActive()
                // The contract stores AVMBytes here, not an ARC-4 dynamic byte array.
                // Only the channel's signer-hash field above has an ARC-4 length prefix.
                require(storedSigner.contentEquals(signerKey)) { "Channel signer key mismatch" }
                val snapshot = simulateSnapshot(config, channel, simulate)
                currentCoroutineContext().ensureActive()
                Result.success(snapshot)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun validateSessionIdentity(
        box: ByteArray,
        payer: ByteArray,
        payee: ByteArray,
        signerKey: ByteArray,
    ) {
        // ARC-56 ChannelInfo: address,address,byte[],six uint64s.
        // The 114-byte head holds a uint16 offset at 64; its tail is uint16(32) + signer hash.
        // Verified against contract.algo.ts and the generated approval program's extract_uint16 64.
        require(box.size == 148) { "Unexpected session box size=${box.size}" }
        require(box.copyOfRange(0, 32).contentEquals(payer)) { "Session payer mismatch" }
        require(box.copyOfRange(32, 64).contentEquals(payee)) { "Session payee mismatch" }
        require(box[64] == 0.toByte() && box[65] == 114.toByte()) { "Invalid session signer offset" }
        require(box[114] == 0.toByte() && box[115] == 32.toByte()) { "Invalid session signer hash length" }
        require(box.copyOfRange(116, 148).contentEquals(sha512_256(signerKey))) { "Session signer mismatch" }
    }

    // Injection is per invocation, never shared mutable state.
    internal suspend fun read(
        viewerAddress: String,
        creatorAddress: String,
        authorizedSignerPublicKey: ByteArray,
        network: String,
        salt: ByteArray,
        simulate: (Long, String, ByteArray, List<ByteArray>, List<Pair<Long, ByteArray>>) -> ByteArray?,
    ): Result<Snapshot> {
        val signerKey = authorizedSignerPublicKey.copyOf()
        val channelSalt = salt.copyOf()
        return withContext(Dispatchers.Default) {
            try {
                val config = networkConfig(network)
                val channelId = deriveChannelId(viewerAddress, creatorAddress, signerKey, network, channelSalt)
                val snapshot = simulateSnapshot(config, channelId, simulate)
                currentCoroutineContext().ensureActive()
                Result.success(snapshot)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun simulateSnapshot(
        config: NetworkConfig,
        channelId: ByteArray,
        simulate: (Long, String, ByteArray, List<ByteArray>, List<Pair<Long, ByteArray>>) -> ByteArray?,
    ): Snapshot {
        val simulated =
            simulate(
                config.appId,
                config.algodUrl,
                // ABI_GET_SESSION_DYNAMIC_DATA: getSessionDynamicData(byte[])(uint64,uint64,uint64,address)
                byteArrayOf(0xcc.toByte(), 0xde.toByte(), 0x9f.toByte(), 0xb6.toByte()),
                listOf(encodeArc4DynamicBytes(channelId)),
                listOf(
                    config.appId to channelId,
                    config.appId to ("l".encodeToByteArray() + channelId),
                ),
            )
        return decodeSnapshot(simulated ?: error("Session vault readonly simulation returned no data"))
    }

    internal fun deriveChannelId(
        viewerAddress: String,
        creatorAddress: String,
        authorizedSignerPublicKey: ByteArray,
        network: String,
        salt: ByteArray,
    ): ByteArray {
        require(authorizedSignerPublicKey.isNotEmpty()) { "authorizedSignerPublicKey must not be empty" }
        val config = networkConfig(network)
        return sha256(
            decodeAddress(viewerAddress) + decodeAddress(creatorAddress) +
                encodeUint64(config.assetId) + salt + sha512_256(authorizedSignerPublicKey),
        )
    }

    internal fun decodeSnapshot(bytes: ByteArray): Snapshot {
        // Fixed-width ARC-4 (uint64,uint64,uint64,address), with return-log prefix already stripped.
        require(bytes.size == 56) { "Unexpected session dynamic data size=${bytes.size}" }
        val total = decodeAmount(bytes, 0)
        val settled = decodeAmount(bytes, 8)
        val voucher = decodeAmount(bytes, 16)
        require(settled <= total && voucher <= total) { "Session amounts exceed total deposit" }
        return Snapshot(
            // Matches MppPayments' remaining balance and progress semantics using one coherent read.
            remainingBalanceMicroUsdc = total - settled,
            lastSettledMicroUsdc = settled,
            progressBalanceMicroUsdc = total - maxOf(settled, voucher),
            totalDepositMicroUsdc = total,
        )
    }

    private fun decodeAmount(
        bytes: ByteArray,
        offset: Int,
    ): Long {
        require(bytes[offset] >= 0) { "Session uint64 amount exceeds Long.MAX_VALUE" }
        var value = 0L
        for (i in offset until offset + 8) {
            value = (value shl 8) or (bytes[i].toLong() and 0xff)
        }
        return value
    }

    private fun decodeAddress(address: String): ByteArray {
        val publicKey = decodeAlgorandAddressPublicKey(address)
        // The shared decoder only checks minimum length; also enforce canonical encoding/checksum.
        require(encodeAlgorandAddress(publicKey) == address) { "Invalid Algorand address" }
        return publicKey
    }

    internal data class NetworkConfig(
        val appId: Long,
        val assetId: Long,
        val algodUrl: String,
    )

    internal fun networkConfig(network: String): NetworkConfig =
        when (network) {
            MppNetworks.ALGORAND_MAINNET ->
                NetworkConfig(
                    RailMppConstants.MAINNET_MPP_SESSION_VAULT_APP_ID,
                    AssetConstants.USDC_MAINNET_ID,
                    NODE_MAINNET_BASE_URL,
                )
            MppNetworks.ALGORAND_TESTNET ->
                NetworkConfig(
                    RailMppConstants.TESTNET_MPP_SESSION_VAULT_APP_ID,
                    AssetConstants.USDC_TESTNET_ID,
                    NODE_TESTNET_BASE_URL,
                )
            MppNetworks.ALGORAND_FUTURENET ->
                NetworkConfig(
                    RailMppConstants.FUTURENET_MPP_SESSION_VAULT_APP_ID,
                    AssetConstants.USDC_FUTURENET_ID,
                    NODE_FUTURENET_BASE_URL,
                )
            else -> error("Unsupported session vault network: $network")
        }
}
