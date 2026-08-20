package network

import org.ttt.autogenesis.network.RpcDirection
import org.ttt.autogenesis.network.RpcOrigin
import org.ttt.autogenesis.network.RpcRegistry
import org.ttt.autogenesis.network.RestRpcClient
import org.ttt.autogenesis.network.RestRpcClientConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Wire-level contract tests for [RestRpcClientConfig.buildEndpointUrl].
 *
 * Pinning these four cases defends the resume-game flow against silent
 * regressions: server-extend's SSE handler reads `accelbyteId` off the
 * query string (`server-extend/ServerExtend.kt:268`) and short-circuits
 * the resume push if it is blank, so the URL the client opens MUST carry
 * a non-empty `accelbyteId=` whenever one is available. This file is the
 * RED-phase counterpart to Fix 1 of the resume-game audit plan.
 *
 * The test reaches into [RestRpcClient.buildEndpointUrl] via the
 * `internal` visibility seam that was widened solely for testability —
 * no production call site is expected to invoke this method directly.
 */
class RestRpcClientConfigUrlTest
{
    private fun clientFor(cfg: RestRpcClientConfig): RestRpcClient =
        RestRpcClient(
            config = cfg,
            rpcRegistry = RpcRegistry(RpcDirection.SERVER)
        )

    /**
     * Primary regression pin for Fix 1.
     *
     * When the config carries an `accelbyteId`, the SSE URL the client
     * opens MUST include it as `accelbyteId=<value>` so server-extend's
     * `SSE /events` handler can pair the inbound with the user's running
     * snapshot. Today the field is accepted by the config but ignored by
     * [RestRpcClient.buildEndpointUrl], so this test is RED.
     */
    @Test
    fun `buildEndpointUrl includes accelbyteId query parameter when set`()
    {
        val cfg = RestRpcClientConfig(
            baseUrl = "http://127.0.0.1:7070",
            playerId = "p1",
            accelbyteId = "004c3eb0accelbyteUser"
        )
        val url = clientFor(cfg).buildEndpointUrl("/events")

        assertTrue(
            url.contains("accelbyteId=004c3eb0accelbyteUser"),
            "SSE URL must carry the accelbyteId as a query parameter; got: $url"
        )
    }

    /**
     * Backwards-compat pin: a null `accelbyteId` (the pre-login /
     * anonymous case) MUST NOT appear on the wire as `accelbyteId=`
     * with an empty value or any value at all. This protects the
     * receiver from an ambiguous sentinel.
     */
    @Test
    fun `buildEndpointUrl omits accelbyteId when null`()
    {
        val cfg = RestRpcClientConfig(
            baseUrl = "http://127.0.0.1:7070",
            playerId = "p1",
            accelbyteId = null
        )
        val url = clientFor(cfg).buildEndpointUrl("/events")

        assertFalse(
            url.contains("accelbyteId="),
            "SSE URL must not contain an accelbyteId parameter when null; got: $url"
        )
    }

    /**
     * Backwards-compat pin: a blank `accelbyteId` (caller pre-defaulted
     * the field but did not populate it) MUST be treated identically to
     * null. Emitting `accelbyteId=` (empty value) would re-trigger the
     * exact bug Fix 1 closes — server-extend would short-circuit.
     */
    @Test
    fun `buildEndpointUrl omits accelbyteId when blank`()
    {
        val cfg = RestRpcClientConfig(
            baseUrl = "http://127.0.0.1:7070",
            playerId = "p1",
            accelbyteId = ""
        )
        val url = clientFor(cfg).buildEndpointUrl("/events")

        assertFalse(
            url.contains("accelbyteId="),
            "SSE URL must not contain an accelbyteId parameter when blank; got: $url"
        )
    }

    /**
     * Regression pin for the existing wire contract: `playerId` is the
     * session key on the server side and is always present.
     */
    @Test
    fun `buildEndpointUrl always includes playerId query parameter`()
    {
        val cfg = RestRpcClientConfig(
            baseUrl = "http://127.0.0.1:7070",
            playerId = "always-present-player"
        )
        val url = clientFor(cfg).buildEndpointUrl("/events")

        assertTrue(
            url.contains("playerId=always-present-player"),
            "playerId must always be present on the SSE URL; got: $url"
        )
    }

    /**
     * Regression pin: `?origin=server` is emitted only for traffic
     * originated by the dedicated game server. Game-client traffic
     * (the default) leaves the parameter off so existing clients and
     * proxies are unaffected.
     */
    @Test
    fun `buildEndpointUrl emits origin=server only when GAME_SERVER`()
    {
        val gameClientUrl = clientFor(
            RestRpcClientConfig(
                baseUrl = "http://127.0.0.1:7070",
                playerId = "p1",
                origin = RpcOrigin.GAME_CLIENT
            )
        ).buildEndpointUrl("/events")

        val gameServerUrl = clientFor(
            RestRpcClientConfig(
                baseUrl = "http://127.0.0.1:7070",
                playerId = "p1",
                origin = RpcOrigin.GAME_SERVER
            )
        ).buildEndpointUrl("/events")

        assertFalse(
            gameClientUrl.contains("origin="),
            "GAME_CLIENT must not emit an origin parameter; got: $gameClientUrl"
        )
        assertTrue(
            gameServerUrl.contains("origin=server"),
            "GAME_SERVER must emit origin=server; got: $gameServerUrl"
        )
    }

    /**
     * End-to-end pin of the resumed-game flow: every parameter the SSE
     * handler depends on (playerId + accelbyteId + origin) appears in
     * the URL together for an authenticated, non-guest game-client
     * connect. This is the canonical URL the resume push reads from.
     */
    @Test
    fun `buildEndpointUrl carries playerId and accelbyteId together for an authenticated client`()
    {
        val cfg = RestRpcClientConfig(
            baseUrl = "http://127.0.0.1:7070",
            playerId = "session-42",
            accelbyteId = "004c3eb0accelbyteUser",
            isGuestMode = false,
            origin = RpcOrigin.GAME_CLIENT
        )
        val url = clientFor(cfg).buildEndpointUrl("/events")

        assertTrue(
            url.contains("playerId=session-42"),
            "authenticated SSE URL must carry playerId; got: $url"
        )
        assertTrue(
            url.contains("accelbyteId=004c3eb0accelbyteUser"),
            "authenticated SSE URL must carry accelbyteId for resume push pairing; got: $url"
        )
        assertEquals(false, url.contains("guestMode="), "non-guest URL must not carry guestMode")
    }
}