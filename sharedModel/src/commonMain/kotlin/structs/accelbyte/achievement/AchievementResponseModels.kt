package structs.accelbyte.achievement

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable
import structs.accelbyte.common.CursorPagination
import structs.accelbyte.common.PagingInfo

@Serializable
data class Achievement(
    val achievementCode: String,
    val name: String,
    val description: String? = null,
    val iconUrl: String? = null,
    val requirements: JsonElement? = null,
    val rewards: JsonElement? = null,
    val globalUnlockPercentage: Double? = null,
    val isHidden: Boolean? = null,
    val category: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class AchievementListResponse(val data: List<Achievement>, val paging: PagingInfo) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class AchievementUnlockResponse(
    val achievementCode: String,
    val userId: String,
    val unlockedAt: String,
    val progress: Int? = null,
    val rewards: JsonElement? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class UserAchievement(
    val achievementCode: String,
    val name: String,
    val description: String? = null,
    val isUnlocked: Boolean,
    val unlockedAt: String? = null,
    val progress: Int? = null,
    val maxProgress: Int? = null,
    val rewards: JsonElement? = null,
    val category: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class UserAchievementListResponse(val data: List<UserAchievement>, val paging: PagingInfo) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}