package com.michaeltchuang.walletsdk.core.railmpp.solanasmartcontract

import com.michaeltchuang.walletsdk.core.railmpp.internal.sha256
import com.michaeltchuang.walletsdk.core.railmpp.internal.verifyEd25519
import com.michaeltchuang.walletsdk.core.solana.data.SolanaRpcClient
import com.michaeltchuang.walletsdk.core.solana.transaction.SolanaAccountMeta
import com.michaeltchuang.walletsdk.core.solana.transaction.SolanaInstruction
import com.michaeltchuang.walletsdk.core.solana.transaction.SolanaTransactionBuilder
import com.michaeltchuang.walletsdk.core.solana.transaction.SolanaTransactionSigner
import com.michaeltchuang.walletsdk.core.solana.utils.Base58
import com.michaeltchuang.walletsdk.core.solana.utils.SolanaPda
import io.github.aakira.napier.Napier

/** Per-cluster deployment of the `escrow_session_vault_solana_manager` Anchor program. */
data class SolanaEscrowSessionVaultConfig(
    val programId: String,
    val usdcMint: String,
) {
    companion object {
        const val DEVNET_PROGRAM_ID = "EJkkvypbdeRWdFQuf8gm7K65xPsHTUtDsFxsKZceW71B"
        const val DEVNET_USDC_MINT = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
        const val MAINNET_USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"

        val DEVNET = SolanaEscrowSessionVaultConfig(DEVNET_PROGRAM_ID, DEVNET_USDC_MINT)
    }
}

/** On-chain `Channel` account (Anchor layout, see `libs.rs`). */
data class SolanaEscrowChannel(
    val address: String,
    val payer: String,
    val payee: String,
    val mint: String,
    /** Base58 session key allowed to settle; [SolanaEscrowSessionVaultManager.REVOKED_SIGNER] when revoked. */
    val authorizedSigner: String,
    val totalDeposit: Long,
    val lastSettled: Long,
    val latestVoucherAmount: Long,
    val startSlot: Long,
    val startTimestamp: Long,
    /** Unix seconds; 0 when no close is pending. */
    val closeRequestedAt: Long,
    val bump: Int,
    val salt: ByteArray,
) {
    val remainingDeposit: Long get() = totalDeposit - lastSettled
    val isSignerRevoked: Boolean get() = authorizedSigner == SolanaEscrowSessionVaultManager.REVOKED_SIGNER

    /** Earliest unix time the payer may [SolanaEscrowSessionVaultManager.withdraw], or null if no close is pending. */
    val withdrawAvailableAt: Long?
        get() = closeRequestedAt.takeIf { it != 0L }?.plus(SolanaEscrowSessionVaultManager.WITHDRAW_DELAY_SECONDS)

    override fun equals(other: Any?): Boolean = other is SolanaEscrowChannel && other.address == address && other.salt.contentEquals(salt)

    override fun hashCode(): Int = address.hashCode()
}

/**
 * Settlement voucher: a `settle(cumulativeAmount)` transaction partially signed by the channel's
 * authorized session key. The submitter (payee side) validates it, adds the fee-payer signature
 * and broadcasts it. Because it embeds a recent blockhash it must be submitted within ~60s.
 */
data class SolanaSettlementVoucher(
    val channel: String,
    val cumulativeAmount: Long,
    val authorizedSigner: String,
    val feePayer: String,
    val ensurePayeeTokenAccount: Boolean,
    val partiallySignedTransaction: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is SolanaSettlementVoucher &&
            other.channel == channel &&
            other.partiallySignedTransaction.contentEquals(partiallySignedTransaction)

    override fun hashCode(): Int = partiallySignedTransaction.contentHashCode()
}

/** Anchor `VaultError` codes (6000 + enum index). */
enum class SolanaEscrowVaultError(
    val message: String,
) {
    UnauthorizedPayer("Only the channel payer can perform this action"),
    UnauthorizedPayee("Only the channel payee can close the channel"),
    UnauthorizedSigner("Settlement requires the nonzero registered authorized signer"),
    NothingNewToSettle("Cumulative amount must exceed the last settled amount"),
    VoucherExceedsDeposit("Cumulative voucher amount exceeds the total deposit"),
    InvalidMint("Mint is not the configured USDC mint"),
    InvalidMintDecimals("Mint must have six decimals"),
    ArithmeticOverflow("Checked arithmetic overflow or underflow"),
    CloseAlreadyRequested("Channel closure has already been requested"),
    CloseNotRequested("Payer must request closure before withdrawing"),
    WithdrawalTooEarly("The 888-second withdrawal delay has not elapsed"),
    InvalidDeposit("Deposit must be greater than zero"),
    ;

    val code: Int get() = ANCHOR_ERROR_OFFSET + ordinal

    companion object {
        const val ANCHOR_ERROR_OFFSET = 6000

        fun fromCode(code: Int): SolanaEscrowVaultError? = entries.getOrNull(code - ANCHOR_ERROR_OFFSET)
    }
}

class SolanaEscrowVaultException(
    val error: SolanaEscrowVaultError,
    cause: Throwable,
) : IllegalStateException("${error.name}: ${error.message}", cause)

/**
 * Kotlin client for the Solana escrow session vault — the Solana counterpart of
 * [com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultHybridManagerClient].
 *
 * Roles (Liquid Stream):
 * - **Payer / viewer** — the Seed Vault account. Signs [open], [topUp], [setAuthorizedSigner],
 *   [revokeAuthorizedSigner], [requestClose] and [withdraw] (each one Seed Vault approval).
 *   [open] registers the session key in the same transaction, so opening is a single approval.
 * - **Authorized signer** — the payer's linked session key. Signs settlement vouchers silently via
 *   [createSettlementVoucher]; it never pays fees, so it needs no SOL.
 * - **Payee / host** — pays the settle fees. Validates, cosigns as fee payer and broadcasts
 *   vouchers with [submitSettlementVoucher]. A Seed Vault payee uses its own linked session key
 *   as fee payer (see `SubmitSolanaSettlementVoucherUseCase`), so settling needs no prompt.
 *   Settled USDC goes to the payee account itself. The payee calls [close] to refund the
 *   leftover deposit to the payer.
 */
class SolanaEscrowSessionVaultManager(
    private val rpc: SolanaRpcClient,
    private val mainnetConfig: SolanaEscrowSessionVaultConfig? = null,
) {
    fun configFor(network: String): SolanaEscrowSessionVaultConfig =
        when {
            network.contains("mainnet", ignoreCase = true) ->
                mainnetConfig ?: error("Solana escrow session vault is not deployed on mainnet")
            network.contains("testnet", ignoreCase = true) && !network.contains("devnet", ignoreCase = true) ->
                error("Solana escrow session vault is only deployed on devnet (got $network)")
            else -> SolanaEscrowSessionVaultConfig.DEVNET
        }

    // region Addresses

    fun deriveChannelAddress(
        network: String,
        payer: String,
        payee: String,
        salt: ByteArray,
    ): String {
        require(salt.size == SALT_SIZE) { "Channel salt must be $SALT_SIZE bytes" }
        val config = configFor(network)
        return SolanaPda
            .findProgramAddress(
                listOf(
                    CHANNEL_SEED,
                    key(payer),
                    key(payee),
                    key(config.usdcMint),
                    salt,
                ),
                config.programId,
            ).address
    }

    /** The channel's USDC vault (ATA owned by the channel PDA). */
    fun vaultAddress(
        channel: String,
        mint: String,
    ): String = SolanaPda.associatedTokenAddress(channel, mint)

    // endregion

    // region Reads

    suspend fun getChannel(
        network: String,
        channel: String,
    ): SolanaEscrowChannel? = rpc.getAccountData(network, channel)?.let { decodeChannel(channel, it) }

    /** Open channels for [payer] (optionally to [payee]), newest first. */
    suspend fun findChannels(
        network: String,
        payer: String,
        payee: String? = null,
    ): List<SolanaEscrowChannel> {
        val filters =
            buildMap {
                put(0, Base58.encode(CHANNEL_ACCOUNT_DISCRIMINATOR))
                put(PAYER_OFFSET, payer)
                payee?.let { put(PAYEE_OFFSET, it) }
            }
        return rpc
            .getProgramAccounts(network, configFor(network).programId, CHANNEL_ACCOUNT_SIZE, filters)
            .map { (address, data) -> decodeChannel(address, data) }
            .sortedByDescending { it.startSlot }
    }

    // endregion

    // region Payer (Seed Vault) actions

    data class OpenResult(
        val channel: String,
        val signature: String,
    )

    /**
     * Opens a channel, deposits [depositAmount] raw USDC units and registers [authorizedSigner]
     * (the linked session key) in one payer-signed transaction.
     */
    suspend fun open(
        network: String,
        payer: SolanaTransactionSigner,
        payee: String,
        depositAmount: Long,
        authorizedSigner: String,
        salt: ByteArray,
    ): Result<OpenResult> =
        execute("open") {
            require(depositAmount > 0) { "Deposit must be positive" }
            require(payer.address != payee) { "Payer and payee must differ" }
            val config = configFor(network)
            val channel = deriveChannelAddress(network, payer.address, payee, salt)
            val payerToken = payerSourceTokenAccount(network, payer.address, config.usdcMint, depositAmount)
            val instruction =
                SolanaInstruction(
                    programId = config.programId,
                    accounts =
                        listOf(
                            writable(channel),
                            SolanaAccountMeta(payer.address, isSigner = true, isWritable = true),
                            readonly(payee),
                            readonly(config.usdcMint),
                            writable(payerToken),
                            writable(vaultAddress(channel, config.usdcMint)),
                            readonly(SolanaPda.TOKEN_PROGRAM_ID),
                            readonly(SolanaPda.ASSOCIATED_TOKEN_PROGRAM_ID),
                            readonly(SolanaTransactionBuilder.SYSTEM_PROGRAM_ID),
                        ),
                    data = IX_OPEN + salt + u64(depositAmount) + key(authorizedSigner),
                )
            OpenResult(channel, sendAndConfirm(network, payer, listOf(instruction)))
        }

    /** Adds [amount] raw USDC units; also cancels a pending close request (as on Algorand). */
    suspend fun topUp(
        network: String,
        payer: SolanaTransactionSigner,
        channel: String,
        amount: Long,
    ): Result<String> =
        execute("top_up") {
            require(amount > 0) { "Top-up must be positive" }
            val state = requireChannel(network, channel)
            requirePayer(state, payer)
            val payerToken = payerSourceTokenAccount(network, payer.address, state.mint, amount)
            val instruction =
                SolanaInstruction(
                    programId = configFor(network).programId,
                    accounts =
                        listOf(
                            writable(channel),
                            SolanaAccountMeta(payer.address, isSigner = true, isWritable = false),
                            readonly(state.mint),
                            writable(payerToken),
                            writable(vaultAddress(channel, state.mint)),
                            readonly(SolanaPda.TOKEN_PROGRAM_ID),
                        ),
                    data = IX_TOP_UP + u64(amount),
                )
            sendAndConfirm(network, payer, listOf(instruction))
        }

    /** Rotates the session key allowed to settle vouchers. */
    suspend fun setAuthorizedSigner(
        network: String,
        payer: SolanaTransactionSigner,
        channel: String,
        authorizedSigner: String,
    ): Result<String> =
        execute("set_authorized_signer") {
            manage(network, payer, channel, IX_SET_AUTHORIZED_SIGNER + key(authorizedSigner))
        }

    /** Disables settlement until a new signer is set (e.g. if the session key is lost/compromised). */
    suspend fun revokeAuthorizedSigner(
        network: String,
        payer: SolanaTransactionSigner,
        channel: String,
    ): Result<String> = execute("revoke_authorized_signer") { manage(network, payer, channel, IX_REVOKE_AUTHORIZED_SIGNER) }

    /** Starts the [WITHDRAW_DELAY_SECONDS] countdown for a payer-initiated [withdraw]. */
    suspend fun requestClose(
        network: String,
        payer: SolanaTransactionSigner,
        channel: String,
    ): Result<String> = execute("request_close") { manage(network, payer, channel, IX_REQUEST_CLOSE) }

    /** Payer escape hatch after [requestClose] + delay: refunds the vault and reclaims rent. */
    suspend fun withdraw(
        network: String,
        payer: SolanaTransactionSigner,
        channel: String,
    ): Result<String> =
        execute("withdraw") {
            val state = requireChannel(network, channel)
            requirePayer(state, payer)
            finalize(network, IX_WITHDRAW, state, caller = payer, feePayer = payer)
        }

    // endregion

    // region Session key (authorized signer)

    /**
     * Builds a `settle(cumulativeAmount)` transaction paid for by [feePayer] (the address the payee
     * advertised — for a Seed Vault payee, its linked session key) and signs it with the channel's
     * [authorizedSigner]. When
     * [ensurePayeeTokenAccount] is true an idempotent ATA-create for the payee is prepended.
     */
    suspend fun createSettlementVoucher(
        network: String,
        channel: String,
        cumulativeAmount: Long,
        authorizedSigner: SolanaTransactionSigner,
        feePayer: String,
        ensurePayeeTokenAccount: Boolean = true,
    ): SolanaSettlementVoucher {
        val state = requireChannel(network, channel)
        require(state.authorizedSigner == authorizedSigner.address) {
            "${authorizedSigner.address} is not the channel's authorized signer"
        }
        require(feePayer != authorizedSigner.address) { "The payee pays settle fees, not the payer's session key" }
        checkSettleAmount(state, cumulativeAmount)
        val message =
            SolanaTransactionBuilder.compileMessage(
                feePayer = feePayer,
                recentBlockhash = rpc.getLatestBlockhash(network),
                instructions = settleInstructions(network, state, cumulativeAmount, feePayer, ensurePayeeTokenAccount),
            )
        val signers = SolanaTransactionBuilder.requiredSigners(message)
        val signatures = signers.map { if (it == authorizedSigner.address) authorizedSigner.signMessage(message) else null }
        return SolanaSettlementVoucher(
            channel = channel,
            cumulativeAmount = cumulativeAmount,
            authorizedSigner = authorizedSigner.address,
            feePayer = feePayer,
            ensurePayeeTokenAccount = ensurePayeeTokenAccount,
            partiallySignedTransaction = SolanaTransactionBuilder.serialize(message, signatures),
        )
    }

    // endregion

    // region Payee actions

    /**
     * Payee side: verifies the voucher is exactly a `settle` for this channel (rebuilding the
     * expected message from on-chain state), checks the session-key signature, then cosigns as
     * [feePayer] and broadcasts. Returns the transaction signature.
     */
    suspend fun submitSettlementVoucher(
        network: String,
        voucher: SolanaSettlementVoucher,
        feePayer: SolanaTransactionSigner,
    ): Result<String> =
        execute("settle") {
            val state = requireChannel(network, voucher.channel)
            require(!state.isSignerRevoked && state.authorizedSigner == voucher.authorizedSigner) {
                "Voucher signer is not the channel's current authorized signer"
            }
            require(voucher.feePayer == feePayer.address) { "Voucher was built for fee payer ${voucher.feePayer}" }
            checkSettleAmount(state, voucher.cumulativeAmount)

            val (signatures, message) = SolanaTransactionBuilder.deserialize(voucher.partiallySignedTransaction)
            val expected =
                SolanaTransactionBuilder.compileMessage(
                    feePayer = feePayer.address,
                    recentBlockhash = recentBlockhash(message),
                    instructions =
                        settleInstructions(
                            network,
                            state,
                            voucher.cumulativeAmount,
                            feePayer.address,
                            voucher.ensurePayeeTokenAccount,
                        ),
                )
            require(message.contentEquals(expected)) { "Voucher transaction does not match the expected settle instruction" }

            val signers = SolanaTransactionBuilder.requiredSigners(message)
            val signerIndex = signers.indexOf(state.authorizedSigner)
            require(signerIndex >= 0 && verifyEd25519(key(state.authorizedSigner), message, signatures[signerIndex])) {
                "Invalid session-key signature on voucher"
            }
            val signed =
                SolanaTransactionBuilder.addSignature(
                    voucher.partiallySignedTransaction,
                    feePayer.address,
                    feePayer.signMessage(message),
                )
            rpc.sendTransaction(network, signed).also { rpc.confirmTransaction(network, it) }
        }

    /**
     * Payee closes the channel: refunds the remaining vault balance to the payer and returns rent
     * to the payer (mirrors the Algorand host-side `refundRemainingVaultBalance`). [feePayer]
     * defaults to the payee and funds the payer's USDC ATA if it no longer exists.
     */
    suspend fun close(
        network: String,
        payee: SolanaTransactionSigner,
        channel: String,
        feePayer: SolanaTransactionSigner = payee,
    ): Result<String> =
        execute("close") {
            val state = requireChannel(network, channel)
            require(state.payee == payee.address) { "Only the channel payee can close the channel" }
            finalize(network, IX_CLOSE, state, caller = payee, feePayer = feePayer)
        }

    // endregion

    // region Internals

    private suspend fun manage(
        network: String,
        payer: SolanaTransactionSigner,
        channel: String,
        data: ByteArray,
    ): String {
        val state = requireChannel(network, channel)
        requirePayer(state, payer)
        val instruction =
            SolanaInstruction(
                programId = configFor(network).programId,
                accounts =
                    listOf(
                        writable(channel),
                        SolanaAccountMeta(payer.address, isSigner = true, isWritable = false),
                    ),
                data = data,
            )
        return sendAndConfirm(network, payer, listOf(instruction))
    }

    private suspend fun finalize(
        network: String,
        discriminator: ByteArray,
        state: SolanaEscrowChannel,
        caller: SolanaTransactionSigner,
        feePayer: SolanaTransactionSigner,
    ): String {
        val payerToken = SolanaPda.associatedTokenAddress(state.payer, state.mint)
        val instructions =
            listOf(
                createAssociatedTokenAccountIdempotent(feePayer.address, payerToken, state.payer, state.mint),
                SolanaInstruction(
                    programId = configFor(network).programId,
                    accounts =
                        listOf(
                            writable(state.address),
                            SolanaAccountMeta(caller.address, isSigner = true, isWritable = false),
                            writable(state.payer),
                            readonly(state.mint),
                            writable(vaultAddress(state.address, state.mint)),
                            writable(payerToken),
                            readonly(SolanaPda.TOKEN_PROGRAM_ID),
                        ),
                    data = discriminator,
                ),
            )
        return sendAndConfirm(network, feePayer, instructions, listOf(caller))
    }

    private fun settleInstructions(
        network: String,
        state: SolanaEscrowChannel,
        cumulativeAmount: Long,
        feePayer: String,
        ensurePayeeTokenAccount: Boolean,
    ): List<SolanaInstruction> {
        val payeeToken = SolanaPda.associatedTokenAddress(state.payee, state.mint)
        val settle =
            SolanaInstruction(
                programId = configFor(network).programId,
                accounts =
                    listOf(
                        writable(state.address),
                        SolanaAccountMeta(state.authorizedSigner, isSigner = true, isWritable = false),
                        readonly(state.mint),
                        writable(vaultAddress(state.address, state.mint)),
                        writable(payeeToken),
                        readonly(SolanaPda.TOKEN_PROGRAM_ID),
                    ),
                data = IX_SETTLE + u64(cumulativeAmount),
            )
        return if (ensurePayeeTokenAccount) {
            listOf(createAssociatedTokenAccountIdempotent(feePayer, payeeToken, state.payee, state.mint), settle)
        } else {
            listOf(settle)
        }
    }

    private suspend fun sendAndConfirm(
        network: String,
        feePayer: SolanaTransactionSigner,
        instructions: List<SolanaInstruction>,
        additionalSigners: List<SolanaTransactionSigner> = emptyList(),
    ): String {
        val message = SolanaTransactionBuilder.compileMessage(feePayer.address, rpc.getLatestBlockhash(network), instructions)
        val available = (listOf(feePayer) + additionalSigners).distinctBy { it.address }
        val signatures =
            SolanaTransactionBuilder.requiredSigners(message).map { required ->
                val signer = available.firstOrNull { it.address == required } ?: error("Missing signer $required")
                signer.signMessage(message)
            }
        val signature = rpc.sendTransaction(network, SolanaTransactionBuilder.serialize(message, signatures))
        rpc.confirmTransaction(network, signature)
        return signature
    }

    /** Payer's largest token account for [mint] (open/top_up accept any account owned by the payer). */
    private suspend fun payerSourceTokenAccount(
        network: String,
        payer: String,
        mint: String,
        amount: Long,
    ): String {
        val account =
            rpc.getTokenAccounts(network, payer, mint).firstOrNull()
                ?: error("$payer has no USDC token account")
        check(account.amount >= amount) { "Insufficient USDC: ${account.amount} < $amount raw units" }
        check(account.tokenProgramId.isBlank() || account.tokenProgramId == SolanaPda.TOKEN_PROGRAM_ID) {
            "USDC account must use the classic SPL Token program"
        }
        return account.address
    }

    private suspend fun requireChannel(
        network: String,
        channel: String,
    ): SolanaEscrowChannel = getChannel(network, channel) ?: error("Channel $channel not found (closed or never opened)")

    private fun requirePayer(
        state: SolanaEscrowChannel,
        payer: SolanaTransactionSigner,
    ) = require(state.payer == payer.address) { "Only the channel payer can perform this action" }

    private fun checkSettleAmount(
        state: SolanaEscrowChannel,
        cumulativeAmount: Long,
    ) {
        require(cumulativeAmount > state.lastSettled) { SolanaEscrowVaultError.NothingNewToSettle.message }
        require(cumulativeAmount <= state.totalDeposit) { SolanaEscrowVaultError.VoucherExceedsDeposit.message }
    }

    private suspend fun <T> execute(
        action: String,
        block: suspend () -> T,
    ): Result<T> =
        runCatching { block() }
            .recoverCatching { throw mapProgramError(it) }
            .onFailure { Napier.e("[SOLANA_ESCROW_${action.uppercase()}_ERR] ${it.message}", it, tag = TAG) }

    // endregion

    companion object {
        private const val TAG = "SolanaEscrowVault"
        const val SALT_SIZE = 32
        const val WITHDRAW_DELAY_SECONDS = 888L
        const val REVOKED_SIGNER = "11111111111111111111111111111111"

        /** 8 discriminator + 4 pubkeys + 6 u64/i64 + bump + 32-byte salt. */
        const val CHANNEL_ACCOUNT_SIZE = 8 + 32 * 4 + 8 * 6 + 1 + 32
        private const val PAYER_OFFSET = 8
        private const val PAYEE_OFFSET = 40

        private val CHANNEL_SEED = "channel".encodeToByteArray()

        internal val IX_OPEN = anchorDiscriminator("global:open")
        internal val IX_TOP_UP = anchorDiscriminator("global:top_up")
        internal val IX_SET_AUTHORIZED_SIGNER = anchorDiscriminator("global:set_authorized_signer")
        internal val IX_REVOKE_AUTHORIZED_SIGNER = anchorDiscriminator("global:revoke_authorized_signer")
        internal val IX_SETTLE = anchorDiscriminator("global:settle")
        internal val IX_REQUEST_CLOSE = anchorDiscriminator("global:request_close")
        internal val IX_CLOSE = anchorDiscriminator("global:close")
        internal val IX_WITHDRAW = anchorDiscriminator("global:withdraw")
        internal val CHANNEL_ACCOUNT_DISCRIMINATOR = anchorDiscriminator("account:Channel")

        private fun anchorDiscriminator(preimage: String): ByteArray = sha256(preimage.encodeToByteArray()).copyOf(8)

        /**
         * Deterministic per-signer salt, mirroring the Algorand channel id which commits to the
         * device salt and the authorized-signer key hash.
         */
        fun channelSalt(
            deviceSalt: ByteArray,
            authorizedSigner: String,
        ): ByteArray = sha256("liquid-stream-solana-channel".encodeToByteArray() + deviceSalt + key(authorizedSigner))

        internal fun decodeChannel(
            address: String,
            data: ByteArray,
        ): SolanaEscrowChannel {
            require(data.size >= CHANNEL_ACCOUNT_SIZE) { "Channel account too small (${data.size} bytes)" }
            require(data.copyOf(8).contentEquals(CHANNEL_ACCOUNT_DISCRIMINATOR)) { "$address is not a Channel account" }
            var offset = 8

            fun pubkey(): String = Base58.encode(data.copyOfRange(offset, offset + 32)).also { offset += 32 }

            fun u64(): Long {
                var value = 0L
                for (i in 0 until 8) value = value or ((data[offset + i].toLong() and 0xFF) shl (8 * i))
                offset += 8
                return value
            }
            return SolanaEscrowChannel(
                address = address,
                payer = pubkey(),
                payee = pubkey(),
                mint = pubkey(),
                authorizedSigner = pubkey(),
                totalDeposit = u64(),
                lastSettled = u64(),
                latestVoucherAmount = u64(),
                startSlot = u64(),
                startTimestamp = u64(),
                closeRequestedAt = u64(),
                bump = (data[offset++].toInt() and 0xFF),
                salt = data.copyOfRange(offset, offset + SALT_SIZE),
            )
        }

        /** Maps `{"Custom":6002}` / `custom program error: 0x1772` to a typed [SolanaEscrowVaultException]. */
        internal fun mapProgramError(error: Throwable): Throwable {
            val text = error.message ?: return error
            val code =
                Regex("\"Custom\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("custom program error: 0x([0-9a-fA-F]+)").find(text)?.groupValues?.get(1)?.toIntOrNull(16)
            val vaultError = code?.let { SolanaEscrowVaultError.fromCode(it) } ?: return error
            return SolanaEscrowVaultException(vaultError, error)
        }

        /** Blockhash embedded in a compiled legacy message. */
        internal fun recentBlockhash(message: ByteArray): String {
            val (keyCount, keysStart) = SolanaTransactionBuilder.readShortVec(message, 3)
            val start = keysStart + keyCount * 32
            return Base58.encode(message.copyOfRange(start, start + 32))
        }

        private fun createAssociatedTokenAccountIdempotent(
            funder: String,
            associatedAccount: String,
            owner: String,
            mint: String,
        ) = SolanaInstruction(
            programId = SolanaPda.ASSOCIATED_TOKEN_PROGRAM_ID,
            accounts =
                listOf(
                    SolanaAccountMeta(funder, isSigner = true, isWritable = true),
                    writable(associatedAccount),
                    readonly(owner),
                    readonly(mint),
                    readonly(SolanaTransactionBuilder.SYSTEM_PROGRAM_ID),
                    readonly(SolanaPda.TOKEN_PROGRAM_ID),
                ),
            data = byteArrayOf(1), // CreateIdempotent
        )

        private fun key(base58: String): ByteArray = Base58.decode(base58).also { require(it.size == 32) { "Invalid Solana key $base58" } }

        private fun writable(address: String) = SolanaAccountMeta(address, isSigner = false, isWritable = true)

        private fun readonly(address: String) = SolanaAccountMeta(address, isSigner = false, isWritable = false)

        private fun u64(value: Long): ByteArray = ByteArray(8) { i -> (value ushr (8 * i)).toByte() }
    }
}
