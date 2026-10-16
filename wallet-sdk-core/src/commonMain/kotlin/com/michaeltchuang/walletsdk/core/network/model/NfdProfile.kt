package com.michaeltchuang.walletsdk.core.network.model

/**
 * An NFD (Non-Fungible Domain) profile resolved for an Algorand address.
 *
 * NFDs are human readable names (e.g. "michaeltchuang.algo") that map to one or more Algorand
 * addresses, similar to DNS.
 */
data class NfdProfile(
    val address: String,
    val name: String,
    val avatarUrl: String?,
)
