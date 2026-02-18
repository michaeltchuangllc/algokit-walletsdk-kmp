package com.michaeltchuang.walletsdk.core.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response model for asset detail API
 */
@Serializable
data class AssetDetailResponse(
    @SerialName("asset_id") val assetId: Long? = null,
    @SerialName("name") val fullName: String? = null,
    @SerialName("logo") val logoUri: String? = null,
    @SerialName("unit_name") val shortName: String? = null,
    @SerialName("fraction_decimals") val fractionDecimals: Int? = null,
    @SerialName("usd_value") val usdValue: String? = null,
    @SerialName("creator") val assetCreator: AssetCreatorResponse? = null,
    @SerialName("collectible") val collectible: CollectibleResponse? = null,
    @SerialName("total") val maxSupply: String? = null,
    @SerialName("explorer_url") val explorerUrl: String? = null,
    @SerialName("verification_tier") val verificationTier: String? = null,
    @SerialName("project_url") val projectUrl: String? = null,
    @SerialName("project_name") val projectName: String? = null,
    @SerialName("logo_svg") val logoSvgUri: String? = null,
    @SerialName("discord_url") val discordUrl: String? = null,
    @SerialName("telegram_url") val telegramUrl: String? = null,
    @SerialName("twitter_username") val twitterUsername: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("url") val url: String? = null,
    @SerialName("total_supply") val totalSupply: String? = null,
    @SerialName("last_24_hours_algo_price_change_percentage") val last24HoursAlgoPriceChangePercentage: String? = null,
    @SerialName("available_on_discover_mobile") val isAvailableOnDiscoverMobile: Boolean? = null,
    @SerialName("category") val category: Int? = null,
    @SerialName("is_favorited") val isFavorite: Boolean? = null,
    @SerialName("is_price_alert_enabled") val isPriceAlertEnabled: Boolean? = null,
)

@Serializable
data class AssetCreatorResponse(
    @SerialName("public_key") val publicKey: String? = null,
    @SerialName("is_verified") val isVerified: Boolean? = null,
)

@Serializable
data class CollectibleResponse(
    @SerialName("title") val title: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("collection_name") val collectionName: String? = null,
)
