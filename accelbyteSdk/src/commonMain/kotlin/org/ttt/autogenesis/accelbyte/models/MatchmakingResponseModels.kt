package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json

/**
 * A matchmaking ticket for a player or party in a match pool.
 *
 * @property ticketId The unique identifier of this matchmaking ticket.
 * @property status The current status of the ticket (e.g., QUEUED, MATCHED, CANCELLED).
 * @property estimatedWaitTime Estimated wait time in seconds.
 * @property queuePosition The position in the queue.
 * @property matchPool The match pool this ticket is registered in.
 * @property partyId The party ID if this is a party ticket.
 * @property createdAt Timestamp when the ticket was created.
 * @property matchId The match ID once matched.
 */
data class MatchTicketResponse(
    val ticketId : String,
    val status : String,
    val estimatedWaitTime : Int?,
    val queuePosition : Int?,
    val matchPool : String?,
    val partyId : String?,
    val createdAt : String?,
    val matchId : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : MatchTicketResponse = MatchTicketResponse(
            ticketId = json.requireString("ticketId"),
            status = json.requireString("status"),
            estimatedWaitTime = json.optInt("estimatedWaitTime"),
            queuePosition = json.optInt("queuePosition"),
            matchPool = json.optString("matchPool"),
            partyId = json.optString("partyId"),
            createdAt = json.optString("createdAt"),
            matchId = json.optString("matchId")
        )
    }
}

/**
 * Detailed information about a single matchmaking ticket.
 *
 * @property ticketId The unique identifier of this matchmaking ticket.
 * @property status The current status of the ticket.
 * @property createdAt Timestamp when the ticket was created.
 * @property matchPool The match pool this ticket is registered in.
 * @property estimatedWaitTime Estimated wait time in seconds.
 * @property matchId The match ID once matched.
 * @property queuePosition The position in the queue.
 */
data class TicketInfo(
    val ticketId : String,
    val status : String,
    val createdAt : String?,
    val matchPool : String?,
    val estimatedWaitTime : Int?,
    val matchId : String?,
    val queuePosition : Int?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : TicketInfo = TicketInfo(
            ticketId = json.requireString("ticketId"),
            status = json.requireString("status"),
            createdAt = json.optString("createdAt"),
            matchPool = json.optString("matchPool"),
            estimatedWaitTime = json.optInt("estimatedWaitTime"),
            matchId = json.optString("matchId"),
            queuePosition = json.optInt("queuePosition")
        )
    }
}

/**
 * A paginated list of matchmaking tickets.
 *
 * @property data The list of ticket information entries on this page.
 * @property paging Pagination metadata for navigating additional pages.
 */
data class MatchTicketListResponse(
    val data : List<TicketInfo>,
    val paging : PagingInfo
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : MatchTicketListResponse = MatchTicketListResponse(
            data = json.optJsonList("data").map(TicketInfo::fromJson),
            paging = PagingInfo.fromJson(json.requireJson("paging"))
        )
    }
}

/**
 * Response for a matchmaking ticket cancellation request.
 *
 * @property ticketId The unique identifier of the cancelled ticket.
 * @property status The updated status after cancellation.
 * @property cancelledAt Timestamp when the ticket was cancelled.
 * @property reason The reason for cancellation.
 */
data class MatchTicketCancellationResponse(
    val ticketId : String,
    val status : String,
    val cancelledAt : String,
    val reason : String
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : MatchTicketCancellationResponse = MatchTicketCancellationResponse(
            ticketId = json.requireString("ticketId"),
            status = json.requireString("status"),
            cancelledAt = json.requireString("cancelledAt"),
            reason = json.requireString("reason")
        )
    }
}

/**
 * Detailed information about a matchmaking ticket including server and player assignments.
 *
 * @property ticketId The unique identifier of this matchmaking ticket.
 * @property status The current status of the ticket.
 * @property matchId The match ID once matched.
 * @property serverInfo Server information for the matched session.
 * @property playerAssignments List of player assignments for this match.
 * @property estimatedWaitTime Estimated wait time in seconds.
 * @property queuePosition The position in the queue.
 * @property createdAt Timestamp when the ticket was created.
 * @property updatedAt Timestamp when the ticket was last updated.
 */
data class MatchTicketDetailsResponse(
    val ticketId : String,
    val status : String,
    val matchId : String?,
    val serverInfo : Json?,
    val playerAssignments : List<Json>,
    val estimatedWaitTime : Int?,
    val queuePosition : Int?,
    val createdAt : String?,
    val updatedAt : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : MatchTicketDetailsResponse = MatchTicketDetailsResponse(
            ticketId = json.requireString("ticketId"),
            status = json.requireString("status"),
            matchId = json.optString("matchId"),
            serverInfo = json.optJson("serverInfo"),
            playerAssignments = json.optJsonList("playerAssignments"),
            estimatedWaitTime = json.optInt("estimatedWaitTime"),
            queuePosition = json.optInt("queuePosition"),
            createdAt = json.optString("createdAt"),
            updatedAt = json.optString("updatedAt")
        )
    }
}