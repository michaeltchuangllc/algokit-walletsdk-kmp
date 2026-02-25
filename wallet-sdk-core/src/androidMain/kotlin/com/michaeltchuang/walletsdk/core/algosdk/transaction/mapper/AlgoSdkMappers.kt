package com.michaeltchuang.walletsdk.core.algosdk.transaction.mapper

import com.michaeltchuang.walletsdk.core.algosdk.transaction.model.ApplicationCallStateSchema
import com.michaeltchuang.walletsdk.core.algosdk.transaction.model.payload.RawTransactionApplicationCallStateSchemaPayload

internal interface ApplicationCallStateSchemaMapper {
    operator fun invoke(payload: RawTransactionApplicationCallStateSchemaPayload?): ApplicationCallStateSchema
}
