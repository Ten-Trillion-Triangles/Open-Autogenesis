package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json
import kotlin.js.json

/**
 * Query for namespace QoS endpoints.
 */
data class QosmRegionQuery(val status : String? = null) : AccelByteRequest
{
    override fun toJson() : Json = jsonOf("status" to status)
}

/**
 * Alias request payload for `POST /settings/alias`.
 */
data class QosmAliasRequest(val alias : String) : AccelByteRequest
{
    override fun toJson() : Json = json("alias" to alias)
}
