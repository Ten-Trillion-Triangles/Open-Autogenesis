package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Json
import kotlin.js.json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.modules.ChallengeModulePackage
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors

/**
 * Facade for AccelByte Challenge service.
 * Provides access to challenge configuration, progression tracking, and reward claiming.
 */
class ChallengeFacade(private val sdk : AccelByteSdkInstance)
{
    private val challengeConfigAdminApi = ChallengeModulePackage.Challenge.ChallengeConfigurationAdminApi(sdk.rawSdk)
    private val challengeListApi = ChallengeModulePackage.Challenge.ChallengeListApi(sdk.rawSdk)
    private val challengeProgressionAdminApi = ChallengeModulePackage.Challenge.ChallengeProgressionAdminApi(sdk.rawSdk)
    private val challengeProgressionApi = ChallengeModulePackage.Challenge.ChallengeProgressionApi(sdk.rawSdk)
    private val playerRewardAdminApi = ChallengeModulePackage.Challenge.PlayerRewardAdminApi(sdk.rawSdk)
    private val playerRewardApi = ChallengeModulePackage.Challenge.PlayerRewardApi(sdk.rawSdk)
    private val goalConfigAdminApi = ChallengeModulePackage.Challenge.GoalConfigurationAdminApi(sdk.rawSdk)
    private val schedulesAdminApi = ChallengeModulePackage.Challenge.SchedulesAdminApi(sdk.rawSdk)
    private val schedulesApi = ChallengeModulePackage.Challenge.SchedulesApi(sdk.rawSdk)
    private val pluginsAdminApi = ChallengeModulePackage.Challenge.PluginsAdminApi(sdk.rawSdk)
    private val utilitiesAdminApi = ChallengeModulePackage.Challenge.ChallengeUtilitiesAdminApi(sdk.rawSdk)

    /** Lists all challenges (admin). */
    fun getChallenges(queryParams : Json = json()) : Promise<Json> = challengeConfigAdminApi.getChallenges(queryParams).propagateJsErrors()

    /** Gets a specific challenge by code. */
    fun getChallenge(challengeCode : String) : Promise<Json> = challengeConfigAdminApi.getChallenge_ByChallengeCode(challengeCode).propagateJsErrors()

    /** Creates a new challenge. */
    fun createChallenge(data : Json) : Promise<Json> = challengeConfigAdminApi.createChallenge(data).propagateJsErrors()

    /** Updates an existing challenge. */
    fun updateChallenge(challengeCode : String, data : Json) : Promise<Json> = challengeConfigAdminApi.updateChallenge_ByChallengeCode(challengeCode, data).propagateJsErrors()

    /** Deletes a challenge. */
    fun deleteChallenge(challengeCode : String) : Promise<Json> = challengeConfigAdminApi.deleteChallenge_ByChallengeCode(challengeCode).propagateJsErrors()

    /** Lists challenges available to the current user. */
    fun getChallengesMe(queryParams : Json = json()) : Promise<Json> = challengeListApi.getChallenges(queryParams).propagateJsErrors()

    /** Lists goals for a specific challenge. */
    fun getGoals(challengeCode : String, queryParams : Json = json()) : Promise<Json> = challengeListApi.getGoals_ByChallengeCode(challengeCode, queryParams).propagateJsErrors()

    /** Gets the current user's progression for a challenge. */
    fun getMyProgression(challengeCode : String, queryParams : Json = json()) : Promise<Json> = challengeProgressionApi.getUserMeProgres_ByChallengeCode(challengeCode, queryParams).propagateJsErrors()

    /** Submits a progression evaluation for the current user. */
    fun evaluateMyProgression() : Promise<Json> = challengeProgressionApi.updateUserMeProgresEvaluate().propagateJsErrors()

    /** Gets progression for a specific user (admin). */
    fun getUserProgression(userId : String, challengeCode : String, queryParams : Json = json()) : Promise<Json> = challengeProgressionAdminApi.getProgres_ByUserId_ByChallengeCode(userId, challengeCode, queryParams).propagateJsErrors()

    /** Evaluates progression for a user (admin). */
    fun evaluateUserProgression(data : Json) : Promise<Json> = challengeProgressionAdminApi.updateProgresEvaluate(data).propagateJsErrors()

    /** Gets all rewards for the current user. */
    fun getMyRewards(queryParams : Json = json()) : Promise<Json> = playerRewardApi.getUsersMeRewards(queryParams).propagateJsErrors()

    /** Claims a reward for the current user. */
    fun claimMyReward(data : Json) : Promise<Json> = playerRewardApi.updateUserMeRewardClaim(data).propagateJsErrors()

    /** Gets rewards for a specific user (admin). */
    fun getUserRewards(userId : String, queryParams : Json = json()) : Promise<Json> = playerRewardAdminApi.getRewards_ByUserId(userId, queryParams).propagateJsErrors()

    /** Claims a reward for a specific user (admin). */
    fun claimUserReward(userId : String, data : Json) : Promise<Json> = playerRewardAdminApi.updateRewardClaim_ByUserId(userId, data).propagateJsErrors()

    /** Lists schedules for a challenge. */
    fun getSchedules(challengeCode : String, queryParams : Json = json()) : Promise<Json> = schedulesApi.getSchedules_ByChallengeCode(challengeCode, queryParams).propagateJsErrors()

    /** Gets goals for a challenge (admin). */
    fun getGoalsAdmin(challengeCode : String, queryParams : Json = json()) : Promise<Json> = goalConfigAdminApi.getGoals_ByChallengeCode(challengeCode, queryParams).propagateJsErrors()
}