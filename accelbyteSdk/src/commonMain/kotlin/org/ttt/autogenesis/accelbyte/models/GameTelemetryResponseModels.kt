package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json

/**
 * Playtime data for a Steam user.
 *
 * @property steamId The Steam identifier of the user.
 * @property playtimeSeconds The total playtime in seconds.
 * @property updatedAt Timestamp when this data was last updated.
 */
data class TelemetryPlaytimeResponse(
    val steamId : String,
    val playtimeSeconds : Int?,
    val updatedAt : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : TelemetryPlaytimeResponse = TelemetryPlaytimeResponse(
            steamId = json.requireString("steamId"),
            playtimeSeconds = json.optInt("playtimeSeconds") ?: json.optInt("playtime"),
            updatedAt = json.optString("updatedAt")
        )
    }
}

/**
 * Result of processing a protected telemetry event.
 *
 * @property processed Whether the event was successfully processed.
 * @property eventIds List of event identifiers that were processed.
 */
data class TelemetryProtectedEventResult(
    val processed : Boolean?,
    val eventIds : List<String>
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : TelemetryProtectedEventResult = TelemetryProtectedEventResult(
            processed = json.optBoolean("processed"),
            eventIds = json.optStringList("eventIds")
        )
    }
}

/**
 * Response containing a list of namespaces for telemetry.
 *
 * @property namespaces List of namespace identifiers.
 */
data class TelemetryNamespaceListResponse(
    val namespaces : List<String>
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : TelemetryNamespaceListResponse = TelemetryNamespaceListResponse(
            namespaces = json.optJsonArray("namespaces")?.mapNotNull { it?.toString() }
                ?: json.optJsonArray("")?.mapNotNull { it?.toString() }
                ?: emptyList()
        )
    }
}

/**
 * A single telemetry event record.
 *
 * @property eventId The unique identifier of this event.
 * @property eventName The name of the event.
 * @property eventNamespace The namespace where this event occurred.
 * @property timestamp The ISO-8601 timestamp when this event occurred.
 * @property flightId The flight or experiment identifier.
 * @property payload Additional data associated with the event.
 * @property userId The user associated with this event.
 * @property userNamespace The namespace of the user.
 */
data class TelemetryEventRecord(
    val eventId : String,
    val eventName : String,
    val eventNamespace : String,
    val timestamp : String,
    val flightId : String?,
    val payload : Json?,
    val userId : String?,
    val userNamespace : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : TelemetryEventRecord = TelemetryEventRecord(
            eventId = json.requireString("EventId"),
            eventName = json.requireString("EventName"),
            eventNamespace = json.requireString("EventNamespace"),
            timestamp = json.requireString("EventTimestamp"),
            flightId = json.optString("FlightId"),
            payload = json.optJson("Payload"),
            userId = json.optString("UserId"),
            userNamespace = json.optString("UserNamespace")
        )
    }
}

/**
 * A paginated list of telemetry event records.
 *
 * @property data The list of telemetry events on this page.
 * @property paging Pagination metadata.
 */
data class TelemetryEventListResponse(
    val data : List<TelemetryEventRecord>,
    val paging : PagingInfo
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : TelemetryEventListResponse = TelemetryEventListResponse(
            data = json.optJsonList("data").map(TelemetryEventRecord::fromJson),
            paging = PagingInfo.fromJson(json.requireJson("paging"))
        )
    }
}
