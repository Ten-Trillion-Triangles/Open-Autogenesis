@file:JsModule("@accelbyte/sdk-challenge")
@file:JsNonModule

package org.ttt.autogenesis.accelbyte.modules

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSDK
import org.ttt.autogenesis.accelbyte.SdkSetConfigParam

external val Challenge : ChallengeNamespace

external object ChallengeModulePackage {
    val Challenge: ChallengeNamespace
}

external interface ChallengeNamespace
{
    fun ChallengeConfigurationAdminApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : ChallengeConfigurationAdminApi
    fun ChallengeProgressionAdminApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : ChallengeProgressionAdminApi
    fun GoalConfigurationAdminApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : GoalConfigurationAdminApi
    fun PlayerRewardAdminApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : PlayerRewardAdminApi
    fun PluginsAdminApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : PluginsAdminApi
    fun SchedulesAdminApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : SchedulesAdminApi
    fun ChallengeUtilitiesAdminApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : ChallengeUtilitiesAdminApi
    fun ChallengeListApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : ChallengeListApi
    fun ChallengeProgressionApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : ChallengeProgressionApi
    fun PlayerRewardApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : PlayerRewardApi
    fun SchedulesApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : SchedulesApi
}

external interface ChallengeConfigurationAdminApi
{
    fun getChallenges(queryParams : Json = definedExternally) : Promise<Json>
    fun createChallenge(data : Json) : Promise<Json>
    fun getChallengeUser_ByUserId(userId : String, queryParams : Json = definedExternally) : Promise<Json>
    fun deleteChallenge_ByChallengeCode(challengeCode : String) : Promise<Json>
    fun getChallenge_ByChallengeCode(challengeCode : String) : Promise<Json>
    fun updateChallenge_ByChallengeCode(challengeCode : String, data : Json) : Promise<Json>
    fun deleteTied_ByChallengeCode(challengeCode : String) : Promise<Json>
    fun getPeriods_ByChallengeCode(challengeCode : String, queryParams : Json = definedExternally) : Promise<Json>
    fun updateRandomize_ByChallengeCode(challengeCode : String) : Promise<Json>
    fun updateTiedSchedule_ByChallengeCode(challengeCode : String, data : Json) : Promise<Json>
}

external interface ChallengeProgressionAdminApi
{
    fun updateProgresEvaluate(data : Json) : Promise<Json>
    fun getProgres_ByUserId_ByChallengeCode(userId : String, challengeCode : String, queryParams : Json = definedExternally) : Promise<Json>
}

external interface GoalConfigurationAdminApi
{
    fun getGoals_ByChallengeCode(challengeCode : String, queryParams : Json = definedExternally) : Promise<Json>
    fun createGoal_ByChallengeCode(challengeCode : String, data : Json) : Promise<Json>
    fun deleteGoal_ByChallengeCode_ByCode(challengeCode : String, code : String) : Promise<Json>
    fun getGoal_ByChallengeCode_ByCode(challengeCode : String, code : String) : Promise<Json>
    fun updateGoal_ByChallengeCode_ByCode(challengeCode : String, code : String, data : Json) : Promise<Json>
}

external interface PlayerRewardAdminApi
{
    fun updateUserRewardClaim(data : Array<Json>) : Promise<Json>
    fun getRewards_ByUserId(userId : String, queryParams : Json = definedExternally) : Promise<Json>
    fun updateRewardClaim_ByUserId(userId : String, data : Json) : Promise<Json>
    fun updateRewardClaim_ByUserId_ByChallengeCode(userId : String, challengeCode : String, data : Json) : Promise<Json>
}

external interface PluginsAdminApi
{
    fun deletePluginAssignment() : Promise<Json>
    fun getPluginsAssignment() : Promise<Json>
    fun createPluginAssignment(data : Json) : Promise<Json>
    fun updatePluginAssignment(data : Json) : Promise<Json>
}

external interface SchedulesAdminApi
{
    fun getSchedules_ByChallengeCode(challengeCode : String, queryParams : Json = definedExternally) : Promise<Json>
    fun getSchedules_ByChallengeCode_ByCode(challengeCode : String, code : String, queryParams : Json = definedExternally) : Promise<Json>
}

external interface ChallengeUtilitiesAdminApi
{
    fun getChallengesItemReferences(queryParams : Json = definedExternally) : Promise<Json>
}

external interface ChallengeListApi
{
    fun getChallenges(queryParams : Json = definedExternally) : Promise<Json>
    fun getGoals_ByChallengeCode(challengeCode : String, queryParams : Json = definedExternally) : Promise<Json>
}

external interface ChallengeProgressionApi
{
    fun updateUserMeProgresEvaluate() : Promise<Json>
    fun getUserMeProgres_ByChallengeCode(challengeCode : String, queryParams : Json = definedExternally) : Promise<Json>
    fun getIndexMeUser_ByChallengeCode_ByIndex(challengeCode : String, index : Int, queryParams : Json = definedExternally) : Promise<Json>
}

external interface PlayerRewardApi
{
    fun getUsersMeRewards(queryParams : Json = definedExternally) : Promise<Json>
    fun updateUserMeRewardClaim(data : Json) : Promise<Json>
    fun updateRewardClaimMeUser_ByChallengeCode(challengeCode : String, data : Json) : Promise<Json>
}

external interface SchedulesApi
{
    fun getSchedules_ByChallengeCode(challengeCode : String, queryParams : Json = definedExternally) : Promise<Json>
    fun getSchedules_ByChallengeCode_ByCode(challengeCode : String, code : String, queryParams : Json = definedExternally) : Promise<Json>
}