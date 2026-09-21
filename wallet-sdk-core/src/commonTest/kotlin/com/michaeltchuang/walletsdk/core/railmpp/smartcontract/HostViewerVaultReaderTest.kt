package com.michaeltchuang.walletsdk.core.railmpp.smartcontract

import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.NODE_FUTURENET_BASE_URL
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.NODE_MAINNET_BASE_URL
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.NODE_TESTNET_BASE_URL
import com.michaeltchuang.walletsdk.core.railmpp.MppNetworks
import com.michaeltchuang.walletsdk.core.railmpp.internal.encodeAlgorandAddress
import com.michaeltchuang.walletsdk.core.railmpp.internal.encodeArc4DynamicBytes
import com.michaeltchuang.walletsdk.core.railmpp.internal.encodeUint64
import com.michaeltchuang.walletsdk.core.railmpp.internal.sha512_256
import com.michaeltchuang.walletsdk.core.railmpp.utils.RailMppConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HostViewerVaultReaderTest {
    private val viewer = encodeAlgorandAddress(ByteArray(32) { it.toByte() })
    private val creator = encodeAlgorandAddress(ByteArray(32) { (it + 32).toByte() })
    private val secondViewer = encodeAlgorandAddress(ByteArray(32) { (it + 64).toByte() })
    private val signer = byteArrayOf(1, 2, 3, 4)
    private val salt = byteArrayOf(5, 6, 7)
    private val network = MppNetworks.ALGORAND_TESTNET

    @Test
    fun `EXPECT channel derivation to match independent contract vectors WHEN run for every network`() {
        // Independently generated using SHA-256 and OpenSSL SHA-512/256, not the escrow singleton.
        val vectors =
            mapOf(
                MppNetworks.ALGORAND_MAINNET to "b19f3ea6822fe9c0c3d8aa003494f2cd0147ea392dcac053901a7b5bcab93436",
                MppNetworks.ALGORAND_TESTNET to "8583d826d56af314e6fda22db5718b93b27333e6e05eca1a2c2d7ece97a99003",
                MppNetworks.ALGORAND_FUTURENET to "0aeb126153836ff8826b04325ad8ed351a1d69f9e419733881f99eb647df7426",
            )
        vectors.forEach { (network, expected) ->
            val actual = HostViewerVaultReader.deriveChannelId(viewer, creator, signer, network, salt)
            assertContentEquals(expected.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), actual)
        }
    }

    @Test
    fun `EXPECT the channel id to change WHEN viewer, creator, signer, or salt differ`() {
        val first = HostViewerVaultReader.deriveChannelId(viewer, creator, signer, network, salt)
        val others =
            listOf(
                HostViewerVaultReader.deriveChannelId(secondViewer, creator, signer, network, salt),
                HostViewerVaultReader.deriveChannelId(viewer, secondViewer, signer, network, salt),
                HostViewerVaultReader.deriveChannelId(viewer, creator, byteArrayOf(9), network, salt),
                HostViewerVaultReader.deriveChannelId(viewer, creator, signer, network, byteArrayOf(8)),
            )
        others.forEach { assertFalse(first.contentEquals(it)) }
        assertContentEquals(first, HostViewerVaultReader.deriveChannelId(viewer, creator, signer, network, salt))
        assertContentEquals(byteArrayOf(1, 2, 3, 4), signer)
        assertContentEquals(byteArrayOf(5, 6, 7), salt)
    }

    @Test
    fun `EXPECT a single readonly call to use the explicit network and both box keys WHEN reading a snapshot`() =
        runTest {
            val networks =
                listOf(
                    Triple(MppNetworks.ALGORAND_MAINNET, RailMppConstants.MAINNET_MPP_SESSION_VAULT_APP_ID, NODE_MAINNET_BASE_URL),
                    Triple(MppNetworks.ALGORAND_TESTNET, RailMppConstants.TESTNET_MPP_SESSION_VAULT_APP_ID, NODE_TESTNET_BASE_URL),
                    Triple(MppNetworks.ALGORAND_FUTURENET, RailMppConstants.FUTURENET_MPP_SESSION_VAULT_APP_ID, NODE_FUTURENET_BASE_URL),
                )
            networks.forEach { (network, expectedAppId, expectedUrl) ->
                var calls = 0
                val channel = HostViewerVaultReader.deriveChannelId(viewer, creator, signer, network, salt)
                val result =
                    HostViewerVaultReader.read(viewer, creator, signer, network, salt) { appId, url, selector, args, boxes ->
                        calls++
                        assertEquals(expectedAppId, appId)
                        assertEquals(expectedUrl, url)
                        assertContentEquals(byteArrayOf(0xcc.toByte(), 0xde.toByte(), 0x9f.toByte(), 0xb6.toByte()), selector)
                        assertEquals(1, args.size)
                        assertContentEquals(encodeArc4DynamicBytes(channel), args.single())
                        assertEquals(listOf(appId, appId), boxes.map { it.first })
                        assertContentEquals(channel, boxes[0].second)
                        assertContentEquals("l".encodeToByteArray() + channel, boxes[1].second)
                        tuple(1_000, 200, 350)
                    }
                assertEquals(HostViewerVaultReader.Snapshot(800, 200, 650, 1_000), result.getOrThrow())
                assertEquals(1, calls)
            }
        }

    @Test
    fun `EXPECT each viewer to keep its own channel and snapshot WHEN reads run concurrently`() =
        runTest {
            val results =
                listOf(viewer, secondViewer, viewer)
                    .map { address ->
                        async {
                            val expectedChannel = HostViewerVaultReader.deriveChannelId(address, creator, signer, network, salt)
                            HostViewerVaultReader
                                .read(address, creator, signer, network, salt) { _, _, _, args, boxes ->
                                    assertContentEquals(encodeArc4DynamicBytes(expectedChannel), args.single())
                                    assertContentEquals(expectedChannel, boxes.first().second)
                                    if (address == viewer) tuple(1_000, 200, 350) else tuple(9_000, 1_000, 2_000)
                                }.getOrThrow()
                        }
                    }.awaitAll()
            assertEquals(HostViewerVaultReader.Snapshot(800, 200, 650, 1_000), results[0])
            assertEquals(HostViewerVaultReader.Snapshot(8_000, 1_000, 7_000, 9_000), results[1])
            assertEquals(results[0], results[2])
        }

    @Test
    fun `EXPECT cumulative settlement and progress semantics to be preserved WHEN decoding a snapshot`() {
        assertEquals(HostViewerVaultReader.Snapshot(800, 200, 650, 1_000), decode(1_000, 200, 350))
        assertEquals(HostViewerVaultReader.Snapshot(800, 200, 800, 1_000), decode(1_000, 200, 100))
        assertEquals(HostViewerVaultReader.Snapshot(0, 1_000, 0, 1_000), decode(1_000, 1_000, 1_000))
        assertEquals(HostViewerVaultReader.Snapshot(0, 0, 0, 0), decode(0, 0, 0))
        assertEquals(Long.MAX_VALUE, decode(Long.MAX_VALUE, 0, 0).remainingBalanceMicroUsdc)
    }

    @Test
    fun `EXPECT decoding to fail WHEN the tuple is truncated, overflows unsigned, or has inconsistent amounts`() {
        listOf(0, 23, 24, 55, 57).forEach { size ->
            assertFailsWith<IllegalArgumentException> { HostViewerVaultReader.decodeSnapshot(ByteArray(size)) }
        }
        listOf(tuple(-1, 0, 0), tuple(100, Long.MIN_VALUE, 0), tuple(100, 0, -1)).forEach {
            assertFailsWith<IllegalArgumentException> { HostViewerVaultReader.decodeSnapshot(it) }
        }
        assertFailsWith<IllegalArgumentException> { decode(100, 101, 0) }
        assertFailsWith<IllegalArgumentException> { decode(100, 0, 101) }
    }

    @Test
    fun `EXPECT invalid inputs to fail without reading and missing data to not be treated as zero WHEN reading a snapshot`() =
        runTest {
            suspend fun invalid(
                viewerAddress: String = viewer,
                creatorAddress: String = creator,
                key: ByteArray = signer,
                network: String = this@HostViewerVaultReaderTest.network,
            ) {
                var called = false
                val result =
                    HostViewerVaultReader.read(viewerAddress, creatorAddress, key, network, salt) { _, _, _, _, _ ->
                        called = true
                        tuple(0, 0, 0)
                    }
                assertTrue(result.isFailure)
                assertFalse(called)
            }
            invalid(network = "testnet")
            invalid(network = MppNetworks.SOLANA_MAINNET)
            invalid(key = byteArrayOf())
            invalid(viewerAddress = "invalid")
            invalid(creatorAddress = creator.dropLast(1) + if (creator.last() == 'A') "B" else "A")
            assertTrue(
                HostViewerVaultReader.read(viewer, creator, signer, network, salt) { _, _, _, _, _ -> null }.isFailure,
            )
            val failure = IllegalStateException("network unavailable")
            assertSame(
                failure,
                HostViewerVaultReader.read(viewer, creator, signer, network, salt) { _, _, _, _, _ -> throw failure }.exceptionOrNull(),
            )
        }

    @Test
    fun `EXPECT cancellation to be rethrown WHEN the read is cancelled`() =
        runTest {
            val cancelled = CancellationException("cancelled read")
            val thrown =
                assertFailsWith<CancellationException> {
                    HostViewerVaultReader.read(viewer, creator, signer, network, salt) { _, _, _, _, _ -> throw cancelled }
                }
            // Coroutine stacktrace recovery may copy the exception across the dispatcher boundary.
            assertEquals(cancelled.message, thrown.message)
        }

    @Test
    fun `EXPECT identity to be validated before reading the snapshot WHEN an explicit channel is provided without salt`() =
        runTest {
            val hint = ByteArray(32) { 42 }
            var boxCalls = 0
            var simulateCalls = 0
            val result =
                HostViewerVaultReader.readChannel(
                    hint,
                    viewer,
                    creator,
                    signer,
                    network,
                    readBox = { appId, key, url ->
                        boxCalls++
                        assertEquals(RailMppConstants.TESTNET_MPP_SESSION_VAULT_APP_ID, appId)
                        assertEquals(NODE_TESTNET_BASE_URL, url)
                        if (boxCalls == 1) {
                            assertContentEquals(hint, key)
                            sessionBox()
                        } else {
                            assertContentEquals("p".encodeToByteArray() + hint, key)
                            // p || channelId is raw AVMBytes in the deployed contract.
                            signer.copyOf()
                        }
                    },
                    simulate = { _, _, _, args, boxes ->
                        simulateCalls++
                        // Both participant and authorized-signer boxes must be verified first.
                        assertEquals(2, boxCalls)
                        assertContentEquals(encodeArc4DynamicBytes(hint), args.single())
                        assertContentEquals(hint, boxes.first().second)
                        tuple(1000, 300, 500)
                    },
                )
            assertEquals(HostViewerVaultReader.Snapshot(700, 300, 500, 1000), result.getOrThrow())
            assertEquals(2, boxCalls)
            assertEquals(1, simulateCalls)
        }

    @Test
    fun `EXPECT a raw passkey-sized signer box to not be decoded as an ARC-4 length WHEN reading via an explicit channel`() =
        runTest {
            val rawKey =
                ByteArray(1793) { (it % 251).toByte() }.also {
                    it[0] = 0x0a
                    it[1] = 0x7f
                }
            val channel = ByteArray(32) { 42 }
            val channelBox =
                ByteArray(32) { it.toByte() } + ByteArray(32) { (it + 32).toByte() } +
                    byteArrayOf(0, 114) + ByteArray(48) + encodeArc4DynamicBytes(sha512_256(rawKey))
            val snapshot =
                HostViewerVaultReader
                    .readChannel(
                        channel,
                        viewer,
                        creator,
                        rawKey,
                        network,
                        readBox = { _, key, _ -> if (key.size == 32) channelBox else rawKey },
                        simulate = { _, _, _, _, _ -> tuple(7_000_000, 200_000, 200_000) },
                    ).getOrThrow()
            assertEquals(6_800_000, snapshot.remainingBalanceMicroUsdc)
        }

    @Test
    fun `EXPECT identity mismatches and malformed boxes to be rejected WHEN reading via an explicit channel`() =
        runTest {
            val invalidBoxes =
                listOf(
                    sessionBox().also { it[0] = 99 },
                    sessionBox().also { it[32] = 99 },
                    sessionBox().also { it[116] = (it[116].toInt() xor 1).toByte() },
                    sessionBox().also { it[65] = 113 },
                    sessionBox().also { it[115] = 31 },
                    sessionBox().copyOf(147),
                    ByteArray(64),
                    byteArrayOf(),
                )
            var simulateCalls = 0
            for (box in invalidBoxes) {
                val result =
                    HostViewerVaultReader.readChannel(
                        ByteArray(32),
                        viewer,
                        creator,
                        signer,
                        network,
                        readBox = { _, _, _ -> box },
                        simulate = { _, _, _, _, _ ->
                            simulateCalls++
                            tuple(1000, 0, 0)
                        },
                    )
                assertTrue(result.isFailure)
            }
            assertEquals(0, simulateCalls)
        }

    @Test
    fun `EXPECT a missing box to fail and cancellation to escape WHEN reading via an explicit channel`() =
        runTest {
            val failure = IllegalStateException("Box not found")
            val result =
                HostViewerVaultReader.readChannel(
                    ByteArray(32),
                    viewer,
                    creator,
                    signer,
                    network,
                    readBox = { _, _, _ -> throw failure },
                    simulate = { _, _, _, _, _ -> error("Must not simulate") },
                )
            assertSame(failure, result.exceptionOrNull())
            assertFailsWith<CancellationException> {
                HostViewerVaultReader.readChannel(
                    ByteArray(32),
                    viewer,
                    creator,
                    signer,
                    network,
                    readBox = { _, _, _ -> throw CancellationException("Cancelled") },
                    simulate = { _, _, _, _, _ -> error("Must not simulate") },
                )
            }
        }

    @Test
    fun `EXPECT missing, malformed, or mismatched signer boxes to be rejected WHEN reading via a channel hint`() =
        runTest {
            val invalidSigners =
                listOf(
                    byteArrayOf(),
                    byteArrayOf(0),
                    byteArrayOf(0, 0),
                    byteArrayOf(0, 4, 1),
                    encodeArc4DynamicBytes(signer),
                    encodeArc4DynamicBytes(signer) + byteArrayOf(0),
                    byteArrayOf(5, 6, 7, 8),
                )
            for (storedSigner in invalidSigners) {
                var simulated = false
                val result =
                    HostViewerVaultReader.readChannel(
                        ByteArray(32),
                        viewer,
                        creator,
                        signer,
                        network,
                        readBox = { _, key, _ -> if (key.size == 32) sessionBox() else storedSigner },
                        simulate = { _, _, _, _, _ ->
                            simulated = true
                            tuple(1000, 0, 0)
                        },
                    )
                assertTrue(result.isFailure)
                assertFalse(simulated)
            }
        }

    private fun sessionBox(): ByteArray =
        ByteArray(32) { it.toByte() } + ByteArray(32) { (it + 32).toByte() } +
            byteArrayOf(0, 114) + ByteArray(48) + encodeArc4DynamicBytes(sha512_256(signer))

    private fun decode(
        total: Long,
        settled: Long,
        voucher: Long,
    ) = HostViewerVaultReader.decodeSnapshot(tuple(total, settled, voucher))

    private fun tuple(
        total: Long,
        settled: Long,
        voucher: Long,
    ) = encodeUint64(total) + encodeUint64(settled) + encodeUint64(voucher) + ByteArray(32)
}
