package structs.account

/**
 * Detects whether the service is running in local developer mode.
 *
 * The detector is a UNION over four signals — any single one is sufficient
 * to enter developer mode. This makes the gate forgiving when operators
 * legitimately want a "developer unlimited" experience (local box,
 * auto-attach to a live build, force-flag a user, etc.).
 *
 * Signals (in evaluation order, short-circuit on first true):
 *   1. Explicit `AUTOGENESIS_DEVELOPER_TIER=true` env var (or `autogenesis.developerTier`
 *      JVM property — caller passes via [envValue]).
 *   2. The caller asserts the platform is in debug / dev mode via [platformDebugMode].
 *      JVM callers pass `ExtendConfig.debugMode`; JS callers pass `globals.Debug.debugMode`.
 *   3. The gRPC listener is bound to a loopback address — caller passes via
 *      [loopbackPortActive]. Detect by comparing `ExtendConfig.grpcHost` to `"127.0.0.1"`
 *      or `"0.0.0.0"`.
 *   4. JS-side hostname hint (`localhost`, `127.0.0.1`, `*.local`) — caller passes
 *      via [hostnameHint]. JVM callers can pass `null`.
 *
 * The detector deliberately returns `false` when no signals are provided —
 * production never calls this without context.
 */
object DeveloperModeDetector
{
    private val TRUTHY = setOf("true", "1", "yes", "on")

    /**
     * @param platformDebugMode true when the active platform has been started
     *   in dev/debug mode (JVM: `ExtendConfig.debugMode`; JS: `globals.Debug.debugMode`).
     * @param loopbackPortActive true when the gRPC listener is bound to a
     *   loopback address / port.
     * @param envValue explicit env-var override (`AUTOGENESIS_DEVELOPER_TIER`)
     *   or JVM property value (`autogenesis.developerTier`). Trimmed,
     *   lowercased, checked against [TRUTHY].
     * @param hostnameHint optional caller-supplied hostname hint for the JS
     *   side (`localhost` / `127.0.0.1` / `*.local`). JVM callers pass `null`.
     */
    fun detect(
        platformDebugMode: Boolean,
        loopbackPortActive: Boolean,
        envValue: String?,
        hostnameHint: String? = null
    ): Boolean
    {
        if (envValue != null && envValue.trim().lowercase() in TRUTHY) return true
        if (platformDebugMode) return true
        if (loopbackPortActive) return true
        if (hostnameHint != null)
        {
            val h = hostnameHint.trim().lowercase()
            if (h == "localhost" || h == "127.0.0.1" || h.endsWith(".local")) return true
        }
        return false
    }
}
