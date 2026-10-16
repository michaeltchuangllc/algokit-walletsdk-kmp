package com.michaeltchuang.walletsdk.core.network.model

import kotlinx.serialization.Serializable

/**
 * Response payload for the NFD (Non-Fungible Domains) `/nfd/lookup` endpoint using the
 * `thumbnail` view. See https://api-docs.nf.domains for details.
 */
@Serializable
internal data class NfdLookupResponse(
    val name: String,
    val owner: String? = null,
    val depositAccount: String? = null,
    val caAlgo: List<String>? = null,
    val unverifiedCaAlgo: List<String>? = null,
    val properties: NfdPropertiesResponse? = null,
)

@Serializable
internal data class NfdPropertiesResponse(
    val verified: NfdAvatarPropertiesResponse? = null,
    val userDefined: NfdAvatarPropertiesResponse? = null,
)

@Serializable
internal data class NfdAvatarPropertiesResponse(
    val avatar: String? = null,
)
