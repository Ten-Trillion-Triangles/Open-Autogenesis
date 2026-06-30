package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json
import kotlin.js.json

/**
 * Query parameters for listing published seasons.
 */
data class SeasonQueryParams(val language : String? = null, val limit : Int = 20) : AccelByteRequest
{
    override fun toJson() : Json = jsonOf("language" to language, "limit" to limit)
}

/**
 * Parameters for filtering season rewards (paginated list).
 */
data class SeasonRewardQueryParams(val limit : Int? = null, val query : String? = null) : AccelByteRequest
{
    override fun toJson() : Json = jsonOf("limit" to limit, "q" to query)
}

/**
 * Payload for claiming a season reward for a specific pass code/tier.
 */
data class SeasonClaimRequest(val passCode : String, val tierIndex : Int? = null) : AccelByteRequest
{
    override fun toJson() : Json = jsonOf("passCode" to passCode, "tierIndex" to tierIndex)
}

/**
 * Parameter for referencing a specific season store item.
 */
data class ItemReferenceParams(val itemId : String) : AccelByteRequest
{
    override fun toJson() : Json = json("itemId" to itemId)
}
