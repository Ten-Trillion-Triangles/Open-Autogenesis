package matchmaking

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.jupiter.api.Tag
import org.ttt.autogenesis.network.RestRpcClient
import org.ttt.autogenesis.network.RestRpcClientConfig
import org.ttt.autogenesis.network.RpcDirection
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcRegistry
import org.ttt.autogenesis.network.WebSocketRpcClient
import org.ttt.autogenesis.network.WebSocketRpcClientConfig
import java.io.File
import java.net.Socket
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 3 of feature/live-pvp-and-billing. End-to-end smoke test that drives
 * the full multiplayer matchmaking flow against a real AccelByte namespace:
 *
 *  1. Each of two `RestRpcClient` connections (representing two AccelByte
 *     users) calls `server.extend.requestGame` with
 *     `gameType = MULTIPLAYER, matchPool = "pvp-4"`.
 *  2. The test polls the response until both resolve to the same
 *     `serverUrl` (≤ 180 s).
 *  3. Each user opens a `WebSocketRpcClient` connection to the resolved DS
 *     and asserts `isSessionReady` flips within 30 s.
 *
 * Tagged `@Tag("sandbox")` and skipped by the default `test` task; opt in
 * with `./gradlew :server:testSandbox`. The test is slow (60–180 s) and
 * requires both server JVMs to be running on localhost:7070 (REST) and
 * localhost:9080 (WebSocket). The `checkServerExtendReachable` helper from
 * [accounting.BillingWorkflowIntegrationTest] confirms the second one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("sandbox")
class LiveMultiplayerSmokeTest
{
    private lateinit var props: SmokeProps

    @Before
    fun setUp()
    {
        props = SmokeProps.load()
        assumeTrue(
            "Live multiplayer smoke test requires AB_NAMESPACE / AB_BASE_URL / " +
                "AB_CLIENT_ID / AB_CLIENT_SECRET in env or " +
                "server/accelbyte.local.properties. Skipping.",
            props.valid
        )
        assumeTrue(
            "Live multiplayer smoke test requires server-extend running on " +
                "127.0.0.1:7070 (REST) and 127.0.0.1:9092 (gRPC). " +
                "Start it with: ./gradlew :server-extend:run. Skipping.",
            checkServerExtendReachablePreflight()
        )
    }

    @org.junit.Test
    fun twoPlayersResolveToTheSameServerUrl() = runBlockingWithTimeout(240_000L) {
        checkServerExtendReachable()

        val restClientA = restClient("smoke-A")
        val restClientB = restClient("smoke-B")
        try
        {
            restClientA.connect()
            restClientB.connect()

            val (urlA, urlB) = coroutineScope {
                val deferredA = async { pollServerUrl(restClientA, userId = "smoke-A") }
                val deferredB = async { pollServerUrl(restClientB, userId = "smoke-B") }
                deferredA.await() to deferredB.await()
            }
            assertTrue(urlA.isNotBlank(), "user A did not resolve a serverUrl within timeout")
            assertTrue(urlB.isNotBlank(), "user B did not resolve a serverUrl within timeout")
            assertEquals(urlA, urlB, "both users should land on the same DS")

            assertBothReachSessionReady(urlA)
        }
        finally
        {
            runCatching { restClientA.close() }
            runCatching { restClientB.close() }
        }
    }

    private fun restClient(playerId: String): RestRpcClient
    {
        return RestRpcClient(
            config = RestRpcClientConfig(
                baseUrl = "http://localhost:7070",
                playerId = playerId
            ),
            rpcRegistry = RpcRegistry(RpcDirection.CLIENT)
        )
    }

    private suspend fun pollServerUrl(client: RestRpcClient, userId: String): String
    {
        val deadline = System.currentTimeMillis() + 180_000L
        while (System.currentTimeMillis() < deadline)
        {
            val handle = client.rpcInvoker.request(
                method = "server.extend.requestGame",
                params = mapOf(
                    "userId" to userId,
                    "userName" to userId,
                    "gameType" to "MULTIPLAYER",
                    "matchPool" to "pvp-4"
                ),
                timeoutMillis = 5_000L
            )
            val response = handle.await()
            // The server.extend.requestGame response carries a `serverUrl`
            // (string) field. We pull it out via a minimal JsonElement lookup
            // so this test does not depend on the request payload type.
            val url = response.result
                ?.let { el -> el.toString() }
                ?.let { raw ->
                    Regex("\"serverUrl\"\\s*:\\s*\"([^\"]+)\"")
                        .find(raw)
                        ?.groupValues
                        ?.getOrNull(1)
                }
                .orEmpty()
            if (url.isNotBlank()) return url
            delay(2_000L)
        }
        return ""
    }

    private suspend fun assertBothReachSessionReady(serverUrl: String)
    {
        val wsUrl = serverUrl
            .replace("http://", "ws://")
            .replace("https://", "wss://")
        val wsClientA = openWebSocket(wsUrl, "smoke-A")
        val wsClientB = openWebSocket(wsUrl, "smoke-B")
        try
        {
            wsClientA.connect()
            wsClientB.connect()
            withTimeout(30_000L)
            {
                while (!wsClientA.isSessionReady() || !wsClientB.isSessionReady())
                {
                    delay(250L)
                }
            }
            assertTrue(wsClientA.isSessionReady(), "smoke-A WebSocket did not reach session-ready")
            assertTrue(wsClientB.isSessionReady(), "smoke-B WebSocket did not reach session-ready")
        }
        finally
        {
            runCatching { wsClientA.close() }
            runCatching { wsClientB.close() }
        }
    }

    private fun openWebSocket(serverUrl: String, playerId: String): WebSocketRpcClient
    {
        return WebSocketRpcClient(
            config = WebSocketRpcClientConfig(
                baseUrl = serverUrl,
                playerId = playerId
            ),
            rpcRegistry = RpcRegistry(RpcDirection.CLIENT)
        )
    }

    /**
     * Returns true when server-extend is reachable on BOTH the gRPC port
     * (`:9092`) and the REST port (`:7070`). Does NOT throw — designed for
     * the `@Before` `assumeTrue` gate so the test self-skips with a clear
     * message instead of crashing mid-test.
     *
     * Mirrors the socket probes in [checkServerExtendReachable], which IS
     * throwing and is used inside the test body to fail fast once we've
     * committed to running.
     */
    private fun checkServerExtendReachablePreflight(): Boolean
    {
        val grpcReachable = runCatching {
            Socket("127.0.0.1", 9092).use { true }
        }.getOrDefault(false)

        val restReachable = runCatching {
            Socket("127.0.0.1", 7070).use { true }
        }.getOrDefault(false)

        return grpcReachable && restReachable
    }

    /**
     * Confirms the REST (`:7070`) port is reachable before the test starts
     * its long polling loop. Mirrors the helper in
     * [accounting.BillingWorkflowIntegrationTest].
     */
    private fun checkServerExtendReachable()
    {
        val reachable = runCatching {
            Socket("127.0.0.1", 7070).use { true }
        }.getOrDefault(false)
        check(reachable) {
            "server-extend is not reachable on 127.0.0.1:7070. " +
                "Start it with: ./gradlew :server-extend:run"
        }
    }
}

private fun <T> runBlockingWithTimeout(timeoutMillis: Long, block: suspend () -> T): T
{
    return kotlinx.coroutines.runBlocking { withTimeout(timeoutMillis) { block() } }
}

/**
 * Reads the smoke test config from `server/accelbyte.local.properties` first,
 * then env vars. `valid` requires AB_NAMESPACE / AB_BASE_URL / AB_CLIENT_ID /
 * AB_CLIENT_SECRET.
 */
internal data class SmokeProps(
    val namespace: String,
    val baseUrl: String,
    val clientId: String,
    val clientSecret: String
)
{
    val valid: Boolean
        get() = namespace.isNotBlank() && baseUrl.isNotBlank() &&
            clientId.isNotBlank() && clientSecret.isNotBlank()

    companion object
    {
        fun load(): SmokeProps
        {
            val file = File("accelbyte.local.properties")
            val fileProps = Properties().apply {
                if (file.exists())
                {
                    file.inputStream().use { load(it) }
                }
            }
            fun pick(key: String): String =
                System.getenv(key) ?: System.getProperty(key) ?: fileProps.getProperty(key).orEmpty()
            return SmokeProps(
                namespace = pick("AB_NAMESPACE"),
                baseUrl = pick("AB_BASE_URL"),
                clientId = pick("AB_CLIENT_ID"),
                clientSecret = pick("AB_CLIENT_SECRET")
            )
        }
    }
}