package matchmaking

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import net.accelbyte.sdk.api.match2.models.ApiMatchTicketRequest
import net.accelbyte.sdk.api.match2.models.ApiMatchTicketResponse
import net.accelbyte.sdk.api.match2.operations.match_tickets.CreateMatchTicket
import net.accelbyte.sdk.api.match2.operations.match_tickets.DeleteMatchTicket
import net.accelbyte.sdk.api.match2.operations.match_tickets.MatchTicketDetails
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the [PromotionLadder] promotes a ticket from `pvp-4` → `pvp-3` →
 * `pvp-2` and finally exhausts, deleting each ticket before recreating it in
 * the next pool. Uses a [RecordingMatchTickets] fake and a virtual clock so
 * the test runs in milliseconds.
 */
class PromotionLadderTest
{
    private lateinit var scope: CoroutineScope
    private lateinit var fake: RecordingMatchTickets

    @Before
    fun setUp()
    {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        fake = RecordingMatchTickets()
    }

    @After
    fun tearDown()
    {
        scope.cancel()
    }

    @Test
    fun promotesFromPvp4ToPvp3ToPvp2AndExhausts() = runBlocking {
        val ladder = MatchmakingLadder.DEFAULT
        val clockRef = longArrayOf(0L)
        val clock: () -> Long = { clockRef[0] }

        val promotion = PromotionLadder(
            scope = scope,
            ladder = ladder,
            matchTickets = fake,
            namespace = "test-ns",
            attributes = mapOf("cost_class" to "PRO"),
            latencies = emptyMap(),
            pollIntervalMs = 25L,
            clock = clock
        )
        // Pre-seed the starting ticket as if executeLiveMatchmaking had created it.
        promotion.latestTicketId = "initial-1"
        promotion.start()

        // Tier 1: hold = 60_000 ms. Advance the virtual clock past it.
        clockRef[0] += 60_001L
        delay(60L) // give the loop a chance to run
        assertEquals("pvp-3", promotion.latestPoolName, "tier 1 expired → promoted to pvp-3")
        assertEquals("ticket-2", promotion.latestTicketId)
        assertTrue(fake.deletedTicketIds.contains("initial-1"), "previous ticket must be deleted on promotion")

        // Tier 2: hold = 90_000 ms.
        clockRef[0] += 90_001L
        delay(60L)
        assertEquals("pvp-2", promotion.latestPoolName, "tier 2 expired → promoted to pvp-2")
        assertEquals("ticket-3", promotion.latestTicketId)
        assertTrue(fake.deletedTicketIds.contains("ticket-2"))

        // Tier 3: hold = 60_000 ms — but this is the last tier, so the ladder
        // should mark itself exhausted and stop.
        clockRef[0] += 60_001L
        delay(60L)
        assertTrue(promotion.isExhausted, "ladder must exhaust after the final tier")
        assertEquals("pvp-2", promotion.latestPoolName, "exhausted at the final tier — no further promotion")

        promotion.cancel()
    }

    @Test
    fun markMatchedStopsThePromotionLoop() = runBlocking {
        val ladder = MatchmakingLadder.DEFAULT
        val clockRef = longArrayOf(0L)
        val clock: () -> Long = { clockRef[0] }

        val promotion = PromotionLadder(
            scope = scope,
            ladder = ladder,
            matchTickets = fake,
            namespace = "test-ns",
            attributes = emptyMap(),
            latencies = emptyMap(),
            pollIntervalMs = 25L,
            clock = clock
        )
        promotion.latestTicketId = "initial-1"
        promotion.start()

        // Mark matched before any tier expires.
        promotion.markMatched()
        clockRef[0] += 600_000L
        delay(60L)

        assertTrue(promotion.isMatched)
        // The loop should have exited without promoting (still in pvp-4).
        assertEquals("pvp-4", promotion.latestPoolName, "match flag should stop promotion")
        promotion.cancel()
    }

    @Test
    fun cancelStopsTheLoop() = runBlocking {
        val ladder = MatchmakingLadder.DEFAULT
        val clockRef = longArrayOf(0L)
        val clock: () -> Long = { clockRef[0] }

        val promotion = PromotionLadder(
            scope = scope,
            ladder = ladder,
            matchTickets = fake,
            namespace = "test-ns",
            attributes = emptyMap(),
            latencies = emptyMap(),
            pollIntervalMs = 25L,
            clock = clock
        )
        promotion.latestTicketId = "initial-1"
        promotion.start()
        promotion.cancel()
        clockRef[0] += 600_000L
        delay(60L)

        assertEquals("pvp-4", promotion.latestPoolName, "cancelled before any promotion happened")
    }
}

/**
 * Test double for the match2 ticket wrapper. The first `createMatchTicket`
 * after a `deleteMatchTicket` is always assigned the next sequential id
 * (`ticket-2`, `ticket-3`, …) so the test can assert on the ladder's
 * behavior without coupling to a real SDK.
 */
private class RecordingMatchTickets : MatchTicketsApi
{
    val deletedTicketIds = mutableListOf<String>()
    private var createCounter = 1
    private val ticketIds = mutableListOf("ticket-2", "ticket-3", "ticket-4", "ticket-5")

    override fun createMatchTicket(op: CreateMatchTicket): ApiMatchTicketResponse
    {
        val id = if(ticketIds.isEmpty()) "ticket-extra" else ticketIds.removeAt(0)
        return ApiMatchTicketResponse().apply { matchTicketID = id }
    }

    override fun deleteMatchTicket(op: DeleteMatchTicket)
    {
        deletedTicketIds += op.ticketid
    }
}