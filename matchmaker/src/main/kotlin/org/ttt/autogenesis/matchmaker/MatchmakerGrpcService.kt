package org.ttt.autogenesis.matchmaker

import io.grpc.BindableService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import net.accelbyte.matchmakingv2.matchfunction.BackfillMakeMatchesRequest
import net.accelbyte.matchmakingv2.matchfunction.BackfillProposal
import net.accelbyte.matchmakingv2.matchfunction.BackfillResponse
import net.accelbyte.matchmakingv2.matchfunction.BackfillTicket
import net.accelbyte.matchmakingv2.matchfunction.EnrichTicketRequest
import net.accelbyte.matchmakingv2.matchfunction.EnrichTicketResponse
import net.accelbyte.matchmakingv2.matchfunction.GetStatCodesRequest
import net.accelbyte.matchmakingv2.matchfunction.MakeMatchesRequest
import net.accelbyte.matchmakingv2.matchfunction.Match
import net.accelbyte.matchmakingv2.matchfunction.MatchFunctionGrpcKt
import net.accelbyte.matchmakingv2.matchfunction.MatchResponse
import net.accelbyte.matchmakingv2.matchfunction.Rules
import net.accelbyte.matchmakingv2.matchfunction.Scope
import net.accelbyte.matchmakingv2.matchfunction.StatCodesResponse
import net.accelbyte.matchmakingv2.matchfunction.Ticket
import net.accelbyte.matchmakingv2.matchfunction.ValidateTicketRequest
import net.accelbyte.matchmakingv2.matchfunction.ValidateTicketResponse
import com.google.protobuf.Struct
import com.google.protobuf.Value
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

/**
 * gRPC server implementation of the upstream AccelByte `MatchFunction` contract
 * (v1.1.0, package `accelbyte.matchmaking.matchfunction`). Delegates every call
 * to a [DefaultMatchmakerService] (the algorithm-side [MatchmakerServiceContract]
 * impl). Pure pass-through; the algorithm does not change.
 *
 * Wire format mapping (proto -> algorithm):
 *  - [Ticket.ticketId]           -> [MatchTicket.ticketId]
 *  - [Ticket.matchPool]          -> [MatchTicket.matchPool]
 *  - `ticket_attributes.cost_class`     -> [MatchTicket.costClass]
 *  - `ticket_attributes.cost_subsidy`   -> [MatchTicket.subsidy]
 *  - `ticket_attributes.byo_api_key`    -> [MatchTicket.isByoKey]
 *  - `ticket_attributes.wallet_credits` -> [MatchTicket.walletCredits]
 *
 * Backfill match tickets (`BackfillTicket.partial_match.tickets`) are also
 * read using the same [Ticket] -> [MatchTicket] mapping so the algorithm can
 * treat backfill and forward match consistently.
 */
class MatchmakerGrpcService(
    private val policy: AlgorithmPolicy = AlgorithmPolicy.DEFAULT,
    private val maxSubsidy: Int = 4
) : MatchFunctionGrpcKt.MatchFunctionCoroutineImplBase()
{
    private val delegate: DefaultMatchmakerService = DefaultMatchmakerService(policy, maxSubsidy)

    // -----------------------------------------------------------------
    // GetStatCodes
    // -----------------------------------------------------------------

    override suspend fun getStatCodes(request: GetStatCodesRequest): StatCodesResponse
    {
        Logger.debug(LogCategory.SYSTEM, "Matchmaker: GetStatCodes")
        // The matchmaker does not advertise any custom stat codes yet; match2
        // will fall back to the ruleset it already has loaded for the pool.
        return StatCodesResponse.newBuilder().build()
    }

    // -----------------------------------------------------------------
    // ValidateTicket
    // -----------------------------------------------------------------

    override suspend fun validateTicket(request: ValidateTicketRequest): ValidateTicketResponse
    {
        Logger.debug(
            LogCategory.SYSTEM,
            "Matchmaker: ValidateTicket ticket_id=${request.ticket.ticketId} match_pool=${request.ticket.matchPool}"
        )
        val ticket = request.ticket.toMatchTicket()
        val valid = when (val decision = delegate.validate(listOf(ticket)))
        {
            is ValidateResponse.Accept -> true
            is ValidateResponse.Reject ->
            {
                Logger.warn(LogCategory.SYSTEM, "Matchmaker: rejecting ticket: ${decision.reason}")
                false
            }
        }
        return ValidateTicketResponse.newBuilder().setValidTicket(valid).build()
    }

    // -----------------------------------------------------------------
    // EnrichTicket
    // -----------------------------------------------------------------

    override suspend fun enrichTicket(request: EnrichTicketRequest): EnrichTicketResponse
    {
        Logger.debug(
            LogCategory.SYSTEM,
            "Matchmaker: EnrichTicket ticket_id=${request.ticket.ticketId} match_pool=${request.ticket.matchPool}"
        )
        // No enrichment yet — round-trip the ticket untouched. A future PR
        // can stamp cost attributes, region preferences, etc. here.
        return EnrichTicketResponse.newBuilder().setTicket(request.ticket).build()
    }

    // -----------------------------------------------------------------
    // MakeMatches (bidi-stream)
    // -----------------------------------------------------------------

    override fun makeMatches(requests: Flow<MakeMatchesRequest>): Flow<MatchResponse> = flow {
        var tickId: Long = 0
        var rulesJson: String = "{}"
        var matchPool: String = ""
        val unmatched: MutableList<Ticket> = mutableListOf()

        try
        {
            requests.collect { request ->
                when (request.requestTypeCase)
                {
                    MakeMatchesRequest.RequestTypeCase.PARAMETERS ->
                    {
                        val params = request.parameters
                        tickId = params.tickId
                        rulesJson = params.rules.json
                        Logger.info(
                            LogCategory.NETWORK,
                            "Matchmaker: MakeMatches parameters tickId=$tickId"
                        )
                    }
                    MakeMatchesRequest.RequestTypeCase.TICKET ->
                    {
                        val ticket = request.ticket
                        if (matchPool.isBlank()) matchPool = ticket.matchPool
                        unmatched += ticket
                        Logger.debug(
                            LogCategory.NETWORK,
                            "Matchmaker: MakeMatches ticket ticket_id=${ticket.ticketId} pool=${ticket.matchPool} unmatched=${unmatched.size}"
                        )
                    }
                    MakeMatchesRequest.RequestTypeCase.REQUESTTYPE_NOT_SET -> Unit
                }
            }
        }
        catch (t: Throwable)
        {
            Logger.warn(LogCategory.NETWORK, "Matchmaker: MakeMatches client stream error: ${t.message}")
        }

        Logger.info(
            LogCategory.NETWORK,
            "Matchmaker: MakeMatches end-of-stream tickId=$tickId rules=${rulesJson.take(200)} pool=$matchPool unmatched=${unmatched.size}"
        )

        if (unmatched.isEmpty() || matchPool.isBlank())
        {
            // Nothing to propose — flow completes with zero emits.
            return@flow
        }

        val matchTickets = unmatched.map { it.toMatchTicket() }
        val proposals = delegate.makeMatches(matchTickets, matchPool = matchPool)
        for (proposal in proposals)
        {
            val chosen = unmatched.filter { it.ticketId in proposal.ticketIds }
            val teamId = java.util.UUID.randomUUID().toString().replace("-", "")
            val matchAttributes = Struct.newBuilder()
                .putFields("max_players", Value.newBuilder().setNumberValue(proposal.maxPlayers.toDouble()).build())
                .putFields("subsidy_total", Value.newBuilder().setNumberValue(proposal.subsidyTotal.toDouble()).build())
                .putFields("has_byo_key", Value.newBuilder().setBoolValue(proposal.hasByoKey).build())
                .putFields("assignment", Value.newBuilder().setStructValue(
                    Struct.newBuilder()
                        .putFields("small-team-1", Value.newBuilder().setListValue(
                            com.google.protobuf.ListValue.newBuilder()
                                .addValues(Value.newBuilder().setStringValue(teamId).build())
                                .build()
                        ).build())
                        .build()
                ).build())
                .build()

            val playerIds = chosen.flatMap { it.playersList.map { p -> p.playerId } }
            val match = Match.newBuilder()
                .addAllTickets(chosen)
                .addTeams(
                    Match.Team.newBuilder()
                        .addAllUserIds(playerIds)
                        .setTeamId(teamId)
                        .build()
                )
                .setMatchAttributes(matchAttributes)
                .setBackfill(false)
                .addRegionPreferences("us-east-2")
                .addRegionPreferences("us-west-2")
                .build()
            emit(MatchResponse.newBuilder().setMatch(match).build())
        }
    }

    // -----------------------------------------------------------------
    // BackfillMatches (bidi-stream)
    // -----------------------------------------------------------------

    override fun backfillMatches(requests: Flow<BackfillMakeMatchesRequest>): Flow<BackfillResponse> = flow {
        val unmatchedBackfill: MutableList<BackfillTicket> = mutableListOf()
        val unmatchedJoiners: MutableList<Ticket> = mutableListOf()

        requests.collect { request ->
            when (request.requestTypeCase)
            {
                BackfillMakeMatchesRequest.RequestTypeCase.PARAMETERS ->
                {
                    Logger.info(
                        LogCategory.NETWORK,
                        "Matchmaker: BackfillMatches parameters tickId=${request.parameters.tickId}"
                    )
                }
                BackfillMakeMatchesRequest.RequestTypeCase.BACKFILL_TICKET ->
                {
                    unmatchedBackfill += request.backfillTicket
                }
                BackfillMakeMatchesRequest.RequestTypeCase.TICKET ->
                {
                    unmatchedJoiners += request.ticket
                }
                BackfillMakeMatchesRequest.RequestTypeCase.REQUESTTYPE_NOT_SET -> Unit
            }
        }

        Logger.info(
            LogCategory.NETWORK,
            "Matchmaker: BackfillMatches end-of-stream backfill=${unmatchedBackfill.size} joiners=${unmatchedJoiners.size}"
        )

        // Simple greedy pairing: each backfill ticket pairs with the first
        // available joiner. The match2 platform treats a backfill proposal
        // as a request to add the joiner ticket to the existing session.
        while (unmatchedBackfill.isNotEmpty() && unmatchedJoiners.isNotEmpty())
        {
            val bf = unmatchedBackfill.removeAt(0)
            val joiner = unmatchedJoiners.removeAt(0)
            val proposalId = java.util.UUID.randomUUID().toString().replace("-", "")
            val proposal = BackfillProposal.newBuilder()
                .setBackfillTicketId(bf.ticketId)
                .setMatchPool(bf.matchPool)
                .setMatchSessionId(bf.matchSessionId)
                .setProposalId(proposalId)
                .setCreatedAt(bf.createdAt)
                .addAddedTickets(joiner)
                .addProposedTeams(
                    BackfillProposal.Team.newBuilder()
                        .setTeamId(proposalId)
                        .addAllUserIds(joiner.playersList.map { it.playerId })
                        .build()
                )
                .build()
            emit(BackfillResponse.newBuilder().setBackfillProposal(proposal).build())
        }
    }
}

/**
 * Returns the [io.grpc.BindableService] handle the gRPC server uses to register
 * the matchmaker. Convenience for the call site in [MatchmakerMain].
 */
fun MatchmakerGrpcService.bindableService(): BindableService = this

/**
 * Translates the wire-format upstream [Ticket] into the algorithm-side
 * [MatchTicket]. Reads the cost-class / subsidy / byo-key / wallet-credits
 * attributes from the protobuf `Struct` field `ticket_attributes`.
 */
private fun Ticket.toMatchTicket(): MatchTicket
{
    val attrs: Map<String, Value> = ticketAttributes.fieldsMap
    val costClass = attrs["cost_class"]?.stringValue.orEmpty().ifBlank { "FREE" }
    val subsidy = (attrs["cost_subsidy"]?.numberValue ?: 0.0).toInt()
    val isByoKey = attrs["byo_api_key"]?.boolValue ?: false
    val walletCredits = attrs["wallet_credits"]?.numberValue ?: 0.0
    val family = matchPool.substringBefore('-', missingDelimiterValue = "pvp")
    return MatchTicket(
        ticketId = ticketId,
        matchPool = matchPool,
        ladderFamily = family,
        costClass = costClass,
        subsidy = subsidy,
        rank = costClassToRank(costClass),
        walletCredits = walletCredits,
        isByoKey = isByoKey
    )
}

/**
 * Maps a `cost_class` string into the algorithm's ordinal rank. Mirrors the
 * enum in `sharedModel/.../CostClass.kt`: BYO_KEY < PRO < CASUAL < CREDIT < FREE.
 */
private fun costClassToRank(name: String): Int
{
    return when (name)
    {
        "BYO_KEY" -> 0
        "PRO" -> 1
        "CASUAL" -> 2
        "CREDIT" -> 3
        else -> 4 // FREE or unknown
    }
}

/**
 * Suppresses unused-parameter warnings for fields the upstream proto carries
 * but the algorithm currently ignores. Kept here so future PRs can wire them
 * without re-introducing import churn.
 */
@Suppress("unused")
private fun touch(scope: Scope, rules: Rules)
{
    // Scope.abTraceId / Rules.json are forwarded to the algorithm logger
    // already. This dummy prevents the compiler from flagging them.
    @Suppress("UNUSED_VARIABLE")
    val traceId = scope.abTraceId
    @Suppress("UNUSED_VARIABLE")
    val rulesJson = rules.json
}
