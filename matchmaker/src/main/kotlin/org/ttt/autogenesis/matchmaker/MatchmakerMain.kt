package org.ttt.autogenesis.matchmaker

import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.protobuf.services.HealthStatusManager
import io.grpc.health.v1.HealthCheckResponse.ServingStatus
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.LogPriority
import org.ttt.autogenesis.logging.Logger
import java.util.concurrent.TimeUnit

/**
 * Standalone entry point for the matchmaker service.
 *
 * In v1 (pre-Phase 1 of feature/live-pvp-and-billing), the binary hosted an
 * algorithm-only smoke run. From Phase 1 forward, the binary binds the
 * `Service` gRPC contract on `MATCHMAKER_GRPC_PORT` (default 9095). The
 * algorithm-only smoke run is preserved behind a `--smoke` flag for operator
 * debugging and CI sanity.
 *
 * The server also registers the standard `grpc.health.v1.Health` service on
 * the same port. This is what `grpc_health_probe` (used by the k8s
 * liveness/readiness probes and by the in-container `HEALTHCHECK`) calls to
 * report SERVING / NOT_SERVING.
 *
 * Usage:
 *   java -jar matchmaker.jar                  # bind gRPC on 9095
 *   java -jar matchmaker.jar --smoke          # algorithm-only smoke run
 */
fun main(args: Array<String>)
{
    Logger.configure(LogPriority.DEBUG, true, maxLogFiles = 1, serverType = "matchmaker")

    val smoke = args.contains("--smoke")
    if (smoke)
    {
        runSmoke()
        return
    }

    val port = System.getenv("MATCHMAKER_GRPC_PORT")?.toIntOrNull() ?: DEFAULT_GRPC_PORT
    Logger.info(LogCategory.SYSTEM, "Matchmaker: starting gRPC server on port $port")

    // HealthStatusManager backs the `grpc.health.v1.Health` service. We
    // register the overall service (empty service name) as SERVING on
    // startup and NOT_SERVING on shutdown so an in-flight SIGTERM never
    // returns SERVING to a probe while the server is already draining.
    val healthManager = HealthStatusManager()
    healthManager.setStatus(HEALTH_SERVICE_NAME, ServingStatus.SERVING)

    val server = NettyServerBuilder.forPort(port)
        .addService(MatchmakerGrpcService().bindableService())
        .addService(healthManager.healthService)
        .permitKeepAliveTime(30, TimeUnit.SECONDS)
        .permitKeepAliveWithoutCalls(true)
        .build()

    Runtime.getRuntime().addShutdownHook(Thread {
        Logger.info(LogCategory.SYSTEM, "Matchmaker: gRPC server shutting down")
        healthManager.setStatus(HEALTH_SERVICE_NAME, ServingStatus.NOT_SERVING)
        server.shutdown()
        if (!server.awaitTermination(5, TimeUnit.SECONDS))
        {
            server.shutdownNow()
        }
    })

    server.start()
    Logger.info(LogCategory.SYSTEM, "Matchmaker: gRPC server started, awaiting termination")
    server.awaitTermination()
}

/** Default gRPC port (matches the value in [matchmaker/README.md]). */
const val DEFAULT_GRPC_PORT: Int = 9095

/**
 * Service name reported to `grpc.health.v1.Health`. The empty string is the
 * gRPC convention for "the overall server"; `grpc_health_probe` queries
 * this by default with no `-service` flag.
 */
const val HEALTH_SERVICE_NAME: String = ""

/**
 * Runs the algorithm against a sample batch on a fixed schedule. Useful for
 * operator debugging and CI smoke tests; not what production uses.
 */
private fun runSmoke()
{
    Logger.info(LogCategory.SYSTEM, "Matchmaker: --smoke mode, running algorithm in-process (no gRPC bind)")

    val policy = AlgorithmPolicy.DEFAULT
    val service = DefaultMatchmakerService(policy)
    val algo = MatchmakingAlgorithm(policy)

    val sampleBatch: List<MatchTicket> = sampleBatch()
    val proposals = service.makeMatches(sampleBatch, matchPool = "pvp-4")
    Logger.info(
        LogCategory.SYSTEM,
        "Matchmaker: sample makeMatches produced ${proposals.size} proposal(s) for batch of ${sampleBatch.size} tickets"
    )
    for (proposal in proposals)
    {
        Logger.info(
            LogCategory.SYSTEM,
            "Matchmaker: proposal maxPlayers=${proposal.maxPlayers} subsidyTotal=${proposal.subsidyTotal} hasByoKey=${proposal.hasByoKey} ticketIds=${proposal.ticketIds}"
        )
    }

    // Touch the algo so the IDE sees it wired up.
    @Suppress("UNUSED_VARIABLE")
    val _unused = algo.proposeAll(sampleBatch, listOf(4, 3, 2))

    Logger.info(LogCategory.SYSTEM, "Matchmaker: --smoke run complete")
}

// Smoke-test sample batch: 9 tickets so the algorithm can produce a full
// 4-ticket proposal (consuming 4), a 3-ticket proposal (consuming 3), and
// still has 2 tickets remaining for a 2-ticket proposal. Targets are
// attempted in descending order (proposeAll sorts descending), so this
// guarantees all three target sizes (4, 3, 2) can run without the
// `Cannot propose matches with N tickets for target=K` require() tripping.
private fun sampleBatch(): List<MatchTicket> = listOf(
    MatchTicket("t-1", "pvp-4", "pvp", "BYO_KEY", subsidy = 4, rank = 0, isByoKey = true),
    MatchTicket("t-2", "pvp-4", "pvp", "PRO",     subsidy = 2, rank = 1),
    MatchTicket("t-3", "pvp-4", "pvp", "PRO",     subsidy = 2, rank = 1),
    MatchTicket("t-4", "pvp-4", "pvp", "CASUAL",  subsidy = 1, rank = 2),
    MatchTicket("t-5", "pvp-4", "pvp", "FREE",    subsidy = 0, rank = 4),
    MatchTicket("t-6", "pvp-4", "pvp", "CASUAL",  subsidy = 1, rank = 3),
    MatchTicket("t-7", "pvp-4", "pvp", "PRO",     subsidy = 2, rank = 1),
    MatchTicket("t-8", "pvp-4", "pvp", "FREE",    subsidy = 0, rank = 4),
    MatchTicket("t-9", "pvp-4", "pvp", "CASUAL",  subsidy = 1, rank = 3)
)
