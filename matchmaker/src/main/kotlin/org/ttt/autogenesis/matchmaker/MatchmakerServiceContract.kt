package org.ttt.autogenesis.matchmaker

/**
 * Mirror of the AccelByte match2 `Service` gRPC contract, defined as a plain
 * Kotlin interface so the algorithm and the gRPC transport can be developed
 * independently. A future PR will swap this for a protoc-generated
 * `io.grpc.BindableService` once the [protobuf-gradle-plugin] is wired into
 * the build; the algorithm itself is unaffected.
 *
 * The wire-format contract lives in `src/main/proto/accelbyte_matchmaker.proto`.
 * The proto is the source of truth for the gRPC transport; this interface is
 * the source of truth for the algorithm.
 */
interface MatchmakerServiceContract
{
    /**
     * Validates a single ticket. Returns the validation decision the platform
     * uses to accept or reject the ticket from the pool.
     */
    fun validate(tickets: List<MatchTicket>): ValidateResponse

    /**
     * Consumes a batch of tickets and returns the proposals the matchmaker
     * wants the platform to materialize into game sessions. Returning an
     * empty list is a valid response (the platform will keep the stream
     * open and call again on the next tick).
     */
    fun makeMatches(tickets: List<MatchTicket>, matchPool: String): List<ProposedMatch>
}

/** Validation result for a single ticket. */
sealed class ValidateResponse
{
    /** Ticket is accepted into the pool. */
    data object Accept : ValidateResponse()

    /** Ticket is rejected; the platform will not include it in proposals. */
    data class Reject(val reason: String) : ValidateResponse()
}

/**
 * Default implementation backed by [MatchmakingAlgorithm]. Validation only
 * checks that the required attribute fields are present and within the
 * allowed range; the platform performs any other structural checks.
 */
class DefaultMatchmakerService(
    private val policy: AlgorithmPolicy = AlgorithmPolicy.DEFAULT,
    private val maxSubsidy: Int = 4
) : MatchmakerServiceContract
{
    private val algorithm = MatchmakingAlgorithm(policy)

    override fun validate(tickets: List<MatchTicket>): ValidateResponse
    {
        for(ticket in tickets)
        {
            if(ticket.subsidy < 0 || ticket.subsidy > maxSubsidy)
            {
                return ValidateResponse.Reject("subsidy out of range [0,$maxSubsidy]: ${ticket.subsidy}")
            }
            if(ticket.costClass !in ALLOWED_COST_CLASSES)
            {
                return ValidateResponse.Reject("unknown cost_class='${ticket.costClass}' for ticket ${ticket.ticketId}")
            }
        }
        return ValidateResponse.Accept
    }

    override fun makeMatches(tickets: List<MatchTicket>, matchPool: String): List<ProposedMatch>
    {
        val family = tickets.firstOrNull()?.ladderFamily ?: "pvp"
        val targets = listOf(4, 3, 2)
        return algorithm.proposeAll(
            tickets = tickets.filter { it.ladderFamily == family },
            targets = targets
        )
    }

    private companion object
    {
        val ALLOWED_COST_CLASSES = setOf("BYO_KEY", "PRO", "CASUAL", "CREDIT", "FREE")
    }
}