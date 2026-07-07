package accelbyte.ams

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TDD contract tests for Block-3: AMS watchdog WebSocket client.
 *
 * Asserts that the dedicated server connects to the AMS watchdog URL with
 * the `ams-dsid` header and emits the canonical frame format:
 *  - `{"ready":{"dsid":"<DS_ID>"}}` exactly once, on connection open
 *  - `{"heartbeat":{}}` at the configured cadence (canonical: 15 s; the
 *    client uses an overridable constant for test speed)
 *
 * Wire contract verified against the canonical AMS watchdog sample at
 * `AccelByte/ams-samples/python-ds/basicds/ams_watchdog.py` and
 * `AccelByte/ams-samples/go-ds-watchdog-client`.
 *
 * These tests are the RED step of TDD for Block-3: they MUST fail to
 * compile before `AmsWatchdogClient` is implemented, and they MUST pass
 * after.
 *
 * If `AmsWatchdogClient` doesn't exist, `import accelbyte.ams.AmsWatchdogClient`
 * fails to resolve and the test class does not compile — that's the RED
 * signal the orchestrator wants.
 */
class AmsWatchdogClientTest
{
    /**
     * In-memory sink that records the JSON text frames the watchdog would
     * have sent over the wire. Used to verify frame format without
     * requiring a real WebSocket.
     */
    private class FakeWebSocketSink : AmsWatchdogClient.Sink
    {
        val frames: MutableList<String> = mutableListOf()
        var sendCloseCalls: Int = 0
        var headerAmsDsid: String? = null
        var url: String? = null

        override fun recordConnection(url: String, headerAmsDsid: String)
        {
            this.url = url
            this.headerAmsDsid = headerAmsDsid
        }

        override fun sendText(text: String)
        {
            frames += text
        }

        override fun sendClose()
        {
            sendCloseCalls += 1
        }
    }

    @Before
    fun setUp()
    {
        // Shrink the heartbeat cadence so the test loop doesn't take 15s.
        AmsWatchdogClient.HEARTBEAT_INTERVAL_MS = 100L
        AmsWatchdogClient.HEARTBEAT_JITTER_MS = 0L
    }

    @After
    fun tearDown()
    {
        AmsWatchdogClient.stopForTest()
    }

    // -------------------------------------------------------------------
    // Contract: connectWithSink is the test seam for the watchdog WebSocket.
    // The unit under test MUST send `{"ready":{"dsid":"<DS_ID>"}}` once on
    // connect, then `{"heartbeat":{}}` at every cadence tick.
    // -------------------------------------------------------------------

    @Test
    fun `connect sends ready frame on open with the configured dsid`() {
        val sink = FakeWebSocketSink()
        val dsId = "ds-test-1"

        AmsWatchdogClient.connectWithSink(
            watchdogUrl = "wss://watchdog.prod.gamingservices.accelbyte.io",
            dsId = dsId,
            sink = sink
        )

        assertNotNull(sink.url, "connect must record the URL it connected to")
        assertEquals(
            "wss://watchdog.prod.gamingservices.accelbyte.io/watchdog",
            sink.url,
            "watchdog URL MUST be normalized with /watchdog path suffix"
        )
        assertEquals(
            dsId,
            sink.headerAmsDsid,
            "watchdog connection MUST carry the ams-dsid header set to the DS ID"
        )
        assertTrue(
            sink.frames.isNotEmpty(),
            "watchdog connection MUST send at least one frame on open"
        )
        val firstFrame = sink.frames.first()
        assertTrue(
            firstFrame.contains("\"ready\""),
            "first frame MUST be the ready frame, got: $firstFrame"
        )
        assertTrue(
            firstFrame.contains("\"dsid\":\"$dsId\""),
            "ready frame MUST carry the dsid field, got: $firstFrame"
        )
    }

    @Test
    fun `connect with blank watchdogUrl is a no-op`() {
        val sink = FakeWebSocketSink()
        AmsWatchdogClient.connectWithSink(
            watchdogUrl = "",
            dsId = "ds-test-2",
            sink = sink
        )
        assertTrue(
            sink.frames.isEmpty(),
            "blank watchdogUrl must NOT send any frames (dev mode)"
        )
        assertNull(sink.url, "blank watchdogUrl must NOT record a connection")
    }

    @Test
    fun `connect with blank dsId is a no-op`() {
        val sink = FakeWebSocketSink()
        AmsWatchdogClient.connectWithSink(
            watchdogUrl = "wss://watchdog.prod.gamingservices.accelbyte.io",
            dsId = "",
            sink = sink
        )
        assertTrue(
            sink.frames.isEmpty(),
            "blank dsid must NOT send any frames — AMS rejects unknown dsids"
        )
    }

    @Test
    fun `heartbeat frames fire at the configured cadence`() = runBlocking {
        val sink = FakeWebSocketSink()
        val dsId = "ds-test-3"

        AmsWatchdogClient.connectWithSink(
            watchdogUrl = "wss://watchdog.prod.gamingservices.accelbyte.io",
            dsId = dsId,
            sink = sink
        )

        // Initial state: one ready frame already sent.
        assertEquals(1, sink.frames.size, "exactly one ready frame on open")
        assertTrue(sink.frames[0].contains("\"ready\""))

        // Wait long enough for at least two heartbeats at 100ms cadence.
        delay(280L)

        val heartbeatCount = sink.frames.count { it.contains("\"heartbeat\"") }
        assertTrue(
            heartbeatCount >= 2,
            "expected at least 2 heartbeat frames in ~280ms, got $heartbeatCount (frames=${sink.frames})"
        )
        // Every heartbeat frame MUST be exactly the canonical payload.
        for (frame in sink.frames.filter { it.contains("\"heartbeat\"") })
        {
            assertEquals(
                "{\"heartbeat\":{}}",
                frame,
                "heartbeat frame MUST be exactly {\"heartbeat\":{}}, got: $frame"
            )
        }
    }

    @Test
    fun `stop cancels the heartbeat and closes the websocket`() = runBlocking {
        val sink = FakeWebSocketSink()
        val dsId = "ds-test-4"

        AmsWatchdogClient.connectWithSink(
            watchdogUrl = "wss://watchdog.prod.gamingservices.accelbyte.io",
            dsId = dsId,
            sink = sink
        )
        assertEquals(1, sink.frames.size)

        // Let some heartbeats fire.
        delay(250L)
        val beforeStop = sink.frames.count { it.contains("\"heartbeat\"") }

        AmsWatchdogClient.stopForTest()
        delay(150L)

        val afterStop = sink.frames.count { it.contains("\"heartbeat\"") }
        assertEquals(
            beforeStop,
            afterStop,
            "no further heartbeats after stop() (before=$beforeStop, after=$afterStop)"
        )
        assertEquals(
            1,
            sink.sendCloseCalls,
            "stop() must call sendClose on the WebSocket exactly once"
        )
    }

    @Test
    fun `watchdog URL gets the slash-watchdog path appended when missing`() {
        val sink = FakeWebSocketSink()
        AmsWatchdogClient.connectWithSink(
            watchdogUrl = "wss://watchdog.prod.gamingservices.accelbyte.io",
            dsId = "ds-test-5",
            sink = sink
        )
        assertEquals(
            "wss://watchdog.prod.gamingservices.accelbyte.io/watchdog",
            sink.url
        )
    }

    @Test
    fun `watchdog URL is left intact when the slash-watchdog path is already present`() {
        val sink = FakeWebSocketSink()
        AmsWatchdogClient.connectWithSink(
            watchdogUrl = "wss://watchdog.prod.gamingservices.accelbyte.io/watchdog",
            dsId = "ds-test-6",
            sink = sink
        )
        assertEquals(
            "wss://watchdog.prod.gamingservices.accelbyte.io/watchdog",
            sink.url,
            "must NOT double-append /watchdog when already present"
        )
    }
}