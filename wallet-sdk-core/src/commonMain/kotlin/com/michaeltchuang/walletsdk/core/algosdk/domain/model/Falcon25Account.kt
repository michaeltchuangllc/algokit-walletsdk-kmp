package com.michaeltchuang.walletsdk.core.algosdk.domain.model

data class Falcon25Account(
    val address: String,
    val publicKey: ByteArray,
    val privateKey: ByteArray,
    val entropy: ByteArray,
    val seed: ByteArray,
)
