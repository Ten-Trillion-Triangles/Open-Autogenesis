package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json

/**
 * A single event entry returned by the platform event service.
 *
 * @property eventId The unique identifier of this event.
 * @property eventType The type or category of the event.
 * @property userId The user associated with this event.
 * @property timestamp The ISO-8601 timestamp when this event occurred.
 * @property payload Additional data associated with the event.
 * @property namespace The namespace where this event occurred.
 * @property sessionId The session associated with this event.
 */
data class EventEntry(
    val eventId : Number,
    val eventType : String,
    val userId : String,
    val timestamp : String,
    val payload : Json?,
    val namespace : String?,
    val sessionId : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : EventEntry = EventEntry(
            eventId = json.requireNumber("eventId"),
            eventType = json.requireString("eventType"),
            userId = json.requireString("userId"),
            timestamp = json.requireString("timestamp"),
            payload = json.optJson("payload"),
            namespace = json.optString("namespace"),
            sessionId = json.optString("sessionId")
        )
    }
}

/**
 * Represents a date range for event queries.
 *
 * @property startDate The start date of the range.
 * @property endDate The end date of the range.
 */
data class EventDateRange(
    val startDate : String,
    val endDate : String
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : EventDateRange = EventDateRange(
            startDate = json.requireString("startDate"),
            endDate = json.requireString("endDate")
        )
    }
}

/**
 * A paginated list of events with date range information.
 *
 * @property events The list of event entries.
 * @property paging Pagination metadata.
 * @property dateRange The date range of this query.
 * @property userId The user ID if filtered by user.
 */
data class EventListResponse(
    val events : List<EventEntry>,
    val paging : PagingInfo,
    val dateRange : EventDateRange?,
    val userId : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : EventListResponse = EventListResponse(
            events = json.optJsonList("events").map(EventEntry::fromJson),
            paging = PagingInfo.fromJson(json.requireJson("paging")),
            dateRange = json.optJson("dateRange")?.let(EventDateRange::fromJson),
            userId = json.optString("userId")
        )
    }
}

/**
 * Metadata associated with an event.
 *
 * @property source The source of this event.
 * @property version The version of the event schema.
 * @property correlationId Correlation identifier for tracing.
 */
data class EventMetadata(
    val source : String?,
    val version : String?,
    val correlationId : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : EventMetadata = EventMetadata(
            source = json.optString("source"),
            version = json.optString("version"),
            correlationId = json.optString("correlationId")
        )
    }
}

/**
 * Detailed information about a single event.
 *
 * @property eventId The unique identifier of this event.
 * @property eventType The type of the event.
 * @property userId The user associated with this event.
 * @property timestamp The ISO-8601 timestamp when this event occurred.
 * @property payload Additional event data.
 * @property metadata Event metadata.
 * @property context Additional context for the event.
 */
data class EventDetailResponse(
    val eventId : Number,
    val eventType : String,
    val userId : String,
    val timestamp : String,
    val payload : Json?,
    val metadata : EventMetadata?,
    val context : Json?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : EventDetailResponse = EventDetailResponse(
            eventId = json.requireNumber("eventId"),
            eventType = json.requireString("eventType"),
            userId = json.requireString("userId"),
            timestamp = json.requireString("timestamp"),
            payload = json.optJson("payload"),
            metadata = json.optJson("metadata")?.let(EventMetadata::fromJson),
            context = json.optJson("context")
        )
    }
}