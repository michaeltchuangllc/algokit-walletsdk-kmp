package com.michaeltchuang.walletsdk.core.railmpp.internal

import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

internal data class AccountAlgoBalance(
    val amount: Long,
    val minimumBalance: Long,
)

internal fun decodeAlgodAccountResponse(
    status: Int,
    body: String,
): AccountAlgoBalance {
    if (status == 404) return AccountAlgoBalance(0, 100_000)
    check(status in 200..299) { "Account lookup failed: HTTP $status" }
    val json = Json.parseToJsonElement(body).jsonObject
    return AccountAlgoBalance(json.getValue("amount").jsonPrimitive.long, json.getValue("min-balance").jsonPrimitive.long)
}

internal fun accountFundingShortfall(
    balance: AccountAlgoBalance,
    minimumSpendableMicroAlgo: Long,
): Long {
    require(balance.amount >= 0 && balance.minimumBalance >= 0) { "Invalid account balance" }
    require(minimumSpendableMicroAlgo >= 0) { "minimumSpendableMicroAlgo must not be negative" }
    val minimumBalance = maxOf(100_000L, balance.minimumBalance)
    require(minimumSpendableMicroAlgo <= Long.MAX_VALUE - minimumBalance) { "Account funding amount overflow" }
    return (minimumBalance + minimumSpendableMicroAlgo - balance.amount).coerceAtLeast(0)
}

internal expect fun getAccountAlgoBalanceInternal(
    address: String,
    algodUrl: String,
): AccountAlgoBalance

internal expect suspend fun submitPaymentInternal(
    signer: MppWalletSigner,
    receiverAddress: String,
    amountMicroAlgo: Long,
    algodUrl: String,
): String

/** Fetches raw box bytes for [channelId] from the Algod REST API. */
internal expect fun getSessionBoxBytesInternal(
    appId: Long,
    channelId: ByteArray,
    algodUrl: String,
): ByteArray

/** Returns null only for HTTP 404; transport and malformed-response errors propagate. */
internal expect suspend fun findSessionBoxBytesInternal(
    appId: Long,
    boxKey: ByteArray,
    algodUrl: String,
): ByteArray?

/**
 * Builds, signs (via [signer]), and broadcasts an app-call transaction.
 * [boxKeys] is a list of (appId, boxKey) pairs for AVM box references.
 */
internal expect suspend fun submitAppCallInternal(
    signer: MppWalletSigner,
    appId: Long,
    usdcAssetId: Long,
    algodUrl: String,
    args: List<ByteArray>,
    boxKeys: List<Pair<Long, ByteArray>>,
    foreignAssets: List<Long>,
    foreignAccounts: List<String> = emptyList(),
    note: ByteArray? = null,
): String

/**
 * Builds, signs (via [signer]), and broadcasts an asset-transfer + app-call group.
 * [boxKeys] is a list of (appId, boxKey) pairs for AVM box references.
 */
internal expect suspend fun submitAssetTransferAndAppCallInternal(
    signer: MppWalletSigner,
    appId: Long,
    usdcAssetId: Long,
    algodUrl: String,
    appCallArgs: List<ByteArray>,
    boxKeys: List<Pair<Long, ByteArray>>,
    appCallForeignAssets: List<Long>,
    depositAmountMicroUsdc: Long,
): String

/** One app call of an atomic group: the ABI arguments and the box references it needs. */
internal class GroupedAppCall(
    val args: List<ByteArray>,
    val boxKeys: List<Pair<Long, ByteArray>>,
    val foreignAssets: List<Long> = emptyList(),
)

/**
 * Builds the USDC deposit and then each of [appCalls] as one atomic group from [signer], signs the
 * whole group in a single [MppWalletSigner.signTransactionsBytes] call (so a Seed Vault owner approves it
 * once), and broadcasts it. Returns the id of the last transaction.
 */
internal expect suspend fun submitDepositAndAppCallsGroupInternal(
    signer: MppWalletSigner,
    appId: Long,
    usdcAssetId: Long,
    algodUrl: String,
    depositAmountMicroUsdc: Long,
    appCalls: List<GroupedAppCall>,
): String

/**
 * Compiles the channel-specific settlement LogicSig template and returns its resulting address,
 * without registering or funding it. [authorizedSignerPublicKey] must be the *payer's* ephemeral
 * session key — the payer uses this to learn the address before calling
 * [com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultHybridManagerClient.setSettlementLogicSig].
 */
internal expect suspend fun compileSettlementLogicSigAddressInternal(
    appId: Long,
    algodUrl: String,
    channelId: ByteArray,
    authorizedSignerPublicKey: ByteArray,
    payeeAddress: String,
): String

/**
 * Compiles the channel-specific settlement and padding LogicSig templates, then submits their
 * required two-transaction settlement group. The voucher signature is passed to the settlement
 * program as a LogicSig argument; it is never used as an account transaction signature.
 */
internal expect suspend fun submitLogicSigSettlementInternal(
    payerSigner: MppWalletSigner,
    appId: Long,
    usdcAssetId: Long,
    algodUrl: String,
    channelId: ByteArray,
    cumulativeAmountMicroUsdc: Long,
    voucherSignature: ByteArray,
    authorizedSignerPublicKey: ByteArray,
    payeeAddress: String,
    note: ByteArray? = null,
): String

/** Decodes msgpack bytes to a generic map/object; returns null on failure. */
internal expect fun decodeMsgPackAny(bytes: ByteArray): Any?

/**
 * Simulates a readonly ABI method call against Algod (no signature and no real fee required —
 * mirrors how the TypeScript reference script reads `getSessionStaticData`/`getSessionDynamicData`
 * via `appClient.send.*`, which algokit-utils transparently resolves through `/v2/transactions/simulate`
 * for `readonly: true` ARC-56 methods). [selector] is the 4-byte ABI method selector; [args] are the
 * ABI-encoded method arguments (selector is prepended automatically). [boxKeys] is a list of
 * (appId, boxKey) pairs for AVM box references.
 *
 * Returns the raw ABI return-value bytes with the 4-byte "return log" prefix (`0x151f7c75`) already
 * stripped, or `null` if simulation isn't supported on this platform yet, or the call failed for any
 * reason — callers should fall back to decoding raw box bytes directly in that case.
 */
internal expect fun simulateReadonlyMethodInternal(
    appId: Long,
    algodUrl: String,
    selector: ByteArray,
    args: List<ByteArray>,
    boxKeys: List<Pair<Long, ByteArray>>,
): ByteArray?

/**
 * Polls Algod until [txId] is confirmed or [maxRounds] exhausted.
 * Returns (confirmedRound, logCount).
 */
internal expect fun awaitConfirmationDetailsInternal(
    txId: String,
    algodUrl: String,
    maxRounds: Int = 8,
): Pair<Long, Int>

/** Polls Algod until [txId] is confirmed or [maxRounds] exhausted; returns true if confirmed. */
internal expect fun awaitConfirmationInternal(
    txId: String,
    algodUrl: String,
    maxRounds: Int = 10,
): Boolean
