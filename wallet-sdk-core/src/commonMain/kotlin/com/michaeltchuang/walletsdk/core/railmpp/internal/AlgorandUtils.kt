package com.michaeltchuang.walletsdk.core.railmpp.internal

import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSignerType

private const val ALGORAND_ADDRESS_PUBLIC_KEY_LENGTH = 32
private const val ALGORAND_ADDRESS_CHECKSUM_LENGTH = 4

/** Decodes an Algorand base32-encoded address to its 32-byte public key. */
internal fun decodeAlgorandAddressPublicKey(address: String): ByteArray {
    val decoded = decodeBase32(address)
    require(decoded.size >= ALGORAND_ADDRESS_PUBLIC_KEY_LENGTH + ALGORAND_ADDRESS_CHECKSUM_LENGTH) {
        "Invalid Algorand address length"
    }
    return decoded.copyOfRange(0, ALGORAND_ADDRESS_PUBLIC_KEY_LENGTH)
}

internal fun decodeBase32(value: String): ByteArray {
    var buffer = 0
    var bitsLeft = 0
    val bytes = mutableListOf<Byte>()
    value.trim().trimEnd('=').uppercase().forEach { char ->
        val charValue =
            when (char) {
                in 'A'..'Z' -> char - 'A'
                in '2'..'7' -> char - '2' + 26
                else -> error("Invalid base32 character: $char")
            }
        buffer = (buffer shl 5) or charValue
        bitsLeft += 5
        if (bitsLeft >= 8) {
            bitsLeft -= 8
            bytes.add(((buffer shr bitsLeft) and 0xFF).toByte())
        }
    }
    return bytes.toByteArray()
}

/** Big-endian uint64 encoding of [value]. */
internal fun encodeUint64(value: Long): ByteArray {
    var v = value
    val bytes = ByteArray(8)
    for (i in 7 downTo 0) {
        bytes[i] = (v and 0xFF).toByte()
        v = v ushr 8
    }
    return bytes
}

/** Encodes a byte array to Algorand's base32 (no padding). */
internal fun encodeBase32(bytes: ByteArray): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    val sb = StringBuilder()
    var buffer = 0
    var bitsInBuffer = 0
    for (b in bytes) {
        buffer = (buffer shl 8) or (b.toInt() and 0xFF)
        bitsInBuffer += 8
        while (bitsInBuffer >= 5) {
            bitsInBuffer -= 5
            sb.append(alphabet[(buffer ushr bitsInBuffer) and 0x1F])
        }
    }
    if (bitsInBuffer > 0) {
        sb.append(alphabet[(buffer shl (5 - bitsInBuffer)) and 0x1F])
    }
    return sb.toString()
}

/** Encodes a 32-byte public key to an Algorand base32 address (with checksum). */
internal fun encodeAlgorandAddress(publicKey: ByteArray): String {
    require(publicKey.size == ALGORAND_ADDRESS_PUBLIC_KEY_LENGTH) {
        "Public key must be $ALGORAND_ADDRESS_PUBLIC_KEY_LENGTH bytes"
    }
    // Algorand address checksum is the last 4 bytes of SHA512/256(publicKey), not SHA256.
    val checksum = sha512_256(publicKey).takeLast(ALGORAND_ADDRESS_CHECKSUM_LENGTH).toByteArray()
    return encodeBase32(publicKey + checksum)
}

/**
 * Derives the Algorand application address from an app ID.
 * Formula: sha512_256("appID" || encode_uint64(appId))
 */
internal fun appIdToAlgorandAddress(appId: Long): String {
    val hash = sha512_256("appID".encodeToByteArray() + encodeUint64(appId))
    return encodeAlgorandAddress(hash)
}

/**
 * Reads a channel box's raw bytes and extracts the on-chain payer's Algorand address from its
 * first 32 bytes (the box's fixed-layout `payer` field), or `null` if the box is too short.
 * Shared by Android/iOS's `getChannelPayerAddress` helpers, which differ only in how they fetch
 * [boxBytes] (native `algosdk` client vs. the Swift bridge).
 */
internal fun decodeChannelPayerAddress(boxBytes: ByteArray): String? {
    if (boxBytes.size < ALGORAND_ADDRESS_PUBLIC_KEY_LENGTH) return null
    return encodeAlgorandAddress(boxBytes.copyOfRange(0, ALGORAND_ADDRESS_PUBLIC_KEY_LENGTH))
}

// ── Shared fee / txn-building constants (Android & iOS) ────────────────────────────────────

internal const val APP_CALL_FEE = 12_000L
internal const val MIN_TXN_FEE = 1_000L
internal const val DUMMIES_PER_REAL_TXN = 3
internal const val FALCON_SIGNED_TRANSACTION_GROUP_FEE = MIN_TXN_FEE * (DUMMIES_PER_REAL_TXN + 1)

// Hardened LogicSig programs add encoded-byte fee on Futurenet; the teardown-sweep
// branch grew the settlement LogicSig program, bumping this from 18 to 30 microAlgos.
internal const val LOGIC_SIG_SETTLEMENT_GROUP_FEE = 3_030L
internal const val LOGIC_SIG_MINIMUM_BALANCE = 100_000L

internal val SETTLE_FROM_LOGIC_SIG_SELECTOR = byteArrayOf(0x43, 0x9c.toByte(), 0x5f, 0xb1.toByte())
internal val SET_SETTLEMENT_LOGIC_SIG_SELECTOR = byteArrayOf(0x42, 0xd9.toByte(), 0x75, 0xa6.toByte())
internal val SETTLEMENT_LOGIC_SIG_BOX_PREFIX = "l".encodeToByteArray()

/** Fee-pool budget required for a single-transaction submission by [signerType]'s signing scheme. */
internal fun pooledFeeFor(signerType: MppWalletSignerType): Long =
    when (signerType) {
        MppWalletSignerType.FALCON_NATIVE, MppWalletSignerType.FALCON_LSIG -> FALCON_SIGNED_TRANSACTION_GROUP_FEE
        MppWalletSignerType.ED25519 -> MIN_TXN_FEE
    }

/** ARC4 dynamic `byte[]` ABI encoding: a 2-byte big-endian length prefix followed by the bytes. */
internal fun encodeArc4DynamicBytes(bytes: ByteArray): ByteArray {
    require(bytes.size <= 0xFFFF) { "byte[] too long for ARC4 dynamic bytes" }
    return byteArrayOf(((bytes.size ushr 8) and 0xFF).toByte(), (bytes.size and 0xFF).toByte()) + bytes
}

private const val TEAL_HEX_CHARS = "0123456789abcdef"

/** Renders [this] as a TEAL `0x...` byte literal (avoids `String.format`, unavailable on K/N). */
internal fun ByteArray.toTealByteLiteral(): String =
    "0x" +
        joinToString("") {
            val v = it.toInt() and 0xFF
            "${TEAL_HEX_CHARS[v ushr 4]}${TEAL_HEX_CHARS[v and 0xF]}"
        }

private val TEAL_TEMPLATE_VARIABLE_PATTERN = Regex("TMPL_[A-Z0-9_]+")

/** Replaces every `TMPL_*` placeholder in [template] with its value from [substitutions]. */
internal fun substituteTealTemplate(
    template: String,
    substitutions: Map<String, String>,
): String {
    var rendered = template
    substitutions.forEach { (name, value) -> rendered = rendered.replace(name, value) }
    require(!TEAL_TEMPLATE_VARIABLE_PATTERN.containsMatchIn(rendered)) { "Unresolved LogicSig template variables" }
    return rendered
}

// ── ARC-4 readonly-method simulate support (shared between Android & iOS) ──────────────────

/**
 * ARC-4 "return log" prefix (sha512_256("return")[:4]) that precedes an ABI method's
 * ABI-encoded return value inside a transaction's logs.
 */
internal val ABI_RETURN_LOG_PREFIX = byteArrayOf(0x15, 0x1f, 0x7c, 0x75)

private fun mpFixMapHeader(entryCount: Int): Byte {
    require(entryCount in 0..15) { "fixmap supports at most 15 entries, got $entryCount" }
    return (0x80 or entryCount).toByte()
}

private fun mpFixArrayHeader(entryCount: Int): Byte {
    require(entryCount in 0..15) { "fixarray supports at most 15 entries, got $entryCount" }
    return (0x90 or entryCount).toByte()
}

private fun mpFixStr(value: String): ByteArray {
    val bytes = value.encodeToByteArray()
    require(bytes.size <= 31) { "fixstr supports at most 31 bytes, got ${bytes.size}" }
    return byteArrayOf((0xa0 or bytes.size).toByte()) + bytes
}

private val MSGPACK_TRUE = byteArrayOf(0xc3.toByte())

/**
 * Hand-rolls the msgpack bytes for algod's `/v2/transactions/simulate` request body
 * (`SimulateRequest`), wrapping [unsignedTxnBytes] (a single, already msgpack-encoded, unsigned
 * `Transaction`) as an unsigned `SignedTransaction` (`{txn: <bytes>}`, no `sig` field — matches
 * how the Java/Go SDKs omit a default/zero signature) inside a single-transaction group, with
 * `allow-empty-signatures` and `allow-unnamed-resources` both set so the call needs no signature
 * or real fee. This lets platforms without a native msgpack/JSON request-object encoder (e.g. the
 * iOS Swift bridge) reuse the exact same request-building logic as Android.
 */
internal fun buildSimulateRequestMsgpack(unsignedTxnBytes: ByteArray): ByteArray {
    // {"txn": <unsignedTxnBytes>}
    val signedTxnEnvelope = byteArrayOf(mpFixMapHeader(1)) + mpFixStr("txn") + unsignedTxnBytes
    // {"txns": [signedTxnEnvelope]}
    val group = byteArrayOf(mpFixMapHeader(1)) + mpFixStr("txns") + byteArrayOf(mpFixArrayHeader(1)) + signedTxnEnvelope
    // {"allow-empty-signatures": true, "allow-unnamed-resources": true, "txn-groups": [group]}
    return byteArrayOf(mpFixMapHeader(3)) +
        mpFixStr("allow-empty-signatures") + MSGPACK_TRUE +
        mpFixStr("allow-unnamed-resources") + MSGPACK_TRUE +
        mpFixStr("txn-groups") + byteArrayOf(mpFixArrayHeader(1)) + group
}
