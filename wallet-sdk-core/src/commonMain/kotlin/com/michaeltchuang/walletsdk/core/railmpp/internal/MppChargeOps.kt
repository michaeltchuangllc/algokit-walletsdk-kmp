package com.michaeltchuang.walletsdk.core.railmpp.internal

import com.michaeltchuang.walletsdk.core.railmpp.DEFAULT_ALGOD_URLS
import com.michaeltchuang.walletsdk.core.railmpp.MppNetworks
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Suggested params needed to build a charge transaction. */
internal data class MppBuildParams(
    val lastRound: Long,
    val genesisHashB64: String,
    val genesisId: String,
    val fee: Long,
    val minFee: Long,
)

/** Flattened view of a decoded (signed or unsigned) Algorand transaction for provider verification. */
internal data class MppDecodedTxn(
    val type: String,
    val sender: String?,
    val receiver: String?,
    val amount: Long?,
    val assetReceiver: String?,
    val assetAmount: Long?,
    val xferAsset: Long?,
    val lease: ByteArray?,
    val groupId: ByteArray?,
    val hasCloseRemainderTo: Boolean,
    val hasAssetCloseTo: Boolean,
    val hasRekeyTo: Boolean,
    val computedTxId: String?,
    /** Original bytes when the slot was a signed txn; null for an unsigned fee-payer slot. */
    val signedRaw: ByteArray?,
    /** Original bytes when the slot was an unsigned fee-payer txn; null otherwise. */
    val unsignedRaw: ByteArray?,
) {
    companion object {
        const val TYPE_PAYMENT = "pay"
        const val TYPE_ASSET_TRANSFER = "axfer"
    }
}

// ── Platform transaction primitives ──────────────────────────────────────────

/** Fetches the network's suggested params from algod. */
internal expect suspend fun mppFetchSuggestedParams(algodUrl: String): MppBuildParams

/** Builds an unsigned payment (ALGO `pay`) or asset-transfer (`axfer`) txn; returns msgpack bytes. */
internal expect fun mppBuildPaymentTxn(
    sender: String,
    receiver: String,
    amount: Long,
    asaId: String?,
    params: MppBuildParams,
    lease: ByteArray?,
    note: ByteArray?,
    useFeePayer: Boolean,
): ByteArray

/** Builds the unsigned fee-payer txn (0-ALGO self-payment with a pooled fee); returns msgpack bytes. */
internal expect fun mppBuildFeePayerTxn(
    feePayerAddress: String,
    params: MppBuildParams,
    pooledFee: Long,
    note: ByteArray?,
): ByteArray

/** Assigns a shared group id to the given unsigned txns; returns the grouped msgpack bytes in order. */
internal expect fun mppAssignGroup(unsignedTxns: List<ByteArray>): List<ByteArray>

/** Decodes a signed or unsigned txn into [MppDecodedTxn]. [isFeePayerSlot] allows an unsigned slot. */
internal expect fun mppDecodeTxn(
    bytes: ByteArray,
    isFeePayerSlot: Boolean,
): MppDecodedTxn

/** Broadcasts the concatenated signed group to algod; returns the txId (or null). */
internal expect suspend fun mppBroadcastGroup(
    algodUrl: String,
    signedBlobs: List<ByteArray>,
): String?

// ── Shared pure-Kotlin helpers ──────────────────────────────────────────────

/** Resolves the algod URL from an explicit override or the network default. */
internal fun resolveAlgodUrl(
    algodUrl: String?,
    network: String,
): String =
    algodUrl
        ?: DEFAULT_ALGOD_URLS[network]
        ?: DEFAULT_ALGOD_URLS[MppNetworks.ALGORAND_TESTNET]!!

/** Decodes the 32-byte public key from a base32 Algorand address, or null if malformed. */
internal fun parseMppAsaId(
    asaId: String?,
    context: String,
): Long {
    val normalizedAsaId = asaId?.trim()
    require(!normalizedAsaId.isNullOrBlank()) { "asaId required for $context" }
    return normalizedAsaId.toLongOrNull()
        ?: throw MppVerifyException("asaId must be a numeric Algorand ASA id for $context, got '$normalizedAsaId'")
}

internal fun decodeAlgorandAddressBytes(address: String): ByteArray? {
    val cleaned = address.trim().uppercase()
    if (cleaned.length != 58) return null

    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    var bits = 0
    var bitBuffer = 0
    val out = ArrayList<Byte>(36)

    for (ch in cleaned) {
        val value = alphabet.indexOf(ch)
        if (value < 0) return null
        bitBuffer = (bitBuffer shl 5) or value
        bits += 5
        while (bits >= 8) {
            bits -= 8
            out.add(((bitBuffer shr bits) and 0xFF).toByte())
        }
    }

    if (out.size != 36) return null
    return out.take(32).toByteArray()
}

@OptIn(ExperimentalTime::class)
internal fun mppNowMs(): Long = Clock.System.now().toEpochMilliseconds()

private const val SECONDS_PER_DAY = 86_400L

/** Formats `nowMs + secondsFromNow` as RFC 3339 UTC `yyyy-MM-dd'T'HH:mm:ss'Z'`. */
internal fun futureRfc3339(secondsFromNow: Int): String {
    val epochSec = mppNowMs() / 1000L + secondsFromNow
    val days = floorDiv(epochSec, SECONDS_PER_DAY)
    val secOfDay = floorMod(epochSec, SECONDS_PER_DAY)
    val (y, mo, d) = civilFromDays(days)
    val h = secOfDay / 3600
    val mi = (secOfDay % 3600) / 60
    val s = secOfDay % 60
    return "${pad(y, 4)}-${pad(mo, 2)}-${pad(d, 2)}T${pad(h, 2)}:${pad(mi, 2)}:${pad(s, 2)}Z"
}

/** Parses an RFC 3339 UTC timestamp (`yyyy-MM-ddTHH:mm:ssZ`) to epoch millis, or null. */
internal fun parseRfc3339Ms(value: String): Long? {
    val m = Regex("""(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2}):(\d{2})""").find(value) ?: return null
    val (y, mo, d, h, mi, s) = m.destructured
    val days = daysFromCivil(y.toLong(), mo.toInt(), d.toInt())
    val epochSec = days * SECONDS_PER_DAY + h.toLong() * 3600 + mi.toLong() * 60 + s.toLong()
    return epochSec * 1000L
}

private fun pad(
    value: Long,
    width: Int,
): String = value.toString().padStart(width, '0')

private fun pad(
    value: Int,
    width: Int,
): String = value.toString().padStart(width, '0')

private fun floorDiv(
    a: Long,
    b: Long,
): Long {
    var q = a / b
    if (a % b != 0L && ((a xor b) < 0L)) q--
    return q
}

private fun floorMod(
    a: Long,
    b: Long,
): Long = a - floorDiv(a, b) * b

/** Days since 1970-01-01 for a civil (y, m, d) date (Howard Hinnant's algorithm). */
private fun daysFromCivil(
    year: Long,
    month: Int,
    day: Int,
): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097 + doe - 719468
}

/** Civil (year, month, day) from days since 1970-01-01. */
private fun civilFromDays(daysSinceEpoch: Long): Triple<Long, Int, Int> {
    val z = daysSinceEpoch + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
    return Triple(if (m <= 2) y + 1 else y, m, d)
}
