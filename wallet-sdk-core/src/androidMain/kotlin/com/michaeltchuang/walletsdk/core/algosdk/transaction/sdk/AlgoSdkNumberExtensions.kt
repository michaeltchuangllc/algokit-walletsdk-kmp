package com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk

import io.github.algorandecosystem.sdk.Uint64
import java.math.BigInteger

internal object AlgoSdkNumberExtensions {
    fun Long.toUint64(): Uint64 =
        Uint64().apply {
            upper = shr(Int.SIZE_BITS)
            lower = and(Int.MAX_VALUE.toLong())
        }

    fun BigInteger.toUint64(): Uint64 =
        Uint64().apply {
            upper = shr(Int.SIZE_BITS).toLong()
            lower = and(UInt.MAX_VALUE.toLong().toBigInteger()).toLong()
        }
}
