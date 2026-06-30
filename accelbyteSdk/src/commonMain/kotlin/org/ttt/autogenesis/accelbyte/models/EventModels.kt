package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json
import kotlin.js.json

/**
 * Query parameters for searching platform event logs.
 *
 * @property startDate ISO-8601 timestamp marking the start of the query window (inclusive).
 * @property endDate ISO-8601 timestamp marking the end of the query window (inclusive).
 * @property pageSize maximum number of event records to return per page. Defaults to 20.
 */
data class EventQueryParams(
    val startDate : String,
    val endDate : String,
    val pageSize : Int = 20
) : AccelByteRequest {
    override fun toJson() : Json = json(
        "startDate" to startDate,
        "endDate" to endDate,
        "pageSize" to pageSize
    )
}
