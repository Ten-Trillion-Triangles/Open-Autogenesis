package structs.accelbyte.seasonpass

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable
import structs.accelbyte.common.OffsetPagination
import structs.accelbyte.common.PagingInfo

@Serializable
data class SeasonSummaryResponse(
    val id: String,
    val name: String,
    val namespace: String,
    val start: String,
    val end: String,
    val status: String,
    val passCodes: List<String>? = null,
    val publishedAt: String? = null,
    val previous: JsonElement? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class SeasonPassInfoResponse(
    val passItemId: String,
    val passItemName: String? = null,
    val code: String,
    val namespace: String,
    val seasonId: String,
    val createdAt: String,
    val updatedAt: String,
    val displayOrder: String,
    val autoEnroll: Boolean,
    val images: List<JsonElement>? = null,
    val localizations: JsonElement? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class SeasonRewardInfoResponse(
    val code: String,
    val type: String,
    val namespace: String,
    val seasonId: String,
    val currency: JsonElement? = null,
    val image: JsonElement? = null,
    val itemId: String? = null,
    val itemName: String? = null,
    val itemSku: String? = null,
    val itemType: String? = null,
    val quantity: Int? = null,
    val ext: JsonElement? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class SeasonTierResponse(
    val id: String? = null,
    val requiredExp: Int? = null,
    val rewards: JsonElement? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class SeasonInfoResponse(
    val id: String,
    val name: String,
    val namespace: String,
    val images: List<JsonElement>? = null,
    val localizations: JsonElement? = null,
    val passes: List<SeasonPassInfoResponse>,
    val rewards: JsonElement? = null,
    val tiers: List<SeasonTierResponse>
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class SeasonListEntryResponse(
    val id: String,
    val name: String,
    val namespace: String,
    val start: String,
    val end: String,
    val status: String,
    val tierItemId: String,
    val tierItemName: String,
    val defaultLanguage: String,
    val passCodes: List<String>? = null,
    val publishedAt: String? = null,
    val createdAt: String,
    val updatedAt: String
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class SeasonListResponse(
    val data: List<SeasonListEntryResponse>,
    val paging: OffsetPagination? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class SeasonPassListResponse(val data: List<SeasonPassInfoResponse>) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class SeasonRewardListResponse(val data: List<SeasonRewardInfoResponse>) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ExpGrantHistoryResponse(
    val id: String,
    val namespace: String,
    val seasonId: String,
    val userId: String,
    val grantExp: Int,
    val source: String? = null,
    val tags: List<String>? = null,
    val createdAt: String
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ExpGrantHistoryPageResponse(
    val data: List<ExpGrantHistoryResponse>,
    val paging: OffsetPagination? = null,
    val total: Int? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class UserSeasonSummaryResponse(
    val id: String,
    val seasonId: String,
    val namespace: String,
    val userId: String,
    val cleared: Boolean,
    val currentTierIndex: Int,
    val lastTierIndex: Int,
    val enrolledAt: String,
    val season: SeasonSummaryResponse? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class UserSeasonListResponse(
    val data: List<UserSeasonSummaryResponse>,
    val paging: OffsetPagination? = null,
    val total: Int? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class SeasonTierPageResponse(
    val data: List<SeasonTierResponse>,
    val paging: OffsetPagination? = null,
    val total: Int? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ClaimableUserSeasonInfoResponse(
    val id: String,
    val namespace: String,
    val seasonId: String,
    val currentExp: Int,
    val requiredExp: Int,
    val totalExp: Int? = null,
    val enrolledAt: String,
    val enrolledPasses: List<String>,
    val claimingRewards: JsonElement,
    val toClaimRewards: JsonElement,
    val userId: String,
    val updatedAt: String,
    val cleared: Boolean,
    val currentTierIndex: Int,
    val lastTierIndex: Int,
    val totalSweatExp: Int? = null,
    val totalPaidForExp: Int? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ClaimableRewardsResponse(val claimingRewards: JsonElement, val toClaimRewards: JsonElement) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ItemReferenceResponse(
    val module: String? = null,
    val references: List<JsonElement>? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ItemReferenceInfoResponse(val references: List<ItemReferenceResponse>? = null) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}
