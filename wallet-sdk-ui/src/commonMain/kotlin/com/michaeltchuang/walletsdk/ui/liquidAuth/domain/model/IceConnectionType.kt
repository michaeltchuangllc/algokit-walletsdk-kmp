package com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model

import com.michaeltchuang.walletsdk.core.foundation.utils.LiquidStreamConstants

/**
 * WebRTC ICE Connection Type
 *
 * Represents how peers are connected:
 * - LOCAL: Direct connection on local network (host candidate)
 * - STUN: Connection through NAT via STUN server (srflx candidate)
 * - RELAY: Connection through TURN relay server (relay candidate)
 * - UNKNOWN: Connection type not yet determined
 * - FAILED: Connection attempt failed
 *
 * This is useful for:
 * 1. Quality indicators in UI (local = best, relay = worst)
 * 2. Billing/rate limiting (x402-style different pricing per connection type)
 * 3. Debugging connection issues
 */
enum class IceConnectionType {
    UNKNOWN,
    LOCAL, // host - direct local network (best quality, lowest latency)
    STUN, // srflx - NAT traversal (good quality)
    RELAY, // relay - TURN relay server (acceptable, higher latency)
    FAILED, // Connection failed
}

/**
 * Get display name for connection type
 */
fun IceConnectionType.displayName(): String =
    when (this) {
        IceConnectionType.LOCAL -> "Local"
        IceConnectionType.STUN -> "STUN"
        IceConnectionType.RELAY -> "Relay"
        IceConnectionType.FAILED -> "Failed"
        IceConnectionType.UNKNOWN -> "Detecting..."
    }

/**
 * Parse a platform-provided ICE connection type string into the shared UI model.
 */
fun parseIceConnectionType(typeString: String): IceConnectionType =
    when (typeString.trim().lowercase()) {
        "local" -> IceConnectionType.LOCAL
        "stun" -> IceConnectionType.STUN
        "relay" -> IceConnectionType.RELAY
        "failed" -> IceConnectionType.FAILED
        else -> IceConnectionType.UNKNOWN
    }

/**
 * Get quality indicator (1-5 stars) for connection type
 * Higher is better
 */
fun IceConnectionType.qualityRating(): Int =
    when (this) {
        IceConnectionType.LOCAL -> 5 // Best - direct connection
        IceConnectionType.STUN -> 4 // Good - NAT traversal works
        IceConnectionType.RELAY -> 3 // Okay - through relay server
        IceConnectionType.FAILED -> 1 // Failed connection
        IceConnectionType.UNKNOWN -> 2 // Unknown/not yet determined
    }

/**
 * Get typical latency range for connection type
 */
fun IceConnectionType.typicalLatency(): String =
    when (this) {
        IceConnectionType.LOCAL -> "< 1ms"
        IceConnectionType.STUN -> "5-50ms"
        IceConnectionType.RELAY -> "20-200ms"
        IceConnectionType.FAILED -> "N/A"
        IceConnectionType.UNKNOWN -> "..."
    }

/**
 * Get color indicator for UI
 * Returns hex color string
 */
fun IceConnectionType.colorHex(): String =
    when (this) {
        IceConnectionType.LOCAL -> "#4CAF50" // Green
        IceConnectionType.STUN -> "#2196F3" // Blue
        IceConnectionType.RELAY -> "#FF9800" // Orange
        IceConnectionType.FAILED -> "#F44336" // Red
        IceConnectionType.UNKNOWN -> "#9E9E9E" // Gray
    }

/**
 * Check if this is a premium connection type (for x402 billing)
 * Local connections are "free" or cheaper, relay costs more infrastructure
 */
fun IceConnectionType.isPremium(): Boolean =
    when (this) {
        IceConnectionType.LOCAL -> false // No infrastructure cost
        IceConnectionType.STUN -> false // Minimal cost (STUN servers are cheap)
        IceConnectionType.RELAY -> true // Expensive - TURN relays bandwidth
        IceConnectionType.FAILED -> false
        IceConnectionType.UNKNOWN -> false
    }

const val LOCAL_COST_MULTIPLIER = 1L
const val STUN_COST_MULTIPLIER = 2L
const val RELAY_COST_MULTIPLIER = 10L

/**
 * Transport multiplier applied on top of the base content price (per block).
 *
 * - LOCAL: 1x — direct path, no infrastructure cost.
 * - STUN: 2x — NAT traversal via STUN; small infrastructure cost.
 * - RELAY: 10x — every byte flows through a TURN server, the dominant real cost.
 * - UNKNOWN/FAILED: 1x — never discount below content price while detecting, and
 *   avoid price flicker if a stats poll briefly times out.
 *
 * Integer multipliers keep billing in exact micro-USDC.
 */
fun IceConnectionType.costMultiplier(): Long =
    when (this) {
        IceConnectionType.LOCAL -> LOCAL_COST_MULTIPLIER
        IceConnectionType.STUN -> STUN_COST_MULTIPLIER
        IceConnectionType.RELAY -> RELAY_COST_MULTIPLIER
        IceConnectionType.FAILED, IceConnectionType.UNKNOWN -> LOCAL_COST_MULTIPLIER
    }

/** Per-block price in micro-USDC: base content cost × transport multiplier. */
fun IceConnectionType.costPerBlockMicroUsdc(baseCostMicroUsdc: Long): Long = (baseCostMicroUsdc * costMultiplier()).coerceAtLeast(0L)

fun IceConnectionType.sessionVaultMinimumBalanceMicroUsdc(): Long =
    costPerBlockMicroUsdc(LiquidStreamConstants.COST_PER_BLOCK_MICRO_USDC) * 2

/**
 * Picks the connection type used for pricing. Only a concrete detection (LOCAL/STUN/RELAY)
 * changes the price; transient UNKNOWN/FAILED readings keep the last known tier.
 */
fun resolvePricingConnectionType(
    previous: IceConnectionType,
    detected: IceConnectionType,
): IceConnectionType =
    when (detected) {
        IceConnectionType.LOCAL, IceConnectionType.STUN, IceConnectionType.RELAY -> detected
        IceConnectionType.UNKNOWN, IceConnectionType.FAILED -> previous
    }

/** Applies the transport multiplier to a decimal micro-USDC amount string (e.g. a gating amount). */
fun IceConnectionType.scaleMicroUsdcAmount(baseAmount: String): String =
    baseAmount.toLongOrNull()?.let { costPerBlockMicroUsdc(it).toString() } ?: baseAmount

/**
 * Get cost tier indicator for UI ($ to $$)
 * Simple visual indicator of relative cost
 */
fun IceConnectionType.costTier(): String =
    when (this) {
        IceConnectionType.LOCAL -> "$" // Cheapest - direct connection
        IceConnectionType.STUN -> "$" // NAT traversal via STUN
        IceConnectionType.RELAY -> "$$" // Expensive - TURN relay bandwidth
        IceConnectionType.FAILED -> "-"
        IceConnectionType.UNKNOWN -> "..."
    }
