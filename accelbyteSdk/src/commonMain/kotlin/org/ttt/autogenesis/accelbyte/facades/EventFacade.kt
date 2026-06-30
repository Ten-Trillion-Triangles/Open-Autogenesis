package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.models.EventDetailResponse
import org.ttt.autogenesis.accelbyte.models.EventListResponse
import org.ttt.autogenesis.accelbyte.models.EventQueryParams
import org.ttt.autogenesis.accelbyte.modules.EventApi
import org.ttt.autogenesis.accelbyte.modules.EventModulePackage
import org.ttt.autogenesis.accelbyte.util.mapJson
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors

/**
 * Wraps Event Log operations for querying namespace, user, and specific event data. Methods return raw JSON
 * containing event arrays, pagination metadata, or individual event details; callers should map these into their domain models.
 *
 * JSON Response Structures:
 * - Event List: `{ "events": [{ "eventId": number, "eventType": "string", "userId": "string", "timestamp": "ISO8601", "payload": {...} }], "paging": {...} }`
 * - Event Detail: `{ "eventId": number, "eventType": "string", "userId": "string", "timestamp": "ISO8601", "payload": {...}, "metadata": {...} }`
 */
class EventFacade(private val sdk : AccelByteSdkInstance)
{
    private val eventApi : EventApi
        get() = EventModulePackage.Event.EventApi(sdk.rawSdk)

    /**
     * Retrieves namespace-wide events within the specified date range with pagination.
     *
     * @param params Query parameters including startDate, endDate, eventType filters, and pagination settings
     * @return `Promise<EventListResponse>` containing paginated events and optional date range metadata
     */
    fun queryNamespaceEvents(params : EventQueryParams) : Promise<EventListResponse> =
        eventApi.getNamespace_ByNamespace(params.toJson())
            .propagateJsErrors()
            .mapJson(EventListResponse::fromJson)

    /**
     * Retrieves events for a specific user within the specified date range.
     *
     * @param userId The user identifier to query events for
     * @param params Query parameters including date range, event type filters, and pagination
     * @return `Promise<EventListResponse>` containing the user-specific events
     */
    fun queryUserEvents(userId : String, params : EventQueryParams) : Promise<EventListResponse> =
        eventApi.getUser_ByUserId(userId, params.toJson())
            .propagateJsErrors()
            .mapJson(EventListResponse::fromJson)

    /**
     * Retrieves detailed information for a specific event by its numeric identifier.
     *
     * @param eventId The numeric event identifier
     * @param params Query parameters for additional context or formatting options
     * @return `Promise<EventDetailResponse>` describing the event payload, metadata, and context
     */
    fun queryEventById(eventId : Number, params : EventQueryParams) : Promise<EventDetailResponse> =
        eventApi.getEventId_ByEventId(eventId, params.toJson())
            .propagateJsErrors()
            .mapJson(EventDetailResponse::fromJson)
}
