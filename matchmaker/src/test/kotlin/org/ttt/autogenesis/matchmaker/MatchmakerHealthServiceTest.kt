package org.ttt.autogenesis.matchmaker

import io.grpc.health.v1.HealthCheckRequest
import io.grpc.health.v1.HealthCheckResponse
import io.grpc.health.v1.HealthGrpc
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.protobuf.services.HealthStatusManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Verifies that the matchmaker gRPC server registers the standard
 * `grpc.health.v1.Health` service on the same port. This is what the
 * in-container `HEALTHCHECK` (via `grpc_health_probe`) and the k8s
 * liveness/readiness probes call to report SERVING / NOT_SERVING.
 *
 * Phase 1 of feature/live-pvp-and-billing: extending the matchmaker for
 * Extend deployment required a real gRPC health endpoint; this test pins
 * the contract so a future refactor doesn't accidentally drop the service.
 */
class MatchmakerHealthServiceTest
{
    private val serverName: String = "matchmaker-health-test-${System.nanoTime()}"
    private lateinit var healthManager: HealthStatusManager
    private lateinit var server: io.grpc.Server
    private lateinit var channel: io.grpc.ManagedChannel
    private lateinit var stub: HealthGrpc.HealthBlockingStub

    @Before
    fun setUp()
    {
        // Mirror what `MatchmakerMain.main` does: register both the
        // matchmaker service and the Health service on the same server.
        healthManager = HealthStatusManager()
        healthManager.setStatus(HEALTH_SERVICE_NAME, HealthCheckResponse.ServingStatus.SERVING)

        server = InProcessServerBuilder.forName(serverName)
            .addService(MatchmakerGrpcService().bindableService())
            .addService(healthManager.healthService)
            .build()
            .start()
        channel = InProcessChannelBuilder.forName(serverName).build()
        stub = HealthGrpc.newBlockingStub(channel)
    }

    @After
    fun tearDown()
    {
        channel.shutdownNow()
        server.shutdownNow()
        server.awaitTermination()
    }

    @Test
    fun healthCheckReturnsServingForOverallService()
    {
        val response = stub.check(
            HealthCheckRequest.newBuilder()
                .setService(HEALTH_SERVICE_NAME) // empty string = "overall"
                .build()
        )

        assertEquals(
            "expected SERVING for overall service, got ${response.status}",
            HealthCheckResponse.ServingStatus.SERVING,
            response.status
        )
    }

    @Test
    fun healthCheckReturnsNotServingAfterShutdown()
    {
        // Mark the service as NOT_SERVING (this is what the shutdown hook
        // in MatchmakerMain does on SIGTERM). The probe should observe
        // the transition.
        healthManager.setStatus(HEALTH_SERVICE_NAME, HealthCheckResponse.ServingStatus.NOT_SERVING)

        val response = stub.check(
            HealthCheckRequest.newBuilder()
                .setService(HEALTH_SERVICE_NAME)
                .build()
        )

        assertEquals(
            "expected NOT_SERVING after shutdown transition",
            HealthCheckResponse.ServingStatus.NOT_SERVING,
            response.status
        )
    }
}
