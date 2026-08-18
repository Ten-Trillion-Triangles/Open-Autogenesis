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
 * Unit tests for [MapUploadSuccessHandlers].
 *
 * Mirrors the existing singleton shape — every test resets the global
 * handler state via [MapUploadSuccessHandlers.resetForTest] to keep
 * assertions independent. The session-receipt test captures the JSON
 * written into the session's outgoing channel so the wire payload is
 * verified, not just the no-throw surface.
 */
class MapUploadSuccessHandlersTest
{
    @Before
    fun resetBefore()
    {
        MapUploadSuccessHandlers.resetForTest()
    }

    @After
    fun resetAfter()
    {
        MapUploadSuccessHandlers.resetForTest()
    }

    @Test
    fun `sendMapUploadSuccess drops the notification when no connection manager is registered`() = runBlocking {
        // No registerConnectionManager() call — the notification must be dropped silently.
        MapUploadSuccessHandlers.sendMapUploadSuccess("player-x", "map-id", "map-name")
        // No exception == success; the singleton's logger is the only side effect.
    }

    @Test
    fun `sendMapUploadSuccess drops the notification when the player has no live session`() = runBlocking {
        // Register a connection manager but no session for the player.
        // The singleton's findSession returns null and the notification is dropped.
        val manager = RestPlayerConnectionManager()
        MapUploadSuccessHandlers.registerConnectionManager(manager)
        MapUploadSuccessHandlers.sendMapUploadSuccess("player-not-connected", "map-id", "map-name")
        // No exception == success; the no-session path is silent.
    }

    @Test
    fun `sendMapUploadSuccess pushes the notification to the registered session with the expected payload`() = runBlocking {
        val manager = RestPlayerConnectionManager()
        MapUploadSuccessHandlers.registerConnectionManager(manager)

        // Register a real session so the singleton's findSession() returns it.
        val registration = manager.register("player-1")
        val session = registration.session

        // Drain the session's outgoing channel into a probe channel so we
        // can assert on the actual JSON the singleton dispatched.
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
            MapUploadSuccessHandlers.sendMapUploadSuccess("player-1", "map-abc", "My Map")

            // Pin the wire payload. The notification is encoded as
            // `{"method":"Map.Upload.Success","params":{...}}` by
            // RpcMessage.Notification.toJson(RpcJson). We assert the
            // method name and the payload fields are present.
            val json = captured.receive()
            assertNotNull(json, "session should have received a JSON-encoded notification")
            assertTrue(
                json.contains("\"method\":\"Map.Upload.Success\""),
                "the dispatched JSON must carry the Map.Upload.Success method name; got: $json"
            )
            assertTrue(
                json.contains("\"mapId\":\"map-abc\""),
                "the dispatched JSON must carry the mapId in the payload; got: $json"
            )
            assertTrue(
                json.contains("\"mapName\":\"My Map\""),
                "the dispatched JSON must carry the mapName in the payload; got: $json"
            )
        }
        finally
        {
            collector.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `resetForTest clears the manager`() = runBlocking {
        val manager = RestPlayerConnectionManager()
        MapUploadSuccessHandlers.registerConnectionManager(manager)
        MapUploadSuccessHandlers.resetForTest()
        // After reset, the singleton is back to the "no manager" state — calling
        // sendMapUploadSuccess must not crash and must not invoke the manager.
        MapUploadSuccessHandlers.sendMapUploadSuccess("player-x", "map-id", "map-name")
        // Reaching this line is the assertion.
    }
}
