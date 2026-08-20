package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json
import kotlin.js.json

/**
 * Query parameters for retrieving a user's stat items (leaderboard entries).
 *
 * @property limit maximum number of stat entries to return. Defaults to 10.
 */
data class StatItemQueryParams(val limit : Int = 10) : AccelByteRequest
{
    override fun toJson() : Json = json("limit" to limit)
}

/**
 * Request payload for a bulk stats operation — wraps an arbitrary JSON query body.
 *
 * The wrapped [payload] is passed unchanged to the underlying API. This is a passthrough
 * model for endpoints that accept a flexible JSON body rather than a typed request structure.
 *
 * @property payload the JSON body to forward directly to the SDK binding.
 */
data class StatItemsBulkRequest(val payload : Json) : AccelByteRequest
{
    override fun toJson() : Json = payload
}