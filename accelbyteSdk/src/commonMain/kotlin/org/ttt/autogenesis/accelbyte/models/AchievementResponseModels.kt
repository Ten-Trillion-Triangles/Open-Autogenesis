package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json

/**
 * Represents a single achievement definition from the platform achievement service.
 *
 * @property achievementCode The unique code identifier for this achievement.
 * @property name The display name of the achievement.
 * @property description A detailed description of how to unlock this achievement.
 * @property iconUrl URL to the achievement icon image.
 * @property requirements JSON object describing unlock requirements.
 * @property rewards JSON object describing the rewards granted upon unlocking.
 * @property globalUnlockPercentage The percentage of all players who have unlocked this achievement.
 * @property isHidden Whether this achievement is hidden from the player achievement list.
 * @property category The category this achievement belongs to.
 */
data class Achievement(
    val achievementCode : String,
    val name : String,
    val description : String?,
    val iconUrl : String?,
    val requirements : Json?,
    val rewards : Json?,
    val globalUnlockPercentage : Double?,
    val isHidden : Boolean?,
    val category : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : Achievement = Achievement(
            achievementCode = json.requireString("achievementCode"),
            name = json.requireString("name"),
            description = json.optString("description"),
            iconUrl = json.optString("iconUrl"),
            requirements = json.optJson("requirements"),
            rewards = json.optJson("rewards"),
            globalUnlockPercentage = json.optDouble("globalUnlockPercentage"),
            isHidden = json.optBoolean("isHidden"),
            category = json.optString("category")
        )
    }
}

/**
 * A paginated list of achievement definitions.
 *
 * @property data The list of achievement definitions on this page.
 * @property paging Pagination metadata for navigating additional pages.
 */
data class AchievementListResponse(
    val data : List<Achievement>,
    val paging : PagingInfo
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : AchievementListResponse = AchievementListResponse(
            data = json.optJsonList("data").map(Achievement::fromJson),
            paging = PagingInfo.fromJson(json.requireJson("paging"))
        )
    }
}

/**
 * Represents a single achievement unlock event for a user.
 *
 * @property achievementCode The code of the achievement that was unlocked.
 * @property userId The unique identifier of the user who unlocked the achievement.
 * @property unlockedAt ISO-8601 timestamp when the achievement was unlocked.
 * @property progress The user progress toward the achievement at the time of unlock.
 * @property rewards JSON object describing the rewards granted upon unlocking.
 */
data class AchievementUnlockResponse(
    val achievementCode : String,
    val userId : String,
    val unlockedAt : String,
    val progress : Int?,
    val rewards : Json?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : AchievementUnlockResponse = AchievementUnlockResponse(
            achievementCode = json.requireString("achievementCode"),
            userId = json.requireString("userId"),
            unlockedAt = json.requireString("unlockedAt"),
            progress = json.optInt("progress"),
            rewards = json.optJson("rewards")
        )
    }
}

/**
 * Represents a user progress toward or unlock state of a specific achievement.
 *
 * @property achievementCode The code of the achievement.
 * @property name The display name of the achievement.
 * @property description A detailed description of the achievement.
 * @property isUnlocked Whether the user has unlocked this achievement.
 * @property unlockedAt ISO-8601 timestamp when the achievement was unlocked, or null if not yet unlocked.
 * @property progress Current progress toward unlocking.
 * @property maxProgress The maximum progress value required to unlock.
 * @property rewards JSON object describing the rewards for this achievement.
 * @property category The category this achievement belongs to.
 */
data class UserAchievement(
    val achievementCode : String,
    val name : String,
    val description : String?,
    val isUnlocked : Boolean,
    val unlockedAt : String?,
    val progress : Int?,
    val maxProgress : Int?,
    val rewards : Json?,
    val category : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : UserAchievement = UserAchievement(
            achievementCode = json.requireString("achievementCode"),
            name = json.requireString("name"),
            description = json.optString("description"),
            isUnlocked = json.requireBoolean("isUnlocked"),
            unlockedAt = json.optString("unlockedAt"),
            progress = json.optInt("progress"),
            maxProgress = json.optInt("maxProgress"),
            rewards = json.optJson("rewards"),
            category = json.optString("category")
        )
    }
}

/**
 * A paginated list of achievements earned by a user.
 *
 * @property data The list of user achievements on this page.
 * @property paging Pagination metadata for navigating additional pages.
 */
data class UserAchievementListResponse(
    val data : List<UserAchievement>,
    val paging : PagingInfo
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : UserAchievementListResponse = UserAchievementListResponse(
            data = json.optJsonList("data").map(UserAchievement::fromJson),
            paging = PagingInfo.fromJson(json.requireJson("paging"))
        )
    }
}
