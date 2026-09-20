package com.michaeltchuang.walletsdk.core.railmpp.smartcontract

import com.michaeltchuang.walletsdk.core.deeplink.utils.AssetConstants
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.NODE_FUTURENET_BASE_URL
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.NODE_MAINNET_BASE_URL
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.NODE_TESTNET_BASE_URL
import com.michaeltchuang.walletsdk.core.railmpp.MppNetworks
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.internal.decodeAlgorandAddressPublicKey
import com.michaeltchuang.walletsdk.core.railmpp.internal.encodeAlgorandAddress
import com.michaeltchuang.walletsdk.core.railmpp.internal.encodeArc4DynamicBytes
import com.michaeltchuang.walletsdk.core.railmpp.internal.encodeUint64
import com.michaeltchuang.walletsdk.core.railmpp.internal.sha512_256
import com.michaeltchuang.walletsdk.core.railmpp.utils.RailMppConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** All I/O is injected: these tests never compile LogicSigs, fund accounts or broadcast. */
class ViewerVaultSettlementTest {
    private val viewer = encodeAlgorandAddress(ByteArray(32) { it.toByte() })
    private val creator = encodeAlgorandAddress(ByteArray(32) { (it + 32).toByte() })
    private val otherViewer = encodeAlgorandAddress(ByteArray(32) { (it + 64).toByte() })
    private val channel = ByteArray(32) { 42 }
    // A raw Falcon-sized key whose first two bytes are NOT an ARC-4 length.
    private val key = ByteArray(1793) { (it % 251).toByte() }.also {
        it[0] = 0x0a
        it[1] = 0x7f
    }
    private val signature = byteArrayOf(7, 8, 9)
    private val network = MppNetworks.ALGORAND_TESTNET
    private val funder =
        object : MppWalletSigner {
            override val address = creator
            override val authorizedSignerPublicKey = byteArrayOf(99)

            override suspend fun signTransactionBytes(txnMsgpack: ByteArray): ByteArray =
                error("Fake tests must never sign transactions")
        }

    @Test
    fun submitsExactCumulativeVoucherWithExplicitConfigForEveryNetwork() =
        runTest {
            val configs =
                listOf(
                    ExpectedConfig(
                        MppNetworks.ALGORAND_MAINNET,
                        RailMppConstants.MAINNET_MPP_SESSION_VAULT_APP_ID,
                        AssetConstants.USDC_MAINNET_ID,
                        NODE_MAINNET_BASE_URL,
                    ),
                    ExpectedConfig(
                        MppNetworks.ALGORAND_TESTNET,
                        RailMppConstants.TESTNET_MPP_SESSION_VAULT_APP_ID,
                        AssetConstants.USDC_TESTNET_ID,
                        NODE_TESTNET_BASE_URL,
                    ),
                    ExpectedConfig(
                        MppNetworks.ALGORAND_FUTURENET,
                        RailMppConstants.FUTURENET_MPP_SESSION_VAULT_APP_ID,
                        AssetConstants.USDC_FUTURENET_ID,
                        NODE_FUTURENET_BASE_URL,
                    ),
                )
            for (config in configs) {
                val fake = Fake(config)
                assertEquals("fake-tx", fake.settle(cumulativeAmount = 1_000).getOrThrow())
                assertEquals(2, fake.boxReads)
                assertEquals(1, fake.simulations)
                assertEquals(1, fake.signatureChecks)
                assertEquals(1, fake.submissions)
                // It forwards the authorized cumulative amount, not 1_000 - lastSettled.
                assertEquals(1_000L, fake.submittedAmount)
            }
        }

    @Test
    fun companionValidationNeedsNoWalletAndRejectsForgedHighWatermark() =
        runTest {
            var checks = 0
            suspend fun validate(amount: Long, sig: ByteArray): Result<HostViewerVaultReader.Snapshot> =
                ViewerVaultSettlement.validateVoucher(
                    viewer, creator, key, channel, sig, amount, network,
                    readBox = { _, boxKey, _ -> if (boxKey.size == 32) sessionBox() else key },
                    simulate = { _, _, _, _, _ -> tuple(1_000, 300, 500) },
                    validateSignature = { signer, app, asset, url, id, cumulative, signatureBytes, publicKey, payee ->
                        checks++
                        assertEquals(creator, signer.address)
                        assertFailsWith<IllegalStateException> { signer.signTransactionBytes(byteArrayOf(1)) }
                        assertEquals(RailMppConstants.TESTNET_MPP_SESSION_VAULT_APP_ID, app)
                        assertEquals(AssetConstants.USDC_TESTNET_ID, asset)
                        assertEquals(NODE_TESTNET_BASE_URL, url)
                        assertContentEquals(channel, id)
                        assertContentEquals(key, publicKey)
                        assertEquals(creator, payee)
                        require(cumulative == 500L && signatureBytes.contentEquals(signature))
                    },
                )

            assertTrue(validate(1_000, byteArrayOf(0)).isFailure)
            assertEquals(HostViewerVaultReader.Snapshot(700, 300, 500, 1_000), validate(500, signature).getOrThrow())
            assertEquals(2, checks)
        }

    @Test
    fun everyAcceptedVoucherMustPassCurrentDepositAndSignatureChecks() =
        runTest {
            val fake = Fake()
            assertTrue(fake.api.validateVoucher(viewer, creator, key, channel, signature, 500, network).isSuccess)
            assertEquals(1, fake.signatureChecks)
            assertEquals(0, fake.submissions)

            fake.dynamicData = tuple(600, 300, 500)
            assertTrue(fake.api.validateVoucher(viewer, creator, key, channel, signature, 700, network).isFailure)
            assertEquals(2, fake.simulations)
            assertEquals(1, fake.signatureChecks)
            assertEquals(0, fake.submissions)

            fake.validationFailure = IllegalArgumentException("Invalid signature")
            assertTrue(fake.api.validateVoucher(viewer, creator, key, channel, signature, 600, network).isFailure)
            assertEquals(3, fake.simulations)
            assertEquals(2, fake.signatureChecks)
            assertEquals(0, fake.submissions)

            fake.validationFailure = null
            assertTrue(fake.api.validateVoucher(viewer, creator, key, channel, signature, 550, network).isSuccess)
            assertEquals(4, fake.simulations)
            assertEquals(3, fake.signatureChecks)
            assertEquals(0, fake.submissions)
        }

    @Test
    fun readSnapshotReusesIdentityValidationAndNeverSubmits() =
        runTest {
            val fake = Fake()
            assertEquals(
                HostViewerVaultReader.Snapshot(700, 300, 500, 1_000),
                fake.api.readSnapshot(viewer, creator, key, channel, network).getOrThrow(),
            )
            assertEquals(2, fake.boxReads)
            assertEquals(1, fake.simulations)
            assertEquals(0, fake.submissions)
            assertEquals(0, fake.signatureChecks)
        }

    @Test
    fun forgedVoucherCannotBeAcceptedOrReachFundingSubmission() =
        runTest {
            val rejected = Fake().also { it.validationFailure = IllegalArgumentException("Invalid Falcon signature") }
            assertTrue(rejected.api.validateVoucher(viewer, creator, key, channel, signature, 1_000, network).isFailure)
            assertTrue(rejected.settle(cumulativeAmount = 1_000).isFailure)
            assertEquals(2, rejected.signatureChecks)
            assertEquals(0, rejected.submissions)
            // A forged high watermark is not retained and cannot poison subsequent valid input.
            rejected.validationFailure = null
            assertEquals("fake-tx", rejected.settle(cumulativeAmount = 500).getOrThrow())
            assertEquals(1, rejected.submissions)
        }

    @Test
    fun acceptanceValidatesWithoutSubmittingAndSettlementRevalidates() =
        runTest {
            val fake = Fake()
            assertEquals(
                HostViewerVaultReader.Snapshot(700, 300, 500, 1_000),
                fake.api.validateVoucher(viewer, creator, key, channel, signature, 500, network).getOrThrow(),
            )
            assertEquals(1, fake.signatureChecks)
            assertEquals(0, fake.submissions)
            // No cached authorization: revoked/changed state between acceptance and settlement fails.
            fake.validationFailure = IllegalStateException("Simulation rejected")
            assertTrue(fake.settle().isFailure)
            assertEquals(2, fake.signatureChecks)
            assertEquals(0, fake.submissions)
        }

    @Test
    fun everyAcceptedVoucherChecksCurrentDepositBeforeSignatureAndNeverSubmits() =
        runTest {
            val fake = Fake()
            assertTrue(fake.api.validateVoucher(viewer, creator, key, channel, signature, 500, network).isSuccess)
            assertEquals(1, fake.signatureChecks)
            assertTrue(fake.api.validateVoucher(viewer, creator, key, channel, signature, 1_001, network).isFailure)
            assertEquals(1, fake.signatureChecks)
            fake.dynamicData = tuple(600, 300, 500)
            assertTrue(fake.api.validateVoucher(viewer, creator, key, channel, signature, 700, network).isFailure)
            assertEquals(1, fake.signatureChecks)
            assertTrue(fake.api.validateVoucher(viewer, creator, key, channel, signature, 600, network).isSuccess)
            assertEquals(2, fake.signatureChecks)
            assertEquals(4, fake.simulations)
            assertEquals(0, fake.submissions)
        }

    @Test
    fun unavailableOrFailedDryrunNeverFallsBackToFunding() =
        runTest {
            for (reason in listOf("invalid signature", "dryrun unavailable", "missing signature trace")) {
                val failure = IllegalStateException(reason)
                val fake = Fake().also { it.validationFailure = failure }
                assertSame(failure, fake.settle().exceptionOrNull())
                assertEquals(0, fake.submissions)
            }
            val fake = Fake().also { it.validationFailure = CancellationException("verification cancelled") }
            assertFailsWith<CancellationException> { fake.settle() }
            assertEquals(0, fake.submissions)
        }

    @Test
    fun modifiedSignatureAmountOrNetworkCannotReuseVoucherAuthorization() =
        runTest {
            var submissions = 0
            val api =
                ViewerVaultSettlement(
                    funder,
                    readBox = { _, boxKey, _ -> if (boxKey.size == 32) sessionBox() else key },
                    simulate = { _, _, _, _, _ -> tuple(1_000, 300, 500) },
                    validateSignature = { signer, app, asset, url, id, amount, sig, publicKey, payee ->
                        assertSame(funder, signer)
                        require(app == RailMppConstants.TESTNET_MPP_SESSION_VAULT_APP_ID)
                        require(asset == AssetConstants.USDC_TESTNET_ID)
                        require(url == NODE_TESTNET_BASE_URL)
                        require(id.contentEquals(channel))
                        require(amount == 500L)
                        require(sig.contentEquals(signature))
                        require(publicKey.contentEquals(key))
                        require(payee == creator)
                    },
                    submit = { _, _, _, _, _, _, _, _, _, _ ->
                        submissions++
                        "verified-tx"
                    },
                )
            val forgedSignature = signature.copyOf().also { it[0] = 0 }
            assertTrue(api.validateVoucher(viewer, creator, key, channel, forgedSignature, 500, network).isFailure)
            assertTrue(api.settle(viewer, creator, key, channel, forgedSignature, 500, network).isFailure)
            assertTrue(api.validateVoucher(viewer, creator, key, channel, signature, 1_000, network).isFailure)
            assertTrue(api.settle(viewer, creator, key, channel, signature, 1_000, network).isFailure)
            assertTrue(
                api.validateVoucher(viewer, creator, key, channel, signature, 500, MppNetworks.ALGORAND_MAINNET).isFailure,
            )
            assertTrue(api.settle(viewer, creator, key, channel, signature, 500, MppNetworks.ALGORAND_MAINNET).isFailure)
            assertEquals(0, submissions)
            assertTrue(api.validateVoucher(viewer, creator, key, channel, signature, 500, network).isSuccess)
            assertEquals(0, submissions)
            assertEquals("verified-tx", api.settle(viewer, creator, key, channel, signature, 500, network).getOrThrow())
            assertEquals(1, submissions)
        }

    @Test
    fun wrongChannelParticipantsAndSignerNeverSubmit() =
        runTest {
            val wrongChannel = Fake()
            assertTrue(wrongChannel.settle(channelId = ByteArray(32) { 43 }).isFailure)
            assertEquals(0, wrongChannel.submissions)
            assertEquals(0, wrongChannel.simulations)

            val invalidBoxes =
                listOf(
                    sessionBox(payer = otherViewer),
                    sessionBox(payee = otherViewer),
                    sessionBox().also { it[116] = (it[116].toInt() xor 1).toByte() },
                    sessionBox().copyOf(147),
                )
            for (box in invalidBoxes) {
                val fake = Fake().also { it.box = box }
                assertTrue(fake.settle().isFailure)
                assertEquals(0, fake.submissions)
                assertEquals(0, fake.simulations)
            }
            for (storedKey in listOf(byteArrayOf(), byteArrayOf(1), encodeArc4DynamicBytes(key))) {
                val fake = Fake().also { it.storedKey = storedKey }
                assertTrue(fake.settle().isFailure)
                assertEquals(0, fake.submissions)
                assertEquals(0, fake.simulations)
            }
        }

    @Test
    fun overclaimAndAlreadyConfirmedVouchersSkipSubmission() =
        runTest {
            for (amount in listOf(1_001L, Long.MAX_VALUE, 300L, 299L)) {
                val fake = Fake()
                val result = fake.settle(cumulativeAmount = amount)
                assertTrue(result.isFailure)
                assertEquals(0, fake.submissions)
                assertEquals(1, fake.simulations)
            }
            val fake = Fake()
            assertEquals("fake-tx", fake.settle().getOrThrow())
            // Model confirmation becoming visible to a fresh read; there is no local watermark.
            fake.dynamicData = tuple(1_000, 500, 500)
            assertTrue(fake.settle().isFailure)
            assertEquals(1, fake.submissions)
            assertEquals(2, fake.simulations)
        }

    @Test
    fun invalidArgumentsFailBeforeIo() =
        runTest {
            val fake = Fake()
            assertTrue(fake.settle(channelId = ByteArray(31)).isFailure)
            assertTrue(fake.settle(authorizedKey = byteArrayOf()).isFailure)
            assertTrue(fake.settle(voucherSignature = byteArrayOf()).isFailure)
            assertTrue(fake.settle(cumulativeAmount = 0).isFailure)
            assertTrue(fake.settle(cumulativeAmount = -1).isFailure)
            assertTrue(fake.settle(selectedNetwork = "testnet").isFailure)
            assertTrue(fake.settle(selectedNetwork = MppNetworks.SOLANA_MAINNET).isFailure)
            assertTrue(fake.settle(viewerAddress = "invalid").isFailure)
            assertTrue(fake.settle(creatorAddress = "invalid").isFailure)
            assertEquals(0, fake.boxReads)
            assertEquals(0, fake.simulations)
            assertEquals(0, fake.submissions)
        }

    @Test
    fun readAndSubmissionFailuresAreReturnedWithoutFallback() =
        runTest {
            val readFailure = IllegalStateException("missing box")
            val missing = Fake().also { it.readFailure = readFailure }
            assertSame(readFailure, missing.settle().exceptionOrNull())
            assertEquals(0, missing.submissions)
            for (data in listOf(null, ByteArray(55), tuple(100, 101, 0), tuple(-1, 0, 0))) {
                val fake = Fake().also { it.dynamicData = data }
                assertTrue(fake.settle().isFailure)
                assertEquals(0, fake.submissions)
            }
            val failure = IllegalStateException("on-chain signature rejected")
            val rejected = Fake().also { it.submitFailure = failure }
            assertSame(failure, rejected.settle().exceptionOrNull())
            assertEquals(1, rejected.submissions)
        }

    @Test
    fun cancellationEscapesReadsAndSubmission() =
        runTest {
            val reading = Fake().also { it.readFailure = CancellationException("read cancelled") }
            assertFailsWith<CancellationException> { reading.settle() }
            assertEquals(0, reading.submissions)
            val submitting = Fake().also { it.submitFailure = CancellationException("submit cancelled") }
            assertFailsWith<CancellationException> { submitting.settle() }
            assertEquals(1, submitting.submissions)
        }

    @Test
    fun callerArrayMutationDuringReadCannotChangeSubmittedVoucher() =
        runTest {
            val inputChannel = channel.copyOf()
            val inputKey = key.copyOf()
            val inputSignature = signature.copyOf()
            val fake =
                Fake().also {
                    it.beforeBoxRead = {
                        inputChannel.fill(0)
                        inputKey.fill(0)
                        inputSignature.fill(0)
                    }
                }
            assertEquals(
                "fake-tx",
                fake.settle(
                    channelId = inputChannel,
                    authorizedKey = inputKey,
                    voucherSignature = inputSignature,
                ).getOrThrow(),
            )
            assertEquals(1, fake.submissions)
        }

    @Test
    fun sameInstanceKeepsConcurrentViewersAndNetworksIsolated() =
        runTest {
            val firstSubmitted = CompletableDeferred<Unit>()
            val secondSubmitted = CompletableDeferred<Unit>()
            val secondChannel = ByteArray(32) { 43 }
            val secondKey = byteArrayOf(4, 5, 6)
            val secondSignature = byteArrayOf(10, 11)
            val testApp = RailMppConstants.TESTNET_MPP_SESSION_VAULT_APP_ID
            val mainApp = RailMppConstants.MAINNET_MPP_SESSION_VAULT_APP_ID
            val api =
                ViewerVaultSettlement(
                    funder,
                    readBox = { app, boxKey, url ->
                        val first = url == NODE_TESTNET_BASE_URL
                        val expectedChannel = if (first) channel else secondChannel
                        val expectedKey = if (first) key else secondKey
                        assertEquals(if (first) testApp else mainApp, app)
                        if (boxKey.size == 32) {
                            assertContentEquals(expectedChannel, boxKey)
                            sessionBox(payer = if (first) viewer else otherViewer, signerKey = expectedKey)
                        } else {
                            assertContentEquals("p".encodeToByteArray() + expectedChannel, boxKey)
                            expectedKey
                        }
                    },
                    simulate = { _, _, _, _, _ -> tuple(1_000, 300, 500) },
                    validateSignature = { signer, app, asset, url, id, amount, sig, publicKey, payee ->
                        assertSame(funder, signer)
                        assertEquals(creator, payee)
                        val first = url == NODE_TESTNET_BASE_URL
                        assertEquals(if (first) testApp else mainApp, app)
                        assertEquals(if (first) AssetConstants.USDC_TESTNET_ID else AssetConstants.USDC_MAINNET_ID, asset)
                        assertContentEquals(if (first) channel else secondChannel, id)
                        assertContentEquals(if (first) key else secondKey, publicKey)
                        assertContentEquals(if (first) signature else secondSignature, sig)
                        assertEquals(if (first) 500L else 700L, amount)
                    },
                    submit = { signer, app, asset, url, id, amount, sig, publicKey, payee, _ ->
                        assertSame(funder, signer)
                        assertEquals(creator, payee)
                        if (url == NODE_TESTNET_BASE_URL) {
                            firstSubmitted.complete(Unit)
                            secondSubmitted.await()
                            assertEquals(testApp, app)
                            assertEquals(AssetConstants.USDC_TESTNET_ID, asset)
                            assertContentEquals(channel, id)
                            assertContentEquals(key, publicKey)
                            assertContentEquals(signature, sig)
                            assertEquals(500L, amount)
                            "viewer-one"
                        } else {
                            assertEquals(NODE_MAINNET_BASE_URL, url)
                            assertEquals(mainApp, app)
                            assertEquals(AssetConstants.USDC_MAINNET_ID, asset)
                            assertContentEquals(secondChannel, id)
                            assertContentEquals(secondKey, publicKey)
                            assertContentEquals(secondSignature, sig)
                            assertEquals(700L, amount)
                            secondSubmitted.complete(Unit)
                            "viewer-two"
                        }
                    },
                )
            val first = async { api.settle(viewer, creator, key, channel, signature, 500, network).getOrThrow() }
            firstSubmitted.await()
            val second =
                async {
                    api.settle(
                        otherViewer, creator, secondKey, secondChannel, secondSignature, 700, MppNetworks.ALGORAND_MAINNET,
                    ).getOrThrow()
                }
            assertEquals("viewer-two", second.await())
            assertEquals("viewer-one", first.await())
        }

    private data class ExpectedConfig(
        val network: String,
        val appId: Long,
        val assetId: Long,
        val url: String,
    )

    private inner class Fake(
        private val config: ExpectedConfig =
            ExpectedConfig(network, RailMppConstants.TESTNET_MPP_SESSION_VAULT_APP_ID, AssetConstants.USDC_TESTNET_ID, NODE_TESTNET_BASE_URL),
    ) {
        var box = sessionBox()
        var storedKey = key.copyOf()
        var dynamicData: ByteArray? = tuple(1_000, 300, 500)
        var boxReads = 0
        var simulations = 0
        var submissions = 0
        var signatureChecks = 0
        var submittedAmount: Long? = null
        var readFailure: Exception? = null
        var submitFailure: Exception? = null
        var validationFailure: Exception? = null
        var beforeBoxRead: () -> Unit = {}
        val api =
            ViewerVaultSettlement(
                funder,
                readBox = { app, boxKey, url ->
                    beforeBoxRead()
                    boxReads++
                    assertEquals(config.appId, app)
                    assertEquals(config.url, url)
                    readFailure?.let { throw it }
                    when {
                        boxKey.contentEquals(channel) -> box
                        boxKey.contentEquals("p".encodeToByteArray() + channel) -> storedKey
                        else -> error("Channel does not exist")
                    }
                },
                simulate = { app, url, _, args, boxes ->
                    simulations++
                    assertEquals(config.appId, app)
                    assertEquals(config.url, url)
                    assertContentEquals(encodeArc4DynamicBytes(channel), args.single())
                    assertContentEquals(channel, boxes.first().second)
                    dynamicData
                },
                validateSignature = { signer, app, asset, url, id, amount, sig, publicKey, payee ->
                    signatureChecks++
                    assertSame(funder, signer)
                    assertEquals(config.appId, app)
                    assertEquals(config.assetId, asset)
                    assertEquals(config.url, url)
                    assertContentEquals(channel, id)
                    assertContentEquals(signature, sig)
                    assertContentEquals(key, publicKey)
                    assertEquals(creator, payee)
                    assertTrue(amount in 301L..1_000L)
                    validationFailure?.let { throw it }
                },
                submit = { signer, app, asset, url, id, amount, sig, publicKey, payee, note ->
                    assertTrue(signatureChecks > 0, "Submission must never precede signature validation")
                    submissions++
                    assertSame(funder, signer)
                    assertEquals(config.appId, app)
                    assertEquals(config.assetId, asset)
                    assertEquals(config.url, url)
                    assertContentEquals(channel, id)
                    assertContentEquals(signature, sig)
                    assertContentEquals(key, publicKey)
                    assertEquals(creator, payee)
                    assertContentEquals("N/A".encodeToByteArray(), note)
                    submittedAmount = amount
                    submitFailure?.let { throw it }
                    "fake-tx"
                },
            )

        suspend fun settle(
            viewerAddress: String = viewer,
            creatorAddress: String = creator,
            authorizedKey: ByteArray = key,
            channelId: ByteArray = channel,
            voucherSignature: ByteArray = signature,
            cumulativeAmount: Long = 500,
            selectedNetwork: String = config.network,
        ): Result<String> =
            api.settle(viewerAddress, creatorAddress, authorizedKey, channelId, voucherSignature, cumulativeAmount, selectedNetwork)
    }

    private fun sessionBox(
        payer: String = viewer,
        payee: String = creator,
        signerKey: ByteArray = key,
    ): ByteArray =
        decodeAlgorandAddressPublicKey(payer) + decodeAlgorandAddressPublicKey(payee) +
            byteArrayOf(0, 114) + ByteArray(48) + encodeArc4DynamicBytes(sha512_256(signerKey))

    private fun tuple(
        total: Long,
        settled: Long,
        voucher: Long,
    ): ByteArray = encodeUint64(total) + encodeUint64(settled) + encodeUint64(voucher) + ByteArray(32)
}
