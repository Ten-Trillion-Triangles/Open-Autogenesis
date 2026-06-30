@file:JsModule("@accelbyte/sdk-leaderboard")
@file:JsNonModule

package org.ttt.autogenesis.accelbyte.modules

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSDK
import org.ttt.autogenesis.accelbyte.SdkSetConfigParam

external object LeaderboardModulePackage {
    val Leaderboard: LeaderboardNamespace
}

external interface LeaderboardNamespace {
    val LeaderboardDataApi: LeaderboardDataApiFactory
}

external interface LeaderboardDataApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): LeaderboardDataApi
}

external interface LeaderboardDataApi {
    fun getWeek_ByLeaderboardCode(leaderboardCode: String, queryParams: Json = definedExternally): Promise<Json>
    fun getAlltime_ByLeaderboardCode(leaderboardCode: String, queryParams: Json = definedExternally): Promise<Json>
    fun getUser_ByLeaderboardCode_ByUserId(leaderboardCode: String, userId: String, queryParams: Json = definedExternally): Promise<Json>
}
