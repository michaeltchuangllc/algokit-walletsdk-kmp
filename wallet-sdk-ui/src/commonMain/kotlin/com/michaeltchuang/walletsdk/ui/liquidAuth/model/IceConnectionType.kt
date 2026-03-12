package com.michaeltchuang.walletsdk.ui.liquidAuth.model

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
    LOCAL,      // host - direct local network (best quality, lowest latency)
    STUN,       // srflx - NAT traversal (good quality)
    RELAY,      // relay - TURN relay server (acceptable, higher latency)
    FAILED,     // Connection failed
}

/**
 * Get display name for connection type
 */
fun IceConnectionType.displayName(): String = when (this) {
    IceConnectionType.LOCAL -> "Local"
    IceConnectionType.STUN -> "STUN"
    IceConnectionType.RELAY -> "Relay"
    IceConnectionType.FAILED -> "Failed"
    IceConnectionType.UNKNOWN -> "Detecting..."
}

/**
 * Get quality indicator (1-5 stars) for connection type
 * Higher is better
 */
fun IceConnectionType.qualityRating(): Int = when (this) {
    IceConnectionType.LOCAL -> 5      // Best - direct connection
    IceConnectionType.STUN -> 4       // Good - NAT traversal works
    IceConnectionType.RELAY -> 3      // Okay - through relay server
    IceConnectionType.FAILED -> 1     // Failed connection
    IceConnectionType.UNKNOWN -> 2    // Unknown/not yet determined
}

/**
 * Get typical latency range for connection type
 */
fun IceConnectionType.typicalLatency(): String = when (this) {
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
fun IceConnectionType.colorHex(): String = when (this) {
    IceConnectionType.LOCAL -> "#4CAF50"     // Green
    IceConnectionType.STUN -> "#2196F3"      // Blue
    IceConnectionType.RELAY -> "#FF9800"     // Orange
    IceConnectionType.FAILED -> "#F44336"    // Red
    IceConnectionType.UNKNOWN -> "#9E9E9E"   // Gray
}

/**
 * Check if this is a premium connection type (for x402 billing)
 * Local connections are "free" or cheaper, relay costs more infrastructure
 */
fun IceConnectionType.isPremium(): Boolean = when (this) {
    IceConnectionType.LOCAL -> false     // No infrastructure cost
    IceConnectionType.STUN -> false     // Minimal cost (STUN servers are cheap)
    IceConnectionType.RELAY -> true      // Expensive - TURN relays bandwidth
    IceConnectionType.FAILED -> false
    IceConnectionType.UNKNOWN -> false
}

/**
 * Suggested pricing tier for x402-style billing
 * Returns relative cost multiplier
 */
fun IceConnectionType.suggestedPricingTier(): Float = when (this) {
    IceConnectionType.LOCAL -> 1.0f      // Base rate
    IceConnectionType.STUN -> 1.0f      // Base rate
    IceConnectionType.RELAY -> 1.5f      // 50% premium for relay costs
    IceConnectionType.FAILED -> 0.0f    // No charge for failed
    IceConnectionType.UNKNOWN -> 0.0f     // No charge while detecting
}

/**
 * Get cost tier indicator for UI ($ to $$$)
 * Simple visual indicator of relative cost
 */
fun IceConnectionType.costTier(): String = when (this) {
    IceConnectionType.LOCAL -> "$"           // Cheapest - direct connection
    IceConnectionType.STUN -> "$"            // Cheap - just STUN lookup
    IceConnectionType.RELAY -> "$$$"         // Expensive - TURN relay bandwidth
    IceConnectionType.FAILED -> "-"
    IceConnectionType.UNKNOWN -> "..."
}