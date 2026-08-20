@file:JsModule("@accelbyte/sdk-seasonpass")
@file:JsNonModule

package org.ttt.autogenesis.accelbyte.modules

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSDK
import org.ttt.autogenesis.accelbyte.SdkSetConfigParam

external object SeasonpassModulePackage {
    val Seasonpass: SeasonpassNamespace
}

external interface SeasonpassNamespace {
    val ExportAdminApi: ExportAdminApiFactory
    val PassAdminApi: PassAdminApiFactory
    val RewardAdminApi: RewardAdminApiFactory
    val SeasonAdminApi: SeasonAdminApiFactory
    val TierAdminApi: TierAdminApiFactory
    val UtilitiesAdminApi: UtilitiesAdminApiFactory
    val RewardApi: RewardApiFactory
    val SeasonApi: SeasonApiFactory
}

external interface ExportAdminApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): ExportAdminApi
}

external interface ExportAdminApi {
    fun getExport(): Promise<Json>
}

external interface PassAdminApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): PassAdminApi
}

external interface PassAdminApi {
    fun getPasses_BySeasonId(seasonId: String): Promise<Json>
    fun createPasse_BySeasonId(seasonId: String, data: Json): Promise<Json>
    fun deletePasse_BySeasonId_ByCode(seasonId: String, code: String): Promise<Json>
    fun getPasse_BySeasonId_ByCode(seasonId: String, code: String): Promise<Json>
    fun patchPasse_BySeasonId_ByCode(seasonId: String, code: String, data: Json): Promise<Json>
    fun createSeasonCurrentPasse_ByUserId(userId: String, data: Json): Promise<Json>
}

external interface RewardAdminApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): RewardAdminApi
}

external interface RewardAdminApi {
    fun getRewards_BySeasonId(seasonId: String, queryParams: Json? = definedExternally): Promise<Json>
    fun createReward_BySeasonId(seasonId: String, data: Json): Promise<Json>
    fun deleteReward_BySeasonId_ByCode(seasonId: String, code: String): Promise<Json>
    fun getReward_BySeasonId_ByCode(seasonId: String, code: String): Promise<Json>
    fun patchReward_BySeasonId_ByCode(seasonId: String, code: String, data: Json): Promise<Json>
}

external interface SeasonAdminApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): SeasonAdminApi
}

external interface SeasonAdminApi {
    fun getSeasons(queryParams: Json? = definedExternally): Promise<Json>
    fun createSeason(data: Json): Promise<Json>
    fun getSeasonsCurrent(): Promise<Json>
    fun deleteSeason_BySeasonId(seasonId: String): Promise<Json>
    fun getSeason_BySeasonId(seasonId: String): Promise<Json>
    fun patchSeason_BySeasonId(seasonId: String, data: Json): Promise<Json>
    fun getFull_BySeasonId(seasonId: String): Promise<Json>
    fun createClone_BySeasonId(seasonId: String, data: Json): Promise<Json>
    fun updateRetire_BySeasonId(seasonId: String, queryParams: Json? = definedExternally): Promise<Json>
    fun updatePublish_BySeasonId(seasonId: String): Promise<Json>
    fun updateUnpublish_BySeasonId(seasonId: String, queryParams: Json? = definedExternally): Promise<Json>
}

external interface TierAdminApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): TierAdminApi
}

external interface TierAdminApi {
    fun getTiers_BySeasonId(seasonId: String, queryParams: Json? = definedExternally): Promise<Json>
    fun createTier_BySeasonId(seasonId: String, data: Json): Promise<Json>
    fun deleteTier_BySeasonId_ById(seasonId: String, id: String): Promise<Json>
    fun updateTier_BySeasonId_ById(seasonId: String, id: String, data: Json): Promise<Json>
    fun createSeasonCurrentExp_ByUserId(userId: String, data: Json): Promise<Json>
    fun createSeasonCurrentTier_ByUserId(userId: String, data: Json): Promise<Json>
    fun updateReorder_BySeasonId_ById(seasonId: String, id: String, data: Json): Promise<Json>
}

external interface UtilitiesAdminApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): UtilitiesAdminApi
}

external interface UtilitiesAdminApi {
    fun getSeasonsItemReferences(queryParams: Json): Promise<Json>
}

external interface RewardApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): RewardApi
}

external interface RewardApi {
    fun createSeasonCurrentReward_ByUserId(userId: String, data: Json): Promise<Json>
    fun createSeasonCurrentRewardBulk_ByUserId(userId: String): Promise<Json>
}

external interface SeasonApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): SeasonApi
}

external interface SeasonApi {
    fun getSeasonsCurrent(queryParams: Json? = definedExternally): Promise<Json>
    fun getSeasonsCurrentData_ByUserId(userId: String): Promise<Json>
    fun getData_ByUserId_BySeasonId(userId: String, seasonId: String): Promise<Json>
}