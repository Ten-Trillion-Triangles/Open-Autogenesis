package accelbyte.dsm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * Owns the dedicated-server registration lifecycle against the AccelByte DSM controller.
 *
 * The class is intentionally JVM-only and lives in the `server` module because DSMC
 * registration is only meaningful for the dedicated game server JVM, not for server-extend.
 *
 * Lifecycle:
 *  - [start] registers the server with DSM (with bounded retries) and starts the heartbeat loop.
 *  - [drain] is the pre-shutdown path: cancel heartbeat, then call `DSM.shutdownDedicatedServer`
 *    with `reason = "drain_signal"`.
 *  - [stop] is the unconditional shutdown path: cancel heartbeat, then call
 *    `DSM.shutdownDedicatedServer` with `reason = "vm_terminated"` (failures are swallowed so
 *    the JVM is free to exit even if DSM is unreachable).
 *
 * All DSM calls are best-effort: failures are logged at WARN, never thrown. This is the
 * registration side, not gameplay — a missed heartbeat must not crash the server.
 */
object DedicatedServerRegistration
{
    /** Heartbeat cadence in milliseconds. */
    @JvmField
    internal var HEARTBEAT_INTERVAL_MS: Long = 30_000L

    /** Maximum jitter (absolute) applied to the heartbeat interval to avoid thundering herds. */
    @JvmField
    internal var HEARTBEAT_JITTER_MS: Long = 2_000L

    /** Initial backoff (ms) for registration retries. Doubles on each attempt up to [MAX_BACKOFF_MS]. */
    @JvmField
    internal var INITIAL_BACKOFF_MS: Long = 5_000L

    /** Maximum backoff (ms) for registration retries. */
    @JvmField
    internal var MAX_BACKOFF_MS: Long = 20_000L

    /** Maximum number of consecutive registration attempts before giving up. */
    @JvmField
    internal var MAX_REGISTRATION_ATTEMPTS: Int = 5

    /**
     * Sleep hook used between registration retries. Production code uses [Thread.sleep];
     * tests override this with a no-op (or a shorter wait) so the suite stays fast.
     */
    @JvmField
    internal var backoffSleep: (Long) -> Unit = { ms -> Thread.sleep(ms) }

    /**
     * The current heartbeat coroutine job, if any. Cleared when the heartbeat is cancelled by
     * [stop] or [drain]. Exposed primarily for tests.
     */
    private val heartbeatJob = AtomicReference<Job?>(null)

    /**
     * Supervisor scope used for the heartbeat coroutine. Created lazily on first [start].
     */
    private val heartbeatScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    /**
     * Cached last-known serverId/region/namespace triple, so [stop] / [drain] can build the
     * shutdown payload without re-reading the env config. `null` until [start] succeeds.
     */
    private var registeredServerId: String? = null

    /**
     * Returns the active heartbeat coroutine, primarily for tests.
     */
    fun heartbeatJobForTest(): Job? = heartbeatJob.get()

    /**
     * Registers this dedicated server with the AccelByte DSM controller and starts the periodic
     * heartbeat loop. Safe to call multiple times — a second call is a no-op when the heartbeat
     * is already running.
     *
     * @param serverId The DS ID assigned by AMS (typically `AB_DS_ID`). When blank, the call is
     *                 logged and skipped (used for local dev / non-AMS environments).
     * @param region   Region this DS is operating in. Empty string is fine for tests.
     * @param namespace AccelByte namespace. Empty string is fine for tests.
     */
    fun start(serverId: String, region: String, namespace: String)
    {
        if (serverId.isBlank())
        {
            Logger.info(LogCategory.SYSTEM, "DedicatedServerRegistration: serverId is blank, skipping DSM registration (likely dev mode)")
            return
        }

        if (heartbeatJob.get()?.isActive == true)
        {
            Logger.info(LogCategory.NETWORK, "DedicatedServerRegistration: heartbeat already running, ignoring start()")
            return
        }

        val payload = buildRegistrationPayload(serverId, region, namespace)
        val registered = runRegistrationWithRetry(payload)
        if (!registered)
        {
            Logger.warn(
                LogCategory.SYSTEM,
                "DedicatedServerRegistration: Gave up registering serverId=$serverId after $MAX_REGISTRATION_ATTEMPTS attempts; heartbeat will NOT start"
            )
            return
        }

        registeredServerId = serverId
        Logger.info(LogCategory.SYSTEM, "DedicatedServerRegistration: Registered with DSM, starting heartbeat loop (interval=${HEARTBEAT_INTERVAL_MS}ms)")

        val job = heartbeatScope.launch {
            while (isActive)
            {
                val jitter = Random.nextLong(-HEARTBEAT_JITTER_MS, HEARTBEAT_JITTER_MS + 1)
                val nextDelay = HEARTBEAT_INTERVAL_MS + jitter
                delay(nextDelay)
                try
                {
                    DSM.heartbeatDedicatedServer(payload)
                    Logger.debug(LogCategory.NETWORK, "DedicatedServerRegistration: heartbeat sent for serverId=$serverId")
                }
                catch (err: Throwable)
                {
                    Logger.warn(LogCategory.NETWORK, "DedicatedServerRegistration: heartbeat threw for serverId=$serverId: ${err.message}")
                }
            }
        }
        heartbeatJob.set(job)
    }

    /**
     * Stops the heartbeat and best-effort calls `DSM.shutdownDedicatedServer` with
     * `reason = "vm_terminated"`. Failures are logged and swallowed so a graceful JVM exit is
     * never blocked by DSM.
     */
    fun stop()
    {
        cancelHeartbeat("vm_terminated")
    }

    /**
     * Drains the server: stops the heartbeat and best-effort calls `DSM.shutdownDedicatedServer`
     * with `reason = "drain_signal"`. Call this from the DS Hub drain path.
     *
     * @param reason Free-form reason recorded in the log line for diagnostic purposes.
     */
    fun drain(reason: String)
    {
        Logger.info(LogCategory.SYSTEM, "DedicatedServerRegistration: drain invoked (reason=$reason)")
        cancelHeartbeat(reason)
    }

    /**
     * Cancels the heartbeat coroutine and best-effort fires `DSM.shutdownDedicatedServer`. Always
     * returns; never throws.
     */
    private fun cancelHeartbeat(shutdownReason: String)
    {
        val job = heartbeatJob.getAndSet(null)
        if (job != null)
        {
            Logger.info(LogCategory.SYSTEM, "DedicatedServerRegistration: cancelling heartbeat coroutine")
            job.cancel()
        }

        val serverId = registeredServerId
        if (serverId.isNullOrBlank())
        {
            Logger.info(LogCategory.NETWORK, "DedicatedServerRegistration: no registered serverId, skipping shutdown call")
            return
        }

        val shutdownPayload = buildJsonObject {
            put("serverId", JsonPrimitive(serverId))
            put("reason", JsonPrimitive(shutdownReason))
        }

        val result = try
        {
            DSM.shutdownDedicatedServer(shutdownPayload)
        }
        catch (err: Throwable)
        {
            Logger.warn(LogCategory.SYSTEM, "DedicatedServerRegistration: DSM.shutdownDedicatedServer threw: ${err.message}")
            return
        }

        if (result.isFailure)
        {
            Logger.warn(
                LogCategory.SYSTEM,
                "DedicatedServerRegistration: DSM.shutdownDedicatedServer returned failure: ${result.exceptionOrNull()?.message}"
            )
        }
        else
        {
            Logger.info(LogCategory.SYSTEM, "DedicatedServerRegistration: DSM shutdown acknowledged (reason=$shutdownReason)")
        }
    }

    /**
     * Attempts to register with bounded retries. Returns `true` on success, `false` if all retries
     * are exhausted. Each attempt uses an exponential backoff capped at [MAX_BACKOFF_MS].
     */
    private fun runRegistrationWithRetry(
        payload: kotlinx.serialization.json.JsonObject
    ): Boolean
    {
        var backoff = INITIAL_BACKOFF_MS
        for (attempt in 1..MAX_REGISTRATION_ATTEMPTS)
        {
            val result = try
            {
                DSM.registerDedicatedServer(payload)
            }
            catch (err: Throwable)
            {
                Logger.warn(LogCategory.SYSTEM, "DedicatedServerRegistration: register attempt $attempt threw: ${err.message}")
                null
            }

            if (result != null && result.isSuccess)
            {
                Logger.info(LogCategory.SYSTEM, "DedicatedServerRegistration: register succeeded on attempt $attempt")
                return true
            }

            val errMsg = result?.exceptionOrNull()?.message ?: "threw above"
            Logger.warn(LogCategory.SYSTEM, "DedicatedServerRegistration: register attempt $attempt failed: $errMsg; backoff=${backoff}ms")
            if (attempt < MAX_REGISTRATION_ATTEMPTS)
            {
                try
                {
                    backoffSleep(backoff)
                }
                catch (ie: InterruptedException)
                {
                    Thread.currentThread().interrupt()
                    Logger.warn(LogCategory.SYSTEM, "DedicatedServerRegistration: register retry sleep interrupted")
                    return false
                }
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
        return false
    }

    /**
     * Builds the registration payload sent to `DSM.registerDedicatedServer`. Mirrors the shape
     * the existing DSM controller expects: `serverId`, `region`, `namespace`, `pod_name`,
     * `status`, `created_at`.
     */
    private fun buildRegistrationPayload(
        serverId: String,
        region: String,
        namespace: String
    ): kotlinx.serialization.json.JsonObject
    {
        val podName = try
        {
            InetAddress.getLocalHost().hostName
        }
        catch (e: Exception)
        {
            Logger.debug(LogCategory.SYSTEM, "DedicatedServerRegistration: hostname lookup failed, using serverId as pod_name (${e.message})")
            serverId
        }

        return buildJsonObject {
            put("serverId", JsonPrimitive(serverId))
            put("region", JsonPrimitive(region))
            put("namespace", JsonPrimitive(namespace))
            put("pod_name", JsonPrimitive(podName))
            put("status", JsonPrimitive("READY"))
            put("created_at", JsonPrimitive(Instant.now().toString()))
        }
    }
}