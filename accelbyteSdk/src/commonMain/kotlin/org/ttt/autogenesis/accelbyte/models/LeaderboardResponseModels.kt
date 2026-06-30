package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json

/**
 * A single leaderboard ranking entry for a user.
 *
 * @property userId The unique identifier of the user.
 * @property username The display name of the user.
 * @property score The user current score.
 * @property rank The user current rank position.
 * @property additionalData Additional data associated with this entry.
 * @property updatedAt Timestamp when this entry was last updated.
 * @property firstScoreTime Timestamp when the user first posted a score.
 * @property lastScoreTime Timestamp when the user last posted a score.
 */
data class LeaderboardRankingEntry(
    val userId : String,
    val username : String,
    val score : Double,
    val rank : Int,
    val additionalData : Json?,
    val updatedAt : String?,
    val firstScoreTime : String?,
    val lastScoreTime : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : LeaderboardRankingEntry = LeaderboardRankingEntry(
            userId = json.requireString("userId"),
            username = json.requireString("username"),
            score = json.requireDouble("score"),
            rank = json.requireInt("rank"),
            additionalData = json.optJson("additionalData"),
            updatedAt = json.optString("updatedAt"),
            firstScoreTime = json.optString("firstScoreTime"),
            lastScoreTime = json.optString("lastScoreTime")
        )
    }
}

/**
 * A paginated list of leaderboard rankings.
 *
 * @property data The list of ranking entries on this page.
 * @property paging Pagination metadata for navigating additional pages.
 */
data class LeaderboardListResponse(
    val data : List<LeaderboardRankingEntry>,
    val paging : PagingInfo
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : LeaderboardListResponse = LeaderboardListResponse(
            data = json.optJsonList("data").map(LeaderboardRankingEntry::fromJson),
            paging = PagingInfo.fromJson(json.requireJson("paging"))
        )
    }
}

/**
 * A user ranking response for a specific user leaderboard query.
 *
 * @property userId The unique identifier of the user.
 * @property username The display name of the user.
 * @property score The user current score.
 * @property rank The user current rank position.
 * @property additionalData Additional data associated with this entry.
 * @property lastScoreTime Timestamp when the user last posted a score.
 */
data class LeaderboardUserRankingResponse(
    val userId : String,
    val username : String,
    val score : Double,
    val rank : Int?,
    val additionalData : Json?,
    val lastScoreTime : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : LeaderboardUserRankingResponse = LeaderboardUserRankingResponse(
            userId = json.requireString("userId"),
            username = json.requireString("username"),
            score = json.requireDouble("score"),
            rank = json.optInt("rank"),
            additionalData = json.optJson("additionalData"),
            lastScoreTime = json.optString("lastScoreTime")
        )
    }
}
