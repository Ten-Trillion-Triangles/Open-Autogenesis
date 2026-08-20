package org.ttt.autogenesis.matchmaker

import io.grpc.BindableService
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import net.accelbyte.matchmakingv2.matchfunction.GetStatCodesRequest
import net.accelbyte.matchmakingv2.matchfunction.MatchFunctionGrpcKt
import net.accelbyte.matchmakingv2.matchfunction.MatchResponse
import net.accelbyte.matchmakingv2.matchfunction.Rules
import net.accelbyte.matchmakingv2.matchfunction.MakeMatchesRequest
import net.accelbyte.matchmakingv2.matchfunction.Scope
import net.accelbyte.matchmakingv2.matchfunction.StatCodesResponse
import net.accelbyte.matchmakingv2.matchfunction.Ticket
import net.accelbyte.matchmakingv2.matchfunction.ValidateTicketRequest
import net.accelbyte.matchmakingv2.matchfunction.ValidateTicketResponse
import net.accelbyte.matchmakingv2.matchfunction.EnrichTicketRequest
import net.accelbyte.matchmakingv2.matchfunction.EnrichTicketResponse
import net.accelbyte.matchmakingv2.matchfunction.BackfillMakeMatchesRequest
import net.accelbyte.matchmakingv2.matchfunction.BackfillResponse
import com.google.protobuf.Timestamp
import com.google.protobuf.Struct
import com.google.protobuf.Value
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TDD contract tests for Block-1.
 *
 * Asserts that the matchmaker gRPC server exposes the upstream
 * AccelByte `MatchFunction` contract from
 * `AccelByte/matchmaking-function-grpc-plugin-server-java:main/src/main/proto/matchFunction.proto`
 * (v1.1.0) — NOT the legacy vendored 2-RPC `Service` sketch.
 *
 * These tests are the RED step of TDD for Block-1: they MUST fail before
 * `MatchmakerGrpcService` is rewritten against the upstream proto, and they
 * MUST pass after.
 *
 * Contract pinpoints (from the upstream proto):
 *  - service name  : `MatchFunction`
 *  - package       : `accelbyte.matchmaking.matchfunction`
 *  - java_package  : `net.accelbyte.matchmakingv2.matchfunction`
 *  - 5 RPCs        : `GetStatCodes`, `ValidateTicket`, `EnrichTicket`,
 *                    `MakeMatches`, `BackfillMatches`
 *  - MakeMatches   : bidi-stream; first frame MUST carry `parameters`
 *                    (Scope + Rules + tickId), subsequent frames carry
 *                    `Ticket`.
 *
 * If the generated `MatchFunctionGrpcKt` is missing or the generated service
 * base class doesn't include all 5 RPCs, the tests below fail to compile —
 * that's the RED signal the orchestrator wants.
 */
class MatchmakerUpstreamContractTest
{
    private val serverName: String = "matchmaker-upstream-contract-test-${System.nanoTime()}"
    private lateinit var server: io.grpc.Server
    private lateinit var channel: io.grpc.ManagedChannel
    private lateinit var stub: MatchFunctionGrpcKt.MatchFunctionCoroutineStub

    @Before
    fun setUp()
    {
        // The TDD fix replaces the old 2-RPC `ServiceGrpcKt.ServiceCoroutineImplBase`
        // with the upstream 5-RPC `MatchFunctionGrpcKt.MatchFunctionCoroutineImplBase`.
        // If the rewrite hasn't landed yet, this cast fails to compile — that's
        // the RED signal.
        @Suppress("UNCHECKED_CAST")
        val serviceImpl = MatchmakerGrpcService() as BindableService
        server = InProcessServerBuilder.forName(serverName)
            .addService(serviceImpl)
            .build()
            .start()
        channel = InProcessChannelBuilder.forName(serverName).build()
        stub = MatchFunctionGrpcKt.MatchFunctionCoroutineStub(channel)
    }

    @After
    fun tearDown()
    {
        channel.shutdownNow()
        server.shutdownNow()
        server.awaitTermination()
    }

    // -------------------------------------------------------------------
    // Contract: the upstream proto package is the one and only MatchFunction.
    // -------------------------------------------------------------------

    @Test
    fun packageIsAccelByteMatchmakingV2()
    {
        // The generated Stub class lives in the upstream package.
        // If anyone reverts to the legacy `net.accelbyte.matchmaker.proto`
        // package, this assertion fails.
        assertNotNull(MatchFunctionGrpcKt.MatchFunctionCoroutineStub::class.java)
        assertEquals(
            "net.accelbyte.matchmakingv2.matchfunction",
            MatchFunctionGrpcKt.MatchFunctionCoroutineStub::class.java.`package`.name
        )
    }

    // -------------------------------------------------------------------
    // GetStatCodes: returns empty codes list (no custom stat codes yet).
    // -------------------------------------------------------------------

    @Test
    fun getStatCodesReturnsEmpty() = runBlocking {
        val req = GetStatCodesRequest.newBuilder()
            .setRules(Rules.newBuilder().setJson("{}").build())
            .build()
        val resp: StatCodesResponse = stub.getStatCodes(req)
        assertNotNull(resp, "GetStatCodes must return a response")
        assertEquals(0, resp.codesCount, "Matchmaker does not advertise custom stat codes yet")
    }

    // -------------------------------------------------------------------
    // ValidateTicket: returns valid_ticket=true when subsidy in range and
    // cost_class is known. Rejects otherwise.
    // -------------------------------------------------------------------

    @Test
    fun validateTicketAcceptsWellFormedTicket() = runBlocking {
        val ticket = ticketWith(
            ticketId = "t-valid",
            matchPool = "pvp-4",
            costClass = "PRO",
            subsidy = 2L,
            isByoKey = false
        )
        val req = ValidateTicketRequest.newBuilder()
            .setTicket(ticket)
            .setRules(Rules.newBuilder().setJson("{}").build())
            .build()
        val resp: ValidateTicketResponse = stub.validateTicket(req)
        assertTrue(resp.validTicket, "A well-formed ticket must validate as valid")
    }

    @Test
    fun validateTicketRejectsSubsidyOutOfRange() = runBlocking {
        val ticket = ticketWith(
            ticketId = "t-bad-subsidy",
            matchPool = "pvp-4",
            costClass = "PRO",
            subsidy = 99L, // max allowed is 4
            isByoKey = false
        )
        val req = ValidateTicketRequest.newBuilder()
            .setTicket(ticket)
            .setRules(Rules.newBuilder().setJson("{}").build())
            .build()
        val resp: ValidateTicketResponse = stub.validateTicket(req)
        assertEquals(false, resp.validTicket, "Subsidy out of range must be rejected")
    }

    @Test
    fun validateTicketRejectsUnknownCostClass() = runBlocking {
        val ticket = ticketWith(
            ticketId = "t-bad-class",
            matchPool = "pvp-4",
            costClass = "UNICORN",
            subsidy = 1L,
            isByoKey = false
        )
        val req = ValidateTicketRequest.newBuilder()
            .setTicket(ticket)
            .setRules(Rules.newBuilder().setJson("{}").build())
            .build()
        val resp: ValidateTicketResponse = stub.validateTicket(req)
        assertEquals(false, resp.validTicket, "Unknown cost_class must be rejected")
    }

    // -------------------------------------------------------------------
    // EnrichTicket: returns the same ticket (no enrichment yet). Round-trip.
    // -------------------------------------------------------------------

    @Test
    fun enrichTicketRoundTripReturnsSameTicket() = runBlocking {
        val original = ticketWith(
            ticketId = "t-enrich",
            matchPool = "pvp-4",
            costClass = "PRO",
            subsidy = 2L,
            isByoKey = false
        )
        val req = EnrichTicketRequest.newBuilder()
            .setTicket(original)
            .setRules(Rules.newBuilder().setJson("{}").build())
            .build()
        val resp: EnrichTicketResponse = stub.enrichTicket(req)
        assertEquals(original.ticketId, resp.ticket.ticketId, "ticket id must round-trip")
        assertEquals(original.matchPool, resp.ticket.matchPool, "match pool must round-trip")
    }

    // -------------------------------------------------------------------
    // MakeMatches: bidi-stream. First frame carries `parameters` (Scope,
    // Rules, tickId). Subsequent frames carry `Ticket`. Server streams back
    // MatchResponse per match.
    // -------------------------------------------------------------------

    @Test
    fun makeMatchesStreamEmitsMatchResponseWhenEnoughTickets() = runBlocking {
        val params = MakeMatchesRequest.MakeMatchesParameters.newBuilder()
            .setScope(Scope.newBuilder().setAbTraceId("trace-1").build())
            .setRules(Rules.newBuilder().setJson("{}").build())
            .setTickId(1L)
            .build()
        val paramsFrame = MakeMatchesRequest.newBuilder().setParameters(params).build()
        // The proto oneof `request_type` allows EITHER `parameters` OR
        // `ticket` per `MakeMatchesRequest` frame. Sending 4 tickets means
        // 4 frames after the parameters frame.
        val ticket1 = MakeMatchesRequest.newBuilder().setTicket(ticketWith("t-byo", "pvp-4", "BYO_KEY", 4L, true)).build()
        val ticket2 = MakeMatchesRequest.newBuilder().setTicket(ticketWith("t-pro-1", "pvp-4", "PRO", 2L, false)).build()
        val ticket3 = MakeMatchesRequest.newBuilder().setTicket(ticketWith("t-pro-2", "pvp-4", "PRO", 2L, false)).build()
        val ticket4 = MakeMatchesRequest.newBuilder().setTicket(ticketWith("t-free", "pvp-4", "FREE", 0L, false)).build()

        val responses: List<MatchResponse> = stub.makeMatches(
            flowOf(paramsFrame, ticket1, ticket2, ticket3, ticket4)
        ).toList()

        // We have 4 tickets for pvp-4. The algorithm should emit at least one
        // MatchResponse.
        assertTrue(responses.isNotEmpty(), "Expected at least one MatchResponse when 4 tickets are available")
        val firstMatch = responses.first().match
        assertEquals(4, firstMatch.ticketsCount, "pvp-4 match should contain 4 tickets")
        // Ticket ids should match what we sent.
        val sentIds = setOf("t-byo", "t-pro-1", "t-pro-2", "t-free")
        assertEquals(
            sentIds,
            firstMatch.ticketsList.map { it.ticketId }.toSet(),
            "Match must contain exactly the four tickets we sent"
        )
        // backfill=false for a full 4-ticket match.
        assertEquals(false, firstMatch.backfill, "A full 4-ticket match should not request backfill")
    }

    @Test
    fun makeMatchesStreamEmitsEmptyResponseWhenInsufficientTickets() = runBlocking {
        val params = MakeMatchesRequest.MakeMatchesParameters.newBuilder()
            .setScope(Scope.newBuilder().setAbTraceId("trace-2").build())
            .setRules(Rules.newBuilder().setJson("{}").build())
            .setTickId(2L)
            .build()
        val paramsFrame = MakeMatchesRequest.newBuilder().setParameters(params).build()
        // Only one ticket — not enough for any of the 4/3/2 targets.
        val ticketFrame = MakeMatchesRequest.newBuilder()
            .setTicket(ticketWith("t-alone", "pvp-4", "PRO", 2L, false))
            .build()

        val responses = stub.makeMatches(flowOf(paramsFrame, ticketFrame)).toList()
        assertTrue(responses.isEmpty(), "Single-ticket batch must not produce any match")
    }

    // -------------------------------------------------------------------
    // BackfillMatches: bidi-stream. One backfill ticket + one joining
    // ticket → one BackfillResponse. Empty when no pair available.
    // -------------------------------------------------------------------

    @Test
    fun backfillMatchesStreamEmitsResponseWhenPairAvailable() = runBlocking {
        val params = BackfillMakeMatchesRequest.MakeMatchesParameters.newBuilder()
            .setScope(Scope.newBuilder().setAbTraceId("trace-3").build())
            .setRules(Rules.newBuilder().setJson("{}").build())
            .setTickId(3L)
            .build()
        val paramsFrame = BackfillMakeMatchesRequest.newBuilder().setParameters(params).build()
        val backfillTicketFrame = BackfillMakeMatchesRequest.newBuilder()
            .setBackfillTicket(
                net.accelbyte.matchmakingv2.matchfunction.BackfillTicket.newBuilder()
                    .setTicketId("bf-1")
                    .setMatchPool("pvp-4")
                    .setMatchSessionId("session-xyz")
                    .setCreatedAt(Timestamp.newBuilder().setSeconds(1_700_000_000L).build())
                    .setPartialMatch(
                        net.accelbyte.matchmakingv2.matchfunction.BackfillTicket.PartialMatch.newBuilder()
                            .addTickets(ticketWith("t-existing", "pvp-4", "PRO", 2L, false))
                            .setBackfill(true)
                            .build()
                    )
                    .build()
            )
            .build()
        val joiningTicketFrame = BackfillMakeMatchesRequest.newBuilder()
            .setTicket(ticketWith("t-joiner", "pvp-4", "PRO", 2L, false))
            .build()

        val responses: List<BackfillResponse> = stub.backfillMatches(
            flowOf(paramsFrame, backfillTicketFrame, joiningTicketFrame)
        ).toList()

        assertTrue(responses.isNotEmpty(), "Expected at least one BackfillResponse when pair is available")
        val firstProposal = responses.first().backfillProposal
        assertEquals("bf-1", firstProposal.backfillTicketId)
        assertEquals("pvp-4", firstProposal.matchPool)
    }

    @Test
    fun backfillMatchesStreamEmitsEmptyWhenNoBackfillTickets() = runBlocking {
        val params = BackfillMakeMatchesRequest.MakeMatchesParameters.newBuilder()
            .setScope(Scope.newBuilder().setAbTraceId("trace-4").build())
            .setRules(Rules.newBuilder().setJson("{}").build())
            .setTickId(4L)
            .build()
        val paramsFrame = BackfillMakeMatchesRequest.newBuilder().setParameters(params).build()

        val responses = stub.backfillMatches(flowOf(paramsFrame)).toList()
        assertTrue(responses.isEmpty(), "Empty backfill stream must produce no responses")
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    /**
     * Build an upstream `Ticket` from the algorithm-side `MatchTicket` shape.
     * Encodes the cost-class / subsidy / byo-key fields as a Struct in
     * `ticket_attributes` so the matchmaker can read them the same way
     * server-extend stamps them on the wire.
     */
    private fun ticketWith(
        ticketId: String,
        matchPool: String,
        costClass: String,
        subsidy: Long,
        isByoKey: Boolean
    ): Ticket
    {
        val attrs = Struct.newBuilder()
            .putFields("cost_class", Value.newBuilder().setStringValue(costClass).build())
            .putFields("cost_subsidy", Value.newBuilder().setNumberValue(subsidy.toDouble()).build())
            .putFields("byo_api_key", Value.newBuilder().setBoolValue(isByoKey).build())
            .build()
        return Ticket.newBuilder()
            .setTicketId(ticketId)
            .setMatchPool(matchPool)
            .setNamespace("echoofmaridia-autogenesis")
            .setCreatedAt(Timestamp.newBuilder().setSeconds(1_700_000_000L).build())
            .putLatencies("us-east-2", 25L)
            .setTicketAttributes(attrs)
            .addPlayers(
                Ticket.PlayerData.newBuilder()
                    .setPlayerId("player-$ticketId")
                    .setAttributes(
                        Struct.newBuilder()
                            .putFields("elo", Value.newBuilder().setNumberValue(1500.0).build())
                            .build()
                    )
                    .build()
            )
            .build()
    }
}
