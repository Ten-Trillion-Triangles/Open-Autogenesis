package network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.serverextend.RestPlayerConnectionManager
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [MapDeleteErrorHandlers].
 *
 * Mirrors the existing [MapUploadErrorHandlers] unit-test contract — the
 * session-receipt test captures the JSON written into the session's outgoing
 * channel so the wire payload is verified, not just the no-throw surface.
 */
class MapDeleteErrorHandlersTest
{
    @Before
    fun resetBefore()
    {
        MapDeleteErrorHandlers.resetForTest()
    }

    @After
    fun resetAfter()
    {
        MapDeleteErrorHandlers.resetForTest()
    }

    @Test
    fun `sendMapDeleteError drops the notification when no connection manager is registered`() = runBlocking {
        // No registerConnectionManager() call — the notification must be dropped silently.
        MapDeleteErrorHandlers.sendMapDeleteError("player-x", "AGS record not found")
        // No exception == success; the singleton's logger is the only side effect.
    }

    @Test
    fun `sendMapDeleteError drops the notification when the player has no live session`() = runBlocking {
        val manager = RestPlayerConnectionManager()
        MapDeleteErrorHandlers.registerConnectionManager(manager)
        MapDeleteErrorHandlers.sendMapDeleteError("player-not-connected", "AGS record not found")
        // No exception == success; the no-session path is silent.
    }

    @Test
    fun `sendMapDeleteError pushes the notification to the registered session with the expected payload`() = runBlocking {
        val manager = RestPlayerConnectionManager()
        MapDeleteErrorHandlers.registerConnectionManager(manager)

        val registration = manager.register("player-1")
        val session = registration.session

        val captured = Channel<String>(capacity = 1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val collector: Job = scope.launch {
            try
            {
                session.outgoingFlow().collect { captured.send(it) }
            }
            catch (_: Throwable)
            {
                // Channel was closed or collection cancelled — propagate
                // nothing; the test will surface a missing-payload failure
                // via the `captured.receive()` timeout below.
            }
        }
        try
        {
            MapDeleteErrorHandlers.sendMapDeleteError("player-1", "AGS record not found")

            val json = captured.receive()
            assertNotNull(json, "session should have received a JSON-encoded notification")
            assertTrue(
                json.contains("\"method\":\"Map.Delete.Error\""),
                "the dispatched JSON must carry the Map.Delete.Error method name; got: $json"
            )
            assertTrue(
                json.contains("\"reason\":\"AGS record not found\""),
                "the dispatched JSON must carry the reason in the payload; got: $json"
            )
        }
        finally
        {
            collector.cancel()
            scope.cancel()
        }
    }
}
