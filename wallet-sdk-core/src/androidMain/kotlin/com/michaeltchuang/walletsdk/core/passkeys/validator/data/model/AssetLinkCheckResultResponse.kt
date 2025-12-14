package com.michaeltchuang.walletsdk.core.passkeys.validator.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class AssetLinkCheckResultResponse(
    @SerialName("linked")
    val linked: Boolean,
    @SerialName("maxAge")
    val maxAge: String?,
    @SerialName("debugString")
    val debugMessage: String?
)
