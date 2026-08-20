package structs.accelbyte.leaderboard

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable
import structs.accelbyte.common.PagingInfo

@Serializable
data class LeaderboardRankingEntry(
    val userId: String,
    val username: String,
    val score: Double,
    val rank: Int,
    val additionalData: JsonElement? = null,
    val updatedAt: String? = null,
    val firstScoreTime: String? = null,
    val lastScoreTime: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class LeaderboardListResponse(
    val data: List<LeaderboardRankingEntry>,
    val paging: PagingInfo
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}