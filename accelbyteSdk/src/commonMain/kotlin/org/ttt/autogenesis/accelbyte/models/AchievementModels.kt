package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json
import kotlin.js.json

/**
 * Query parameters for listing the global achievement definitions (master catalog).
 * Use with [org.ttt.autogenesis.accelbyte.facades.AchievementFacade.listAchievements].
 *
 * @property limit maximum number of achievement definitions to return per page.
 *                 Defaults to 20.
 */
data class AchievementQueryParams(val limit : Int = 20) : AccelByteRequest
{
    override fun toJson() : Json = json("limit" to limit)
}

/**
 * Query parameters for listing achievements earned by a specific user.
 * Use with [org.ttt.autogenesis.accelbyte.facades.AchievementFacade.getUserAchievements].
 *
 * @property userId the user whose earned achievements should be enumerated.
 * @property limit maximum number of user achievements to return per page.
 *                 Defaults to 20.
 */
data class UserAchievementQuery(val userId : String, val limit : Int = 20) : AccelByteRequest
{
    override fun toJson() : Json = json("limit" to limit)
}
