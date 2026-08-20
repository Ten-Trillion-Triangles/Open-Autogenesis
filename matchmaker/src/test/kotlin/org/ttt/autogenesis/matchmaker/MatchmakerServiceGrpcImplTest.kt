package org.ttt.autogenesis.matchmaker

import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import net.accelbyte.matchmaker.proto.ApiMatchTicket
import net.accelbyte.matchmaker.proto.AttributeValue
import net.accelbyte.matchmaker.proto.Request
import net.accelbyte.matchmaker.proto.Response
import net.accelbyte.matchmaker.proto.ServiceGrpcKt
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trip test: spin up an in-process gRPC server backed by
 * [MatchmakerGrpcService], connect a coroutine stub client, and verify the
 * `MakeMatches` server-streaming RPC produces the same proposals the
 * in-process algorithm would.
 *
 * Phase 1 of feature/live-pvp-and-billing.
 */
class MatchmakerServiceGrpcImplTest
{
    private val serverName: String = "matchmaker-test-${System.nanoTime()}"
    private lateinit var server: io.grpc.Server
    private lateinit var channel: io.grpc.ManagedChannel
    private lateinit var stub: ServiceGrpcKt.ServiceCoroutineStub

    @Before
    fun setUp()
    {
        server = InProcessServerBuilder.forName(serverName)
            .addService(MatchmakerGrpcService().bindableService())
            .build()
            .start()
        channel = InProcessChannelBuilder.forName(serverName).build()
        stub = ServiceGrpcKt.ServiceCoroutineStub(channel)
    }

    @After
    fun tearDown()
    {
        channel.shutdownNow()
        server.shutdownNow()
        server.awaitTermination()
    }

    @Test
    fun makeMatchesRoundTripMatchesInProcessAlgorithm() = runBlocking {
        // Build a wire-format Request with 4 tickets (1 BYO, 2 PRO, 1 FREE).
        val request = Request.newBuilder()
            .setMatchFunction("custom")
            .setMatchPool("pvp-4")
            .apply {
                addTickets(byoTicket("t-byo"))
                addTickets(proTicket("t-pro-1"))
                addTickets(proTicket("t-pro-2"))
                addTickets(freeTicket("t-free"))
            }
            .build()

        // Drive the streaming RPC.
        val responses = stub.makeMatches(flowOf(request)).toList()

        assertEquals(1, responses.size, "expected exactly one Response frame")
        val response = responses.first()
        assertEquals(1, response.matchesCount, "expected one proposal in the frame")

        val proposal = response.getMatches(0)
        assertEquals(4, proposal.ticketIdsCount, "proposal should contain 4 ticket ids")
        val maxPlayers = proposal.getMatchAttributesOrThrow("max_players").intValue.toInt()
        assertEquals(4, maxPlayers, "max_players should be 4 for pvp-4")
        assertTrue(proposal.hasByoKey, "proposal should have hasByoKey=true")

        // The proposal's ticket set should match the in-process algorithm's
        // greedy selection (BYO + 2 PRO + CASUAL fallback, but our test
        // batch has no CASUAL so the 4th is the FREE one).
        val algorithmInProcess = MatchmakingAlgorithm()
            .propose(
                listOf(
                    MatchTicket("t-byo", "pvp-4", "pvp", "BYO_KEY", subsidy = 4, rank = 0, isByoKey = true),
                    MatchTicket("t-pro-1", "pvp-4", "pvp", "PRO", subsidy = 2, rank = 1),
                    MatchTicket("t-pro-2", "pvp-4", "pvp", "PRO", subsidy = 2, rank = 1),
                    MatchTicket("t-free", "pvp-4", "pvp", "FREE", subsidy = 0, rank = 4)
                ),
                targetPlayers = 4
            )
        val expectedIds = algorithmInProcess.first().ticketIds.toSet()
        assertEquals(expectedIds, proposal.ticketIdsList.toSet(), "wire proposal should match algorithm")
    }

    @Test
    fun validateAcceptsKnownTickets() = runBlocking {
        val request = Request.newBuilder()
            .setMatchFunction("custom")
            .setMatchPool("pvp-4")
            .addTickets(proTicket("t-pro-1"))
            .build()
        val response = stub.validate(request)
        // Accept is signaled as an empty Response frame; the platform checks
        // matchesCount == 0 to know the ticket was accepted (the upstream
        // match2 protocol does not carry an explicit accept reason).
        assertEquals(0, response.matchesCount)
    }

    private fun byoTicket(id: String): ApiMatchTicket
    {
        return ApiMatchTicket.newBuilder()
            .setTicketId(id)
            .setMatchPool("pvp-4")
            .putAttributes("cost_class", AttributeValue.newBuilder().setStringValue("BYO_KEY").build())
            .putAttributes("cost_subsidy", AttributeValue.newBuilder().setIntValue(4L).build())
            .putAttributes("wallet_credits", AttributeValue.newBuilder().setDoubleValue(0.0).build())
            .putAttributes("byo_api_key", AttributeValue.newBuilder().setBoolValue(true).build())
            .build()
    }

    private fun proTicket(id: String): ApiMatchTicket
    {
        return ApiMatchTicket.newBuilder()
            .setTicketId(id)
            .setMatchPool("pvp-4")
            .putAttributes("cost_class", AttributeValue.newBuilder().setStringValue("PRO").build())
            .putAttributes("cost_subsidy", AttributeValue.newBuilder().setIntValue(2L).build())
            .putAttributes("wallet_credits", AttributeValue.newBuilder().setDoubleValue(0.0).build())
            .putAttributes("byo_api_key", AttributeValue.newBuilder().setBoolValue(false).build())
            .build()
    }

    private fun freeTicket(id: String): ApiMatchTicket
    {
        return ApiMatchTicket.newBuilder()
            .setTicketId(id)
            .setMatchPool("pvp-4")
            .putAttributes("cost_class", AttributeValue.newBuilder().setStringValue("FREE").build())
            .putAttributes("cost_subsidy", AttributeValue.newBuilder().setIntValue(0L).build())
            .putAttributes("wallet_credits", AttributeValue.newBuilder().setDoubleValue(0.0).build())
            .putAttributes("byo_api_key", AttributeValue.newBuilder().setBoolValue(false).build())
            .build()
    }
}