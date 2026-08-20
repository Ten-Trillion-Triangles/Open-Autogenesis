package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Json
import kotlin.js.Promise
import kotlin.js.undefined
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.models.ClaimableRewardsResponse
import org.ttt.autogenesis.accelbyte.models.ClaimableUserSeasonInfoResponse
import org.ttt.autogenesis.accelbyte.models.ExpGrantHistoryResponse
import org.ttt.autogenesis.accelbyte.models.ItemReferenceInfoResponse
import org.ttt.autogenesis.accelbyte.models.ItemReferenceParams
import org.ttt.autogenesis.accelbyte.models.PaginationParams
import org.ttt.autogenesis.accelbyte.models.SeasonClaimRequest
import org.ttt.autogenesis.accelbyte.models.SeasonInfoResponse
import org.ttt.autogenesis.accelbyte.models.SeasonListResponse
import org.ttt.autogenesis.accelbyte.models.SeasonPassInfoResponse
import org.ttt.autogenesis.accelbyte.models.SeasonPassListResponse
import org.ttt.autogenesis.accelbyte.models.SeasonQueryParams
import org.ttt.autogenesis.accelbyte.models.SeasonRewardInfoResponse
import org.ttt.autogenesis.accelbyte.models.SeasonRewardListResponse
import org.ttt.autogenesis.accelbyte.models.SeasonRewardQueryParams
import org.ttt.autogenesis.accelbyte.models.SeasonTierPageResponse
import org.ttt.autogenesis.accelbyte.models.SeasonTierResponse
import org.ttt.autogenesis.accelbyte.models.SeasonpassExportResponse
import org.ttt.autogenesis.accelbyte.models.ForceActionParams
import org.ttt.autogenesis.accelbyte.modules.SeasonpassModulePackage
import org.ttt.autogenesis.accelbyte.util.mapJson
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors

/**
 * A thin facade over the AccelByte Season Pass module. Provides operations for querying
 * published seasons, viewing season rewards and tiers, requesting reward claims, and
 * accessing season configuration. Admin endpoints expose broader/cross-namespace data.
 *
 * @property sdk the AccelByte SDK instance used to make API calls
 */
class SeasonpassFacade(private val sdk : AccelByteSdkInstance)
{
    private val module get() = SeasonpassModulePackage.Seasonpass

    private val exportApi get() = module.ExportAdminApi(sdk.rawSdk)
    private val passAdmin get() = module.PassAdminApi(sdk.rawSdk)
    private val rewardAdmin get() = module.RewardAdminApi(sdk.rawSdk)
    private val seasonAdmin get() = module.SeasonAdminApi(sdk.rawSdk)
    private val tierAdmin get() = module.TierAdminApi(sdk.rawSdk)
    private val utilitiesApi get() = module.UtilitiesAdminApi(sdk.rawSdk)
    private val rewardApi get() = module.RewardApi(sdk.rawSdk)
    private val seasonApi get() = module.SeasonApi(sdk.rawSdk)

    /**
     * Exports all season pass data for the current namespace.
     *
     * @return a [Promise] containing the full season pass export response as [SeasonpassExportResponse]
     * @throws Throwable propagates any JS errors encountered during the API call
     */
    fun exportAll() : Promise<SeasonpassExportResponse> =
        exportApi.getExport()
            .propagateJsErrors()
            .mapJson(SeasonpassExportResponse::fromJson)

    /**
     * Fetches the currently active published season for the current user.
     *
     * @param params optional query parameters for filtering the season (default: [SeasonQueryParams])
     * @return a [Promise] containing the current season's information as [SeasonInfoResponse]
     * @throws Throwable propagates any JS errors encountered during the API call
     */
    fun fetchCurrentSeason(params : SeasonQueryParams = SeasonQueryParams()) : Promise<SeasonInfoResponse> =
        seasonApi.getSeasonsCurrent(params.toJson())
            .propagateJsErrors()
            .mapJson(SeasonInfoResponse::fromJson)

    /**
     * Fetches the current user season data for the active published season.
     *
     * @param userId the ID of the user whose season data to retrieve
     * @return a [Promise] containing the user season information as [ClaimableUserSeasonInfoResponse]
     * @throws Throwable propagates any JS errors encountered during the API call
     */
    fun fetchUserSeason(userId : String) : Promise<ClaimableUserSeasonInfoResponse> =
        seasonApi.getSeasonsCurrentData_ByUserId(userId)
            .propagateJsErrors()
            .mapJson(ClaimableUserSeasonInfoResponse::fromJson)

    /**
     * Fetches a specific user season data for a given season.
     *
     * @param userId the ID of the user whose season data to retrieve
     * @param seasonId the ID of the season to query
     * @return a [Promise] containing the user season information as [ClaimableUserSeasonInfoResponse]
     * @throws Throwable propagates any JS errors encountered during the API call
     */
    fun fetchSeasonData(userId : String, seasonId : String) : Promise<ClaimableUserSeasonInfoResponse> =
        seasonApi.getData_ByUserId_BySeasonId(userId, seasonId)
            .propagateJsErrors()
            .mapJson(ClaimableUserSeasonInfoResponse::fromJson)

    /**
     * Claims a specific reward for a user within the current season.
     *
     * @param userId the ID of the user claiming the reward
     * @param request the claim request containing reward details as [SeasonClaimRequest]
     * @return a [Promise] containing the claim result as [ClaimableRewardsResponse]
     * @throws Throwable propagates any JS errors encountered during the API call
     */
    fun claimReward(userId : String, request : SeasonClaimRequest) : Promise<ClaimableRewardsResponse> =
        rewardApi.createSeasonCurrentReward_ByUserId(userId, request.toJson())
            .propagateJsErrors()
            .mapJson(ClaimableRewardsResponse::fromJson)

    /**
     * Claims all available rewards for a user within the current season.
     *
     * @param userId the ID of the user claiming all rewards
     * @return a [Promise] containing the bulk claim result as [ClaimableRewardsResponse]
     * @throws Throwable propagates any JS errors encountered during the API call
     */
    fun claimAllRewards(userId : String) : Promise<ClaimableRewardsResponse> =
        rewardApi.createSeasonCurrentRewardBulk_ByUserId(userId)
            .propagateJsErrors()
            .mapJson(ClaimableRewardsResponse::fromJson)

    /**
     * Lists all season passes defined for a given season.
     *
     * @param seasonId the ID of the season whose passes to list
     * @return a [Promise] containing the list of season passes as [SeasonPassListResponse]
     * @throws Throwable propagates any JS errors encountered during the API call
     */
    fun listPasses(seasonId : String) : Promise<SeasonPassListResponse> =
        passAdmin.getPasses_BySeasonId(seasonId)
            .propagateJsErrors()
            .mapJson(SeasonPassListResponse::fromJson)

    /**
     * Grants a season pass to a user for the current season.
     *
     * @param userId the ID of the user to grant the pass to
     * @param payload a JSON payload describing the pass grant details
     * @return a [Promise] containing the granted pass information as [SeasonPassInfoResponse]
     * @throws Throwable propagates any JS errors encountered during the API call
     */
    fun grantPass(userId : String, payload : Json) : Promise<SeasonPassInfoResponse> =
        passAdmin.createSeasonCurrentPasse_ByUserId(userId, payload)
            .propagateJsErrors()
            .mapJson(SeasonPassInfoResponse::fromJson)

    /**
     * Lists all rewards available within a given season.
     *
     * @param seasonId the ID of the season whose rewards to list
     * @param params optional query parameters for filtering rewards (default: [SeasonRewardQueryParams])
     * @return a [Promise] containing the list of season rewards as [SeasonRewardListResponse]
     * @throws Throwable propagates any JS errors encountered during the API call
     */
    fun listSeasonRewards(seasonId : String, params : SeasonRewardQueryParams = SeasonRewardQueryParams()) : Promise<SeasonRewardListResponse> =
        rewardAdmin.getRewards_BySeasonId(seasonId, params.toJson())
            .propagateJsErrors()
            .mapJson(SeasonRewardListResponse::fromJson)

    /**
     * Creates a new season. The season is created in draft state and must be published separately.
     *
     * @param season a JSON payload describing the season to create
     * @return a [Promise] containing the created season information as [SeasonInfoResponse]
     * @throws Throwable propagates any JS errors encountered during the API call
     */
    fun createSeason(season : Json) : Promise<SeasonInfoResponse> =
        seasonAdmin.createSeason(season)
            .propagateJsErrors()
            .mapJson(SeasonInfoResponse::fromJson)

    /**
     * Publishes a draft season, making it visible and active.
     *
     * @param seasonId the ID of the season to publish
     * @return a [Promise] containing the published season information as [SeasonInfoResponse]
     * @throws Throwable propagates any JS errors encountered during the API call
     */
    fun publishSeason(seasonId : String) : Promise<SeasonInfoResponse> =
        seasonAdmin.updatePublish_BySeasonId(seasonId)
            .propagateJsErrors()
            .mapJson(SeasonInfoResponse::fromJson)

    /**
     * Retires a season, marking it as inactive. Optionally forces the retirement action.
     *
     * @param seasonId the ID of the season to retire
     * @param force if true, forces retirement even if preconditions are not met (default: false)
     * @return a [Promise] containing the retired season information as [SeasonInfoResponse]
     * @throws Throwable propagates any JS errors encountered during the API call
     */
    fun retireSeason(seasonId : String, force : Boolean = false) : Promise<SeasonInfoResponse> {
        val query = if (force) ForceActionParams(true).toJson() else undefined
        return seasonAdmin.updateRetire_BySeasonId(seasonId, query)
            .propagateJsErrors()
            .mapJson(SeasonInfoResponse::fromJson)
    }

    /**
     * Unpublishes a season, making it no longer visible to users. Optionally forces the unpublish action.
     *
     * @param seasonId the ID of the season to unpublish
     * @param force if true, forces unpublish even if preconditions are not met (default: false)
     * @return a [Promise] containing the unpublished season information as [SeasonInfoResponse]
     * @throws Throwable propagates any JS errors encountered during the API call
     */
    fun unpublishSeason(seasonId : String, force : Boolean = false) : Promise<SeasonInfoResponse> {
        val query = if (force) ForceActionParams(true).toJson() else undefined
        return seasonAdmin.updateUnpublish_BySeasonId(seasonId, query)
            .propagateJsErrors()
            .mapJson(SeasonInfoResponse::fromJson)
    }

    /**
     * Retrieves full details for a specific season.
     *
     * @param seasonId the ID of the season to retrieve
     * @return a [Promise] containing the season full details as [SeasonInfoResponse]
     * @throws Throwable propagates any JS errors encountered during the API call
     */
    fun getSeasonDetails(seasonId : String) : Promise<SeasonInfoResponse> =
        seasonAdmin.getSeason_BySeasonId(seasonId)
            .propagateJsErrors()
            .mapJson(SeasonInfoResponse::fromJson)

    /**
     * Clones an existing season with an optional payload to override clone parameters.
     *
     * @param seasonId the ID of the season to clone
     * @param payload a JSON payload with optional override parameters for the cloned season
     * @return a [Promise] containing the cloned season information as [SeasonInfoResponse]
     * @throws Throwable propagates any JS errors encountered during the API call
     */
    fun cloneSeason(seasonId : String, payload : Json) : Promise<SeasonInfoResponse> =
        seasonAdmin.createClone_BySeasonId(seasonId, payload)
            .propagateJsErrors()
            .mapJson(SeasonInfoResponse::fromJson)

    /**
     * Lists all seasons with pagination support.
     *
     * @param pagination optional pagination parameters (default: [PaginationParams])
     * @return a [Promise] containing a paginated list of seasons as [SeasonListResponse]
     * @throws Throwable propagates any JS errors encountered during the API call
     */
    fun listSeasons(pagination : PaginationParams = PaginationParams()) : Promise<SeasonListResponse> =
        seasonAdmin.getSeasons(pagination.toJson())
            .propagateJsErrors()
            .mapJson(SeasonListResponse::fromJson)

    /**
     * Lists all tiers within a given season with pagination support.
     *
     * @param seasonId the ID of the season whose tiers to list
     * @param pagination optional pagination parameters (default: [PaginationParams])
     * @return a [Promise] containing a paginated list of tiers as [SeasonTierPageResponse]
     * @throws Throwable propagates any JS errors encountered during the API call
     */
    fun listTiers(seasonId : String, pagination : PaginationParams = PaginationParams()) : Promise<SeasonTierPageResponse> =
        tierAdmin.getTiers_BySeasonId(seasonId, pagination.toJson())
            .propagateJsErrors()
            .mapJson(SeasonTierPageResponse::fromJson)

    /**
     * Grants experience points (EXP) to a user within the current season.
     *
     * @param userId the ID of the user to grant EXP to
     * @param payload a JSON payload describing the EXP grant details
     * @return a [Promise] containing the EXP grant history as [ExpGrantHistoryResponse]
     * @throws Throwable propagates any JS errors encountered during the API call
     */
    fun grantExp(userId : String, payload : Json) : Promise<ExpGrantHistoryResponse> =
        tierAdmin.createSeasonCurrentExp_ByUserId(userId, payload)
            .propagateJsErrors()
            .mapJson(ExpGrantHistoryResponse::fromJson)

    /**
     * Grants a specific tier to a user within the current season.
     *
     * @param userId the ID of the user to grant the tier to
     * @param payload a JSON payload describing the tier grant details
     * @return a [Promise] containing the granted tier as [SeasonTierResponse]
     * @throws Throwable propagates any JS errors encountered during the API call
     */
    fun grantTier(userId : String, payload : Json) : Promise<SeasonTierResponse> =
        tierAdmin.createSeasonCurrentTier_ByUserId(userId, payload)
            .propagateJsErrors()
            .mapJson(SeasonTierResponse::fromJson)

    /**
     * Retrieves item references associated with the season pass configuration.
     *
     * @param params the query parameters for item reference lookup as [ItemReferenceParams]
     * @return a [Promise] containing the item reference information as [ItemReferenceInfoResponse]
     * @throws Throwable propagates any JS errors encountered during the API call
     */
    fun getSeasonItemReference(params : ItemReferenceParams) : Promise<ItemReferenceInfoResponse> =
        utilitiesApi.getSeasonsItemReferences(params.toJson())
            .propagateJsErrors()
            .mapJson(ItemReferenceInfoResponse::fromJson)
}