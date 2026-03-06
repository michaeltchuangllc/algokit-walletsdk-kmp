package com.michaeltchuang.walletsdk.core.algosdk.transaction.model.payload

import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigInteger

@Serializable
internal data class RawTransactionApplicationCallStateSchemaPayload(
    @Contextual @SerialName("nui") val numberOfInts: BigInteger? = null,
    @Contextual @SerialName("nbs") val numberOfBytes: BigInteger? = null,
)
