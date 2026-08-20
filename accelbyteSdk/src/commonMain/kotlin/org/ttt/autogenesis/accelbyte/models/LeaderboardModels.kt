package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json
import kotlin.js.json

/**
 * Represents the page size for leaderboard lookups.
 *
 * @property limit Maximum number of entries to return per page. Defaults to 20.
 */
data class LeaderboardQueryParams(val limit : Int = 20) : AccelByteRequest
{
    override fun toJson() : Json = json("limit" to limit)
}