package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json

/**
 * A summary of a season including its active period and status.
 *
 * @property id The unique identifier of this season.
 * @property name The display name of the season.
 * @property namespace The namespace where this season exists.
 * @property start The start date of the season.
 * @property end The end date of the season.
 * @property status The current status of the season.
 * @property passCodes List of pass codes associated with this season.
 * @property publishedAt Timestamp when the season was published.
 * @property previous The previous season data as JSON.
 */
data class SeasonSummaryResponse(
    val id : String,
    val name : String,
    val namespace : String,
    val start : String,
    val end : String,
    val status : String,
    val passCodes : List<String>?,
    val publishedAt : String?,
    val previous : Json?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : SeasonSummaryResponse = SeasonSummaryResponse(
            id = json.requireString("id"),
            name = json.requireString("name"),
            namespace = json.requireString("namespace"),
            start = json.requireString("start"),
            end = json.requireString("end"),
            status = json.requireString("status"),
            passCodes = json.optStringList("passCodes").takeIf { it.isNotEmpty() },
            publishedAt = json.optString("publishedAt"),
            previous = json.optJson("previous")
        )
    }
}

/**
 * Information about a season pass including its item reference and settings.
 *
 * @property passItemId The item ID of the pass.
 * @property passItemName The name of the pass item.
 * @property code The code for this pass.
 * @property namespace The namespace where this pass exists.
 * @property seasonId The season this pass belongs to.
 * @property createdAt Timestamp when this pass was created.
 * @property updatedAt Timestamp when this pass was last updated.
 * @property displayOrder The display order for this pass.
 * @property autoEnroll Whether to auto-enroll users in this pass.
 * @property images List of images for this pass.
 * @property localizations Localized content as JSON.
 */
data class SeasonPassInfoResponse(
    val passItemId : String,
    val passItemName : String?,
    val code : String,
    val namespace : String,
    val seasonId : String,
    val createdAt : String,
    val updatedAt : String,
    val displayOrder : String,
    val autoEnroll : Boolean,
    val images : List<ImageResponse>?,
    val localizations : Json?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : SeasonPassInfoResponse = SeasonPassInfoResponse(
            passItemId = json.requireString("passItemId"),
            passItemName = json.optString("passItemName"),
            code = json.requireString("code"),
            namespace = json.requireString("namespace"),
            seasonId = json.requireString("seasonId"),
            createdAt = json.requireString("createdAt"),
            updatedAt = json.requireString("updatedAt"),
            displayOrder = json.requireString("displayOrder"),
            autoEnroll = json.requireBoolean("autoEnroll"),
            images = json.optJsonList("images").map(ImageResponse::fromJson).takeIf { it.isNotEmpty() },
            localizations = json.optJson("localizations")
        )
    }
}

/**
 * Information about a reward in the season pass system.
 *
 * @property code The code identifying this reward.
 * @property type The type of reward (e.g., ITEM, CURRENCY).
 * @property namespace The namespace where this reward exists.
 * @property seasonId The season this reward belongs to.
 * @property currency Currency reward details as JSON.
 * @property image The image for this reward.
 * @property itemId The item ID if this is an item reward.
 * @property itemName The name of the item reward.
 * @property itemSku The SKU of the item reward.
 * @property itemType The type of the item reward.
 * @property quantity The quantity of the reward.
 * @property ext Extended attributes as JSON.
 */
data class SeasonRewardInfoResponse(
    val code : String,
    val type : String,
    val namespace : String,
    val seasonId : String,
    val currency : Json?,
    val image : ImageResponse?,
    val itemId : String?,
    val itemName : String?,
    val itemSku : String?,
    val itemType : String?,
    val quantity : Int?,
    val ext : Json?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : SeasonRewardInfoResponse = SeasonRewardInfoResponse(
            code = json.requireString("code"),
            type = json.requireString("type"),
            namespace = json.requireString("namespace"),
            seasonId = json.requireString("seasonId"),
            currency = json.optJson("currency"),
            image = json.optJson("image")?.let(ImageResponse::fromJson),
            itemId = json.optString("itemId"),
            itemName = json.optString("itemName"),
            itemSku = json.optString("itemSku"),
            itemType = json.optString("itemType"),
            quantity = json.optInt("quantity"),
            ext = json.optJson("ext")
        )
    }
}

/**
 * A tier within the season pass reward ladder.
 *
 * @property id The unique identifier of this tier.
 * @property requiredExp The experience required to reach this tier.
 * @property rewards The rewards for this tier as JSON.
 */
data class SeasonTierResponse(
    val id : String?,
    val requiredExp : Int?,
    val rewards : Json?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : SeasonTierResponse = SeasonTierResponse(
            id = json.optString("id"),
            requiredExp = json.optInt("requiredExp"),
            rewards = json.optJson("rewards")
        )
    }
}

/**
 * Complete information about a season including passes, rewards, and tiers.
 *
 * @property id The unique identifier of this season.
 * @property name The display name of the season.
 * @property namespace The namespace where this season exists.
 * @property images List of images for this season.
 * @property localizations Localized content as JSON.
 * @property passes List of passes available in this season.
 * @property rewards The rewards configuration as JSON.
 * @property tiers List of tiers in this season.
 */
data class SeasonInfoResponse(
    val id : String,
    val name : String,
    val namespace : String,
    val images : List<ImageResponse>?,
    val localizations : Json?,
    val passes : List<SeasonPassInfoResponse>,
    val rewards : Json?,
    val tiers : List<SeasonTierResponse>
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : SeasonInfoResponse = SeasonInfoResponse(
            id = json.requireString("id"),
            name = json.requireString("name"),
            namespace = json.requireString("namespace"),
            images = json.optJsonList("images").map(ImageResponse::fromJson).takeIf { it.isNotEmpty() },
            localizations = json.optJson("localizations"),
            passes = json.optJsonList("passes").map(SeasonPassInfoResponse::fromJson),
            rewards = json.optJson("rewards"),
            tiers = json.optJsonList("tiers").map(SeasonTierResponse::fromJson)
        )
    }
}

/**
 * A single season entry in a list response.
 *
 * @property id The unique identifier of this season.
 * @property name The display name of the season.
 * @property namespace The namespace where this season exists.
 * @property start The start date of the season.
 * @property end The end date of the season.
 * @property status The current status of the season.
 * @property tierItemId The item ID for the tier.
 * @property tierItemName The name of the tier item.
 * @property defaultLanguage The default language for this season.
 * @property passCodes List of pass codes associated with this season.
 * @property publishedAt Timestamp when the season was published.
 * @property createdAt Timestamp when this season was created.
 * @property updatedAt Timestamp when this season was last updated.
 */
data class SeasonListEntryResponse(
    val id : String,
    val name : String,
    val namespace : String,
    val start : String,
    val end : String,
    val status : String,
    val tierItemId : String,
    val tierItemName : String,
    val defaultLanguage : String,
    val passCodes : List<String>?,
    val publishedAt : String?,
    val createdAt : String,
    val updatedAt : String
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : SeasonListEntryResponse = SeasonListEntryResponse(
            id = json.requireString("id"),
            name = json.requireString("name"),
            namespace = json.requireString("namespace"),
            start = json.requireString("start"),
            end = json.requireString("end"),
            status = json.requireString("status"),
            tierItemId = json.requireString("tierItemId"),
            tierItemName = json.requireString("tierItemName"),
            defaultLanguage = json.requireString("defaultLanguage"),
            passCodes = json.optStringList("passCodes").takeIf { it.isNotEmpty() },
            publishedAt = json.optString("publishedAt"),
            createdAt = json.requireString("createdAt"),
            updatedAt = json.requireString("updatedAt")
        )
    }
}

/**
 * A paginated list of seasons.
 *
 * @property data The list of season entries on this page.
 * @property paging Pagination metadata for navigating additional pages.
 */
data class SeasonListResponse(
    val data : List<SeasonListEntryResponse>,
    val paging : OffsetPagination?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : SeasonListResponse = SeasonListResponse(
            data = json.optJsonList("data").map(SeasonListEntryResponse::fromJson),
            paging = json.optJson("paging")?.let(OffsetPagination::fromJson)
        )
    }
}

/**
 * A list of season pass information entries.
 *
 * @property data The list of season passes.
 */
data class SeasonPassListResponse(
    val data : List<SeasonPassInfoResponse>
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : SeasonPassListResponse = SeasonPassListResponse(
            data = json.optJsonList("data").map(SeasonPassInfoResponse::fromJson)
        )
    }
}

/**
 * A list of season reward information entries.
 *
 * @property data The list of season rewards.
 */
data class SeasonRewardListResponse(
    val data : List<SeasonRewardInfoResponse>
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : SeasonRewardListResponse = SeasonRewardListResponse(
            data = json.optJsonList("data").map(SeasonRewardInfoResponse::fromJson)
        )
    }
}

/**
 * A record of experience granted to a user in a season.
 *
 * @property id The unique identifier of this grant record.
 * @property namespace The namespace where this grant occurred.
 * @property seasonId The season ID this grant is for.
 * @property userId The user who received the grant.
 * @property grantExp The amount of experience granted.
 * @property source The source of the grant.
 * @property tags Tags associated with this grant.
 * @property createdAt Timestamp when this grant was created.
 */
data class ExpGrantHistoryResponse(
    val id : String,
    val namespace : String,
    val seasonId : String,
    val userId : String,
    val grantExp : Int,
    val source : String?,
    val tags : List<String>?,
    val createdAt : String
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : ExpGrantHistoryResponse = ExpGrantHistoryResponse(
            id = json.requireString("id"),
            namespace = json.requireString("namespace"),
            seasonId = json.requireString("seasonId"),
            userId = json.requireString("userId"),
            grantExp = json.requireInt("grantExp"),
            source = json.optString("source"),
            tags = json.optStringList("tags").takeIf { it.isNotEmpty() },
            createdAt = json.requireString("createdAt")
        )
    }
}

/**
 * A paginated list of experience grant history entries.
 *
 * @property data The list of grant history entries on this page.
 * @property paging Pagination metadata for navigating additional pages.
 * @property total The total number of grant records.
 */
data class ExpGrantHistoryPageResponse(
    val data : List<ExpGrantHistoryResponse>,
    val paging : OffsetPagination?,
    val total : Int?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : ExpGrantHistoryPageResponse = ExpGrantHistoryPageResponse(
            data = json.optJsonList("data").map(ExpGrantHistoryResponse::fromJson),
            paging = json.optJson("paging")?.let(OffsetPagination::fromJson),
            total = json.optInt("total")
        )
    }
}

/**
 * A user's summary for a specific season including progress and enrollment.
 *
 * @property id The unique identifier of this user season summary.
 * @property seasonId The season ID for this summary.
 * @property namespace The namespace where this summary exists.
 * @property userId The user this summary belongs to.
 * @property cleared Whether the season has been cleared.
 * @property currentTierIndex The current tier index of the user.
 * @property lastTierIndex The last tier index achieved.
 * @property enrolledAt Timestamp when the user enrolled in the season.
 * @property season The season summary data.
 */
data class UserSeasonSummaryResponse(
    val id : String,
    val seasonId : String,
    val namespace : String,
    val userId : String,
    val cleared : Boolean,
    val currentTierIndex : Int,
    val lastTierIndex : Int,
    val enrolledAt : String,
    val season : SeasonSummaryResponse?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : UserSeasonSummaryResponse = UserSeasonSummaryResponse(
            id = json.requireString("id"),
            seasonId = json.requireString("seasonId"),
            namespace = json.requireString("namespace"),
            userId = json.requireString("userId"),
            cleared = json.requireBoolean("cleared"),
            currentTierIndex = json.requireInt("currentTierIndex"),
            lastTierIndex = json.requireInt("lastTierIndex"),
            enrolledAt = json.requireString("enrolledAt"),
            season = json.optJson("season")?.let(SeasonSummaryResponse::fromJson)
        )
    }
}

/**
 * A paginated list of user season summaries.
 *
 * @property data The list of user season summaries on this page.
 * @property paging Pagination metadata for navigating additional pages.
 * @property total The total number of user season summaries.
 */
data class UserSeasonListResponse(
    val data : List<UserSeasonSummaryResponse>,
    val paging : OffsetPagination?,
    val total : Int?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : UserSeasonListResponse = UserSeasonListResponse(
            data = json.optJsonList("data").map(UserSeasonSummaryResponse::fromJson),
            paging = json.optJson("paging")?.let(OffsetPagination::fromJson),
            total = json.optInt("total")
        )
    }
}

/**
 * A paginated list of season tiers.
 *
 * @property data The list of season tiers on this page.
 * @property paging Pagination metadata for navigating additional pages.
 * @property total The total number of season tiers.
 */
data class SeasonTierPageResponse(
    val data : List<SeasonTierResponse>,
    val paging : OffsetPagination?,
    val total : Int?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : SeasonTierPageResponse = SeasonTierPageResponse(
            data = json.optJsonList("data").map(SeasonTierResponse::fromJson),
            paging = json.optJson("paging")?.let(OffsetPagination::fromJson),
            total = json.optInt("total")
        )
    }
}

/**
 * Detailed information about a user's claimable season progress and rewards.
 *
 * @property id The unique identifier of this claimable info.
 * @property namespace The namespace where this info exists.
 * @property seasonId The season ID for this claimable info.
 * @property seasonSummary The season summary data.
 * @property currentExp The user's current experience points.
 * @property requiredExp The experience required for the current tier.
 * @property totalExp The total experience earned.
 * @property enrolledAt Timestamp when the user enrolled.
 * @property enrolledPasses List of passes the user is enrolled in.
 * @property claimingRewards Rewards currently being claimed as JSON.
 * @property toClaimRewards Rewards available to claim as JSON.
 * @property userId The user ID this info belongs to.
 * @property updatedAt Timestamp when this info was last updated.
 * @property cleared Whether the season has been cleared.
 * @property currentTierIndex The current tier index.
 * @property lastTierIndex The last tier index achieved.
 * @property totalSweatExp Total experience from sweat (free) sources.
 * @property totalPaidForExp Total experience from paid sources.
 */
data class ClaimableUserSeasonInfoResponse(
    val id : String,
    val namespace : String,
    val seasonId : String,
    val seasonSummary : SeasonSummaryResponse?,
    val currentExp : Int,
    val requiredExp : Int,
    val totalExp : Int?,
    val enrolledAt : String,
    val enrolledPasses : List<String>,
    val claimingRewards : Json,
    val toClaimRewards : Json,
    val userId : String,
    val updatedAt : String,
    val cleared : Boolean,
    val currentTierIndex : Int,
    val lastTierIndex : Int,
    val totalSweatExp : Int?,
    val totalPaidForExp : Int?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : ClaimableUserSeasonInfoResponse = ClaimableUserSeasonInfoResponse(
            id = json.requireString("id"),
            namespace = json.requireString("namespace"),
            seasonId = json.requireString("seasonId"),
            seasonSummary = json.optJson("season")?.let(SeasonSummaryResponse::fromJson),
            currentExp = json.requireInt("currentExp"),
            requiredExp = json.requireInt("requiredExp"),
            totalExp = json.optInt("totalExp"),
            enrolledAt = json.requireString("enrolledAt"),
            enrolledPasses = json.optStringList("enrolledPasses"),
            claimingRewards = json.requireJson("claimingRewards"),
            toClaimRewards = json.requireJson("toClaimRewards"),
            userId = json.requireString("userId"),
            updatedAt = json.requireString("updatedAt"),
            cleared = json.requireBoolean("cleared"),
            currentTierIndex = json.requireInt("currentTierIndex"),
            lastTierIndex = json.requireInt("lastTierIndex"),
            totalSweatExp = json.optInt("totalSweatExp"),
            totalPaidForExp = json.optInt("totalPaidForExp")
        )
    }
}

/**
 * Rewards that are being claimed or available to claim.
 *
 * @property claimingRewards The rewards currently being claimed as JSON.
 * @property toClaimRewards The rewards available to claim as JSON.
 */
data class ClaimableRewardsResponse(
    val claimingRewards : Json,
    val toClaimRewards : Json
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : ClaimableRewardsResponse = ClaimableRewardsResponse(
            claimingRewards = json.requireJson("claimingRewards"),
            toClaimRewards = json.requireJson("toClaimRewards")
        )
    }
}

/**
 * Reference information for an item module.
 *
 * @property module The module name for this item reference.
 * @property references List of reference entries as JSON.
 */
data class ItemReferenceResponse(
    val module : String?,
    val references : List<Json>?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : ItemReferenceResponse = ItemReferenceResponse(
            module = json.optString("module"),
            references = json.optJsonArray("references")?.mapNotNull { it }
        )
    }
}

/**
 * List of item reference information entries.
 *
 * @property references List of item reference responses.
 */
data class ItemReferenceInfoResponse(
    val references : List<ItemReferenceResponse>?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : ItemReferenceInfoResponse = ItemReferenceInfoResponse(
            references = json.optJsonList("references").map(ItemReferenceResponse::fromJson)
        )
    }
}