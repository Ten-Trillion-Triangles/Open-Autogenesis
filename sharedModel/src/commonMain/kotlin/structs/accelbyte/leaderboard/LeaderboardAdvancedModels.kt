@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package structs.accelbyte.leaderboard
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable

@Serializable
data class LeaderboardDailyConfig(
    @SerialName("resetTime")
    val resetTime: String
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class LeaderboardMonthlyConfig(
    @SerialName("resetDate")
    val resetDate: Int,
    @SerialName("resetTime")
    val resetTime: String
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class LeaderboardWeeklyConfig(
    @SerialName("resetDay")
    val resetDay: Int,
    @SerialName("resetTime")
    val resetTime: String
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class LeaderboardConfig(
    val leaderboardCode: String,
    val name: String,
    val statCode: String,
    val daily: LeaderboardDailyConfig? = null,
    val monthly: LeaderboardMonthlyConfig? = null,
    val weekly: LeaderboardWeeklyConfig? = null,
    val seasonPeriod: Int? = null,
    val startTime: String? = null,
    @SerialName("iconURL")
    @JsonNames("iconUrl")
    val iconUrl: String? = null,
    val descending: Boolean? = null,
    val isArchived: Boolean? = null,
    val isDeleted: Boolean? = null,
    val deletedAt: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class LeaderboardConfigListResponse(
    val data: List<LeaderboardConfig>,
    val paging: LeaderboardPagination
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class LeaderboardPagination(
    @SerialName("next")
    @JsonNames("Next")
    val next: String? = null,
    @SerialName("previous")
    @JsonNames("Previous")
    val previous: String? = null,
    @SerialName("first")
    @JsonNames("First")
    val first: String? = null,
    @SerialName("last")
    @JsonNames("Last")
    val last: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class LeaderboardUserPoint(
    val userId: String,
    val point: Double? = null,
    val rank: Long? = null,
    val hidden: Boolean? = null,
    val additionalData: JsonElement? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class LeaderboardRankingResponse(
    val data: List<LeaderboardUserPoint>,
    val paging: LeaderboardPagination
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class LeaderboardUserRankingDetail(
    val point: Double? = null,
    val rank: Long? = null,
    val hidden: Boolean? = null,
    val additionalData: JsonElement? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class LeaderboardUserRankingResponse(
    val userId: String,
    val allTime: LeaderboardUserRankingDetail? = null,
    val current: LeaderboardUserRankingDetail? = null,
    val daily: LeaderboardUserRankingDetail? = null,
    val monthly: LeaderboardUserRankingDetail? = null,
    val weekly: LeaderboardUserRankingDetail? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class LeaderboardUpdateUserPointResponse(
    val userId: String,
    val point: Double
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class LeaderboardPublicConfigSummaryV2(
    val leaderboardCode: String,
    val name: String,
    val statCode: String,
    @SerialName("iconURL")
    @JsonNames("iconUrl")
    val iconUrl: String?
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class LeaderboardPublicConfigListV2(
    val data: List<LeaderboardPublicConfigSummaryV2>,
    val paging: LeaderboardPagination
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class LeaderboardConfigV3(
    val leaderboardCode: String,
    val name: String,
    val statCode: String,
    val allTime: Boolean,
    val cycleIds: List<String>? = null,
    val description: String? = null,
    @SerialName("iconURL")
    @JsonNames("iconUrl")
    val iconUrl: String? = null,
    val isDeleted: Boolean? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class LeaderboardConfigListV3(
    val data: List<LeaderboardConfigV3>,
    val paging: LeaderboardPagination
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class LeaderboardCycleRankingDetail(
    val cycleId: String,
    val point: Double? = null,
    val rank: Long? = null,
    val hidden: Boolean? = null,
    val additionalData: JsonElement? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class LeaderboardUserRankingV3(
    val userId: String,
    val allTime: LeaderboardUserRankingDetail? = null,
    val cycles: List<LeaderboardCycleRankingDetail>
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class LeaderboardBulkUserRankingV3(
    val data: List<LeaderboardUserRankingV3>
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}