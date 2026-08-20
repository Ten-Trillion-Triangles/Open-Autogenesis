package accelbyte.leaderboard

import accelbyte.AccelByteSdkProvider
import accelbyte.session.toJsonElement
import accelbyte.session.toModel
import kotlinx.serialization.json.JsonElement
import net.accelbyte.sdk.api.leaderboard.models.ModelsUpdateUserPointAdminV1Request
import net.accelbyte.sdk.api.leaderboard.operations.leaderboard_configuration.GetLeaderboardConfigurationAdminV1
import net.accelbyte.sdk.api.leaderboard.operations.leaderboard_configuration.GetLeaderboardConfigurationsAdminV1
import net.accelbyte.sdk.api.leaderboard.operations.leaderboard_configuration.GetLeaderboardConfigurationsPublicV1
import net.accelbyte.sdk.api.leaderboard.operations.leaderboard_data.GetAllTimeLeaderboardRankingAdminV1
import net.accelbyte.sdk.api.leaderboard.operations.leaderboard_data.GetCurrentMonthLeaderboardRankingAdminV1
import net.accelbyte.sdk.api.leaderboard.operations.leaderboard_data.GetCurrentSeasonLeaderboardRankingAdminV1
import net.accelbyte.sdk.api.leaderboard.operations.leaderboard_data.GetCurrentWeekLeaderboardRankingAdminV1
import net.accelbyte.sdk.api.leaderboard.operations.leaderboard_data.GetTodayLeaderboardRankingAdminV1
import net.accelbyte.sdk.api.leaderboard.operations.leaderboard_data.GetUserRankingAdminV1
import net.accelbyte.sdk.api.leaderboard.operations.leaderboard_data.GetAllTimeLeaderboardRankingPublicV1
import net.accelbyte.sdk.api.leaderboard.operations.leaderboard_data.GetCurrentMonthLeaderboardRankingPublicV1
import net.accelbyte.sdk.api.leaderboard.operations.leaderboard_data.GetCurrentSeasonLeaderboardRankingPublicV1
import net.accelbyte.sdk.api.leaderboard.operations.leaderboard_data.GetCurrentWeekLeaderboardRankingPublicV1
import net.accelbyte.sdk.api.leaderboard.operations.leaderboard_data.GetTodayLeaderboardRankingPublicV1
import net.accelbyte.sdk.api.leaderboard.operations.leaderboard_data.GetUserRankingPublicV1
import net.accelbyte.sdk.api.leaderboard.operations.leaderboard_data.UpdateUserPointAdminV1
import net.accelbyte.sdk.api.leaderboard.wrappers.LeaderboardConfiguration
import net.accelbyte.sdk.api.leaderboard.wrappers.LeaderboardData
import structs.accelbyte.leaderboard.*

object Leaderboard
{
    private val sdk get() = AccelByteSdkProvider.sdk
    private val namespace get() = AccelByteSdkProvider.namespace

    private val configuration by lazy { LeaderboardConfiguration(sdk) }
    private val data by lazy { LeaderboardData(sdk) }

    private inline fun <reified T> runModel(action : () -> Any) : Result<T> =
        runCatching { action().toJsonElement().toModel<T>() }

    /**
     * Lists leaderboard configurations using admin privileges with filtering options.
     * 
     * @param limit Maximum number of configurations to return
     * @param offset Number of configurations to skip for pagination
     * @param isArchived Filter by archived status
     * @param isDeleted Filter by deleted status
     * @return [Result] containing [LeaderboardConfigListResponse] with configuration list
     */
    fun listLeaderboardConfigsAdmin(
        limit : Int? = null,
        offset : Int? = null,
        isArchived : Boolean? = null,
        isDeleted : Boolean? = null
    ) : Result<LeaderboardConfigListResponse> =
        runModel {
            configuration.getLeaderboardConfigurationsAdminV1(
                GetLeaderboardConfigurationsAdminV1.builder()
                    .namespace(namespace)
                    .limit(limit)
                    .offset(offset)
                    .isArchived(isArchived)
                    .isDeleted(isDeleted)
                    .build()
            )
        }

    /**
     * Retrieves a specific leaderboard configuration using admin privileges.
     * 
     * @param leaderboardCode Unique identifier for the leaderboard
     * @return [Result] containing [LeaderboardConfig] with configuration details
     */
    fun getLeaderboardConfigAdmin(leaderboardCode : String) : Result<LeaderboardConfig> = runModel {
        configuration.getLeaderboardConfigurationAdminV1(
            GetLeaderboardConfigurationAdminV1.builder()
                .namespace(namespace)
                .leaderboardCode(leaderboardCode)
                .build()
        )
    }

    /**
     * Lists leaderboard configurations using public access with filtering options.
     * 
     * @param limit Maximum number of configurations to return
     * @param offset Number of configurations to skip for pagination
     * @param isArchived Filter by archived status
     * @param isDeleted Filter by deleted status
     * @return [Result] containing [LeaderboardConfigListResponse] with public configuration list
     */
    fun listLeaderboardConfigsPublic(
        limit : Int? = null,
        offset : Int? = null,
        isArchived : Boolean? = null,
        isDeleted : Boolean? = null
    ) : Result<LeaderboardConfigListResponse> =
        runModel {
            configuration.getLeaderboardConfigurationsPublicV1(
                GetLeaderboardConfigurationsPublicV1.builder()
                    .namespace(namespace)
                    .limit(limit)
                    .offset(offset)
                    .isArchived(isArchived)
                    .isDeleted(isDeleted)
                    .build()
            )
        }

    /**
     * Retrieves weekly leaderboard rankings using admin privileges.
     * 
     * @param leaderboardCode Unique identifier for the leaderboard
     * @param limit Maximum number of ranking entries to return
     * @param offset Number of entries to skip for pagination
     * @param previousVersion Optional previous version for historical data
     * @return [Result] containing [LeaderboardRankingResponse] with weekly rankings
     */
    fun getWeekRankingAdmin(
        leaderboardCode : String,
        limit : Int? = null,
        offset : Int? = null,
        previousVersion : Int? = null
    ) : Result<LeaderboardRankingResponse> =
        runModel {
            data.getCurrentWeekLeaderboardRankingAdminV1(
                GetCurrentWeekLeaderboardRankingAdminV1.builder()
                    .leaderboardCode(leaderboardCode)
                    .namespace(namespace)
                    .limit(limit)
                    .offset(offset)
                    .previousVersion(previousVersion)
                    .build()
            )
        }

    /**
     * Retrieves all-time leaderboard rankings using admin privileges.
     * 
     * @param leaderboardCode Unique identifier for the leaderboard
     * @param limit Maximum number of ranking entries to return
     * @param offset Number of entries to skip for pagination
     * @return [Result] containing [LeaderboardRankingResponse] with all-time rankings
     */
    fun getAlltimeRankingAdmin(
        leaderboardCode : String,
        limit : Int? = null,
        offset : Int? = null
    ) : Result<LeaderboardRankingResponse> =
        runModel {
            data.getAllTimeLeaderboardRankingAdminV1(
                GetAllTimeLeaderboardRankingAdminV1.builder()
                    .leaderboardCode(leaderboardCode)
                    .namespace(namespace)
                    .limit(limit)
                    .offset(offset)
                    .build()
            )
        }

    /**
     * Retrieves monthly leaderboard rankings using admin privileges.
     * 
     * @param leaderboardCode Unique identifier for the leaderboard
     * @param limit Maximum number of ranking entries to return
     * @param offset Number of entries to skip for pagination
     * @return [Result] containing [LeaderboardRankingResponse] with monthly rankings
     */
    fun getMonthRankingAdmin(
        leaderboardCode : String,
        limit : Int? = null,
        offset : Int? = null
    ) : Result<LeaderboardRankingResponse> =
        runModel {
            data.getCurrentMonthLeaderboardRankingAdminV1(
                GetCurrentMonthLeaderboardRankingAdminV1.builder()
                    .leaderboardCode(leaderboardCode)
                    .namespace(namespace)
                    .limit(limit)
                    .offset(offset)
                    .build()
            )
        }

    /**
     * Retrieves seasonal leaderboard rankings using admin privileges.
     * 
     * @param leaderboardCode Unique identifier for the leaderboard
     * @param limit Maximum number of ranking entries to return
     * @param offset Number of entries to skip for pagination
     * @return [Result] containing [LeaderboardRankingResponse] with seasonal rankings
     */
    fun getSeasonRankingAdmin(
        leaderboardCode : String,
        limit : Int? = null,
        offset : Int? = null
    ) : Result<LeaderboardRankingResponse> =
        runModel {
            data.getCurrentSeasonLeaderboardRankingAdminV1(
                GetCurrentSeasonLeaderboardRankingAdminV1.builder()
                    .leaderboardCode(leaderboardCode)
                    .namespace(namespace)
                    .limit(limit)
                    .offset(offset)
                    .build()
            )
        }

    /**
     * Retrieves today's leaderboard rankings using admin privileges.
     * 
     * @param leaderboardCode Unique identifier for the leaderboard
     * @param limit Maximum number of ranking entries to return
     * @param offset Number of entries to skip for pagination
     * @return [Result] containing [LeaderboardRankingResponse] with today's rankings
     */
    fun getTodayRankingAdmin(
        leaderboardCode : String,
        limit : Int? = null,
        offset : Int? = null
    ) : Result<LeaderboardRankingResponse> =
        runModel {
            data.getTodayLeaderboardRankingAdminV1(
                GetTodayLeaderboardRankingAdminV1.builder()
                    .leaderboardCode(leaderboardCode)
                    .namespace(namespace)
                    .limit(limit)
                    .offset(offset)
                    .build()
            )
        }

    /**
     * Retrieves a specific user's ranking using admin privileges.
     * 
     * @param leaderboardCode Unique identifier for the leaderboard
     * @param userId Unique identifier for the user
     * @return [Result] containing [LeaderboardUserRankingResponse] with user's ranking
     */
    fun getUserRankingAdmin(leaderboardCode : String, userId : String) : Result<LeaderboardUserRankingResponse> =
        runModel {
            data.getUserRankingAdminV1(
                GetUserRankingAdminV1.builder()
                    .leaderboardCode(leaderboardCode)
                    .namespace(namespace)
                    .userId(userId)
                    .build()
            )
        }

    /**
     * Updates a user's points on a leaderboard using admin privileges.
     * 
     * @param leaderboardCode Unique identifier for the leaderboard
     * @param userId Unique identifier for the user
     * @param payload [JsonElement] containing point update data
     * @return [Result] containing [LeaderboardUpdateUserPointResponse] with update result
     */
    fun updateUserPointAdmin(
        leaderboardCode : String,
        userId : String,
        payload : JsonElement
    ) : Result<LeaderboardUpdateUserPointResponse> =
        runModel {
            val body = payload.toModel<ModelsUpdateUserPointAdminV1Request>()
            data.updateUserPointAdminV1(
                UpdateUserPointAdminV1.builder()
                    .leaderboardCode(leaderboardCode)
                    .namespace(namespace)
                    .userId(userId)
                    .body(body)
                    .build()
            )
        }

    /**
     * Retrieves weekly leaderboard rankings using public access.
     * 
     * @param leaderboardCode Unique identifier for the leaderboard
     * @param limit Maximum number of ranking entries to return
     * @param offset Number of entries to skip for pagination
     * @param previousVersion Optional previous version for historical data
     * @return [Result] containing [LeaderboardRankingResponse] with weekly rankings
     */
    fun getWeekRankingPublic(
        leaderboardCode : String,
        limit : Int? = null,
        offset : Int? = null,
        previousVersion : Int? = null
    ) : Result<LeaderboardRankingResponse> =
        runModel {
            data.getCurrentWeekLeaderboardRankingPublicV1(
                GetCurrentWeekLeaderboardRankingPublicV1.builder()
                    .leaderboardCode(leaderboardCode)
                    .namespace(namespace)
                    .limit(limit)
                    .offset(offset)
                    .previousVersion(previousVersion)
                    .build()
            )
        }

    /**
     * Retrieves all-time leaderboard rankings using public access.
     * 
     * @param leaderboardCode Unique identifier for the leaderboard
     * @param limit Maximum number of ranking entries to return
     * @param offset Number of entries to skip for pagination
     * @return [Result] containing [LeaderboardRankingResponse] with all-time rankings
     */
    fun getAlltimeRankingPublic(
        leaderboardCode : String,
        limit : Int? = null,
        offset : Int? = null
    ) : Result<LeaderboardRankingResponse> =
        runModel {
            data.getAllTimeLeaderboardRankingPublicV1(
                GetAllTimeLeaderboardRankingPublicV1.builder()
                    .leaderboardCode(leaderboardCode)
                    .namespace(namespace)
                    .limit(limit)
                    .offset(offset)
                    .build()
            )
        }

    /**
     * Retrieves monthly leaderboard rankings using public access.
     * 
     * @param leaderboardCode Unique identifier for the leaderboard
     * @param limit Maximum number of ranking entries to return
     * @param offset Number of entries to skip for pagination
     * @return [Result] containing [LeaderboardRankingResponse] with monthly rankings
     */
    fun getMonthRankingPublic(
        leaderboardCode : String,
        limit : Int? = null,
        offset : Int? = null
    ) : Result<LeaderboardRankingResponse> =
        runModel {
            data.getCurrentMonthLeaderboardRankingPublicV1(
                GetCurrentMonthLeaderboardRankingPublicV1.builder()
                    .leaderboardCode(leaderboardCode)
                    .namespace(namespace)
                    .limit(limit)
                    .offset(offset)
                    .build()
            )
        }

    /**
     * Retrieves seasonal leaderboard rankings using public access.
     * 
     * @param leaderboardCode Unique identifier for the leaderboard
     * @param limit Maximum number of ranking entries to return
     * @param offset Number of entries to skip for pagination
     * @return [Result] containing [LeaderboardRankingResponse] with seasonal rankings
     */
    fun getSeasonRankingPublic(
        leaderboardCode : String,
        limit : Int? = null,
        offset : Int? = null
    ) : Result<LeaderboardRankingResponse> =
        runModel {
            data.getCurrentSeasonLeaderboardRankingPublicV1(
                GetCurrentSeasonLeaderboardRankingPublicV1.builder()
                    .leaderboardCode(leaderboardCode)
                    .namespace(namespace)
                    .limit(limit)
                    .offset(offset)
                    .build()
            )
        }

    /**
     * Retrieves today's leaderboard rankings using public access.
     * 
     * @param leaderboardCode Unique identifier for the leaderboard
     * @param limit Maximum number of ranking entries to return
     * @param offset Number of entries to skip for pagination
     * @return [Result] containing [LeaderboardRankingResponse] with today's rankings
     */
    fun getTodayRankingPublic(
        leaderboardCode : String,
        limit : Int? = null,
        offset : Int? = null
    ) : Result<LeaderboardRankingResponse> =
        runModel {
            data.getTodayLeaderboardRankingPublicV1(
                GetTodayLeaderboardRankingPublicV1.builder()
                    .leaderboardCode(leaderboardCode)
                    .namespace(namespace)
                    .limit(limit)
                    .offset(offset)
                    .build()
            )
        }

    /**
     * Retrieves a specific user's ranking using public access.
     * 
     * @param leaderboardCode Unique identifier for the leaderboard
     * @param userId Unique identifier for the user
     * @return [Result] containing [LeaderboardUserRankingResponse] with user's ranking
     */
    fun getUserRankingPublic(leaderboardCode : String, userId : String) : Result<LeaderboardUserRankingResponse> =
        runModel {
            data.getUserRankingPublicV1(
                GetUserRankingPublicV1.builder()
                    .leaderboardCode(leaderboardCode)
                    .namespace(namespace)
                    .userId(userId)
                    .build()
            )
        }
}