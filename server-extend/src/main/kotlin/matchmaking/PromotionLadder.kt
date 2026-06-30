package matchmaking

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.accelbyte.sdk.api.match2.operations.match_tickets.CreateMatchTicket
import net.accelbyte.sdk.api.match2.operations.match_tickets.DeleteMatchTicket
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

/**
 * Coroutine that drives a single ticket through the [MatchmakingLadder].
 *
 * For each tier the coroutine waits [MatchPoolSpec.holdSeconds] (sleeping in
 * `holdSeconds / pollIntervalMs` chunks so cancellation lands quickly), then
 * deletes the current ticket and recreates it in the next pool. The coroutine
 * stops when:
 *  - the ticket is matched externally (caller invokes [markMatched]),
 *  - the ladder runs out of tiers (final tier expires without a match),
 *  - the surrounding [scope] is cancelled.
 *
 * The coroutine does not own the polling loop — that is still
 * [ServerConnector.executeLiveMatchmaking]'s responsibility. The ladder only
 * decides when to recreate the ticket; the caller keeps calling
 * [MatchTickets.matchTicketDetails] in parallel and checks [isMatched] /
 * [latestPoolName] on each tick.
 *
 * @param scope The coroutine scope the promotion job runs in. The
 *              [ServerConnector] uses a per-ticket scope so cancellation is
 *              local.
 */
class PromotionLadder(
    private val scope: CoroutineScope,
    private val ladder: MatchmakingLadder,
    private val matchTickets: MatchTicketsApi,
    private val namespace: String,
    private val attributes: Map<String, Any?>,
    private val latencies: Map<String, Int>,
    private val pollIntervalMs: Long = 1_000L,
    private val clock: () -> Long = System::currentTimeMillis
)
{
    /** Job running the promotion loop. `null` until [start] is called. */
    private var job: Job? = null

    /** Name of the pool the latest ticket is in. Updated on each promotion. */
    @Volatile
    var latestPoolName: String = ladder.entry.name
        internal set

    /** Ticket id for the latest ticket. Updated on each promotion. */
    @Volatile
    var latestTicketId: String = ""
        internal set

    /** Set to true when the matchmaker reports a match externally. */
    @Volatile
    var isMatched: Boolean = false
        internal set

    /** Set to true when the ladder has exhausted all tiers. */
    @Volatile
    var isExhausted: Boolean = false
        internal set

    /** Earliest epoch-ms at which the current tier should be promoted. */
    @Volatile
    internal var currentTierStartedAt: Long = 0L

    /**
     * Starts the promotion coroutine. The coroutine sleeps in
     * [pollIntervalMs] chunks, checking both the external [isMatched] signal
     * and the current tier's hold-window.
     */
    fun start()
    {
        if(job != null) return
        currentTierStartedAt = clock()
        job = scope.launch {
            var current: MatchPoolSpec = ladder.entry
            latestPoolName = current.name
            try
            {
                while(isActive)
                {
                    if(isMatched || isExhausted) return@launch

                    val elapsed = clock() - currentTierStartedAt
                    val holdMs = current.holdSeconds * 1_000L
                    if(elapsed >= holdMs)
                    {
                        val next = ladder.nextAfter(current)
                        if(next == null)
                        {
                            Logger.info(
                                LogCategory.NETWORK,
                                "PromotionLadder: ladder exhausted after tier ${current.name} (ticketId=$latestTicketId); no more promotions"
                            )
                            isExhausted = true
                            return@launch
                        }

                        if(latestTicketId.isNotBlank())
                        {
                            val deleted = try
                            {
                                val op = DeleteMatchTicket.builder()
                                    .namespace(namespace)
                                    .ticketid(latestTicketId)
                                    .build()
                                matchTickets.deleteMatchTicket(op)
                                true
                            }
                            catch(err: Throwable)
                            {
                                Logger.warn(
                                    LogCategory.NETWORK,
                                    "PromotionLadder: deleteMatchTicket failed for ticketId=$latestTicketId on tier ${current.name}: ${err.message}"
                                )
                                false
                            }
                            if(!deleted)
                            {
                                // Treat as a no-op: move on, the next create overwrites the row.
                                Logger.debug(
                                    LogCategory.NETWORK,
                                    "PromotionLadder: continuing despite delete failure"
                                )
                            }
                        }

                        val newId = try
                        {
                            val body = net.accelbyte.sdk.api.match2.models.ApiMatchTicketRequest.builder()
                                .matchPool(next.name)
                                .attributes(attributes)
                                .latencies(latencies)
                                .build()
                            val op = CreateMatchTicket.builder()
                                .namespace(namespace)
                                .body(body)
                                .build()
                            matchTickets.createMatchTicket(op).matchTicketID
                        }
                        catch(err: Throwable)
                        {
                            Logger.error(
                                LogCategory.NETWORK,
                                "PromotionLadder: createMatchTicket failed on promotion to ${next.name}: ${err.message}"
                            )
                            isExhausted = true
                            return@launch
                        }

                        Logger.info(
                            LogCategory.NETWORK,
                            "PromotionLadder: promoted ticket ${latestTicketId.ifBlank { "<new>" }} → $newId (${current.name} → ${next.name})"
                        )
                        latestTicketId = newId
                        latestPoolName = next.name
                        current = next
                        currentTierStartedAt = clock()
                    }
                    else
                    {
                        delay(pollIntervalMs.coerceAtLeast(50L))
                    }
                }
            }
            finally
            {
                Logger.debug(
                    LogCategory.NETWORK,
                    "PromotionLadder: loop exited (matched=$isMatched exhausted=$isExhausted active=$isActive)"
                )
            }
        }
    }

    /** Cancels the promotion coroutine. Idempotent. */
    fun cancel()
    {
        job?.cancel()
        job = null
    }

    /** Marks the ticket as matched. The promotion loop will exit on its next tick. */
    fun markMatched()
    {
        isMatched = true
    }
}

/**
 * Narrow facade over the match2 `MatchTickets` SDK so [PromotionLadder] can be
 * unit-tested with a mock. The production [ServerConnector] injects its
 * existing [net.accelbyte.sdk.api.match2.wrappers.MatchTickets] instance.
 */
interface MatchTicketsApi
{
    fun createMatchTicket(op: CreateMatchTicket): net.accelbyte.sdk.api.match2.models.ApiMatchTicketResponse
    fun deleteMatchTicket(op: DeleteMatchTicket)
}
