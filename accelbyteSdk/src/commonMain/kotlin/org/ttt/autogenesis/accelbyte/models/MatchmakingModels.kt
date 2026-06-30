package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json
import kotlin.js.json

/**
 * Payload for `POST /matchmaking/v1/namespaces/{namespace}/match-tickets`.
 * @param matchPool pool name.
 * @param cooldownInSec optional cooldown in seconds before the next ticket.
 * @param params optional JSON passthrough for additional settings.
 */
data class MatchTicketRequest(
    val matchPool : String,
    val cooldownInSec : Int? = null,
    val params : Json = json()
) : AccelByteRequest {
    override fun toJson() : Json = json("matchPool" to matchPool, "cooldownInSec" to cooldownInSec, "params" to params)
}

/**
 * Query filters used when listing tickets.
 */
data class MatchTicketFilter(
    val limit : Int? = null,
    val offset : Int? = null,
    val matchPool : String? = null
) : AccelByteRequest {
    override fun toJson() : Json = json("limit" to limit, "offset" to offset, "matchPool" to matchPool)
}
