@file:JsModule("@accelbyte/sdk-achievement")
@file:JsNonModule

package org.ttt.autogenesis.accelbyte.modules

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSDK
import org.ttt.autogenesis.accelbyte.SdkSetConfigParam

external object AchievementModulePackage {
    val Achievement: AchievementNamespace
}

external interface AchievementNamespace {
    val AchievementsApi: AchievementsApiFactory
}

external interface AchievementsApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): AchievementsApi
}

external interface AchievementsApi {
    fun getAchievements(namespace: String, queryParams: Json = definedExternally): Promise<Json>
    fun unlockAchievement(achievementCode: String, namespace: String): Promise<Json>
    fun getUserAchievements(namespace: String, userId: String, queryParams: Json = definedExternally): Promise<Json>
}
