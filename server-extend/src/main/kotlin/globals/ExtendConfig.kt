package globals

/**
 * Runtime configuration for the server-extend process.
 *
 * The local gRPC listener defaults to a non-conflicting port so the main
 * `server` JVM can keep using its existing gRPC port during local development.
 *
 * Live-mode toggle: when [liveMode] is `true`, [debugMode] is forced to `false`
 * and the matchmaking path takes the AccelByte match2 + AMS branch instead of
 * the local-dev fast-path. The flag is resolved at JVM init from the
 * `serverExtend.liveMode` JVM property and the `SERVER_EXTEND_LIVE_MODE`
 * environment variable, with the same precedence pattern as [resolveGrpcPort].
 * Default is `false` (dev mode) so existing local builds keep working without
 * changes; the operator opts in by setting the env var at deploy time.
 */
object ExtendConfig
{
    private const val GRPC_PORT_PROPERTY = "serverExtend.grpcPort"
    private const val GRPC_PORT_ENV = "SERVER_EXTEND_GRPC_PORT"
    private const val DEFAULT_GRPC_PORT = 9092

    private const val LIVE_MODE_PROPERTY = "serverExtend.liveMode"
    private const val LIVE_MODE_ENV = "SERVER_EXTEND_LIVE_MODE"
    private const val DEFAULT_LIVE_MODE: Boolean = false

    private const val IDLE_THRESHOLD_MINUTES_PROPERTY = "serverExtend.idleThresholdMinutes"
    private const val IDLE_THRESHOLD_MINUTES_ENV = "SERVER_EXTEND_IDLE_THRESHOLD_MINUTES"
    private const val DEFAULT_IDLE_THRESHOLD_MINUTES : Long = 15L

    /**
     * `true` means: use the local dev fast-path (`serverUrl = "127.0.0.1:9080"`,
     * no AccelByte match2 traffic). `false` means: take the live match2 + AMS path
     * via [matchmaking.ServerConnector.executeLiveMatchmaking]. Backed by [liveMode]
     * at init time so the value is stable for the lifetime of the JVM.
     */
    var debugMode: Boolean = !resolveLiveMode()
    var grpcHost: String = "0.0.0.0"
    var grpcPort: Int = resolveGrpcPort()
    var grpcEnabled: Boolean = true

    /**
     * Resolved once at init. Tests can override by calling [resolveLiveMode] with
     * explicit [propertyValue] / [envValue] parameters (no global env mutation).
     * Mutating the backing property / env var after init has no effect on
     * [debugMode] by design — a runaway client cannot flip a running service
     * from live to dev.
     */
    fun liveMode(): Boolean = !debugMode

    /**
     * Idle threshold in minutes for the [globals.RpcUsageTracker] queries.
     * A subsystem is considered idle when no Request of its origin has
     * arrived in the last `idleThresholdMinutes` minutes; the whole
     * server-extend process is considered idle when both origins are idle.
     *
     * Mutable so tests can override the threshold without restarting the
     * JVM. Production reads [resolveIdleThresholdMinutes] at startup.
     */
    var idleThresholdMinutes : Long = resolveIdleThresholdMinutes()

    /**
     * Resolves the idle threshold in minutes from JVM properties,
     * environment variables, or the local development default of 15
     * minutes. Mirrors [resolveGrpcPort]'s precedence (property wins,
     * then env, then default). A non-positive or unparseable override
     * falls back to the default — idle decisions must never be made
     * with a threshold of zero.
     */
    fun resolveIdleThresholdMinutes(
        propertyValue: String? = System.getProperty(IDLE_THRESHOLD_MINUTES_PROPERTY),
        envValue: String? = System.getenv(IDLE_THRESHOLD_MINUTES_ENV)
    ): Long
    {
        val rawValue = propertyValue?.takeIf { it.isNotBlank() }
            ?: envValue?.takeIf { it.isNotBlank() }

        val parsed = rawValue?.toLongOrNull()
        return if (parsed != null && parsed > 0L) parsed else DEFAULT_IDLE_THRESHOLD_MINUTES
    }

    /**
     * Convenience: the idle threshold converted to milliseconds for
     * direct use with [globals.RpcUsageTracker.isClientIdle] /
     * [globals.RpcUsageTracker.isServerIdle]. Returns a non-negative
     * value even if [idleThresholdMinutes] was externally set to a
     * bad value (defensive: `0ms` means "idle right now").
     */
    fun idleThresholdMillis() : Long = idleThresholdMinutes.coerceAtLeast(0L) * 60_000L

    /**
     * Resolves the gRPC listener port from JVM properties, environment variables,
     * or the local development default.
     *
     * @param propertyValue Optional explicit JVM property value.
     * @param envValue Optional environment variable value.
     * @return The parsed port or [DEFAULT_GRPC_PORT] when no override is present.
     */
    fun resolveGrpcPort(
        propertyValue: String? = System.getProperty(GRPC_PORT_PROPERTY),
        envValue: String? = System.getenv(GRPC_PORT_ENV)
    ): Int
    {
        val rawValue = propertyValue?.takeIf { it.isNotBlank() }
            ?: envValue?.takeIf { it.isNotBlank() }

        return rawValue?.toIntOrNull() ?: DEFAULT_GRPC_PORT
    }

    /**
     * Resolves the live-mode flag from JVM properties or environment variables.
     * Precedence: `serverExtend.liveMode` JVM property > `SERVER_EXTEND_LIVE_MODE`
     * env var > [DEFAULT_LIVE_MODE] (`false`). Truthy values are `"true"`, `"1"`,
     * `"yes"`, `"on"` (case-insensitive); anything else is `false`. Mirrors the
     * parameter contract of [resolveGrpcPort] so tests can inject overrides
     * without touching real env / property state.
     */
    fun resolveLiveMode(
        propertyValue: String? = System.getProperty(LIVE_MODE_PROPERTY),
        envValue: String? = System.getenv(LIVE_MODE_ENV)
    ): Boolean
    {
        val rawValue = propertyValue?.takeIf { it.isNotBlank() }
            ?: envValue?.takeIf { it.isNotBlank() }
            ?: return DEFAULT_LIVE_MODE
        return rawValue.trim().lowercase() in TRUTHY_VALUES
    }

}

private val TRUTHY_VALUES: Set<String> = setOf("true", "1", "yes", "on")