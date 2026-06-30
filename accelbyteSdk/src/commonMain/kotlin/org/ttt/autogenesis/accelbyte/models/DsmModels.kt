package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json
import kotlin.js.json

/**
 * Query parameters for listing dedicated servers.
 *
 * @property count Number of records to fetch.
 * @property offset List offset for pagination.
 * @property region Optional region filter.
 */
data class DsmServerQueryParams(
    val count : Int = 20,
    val offset : Int = 0,
    val region : String? = null
) : AccelByteRequest
{
    override fun toJson() : Json = jsonOf("count" to count, "offset" to offset, "region" to region)
}

/**
 * Deployment query filters used when listing DSM deployments.
 *
 * @property count Number of records to fetch.
 * @property offset List offset for pagination.
 * @property name Optional deployment name filter.
 */
data class DsmDeploymentQueryParams(
    val count : Int = 20,
    val offset : Int = 0,
    val name : String? = null
) : AccelByteRequest
{
    override fun toJson() : Json = jsonOf("count" to count, "offset" to offset, "name" to name)
}

/**
 * Region filter for endpoints that optionally accept a region query parameter.
 *
 * @property region The region identifier to filter by.
 */
data class DsmRegionQueryParams(val region : String) : AccelByteRequest
{
    override fun toJson() : Json = json("region" to region)
}

/**
 * Wrapper for DSM controller payloads since the JSON body is passed to the API unchanged.
 *
 * @property payload The raw JSON payload for the lifecycle operation.
 */
data class ServerLifecycleRequest(val payload : Json) : AccelByteRequest
{
    override fun toJson() : Json = payload
}
