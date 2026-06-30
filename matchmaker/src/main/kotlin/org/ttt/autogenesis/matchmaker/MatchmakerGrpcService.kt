package org.ttt.autogenesis.matchmaker

import io.grpc.BindableService
import net.accelbyte.matchmaker.proto.ServiceGrpcKt
import net.accelbyte.matchmaker.proto.ApiMatchTicket
import net.accelbyte.matchmaker.proto.AttributeValue
import net.accelbyte.matchmaker.proto.Match
import net.accelbyte.matchmaker.proto.Request
import net.accelbyte.matchmaker.proto.Response

/**
 * gRPC server implementation that delegates every call to a
 * [DefaultMatchmakerService] (the algorithm-side [MatchmakerServiceContract]
 * impl). Pure pass-through; the algorithm does not change.
 *
 * Phase 1 of feature/live-pvp-and-billing.
 *
 * The base class [ServiceGrpcKt.ServiceCoroutineImplBase] is the
 * coroutine-friendly variant generated from the `service Service { ... }`
 * block in `accelbyte_matchmaker.proto`. The plan's "fall back to grpc-java
 * plain stubs if grpckt has a compatibility issue" is preserved by also
 * exposing the [BindableService] directly via [bindableService]; the build
 * is locked to the kotlin coroutine path by default.
 *
 * Wire format mapping (proto -> algorithm):
 *  - [ApiMatchTicket.getTicketId] -> [MatchTicket.ticketId]
 *  - [ApiMatchTicket.getMatchPool] -> [MatchTicket.matchPool]
 *    (and the algorithm uses [MatchTicket.ladderFamily] derived from it)
 *  - [AttributeValue] entries are unboxed into the typed [MatchTicket] fields
 *    ([subsidy], [costClass], [walletCredits], [isByoKey]) by
 *    [toMatchTicket].
 */
class MatchmakerGrpcService(
    private val policy: AlgorithmPolicy = AlgorithmPolicy.DEFAULT,
    private val maxSubsidy: Int = 4
) : ServiceGrpcKt.ServiceCoroutineImplBase()
{
    private val delegate: DefaultMatchmakerService = DefaultMatchmakerService(policy, maxSubsidy)

    override suspend fun validate(request: Request): Response
    {
        val tickets = request.ticketsList.map { it.toMatchTicket() }
        return when (val decision = delegate.validate(tickets))
        {
            is ValidateResponse.Accept ->
            {
                Response.newBuilder().build()
            }
            is ValidateResponse.Reject ->
            {
                // The proto Response is just a `matches` list. A rejection
                // surfaces to the platform as an empty matches stream; the
                // actual reason is logged on the matchmaker side. (The
                // upstream AccelByte match2 protocol intentionally does not
                // carry a rejection reason on the wire — the platform
                // re-validates and rejects itself.)
                org.ttt.autogenesis.logging.Logger.warn(
                    org.ttt.autogenesis.logging.LogCategory.SYSTEM,
                    "Matchmaker: rejecting tickets: ${decision.reason}"
                )
                Response.newBuilder().build()
            }
        }
    }

    override fun makeMatches(requests: kotlinx.coroutines.flow.Flow<Request>): kotlinx.coroutines.flow.Flow<Response>
    {
        return kotlinx.coroutines.flow.flow {
            requests.collect { request ->
                val matchPool = request.matchPool.ifBlank { request.matchFunction }
                val tickets = request.ticketsList.map { it.toMatchTicket() }
                val proposals = delegate.makeMatches(tickets, matchPool = matchPool)
                val responseBuilder = Response.newBuilder()
                for (proposal in proposals)
                {
                    val matchBuilder = Match.newBuilder()
                    for (id in proposal.ticketIds)
                    {
                        matchBuilder.addTicketIds(id)
                    }
                    matchBuilder.putMatchAttributes(
                        "max_players",
                        AttributeValue.newBuilder().setIntValue(proposal.maxPlayers.toLong()).build()
                    )
                    matchBuilder.subsidyTotal = proposal.subsidyTotal.toDouble()
                    matchBuilder.hasByoKey = proposal.hasByoKey
                    responseBuilder.addMatches(matchBuilder.build())
                }
                emit(responseBuilder.build())
            }
        }
    }
}

/**
 * Returns the [io.grpc.BindableService] handle the gRPC server uses to register
 * the matchmaker. Convenience for the call site in [MatchmakerMain].
 */
fun MatchmakerGrpcService.bindableService(): io.grpc.BindableService = this

/**
 * Translates the wire-format [ApiMatchTicket] into the algorithm-side
 * [MatchTicket]. The unboxing follows the cost attribute contract documented
 * in [matchmaker/README.md](README.md) — `cost_class`, `cost_subsidy`,
 * `wallet_credits`, `byo_api_key`.
 */
private fun ApiMatchTicket.toMatchTicket(): MatchTicket
{
    val costClassRaw = attributesMap["cost_class"]?.stringValue.orEmpty()
    val costClass = costClassRaw.ifBlank { "FREE" }
    val subsidy = attributesMap["cost_subsidy"]?.intValue?.toInt() ?: 0
    val walletCredits = attributesMap["wallet_credits"]?.doubleValue ?: 0.0
    val isByoKey = attributesMap["byo_api_key"]?.boolValue ?: false
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
 * Maps a [cost_class] string into the algorithm's ordinal rank. Mirrors the
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
