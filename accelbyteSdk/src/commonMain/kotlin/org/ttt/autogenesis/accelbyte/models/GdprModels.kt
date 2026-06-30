package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json
import kotlin.js.json

/**
 * Password confirmation payload required by data retrieval/deletion endpoints.
 *
 * @property password The user password for confirmation.
 */
data class GdprPasswordRequest(val password : String) : AccelByteRequest
{
    override fun toJson() : Json = json("password" to password)
}

/**
 * Pagination parameters for listing GDPR requests.
 *
 * @property limit Maximum number of requests to return per page.
 */
data class GdprRequestListParams(val limit : Int = 20) : AccelByteRequest
{
    override fun toJson() : Json = json("limit" to limit)
}
