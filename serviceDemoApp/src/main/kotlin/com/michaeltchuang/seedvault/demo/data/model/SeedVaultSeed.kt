package com.michaeltchuang.seedvault.demo.data.model

import kotlinx.serialization.Serializable

/** A Falcon-1024 seed this app is authorized to use in Seed Vault Service. [publicKey] is Base64 encoded. */
@Serializable
data class SeedVaultSeed(
    val id: Long,
    val name: String,
    val address: String,
    val publicKey: String,
)
