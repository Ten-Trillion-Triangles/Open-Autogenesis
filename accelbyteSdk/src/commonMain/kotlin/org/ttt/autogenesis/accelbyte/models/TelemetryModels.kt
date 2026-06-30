package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json
import kotlin.js.json

/**
 * Parameters for retrieving build version history for differential comparison.
 * Used by the GameTelemetry service to compare telemetry configurations between two builds.
 *
 * @property appId the identifier of the application or game title.
 * @property comparedBuildId the build ID to use as the comparison baseline.
 */
data class VersionHistoryParams(val appId : String, val comparedBuildId : String) : AccelByteRequest
{
    override fun toJson() : Json = json("appId" to appId, "comparedBuildId" to comparedBuildId)
}

/**
 * Parameters for retrieving telemetry file upload URLs filtered by allowed file type.
 *
 * @property fileType the permitted file content type (e.g., "video", "image", "audio").
 */
data class BlockUrlParams(val fileType : String) : AccelByteRequest
{
    override fun toJson() : Json = json("fileType" to fileType)
}

/**
 * Comprehensive query parameters for searching telemetry events stored by the backend.
 *
 * @property eventName filter by event name or pattern. Null = no event-name filter.
 * @property startTime ISO-8601 timestamp marking the start of the time range (inclusive).
 *                     Null = no lower bound.
 * @property endTime ISO-8601 timestamp marking the end of the time range (inclusive).
 *                   Null = no upper bound.
 * @property offset number of events to skip for pagination. Null omits the param.
 * @property limit maximum number of events to return per page. Null omits the param.
 * @property userId filter by a specific user's identifier. Null = all users.
 */
data class TelemetryQueryParams(
    val eventName : String? = null,
    val startTime : String? = null,
    val endTime : String? = null,
    val offset : Int? = null,
    val limit : Int? = null,
    val userId : String? = null
) : AccelByteRequest {
    override fun toJson() : Json = jsonOf(
        "eventName" to eventName,
        "startTime" to startTime,
        "endTime" to endTime,
        "offset" to offset,
        "limit" to limit,
        "userId" to userId
    )
}
