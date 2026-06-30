package globals

/**
 * Convenience facade over [RpcUsageTracker] for the future AWS Lambda
 * control plane and any in-process subsystem that needs to ask
 * "is anyone still using us?" or "how busy are we right now?"
 * without restating the threshold arithmetic every time.
 *
 * ## Idle queries
 *
 * All `is*IdleNow()` methods pull the threshold from
 * [ExtendConfig.idleThresholdMillis] so a single config knob governs
 * every idle decision. The facade does not cache — the underlying
 * tracker is already lock-free and the threshold is a plain field
 * read, so per-call cost is dominated by the system-call-free atomic
 * loads in [RpcUsageTracker].
 *
 * ## Frequency queries
 *
 * `*CountInWindow` / `*RatePerSecond` methods pass through to the
 * underlying tracker's ring buffer, which is sized for the largest
 * supported [RpcUsageTracker.Window] (15 min). They do NOT consult
 * [ExtendConfig] — the windows are fixed at 1/5/15 min and are not
 * operator-tunable in v1.
 */
object RpcUsageQuery
{
    /**
     * @return `true` if no game-client traffic has been observed in
     *   the last [ExtendConfig.idleThresholdMinutes] minutes, OR if
     *   no client traffic has been observed at all. The future
     *   control plane uses this as the "no player is connected"
     *   signal.
     */
    fun isClientIdleNow() : Boolean =
        RpcUsageTracker.isClientIdle(ExtendConfig.idleThresholdMillis())

    /**
     * @return `true` if no game-server traffic has been observed in
     *   the last [ExtendConfig.idleThresholdMinutes] minutes, OR if
     *   no server traffic has been observed at all. The future
     *   control plane uses this as the "no `:server` DS is calling
     *   us" signal — the trigger for spinning down individual
     *   subsystems.
     */
    fun isServerIdleNow() : Boolean =
        RpcUsageTracker.isServerIdle(ExtendConfig.idleThresholdMillis())

    /**
     * @return `true` only if both game-client and game-server traffic
     *   are idle per [isClientIdleNow] / [isServerIdleNow]. The
     *   future control plane uses this as the "spin down the whole
     *   JVM" signal.
     */
    fun isFullyIdleNow() : Boolean =
        RpcUsageTracker.isFullyIdle(ExtendConfig.idleThresholdMillis())

    /**
     * @return The number of game-client RPCs observed in the last
     *   [window.seconds] seconds. Zero on a fresh JVM.
     */
    fun clientCountInWindow(window : RpcUsageTracker.Window) : Long =
        RpcUsageTracker.clientCountInWindow(window)

    /**
     * @return The number of game-server RPCs observed in the last
     *   [window.seconds] seconds. Zero on a fresh JVM.
     */
    fun serverCountInWindow(window : RpcUsageTracker.Window) : Long =
        RpcUsageTracker.serverCountInWindow(window)

    /**
     * @return Combined client + server RPCs observed in the last
     *   [window.seconds] seconds. Zero on a fresh JVM.
     */
    fun totalCountInWindow(window : RpcUsageTracker.Window) : Long =
        RpcUsageTracker.totalCountInWindow(window)

    /**
     * @return Game-client request rate in RPCs/second over the last
     *   [window.seconds] seconds. Zero on a fresh JVM.
     */
    fun clientRatePerSecond(window : RpcUsageTracker.Window) : Double =
        RpcUsageTracker.clientRatePerSecond(window)

    /**
     * @return Game-server request rate in RPCs/second over the last
     *   [window.seconds] seconds. Zero on a fresh JVM.
     */
    fun serverRatePerSecond(window : RpcUsageTracker.Window) : Double =
        RpcUsageTracker.serverRatePerSecond(window)

    /**
     * @return Combined client + server request rate in RPCs/second
     *   over the last [window.seconds] seconds. Zero on a fresh JVM.
     */
    fun totalRatePerSecond(window : RpcUsageTracker.Window) : Double =
        RpcUsageTracker.totalRatePerSecond(window)

    /**
     * @return A [RpcUsageTracker.FrequencyStats] snapshot containing
     *   per-window counts for both origins. Each map is guaranteed
     *   to contain all three [RpcUsageTracker.Window] keys.
     */
    fun frequencyStats() : RpcUsageTracker.FrequencyStats =
        RpcUsageTracker.frequencyStats()

    /**
     * @return A best-effort snapshot of the underlying tracker.
     *   Useful for dashboards and the future admin RPC. The two
     *   timestamps in the snapshot may be from slightly different
     *   instants — see [RpcUsageTracker.snapshot] KDoc.
     */
    fun snapshot() : RpcUsageTracker.Snapshot = RpcUsageTracker.snapshot()
}
