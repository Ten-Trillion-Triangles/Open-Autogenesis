package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.models.AchievementListResponse
import org.ttt.autogenesis.accelbyte.models.AchievementQueryParams
import org.ttt.autogenesis.accelbyte.models.AchievementUnlockResponse
import org.ttt.autogenesis.accelbyte.models.UserAchievementListResponse
import org.ttt.autogenesis.accelbyte.models.UserAchievementQuery
import org.ttt.autogenesis.accelbyte.modules.AchievementModulePackage
import org.ttt.autogenesis.accelbyte.modules.AchievementsApi
import org.ttt.autogenesis.accelbyte.util.mapJson
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors

/**
 * Manages achievement system operations including listing, unlocking, and tracking user progress.
 * 
 * Provides comprehensive achievement functionality with support for progress tracking,
 * unlock notifications, and user achievement history across different game modes.
 *
 * @param sdk AccelByte SDK instance used to resolve achievement APIs.
 */
class AchievementFacade(private val sdk : AccelByteSdkInstance)
{
    private val achievementsApi : AchievementsApi
        get() = AchievementModulePackage.Achievement.AchievementsApi(sdk.rawSdk)

    /**
     * Retrieves all available achievements for a specific namespace.
     * 
     * Returns the complete list of achievements that players can unlock, including
     * their requirements, rewards, and current global unlock statistics.
     *
     * @param namespace Target namespace identifier
     * @param params AchievementQueryParams with pagination and filtering options
     * @return `Promise<AchievementListResponse>` resolving to achievements list response containing:
     *         - `data: Array<Achievement>` - Array of achievement objects
     *         - `paging: PagingInfo` - Pagination metadata with total, limit, offset
     *         Each Achievement contains: achievementCode, name, description, iconUrl, 
     *         requirements, rewards, globalUnlockPercentage, isHidden, category
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws ValidationException if namespace or parameters are invalid
     * @throws NamespaceNotFoundException if namespace doesn't exist
     */
    fun listAchievements(namespace : String, params : AchievementQueryParams = AchievementQueryParams()) : Promise<AchievementListResponse> =
        achievementsApi.getAchievements(namespace, params.toJson())
            .propagateJsErrors()
            .mapJson(AchievementListResponse::fromJson)

    /**
     * Unlocks a specific achievement for the current user.
     * 
     * Marks an achievement as completed for the authenticated user, typically
     * triggered when the player meets the achievement requirements.
     *
     * @param achievementCode Unique identifier for the achievement to unlock
     * @param namespace Target namespace where the achievement exists
     * @return `Promise<AchievementUnlockResponse>` resolving to unlock confirmation containing:
     *         - `achievementCode: string` - Code of unlocked achievement
     *         - `userId: string` - ID of user who unlocked the achievement
     *         - `unlockedAt: string` - ISO timestamp when achievement was unlocked
     *         - `progress: number` - Final progress value (typically 100%)
     *         - `rewards: Array<object>` - Granted rewards (XP, items, currency, etc.)
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws ValidationException if achievement code or namespace is invalid
     * @throws AchievementNotFoundException if achievement doesn't exist
     * @throws AchievementAlreadyUnlockedException if user already unlocked this achievement
     */
    fun unlock(achievementCode : String, namespace : String) : Promise<AchievementUnlockResponse> =
        achievementsApi.unlockAchievement(achievementCode, namespace)
            .propagateJsErrors()
            .mapJson(AchievementUnlockResponse::fromJson)

    /**
     * Retrieves achievement progress and unlock status for a specific user.
     * 
     * Returns detailed information about which achievements the user has unlocked,
     * their progress on incomplete achievements, and unlock timestamps.
     *
     * @param namespace Target namespace identifier
     * @param userId Target user identifier
     * @param params UserAchievementQuery with pagination and filtering options
     * @return `Promise<UserAchievementListResponse>` resolving to user achievements response containing:
     *         - `data: Array<UserAchievement>` - Array of user achievement objects
     *         - `paging: PagingInfo` - Pagination metadata
     *         Each UserAchievement contains: achievementCode, name, description, isUnlocked,
     *         unlockedAt, progress, maxProgress, rewards, category
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws ValidationException if namespace, user ID, or parameters are invalid
     * @throws UserNotFoundException if user doesn't exist
     * @throws NamespaceNotFoundException if namespace doesn't exist
     */
    fun getUserAchievements(namespace : String, userId : String, params : UserAchievementQuery = UserAchievementQuery(userId)) : Promise<UserAchievementListResponse> =
        achievementsApi.getUserAchievements(namespace, userId, params.toJson())
            .propagateJsErrors()
            .mapJson(UserAchievementListResponse::fromJson)
}
