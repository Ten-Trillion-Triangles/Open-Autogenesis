package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.models.MatchTicketCancellationResponse
import org.ttt.autogenesis.accelbyte.models.MatchTicketDetailsResponse
import org.ttt.autogenesis.accelbyte.models.MatchTicketFilter
import org.ttt.autogenesis.accelbyte.models.MatchTicketListResponse
import org.ttt.autogenesis.accelbyte.models.MatchTicketRequest
import org.ttt.autogenesis.accelbyte.models.MatchTicketResponse
import org.ttt.autogenesis.accelbyte.modules.MatchTicketsApi
import org.ttt.autogenesis.accelbyte.modules.MatchmakingModulePackage
import org.ttt.autogenesis.accelbyte.util.mapJson
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors

/**
 * Manages matchmaking operations including ticket creation, status tracking, and queue management.
 * 
 * Provides player matching services with support for skill-based matching, party queuing,
 * and real-time status updates for competitive and casual gameplay.
 */
class MatchmakingFacade(private val sdk : AccelByteSdkInstance)
{
    private val matchTicketsApi : MatchTicketsApi
        get() = MatchmakingModulePackage.Matchmaking.MatchTicketsApi(sdk.rawSdk)

    /**
     * Creates a new matchmaking ticket to join the player queue for a specific match pool.
     * 
     * Submits a request to find suitable opponents or teammates based on the specified
     * criteria. The system will attempt to match players with similar skill levels and preferences.
     *
     * @param request MatchTicketRequest containing match pool, cooldown settings, and matchmaking parameters
     * @return `Promise<MatchTicketResponse>` resolving to ticket creation response containing:
     *         - `ticketId: string` - Unique identifier for the matchmaking ticket
     *         - `status: string` - Current ticket status (QUEUED, MATCHING, MATCHED, CANCELLED)
     *         - `estimatedWaitTime: number` - Estimated wait time in seconds
     *         - `queuePosition: number` - Current position in the matchmaking queue
     *         - `createdAt: string` - ISO timestamp when ticket was created
     *         - `matchPool: string` - Matchmaking pool identifier
     *         - `partyId: string` - Associated party ID (if queuing as group)
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws ValidationException if matchmaking criteria are invalid
     * @throws QuotaExceededException if user has too many active tickets
     */
    fun createMatchTicket(request : MatchTicketRequest) : Promise<MatchTicketResponse> =
        matchTicketsApi.createMatchTicket(request.toJson())
            .propagateJsErrors()
            .mapJson(MatchTicketResponse::fromJson)

    /**
     * Retrieves all matchmaking tickets associated with the current user.
     * 
     * Returns a list of tickets with their current status, including active, completed,
     * and cancelled tickets. Supports pagination and filtering options.
     *
     * @param filters MatchTicketFilter with optional status, pool, and pagination criteria
     * @return `Promise<MatchTicketListResponse>` resolving to user tickets response containing:
     *         - `data: Array<TicketInfo>` - Array of ticket objects
     *         - `paging: PagingInfo` - Pagination metadata with total, limit, offset
     *         Each TicketInfo contains: ticketId, status, createdAt, matchPool, estimatedWaitTime, matchId
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws ValidationException if filter parameters are invalid
     */
    fun getMyTickets(filters : MatchTicketFilter = MatchTicketFilter()) : Promise<MatchTicketListResponse> =
        matchTicketsApi.getMatchTicketsMe(filters.toJson())
            .propagateJsErrors()
            .mapJson(MatchTicketListResponse::fromJson)

    /**
     * Cancels an active matchmaking ticket, removing the player from the queue.
     * 
     * Stops the matchmaking process and removes the ticket from consideration.
     * Players can create a new ticket if they want to re-enter matchmaking.
     *
     * @param ticketId Unique identifier of the ticket to cancel
     * @return `Promise<MatchTicketCancellationResponse>` resolving to cancellation confirmation containing:
     *         - `ticketId: string` - ID of cancelled ticket
     *         - `status: string` - Updated status (CANCELLED)
     *         - `cancelledAt: string` - ISO timestamp when cancellation occurred
     *         - `reason: string` - Cancellation reason (USER_REQUESTED, TIMEOUT, etc.)
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws ValidationException if ticket ID is invalid
     * @throws TicketNotFoundException if ticket doesn't exist
     * @throws InvalidOperationException if ticket cannot be cancelled (already matched)
     */
    fun deleteMatchTicket(ticketId : String) : Promise<MatchTicketCancellationResponse> =
        matchTicketsApi.deleteMatchTicket_ByTicketid(ticketId)
            .propagateJsErrors()
            .mapJson(MatchTicketCancellationResponse::fromJson)

    /**
     * Retrieves detailed information about a specific matchmaking ticket.
     * 
     * Returns current status, match results if found, and server connection details.
     * Use this to check if a match has been found and get connection information.
     *
     * @param ticketId Unique identifier of the matchmaking ticket
     * @return `Promise<MatchTicketDetailsResponse>` resolving to ticket details response containing:
     *         - `ticketId: string` - Ticket identifier
     *         - `status: string` - Current status (QUEUED, MATCHING, MATCHED, CANCELLED, EXPIRED)
     *         - `matchId: string` - Match identifier (if matched)
     *         - `serverInfo: object` - Server connection details (if matched)
     *         - `playerAssignments: Array<object>` - Team/role assignments (if matched)
     *         - `estimatedWaitTime: number` - Updated wait time estimate
     *         - `queuePosition: number` - Current queue position
     *         - `createdAt: string` - ISO timestamp when ticket was created
     *         - `updatedAt: string` - ISO timestamp of last status update
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws ValidationException if ticket ID is invalid
     * @throws TicketNotFoundException if ticket doesn't exist or has expired
     */
    fun getMatchTicket(ticketId : String) : Promise<MatchTicketDetailsResponse> =
        matchTicketsApi.getMatchTicket_ByTicketid(ticketId)
            .propagateJsErrors()
            .mapJson(MatchTicketDetailsResponse::fromJson)
}
