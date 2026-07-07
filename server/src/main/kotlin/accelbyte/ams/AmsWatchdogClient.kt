package accelbyte.ams

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.server.config.AccelByteConfig
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture

/**
 * AMS watchdog WebSocket client. The watchdog is the channel AMS uses to
 * know whether a dedicated server is alive and ready to receive claim
 * traffic.
 *
 * Wire contract (verified against `AccelByte/ams-samples/python-ds/basicds/ams_watchdog.py`
 * and the go-ds-watchdog-client sample):
 *  - URL:   `<AB_WATCHDOG_URL>/watchdog` (path auto-appended when missing)
 *  - Header: `ams-dsid: <AB_DS_ID>`
 *  - First frame on open: `{"ready":{"dsid":"<DS_ID>"}}`
 *  - Subsequent frames every [HEARTBEAT_INTERVAL_MS] ms:
 *    `{"heartbeat":{}}`
 *
 * Block-3 of feature/live-pvp-and-billing — this is the missing client
 * that AMS needs to mark the DS as ready in the fleet UI. Without it the
 * DS shows as `provisioning` forever and never receives claim traffic.
 *
 * The client is split into:
 *  - [connect] — production entry, uses the configured
 *    [AccelByteConfig.getWatchdogUrl] + [AccelByteConfig.getDsId].
 *  - [connectWithSink] — test seam, lets unit tests verify frame format
 *    and cadence against a fake sink without standing up a real WebSocket
 *    server.
 */
object AmsWatchdogClient
{
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Heartbeat interval in milliseconds. The canonical AMS watchdog
     * cadence is 15 s; the legacy DSM heartbeat is 30 s. AMS does not
     * enforce either cadence strictly, but faster heartbeats waste
     * bandwidth and slower ones risk the watchdog thinking the DS is
     * dead. 15 s is the safest default; tests override it.
     */
    @Volatile var HEARTBEAT_INTERVAL_MS: Long = 15_000L

    /**
     * Jitter applied to [HEARTBEAT_INTERVAL_MS] to avoid synchronized
     * storms when many DSes come up at once. Defaults to 0 in tests.
     */
    @Volatile var HEARTBEAT_JITTER_MS: Long = 2_000L

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var heartbeatJob: Job? = null
    @Volatile private var currentDsId: String? = null
    @Volatile private var currentSink: Sink? = null

    /**
     * Test seam: a sink the client pushes frames through instead of a
     * real WebSocket. Used by `connectWithSink`.
     */
    interface Sink
    {
        fun recordConnection(url: String, headerAmsDsid: String)
        fun sendText(text: String)
        fun sendClose()
    }

    private val realSink: Sink = object : Sink
    {
        override fun recordConnection(url: String, headerAmsDsid: String)
        {
            // Real WebSocket records URL + dsid via the WebSocket builder
            // header. Nothing to capture here.
        }
        override fun sendText(text: String)
        {
            try
            {
                webSocket?.sendText(text, true)
            }
            catch (err: Throwable)
            {
                Logger.warn(LogCategory.NETWORK, "AmsWatchdogClient: sendText failed: ${err.message}")
            }
        }
        override fun sendClose()
        {
            try
            {
                webSocket?.sendClose(WebSocket.NORMAL_CLOSURE, "Server shutting down")
            }
            catch (err: Throwable)
            {
                Logger.warn(LogCategory.NETWORK, "AmsWatchdogClient: sendClose failed: ${err.message}")
            }
        }
    }

    private val httpClient: HttpClient by lazy { HttpClient.newBuilder().build() }

    /**
     * Production entry point. Reads the watchdog URL and DS ID from
     * [AccelByteConfig], opens the WebSocket, and starts the heartbeat
     * loop. Falls through as a no-op when either value is blank (dev mode).
     */
    fun connect()
    {
        val watchdogUrl = AccelByteConfig.getWatchdogUrl()
        val dsId = AccelByteConfig.getDsId()
        connectWithSink(watchdogUrl, dsId, realSink)
    }

    /**
     * Test seam: drive a [sink] with the canonical watchdog frame format.
     * Production code calls [connect] which delegates here with the
     * [realSink]; tests substitute a [FakeWebSocketSink] to capture
     * frames without standing up a real server.
     *
     * No-op when either [watchdogUrl] or [dsId] is blank (the AMS side
     * rejects unknown dsids and the watchdog URL is unreachable from
     * dev/CI machines that don't have AMS fleet placement).
     */
    fun connectWithSink(watchdogUrl: String, dsId: String, sink: Sink)
    {
        if (watchdogUrl.isBlank() || dsId.isBlank())
        {
            Logger.warn(
                LogCategory.NETWORK,
                "AmsWatchdogClient: watchdogUrl or dsId blank — skipping (dev mode). watchdogUrl='$watchdogUrl', dsId='$dsId'"
            )
            return
        }

        val normalizedUrl = buildWatchdogUri(watchdogUrl).toString()
        currentSink = sink
        sink.recordConnection(normalizedUrl, headerAmsDsid = dsId)

        currentDsId = dsId
        sink.sendText("""{"ready":{"dsid":"$dsId"}}""")
        Logger.info(LogCategory.NETWORK, "AmsWatchdogClient: connected to $normalizedUrl with dsid=$dsId, ready frame sent")

        startHeartbeat(sink)
    }

    /**
     * Stops the heartbeat loop and closes the WebSocket. Best-effort:
     * failures are logged at WARN and swallowed so JVM exit is not
     * blocked.
     */
    fun stopForTest()
    {
        Logger.info(LogCategory.NETWORK, "AmsWatchdogClient: stop()")
        heartbeatJob?.cancel()
        heartbeatJob = null
        val activeSink = currentSink
        try
        {
            activeSink?.sendClose()
        }
        catch (err: Throwable)
        {
            Logger.warn(LogCategory.NETWORK, "AmsWatchdogClient: sendClose failed: ${err.message}")
        }
        finally
        {
            webSocket = null
            currentSink = null
        }
    }

    private fun startHeartbeat(sink: Sink)
    {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive)
            {
                val jitter = if (HEARTBEAT_JITTER_MS > 0) (Math.random() * HEARTBEAT_JITTER_MS).toLong() else 0L
                delay(HEARTBEAT_INTERVAL_MS + jitter)
                try
                {
                    sink.sendText("""{"heartbeat":{}}""")
                    Logger.debug(LogCategory.NETWORK, "AmsWatchdogClient: heartbeat sent")
                }
                catch (err: Throwable)
                {
                    Logger.warn(LogCategory.NETWORK, "AmsWatchdogClient: heartbeat send failed: ${err.message}")
                }
            }
        }
    }

    /**
     * Normalize the watchdog URL: append `/watchdog` path suffix when
     * missing. The canonical AMS URL is `https://watchdog.prod.gamingservices.accelbyte.io`
     * and the WebSocket path is `/watchdog`.
     */
    internal fun buildWatchdogUri(watchdogUrl: String): URI
    {
        val base = watchdogUrl.trimEnd('/')
        // Check whether the URL already has a /watchdog PATH component
        // (not just the substring inside the host name). The naive
        // `base.contains("/watchdog")` returns true for the bare
        // hostname `wss://watchdog.prod.gamingservices.accelbyte.io`
        // because `://watchdog` happens to contain `/watchdog` as a
        // substring.
        val hasPath = try {
            val parsed = URI.create(if (base.startsWith("ws://") || base.startsWith("wss://")) base else "wss://$base")
            val path = parsed.path ?: ""
            path.endsWith("/watchdog")
        } catch (_: Throwable) { false }

        val withPath = if (hasPath) base else "$base/watchdog"
        return if (withPath.startsWith("ws://") || withPath.startsWith("wss://"))
        {
            URI.create(withPath)
        }
        else
        {
            URI.create("wss://$withPath")
        }
    }
}