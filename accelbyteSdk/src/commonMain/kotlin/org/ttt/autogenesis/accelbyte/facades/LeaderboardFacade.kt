package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.models.LeaderboardListResponse
import org.ttt.autogenesis.accelbyte.models.LeaderboardQueryParams
import org.ttt.autogenesis.accelbyte.models.LeaderboardUserRankingResponse
import org.ttt.autogenesis.accelbyte.modules.LeaderboardDataApi
import org.ttt.autogenesis.accelbyte.modules.LeaderboardModulePackage
import org.ttt.autogenesis.accelbyte.util.mapJson
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors

/**
 * Manages leaderboard operations including ranking retrieval and competitive scoring.
 * 
 * Provides comprehensive leaderboard functionality with support for weekly and all-time rankings,
 * player position tracking, and competitive scoring across different game modes.
 */
class LeaderboardFacade(private val sdk : AccelByteSdkInstance)
{
    private val leaderboardApi : LeaderboardDataApi
        get() = LeaderboardModulePackage.Leaderboard.LeaderboardDataApi(sdk.rawSdk)

    /**
     * Retrieves weekly rankings for a specific leaderboard.
     * 
     * Returns the top players for the current week based on their scores in the specified
     * leaderboard. Rankings reset weekly to maintain competitive freshness.
     *
     * @param leaderboardCode Unique identifier for the leaderboard
     * @param params LeaderboardQueryParams with pagination and filtering options
     * @return `Promise<LeaderboardListResponse>` resolving to weekly rankings response containing:
     *         - `data: Array<RankingEntry>` - Array of ranking objects
     *         - `paging: PagingInfo` - Pagination metadata with total, limit, offset
     *         Each RankingEntry contains: userId, username, score, rank, additionalData, updatedAt
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws ValidationException if leaderboard code or parameters are invalid
     * @throws LeaderboardNotFoundException if leaderboard doesn't exist
     */
    fun getWeekRanking(leaderboardCode : String, params : LeaderboardQueryParams = LeaderboardQueryParams()) : Promise<LeaderboardListResponse> =
        leaderboardApi.getWeek_ByLeaderboardCode(leaderboardCode, params.toJson())
            .propagateJsErrors()
            .mapJson(LeaderboardListResponse::fromJson)

    /**
     * Retrieves all-time rankings for a specific leaderboard.
     * 
     * Returns the top players of all time based on their cumulative scores in the specified
     * leaderboard. These rankings persist across weekly resets.
     *
     * @param leaderboardCode Unique identifier for the leaderboard
     * @param params LeaderboardQueryParams with pagination and filtering options
     * @return `Promise<LeaderboardListResponse>` resolving to all-time rankings response containing:
     *         - `data: Array<RankingEntry>` - Array of ranking objects
     *         - `paging: PagingInfo` - Pagination metadata
     *         Each RankingEntry contains: userId, username, score, rank, additionalData, 
     *         firstScoreTime, lastScoreTime
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws ValidationException if leaderboard code or parameters are invalid
     * @throws LeaderboardNotFoundException if leaderboard doesn't exist
     */
    fun getAlltimeRanking(leaderboardCode : String, params : LeaderboardQueryParams = LeaderboardQueryParams()) : Promise<LeaderboardListResponse> =
        leaderboardApi.getAlltime_ByLeaderboardCode(leaderboardCode, params.toJson())
            .propagateJsErrors()
            .mapJson(LeaderboardListResponse::fromJson)

    /**
     * Retrieves ranking information for a specific user on a leaderboard.
     * 
     * Returns the user's current position, score, and ranking details for the specified
     * leaderboard. Useful for displaying personal progress and achievements.
     *
     * @param leaderboardCode Unique identifier for the leaderboard
     * @param userId Target user identifier
     * @return `Promise<LeaderboardUserRankingResponse>` resolving to user ranking response containing:
     *         - `userId: string` - User identifier
     *         - `username: string` - User display name
     *         - `score: number` - User's current score
     *         - `rank: number` - User's current rank position
     *         - `additionalData: object` - Additional scoring metadata
     *         - `lastScoreTime: string` - ISO timestamp of last score update
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws ValidationException if leaderboard code or user ID is invalid
     * @throws LeaderboardNotFoundException if leaderboard doesn't exist
     * @throws UserNotFoundException if user doesn't exist or has no score
     */
    fun getUserRanking(leaderboardCode : String, userId : String) : Promise<LeaderboardUserRankingResponse> =
        leaderboardApi.getUser_ByLeaderboardCode_ByUserId(leaderboardCode, userId)
            .propagateJsErrors()
            .mapJson(LeaderboardUserRankingResponse::fromJson)
}
