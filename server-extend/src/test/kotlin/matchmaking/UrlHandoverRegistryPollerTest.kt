package matchmaking

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.coVerify
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import net.accelbyte.sdk.api.session.models.ApimodelsGameSessionResponse
import net.accelbyte.sdk.api.session.wrappers.GameSession
import net.accelbyte.sdk.core.HttpResponseException
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the "AccelByte game session deleted on the platform" staleness
 * signal: when the platform's [GameSession.getGameSession] call
 * throws an [HttpResponseException] with httpCode == 404 for a
 * sessionId in the [UrlHandoverRegistry], the polling function MUST
 * remove the entry. This catches the case where an AGS admin (or
 * the implicit "all members left" close) deletes the session while
 * server-extend still holds the URL in its registry.
 *
 * The poll is run by [ServerConnector.pollAccelByteSessionsForStaleness]
 * which iterates [UrlHandoverRegistry.allKeys] and calls
 * `getGameSession` for each. The poll coroutine lives in
 * [org.ttt.autogenesis.serverextend.ServerExtend.main] and ticks
 * every 30s in production; the test drives a single iteration
 * directly via the `internal` function.
 *
 * Other error shapes (network, transient 5xx) MUST be logged and
 * skipped — the next iteration will retry. The test exercises only
 * the 404-remove and success-keep paths; the other paths are
 * smoke-tested manually.
 */
class UrlHandoverRegistryPollerTest
{
    private val originalGameSession = ServerConnector.gameSessionWrapper
    private val originalRegistry = ServerConnector.urlHandoverRegistry

    @Before
    fun setup()
    {
        // Ensure AB_NAMESPACE is set so AccelByteConfig.getNamespace() doesn't throw
        // when pollAccelByteSessionsForStaleness builds the getGameSession op.
        if(System.getenv("AB_NAMESPACE").isNullOrBlank())
        {
            System.setProperty("AB_NAMESPACE", "test-namespace")
        }
    }

    @After
    fun tearDown()
    {
        ServerConnector.gameSessionWrapper = originalGameSession
        ServerConnector.urlHandoverRegistry = originalRegistry
    }

    @Test
    fun `polling loop removes registry entry when getGameSession throws 404`() = runBlocking {
        val gameSession = mockk<GameSession>(relaxed = false)
        val registry = UrlHandoverRegistry()
        registry.put("poller-session-1", "10.0.0.5:7777")

        // Simulate AGS returning a 404 by throwing an HttpResponseException
        // with httpCode == 404. The poll function checks the httpCode
        // field on the thrown exception.
        val notFound = mockk<HttpResponseException>(relaxed = false)
        every { notFound.httpCode } returns 404
        every { notFound.message } returns "session not found"
        coEvery { gameSession.getGameSession(any()) } throws notFound

        ServerConnector.gameSessionWrapper = gameSession
        ServerConnector.urlHandoverRegistry = registry

        ServerConnector.pollAccelByteSessionsForStaleness(registry)

        assertNull(registry.get("poller-session-1"),
            "poller must remove registry entry when AGS reports the session is gone (404)")
        coVerify(atLeast = 1) { gameSession.getGameSession(any()) }
    }

    @Test
    fun `polling loop keeps registry entry when getGameSession succeeds`() = runBlocking {
        val gameSession = mockk<GameSession>(relaxed = false)
        val registry = UrlHandoverRegistry()
        registry.put("poller-session-2", "10.0.0.6:7777")

        val okResponse = mockk<ApimodelsGameSessionResponse>(relaxed = true)
        every { okResponse.id } returns "poller-session-2"
        coEvery { gameSession.getGameSession(any()) } returns okResponse

        ServerConnector.gameSessionWrapper = gameSession
        ServerConnector.urlHandoverRegistry = registry

        ServerConnector.pollAccelByteSessionsForStaleness(registry)

        assertEquals("10.0.0.6:7777", registry.get("poller-session-2"),
            "poller must NOT remove a live session's URL")
        coVerify(atLeast = 1) { gameSession.getGameSession(any()) }
    }

    @Test
    fun `polling loop is a no-op when the registry is empty`() = runBlocking {
        val gameSession = mockk<GameSession>(relaxed = false)
        val registry = UrlHandoverRegistry()

        ServerConnector.gameSessionWrapper = gameSession
        ServerConnector.urlHandoverRegistry = registry

        ServerConnector.pollAccelByteSessionsForStaleness(registry)

        // The poller should not call the SDK at all when the registry is
        // empty — no point in an SDK round-trip with no work to do.
        verify(exactly = 0) { gameSession.getGameSession(any()) }
    }
}