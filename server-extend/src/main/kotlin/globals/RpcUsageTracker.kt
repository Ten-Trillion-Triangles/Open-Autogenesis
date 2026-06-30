package globals

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Singleton tracker that records per-origin RPC activity for the
 * server-extend process. Two subsystems share this object:
 *
 * 1. **Idle subsystem** — stores, per origin, the wall-clock millis at
 *    which an inbound RPC was last observed. Used by the future AWS
 *    Lambda control plane to decide when to spin down server-extend
 *    (no game-client traffic) and its subsystems (no game-server
 *    traffic). Threshold is read from [ExtendConfig.idleThresholdMinutes].
 *
 * 2. **Frequency subsystem** — stores, per origin, a per-second ring
 *    buffer of request counts. Used by the future control plane to
 *    read a "rate per second" figure at the 1/5/15-minute windows for
 *    autoscaling, throttling, and load-balancing decisions. See
 *    [Window] for the supported windows.
 *
 * Both subsystems are fed by the same hook —
 * [org.ttt.autogenesis.network.RpcTelemetrySink.onRequestSent] — and
 * the same nowMillis, so a single per-session call advances both
 * subsystems atomically (from the caller's perspective).
 *
 * The tracker is in-memory only. A fresh JVM starts at "never seen"
 * for both origins, which naturally reads as "fully idle" and
 * "zero per window" until the first RPC arrives. Persistence to the
 * VFS is intentionally out of scope for v1.
 *
 * ## Threading
 *
 * All `mark*Seen` / `is*Idle` / `*CountInWindow` / `*RatePerSecond`
 * calls are non-suspending and lock-free. The atomic compare-and-set
 * loops in [markSeen] (idle) and [markInRing] (frequency) guarantee
 * monotonicity even when a late `nowMillis` arrives after a newer
 * one. [Snapshot] and [FrequencyStats] are best-effort — the
 * underlying state may shift between individual reads.
 *
 * ## Idle semantics
 *
 * "Idle" means "no Request of this origin has arrived in the last
 * N milliseconds." Notifications, StreamChunks, StreamCancels,
 * Responses, and ConnectionState messages are NOT counted — only
 * inbound `Request` arrivals advance the clock, and they do so via
 * the [org.ttt.autogenesis.network.RpcTelemetrySink] hook on the
 * per-session [org.ttt.autogenesis.network.RpcInvoker].
 *
 * ## Frequency semantics
 *
 * "Count in the last N seconds" is computed by summing the per-second
 * buckets that fall within the window. The ring buffer is sized to
 * 900 slots (one per second) so it can answer any window up to 15
 * minutes from a single storage location. A write to a slot whose
 * stored second is older than the write's current second reclaims
 * the slot (the previous second's data is now outside the 15-minute
 * window anyway). Cross-second concurrent writes are best-effort: in
 * a tight race, a much later second can overwrite an earlier second's
 * increment in the same slot. Same-second concurrent writes are
 * exact — every mark is counted.
 */
object RpcUsageTracker
{
    /**
     * Sentinel value stored in [lastClientSeenAtMillis] /
     * [lastServerSeenAtMillis] when no traffic has been observed yet.
     * `Long.MIN_VALUE` is used so the next `nowMillis` always wins the
     * compare-and-set (assuming a sane clock — see [markSeen]).
     */
    private const val NEVER_SEEN : Long = Long.MIN_VALUE

    /**
     * Sentinel value stored in [lastClientSeenSecond] /
     * [lastServerSeenSecond] when no traffic has been observed yet.
     * The first real second (`0L` or larger) always wins the CAS.
     */
    private const val NEVER_SEEN_SECOND : Long = Long.MIN_VALUE

    /**
     * Number of per-second slots in the ring buffer. Sized to the
     * largest supported [Window] (15 minutes = 900 seconds) so a
     * single buffer can answer any of the three windows.
     */
    private const val RING_SIZE : Int = 900

    private val lastClientSeenAtMillis = AtomicLong(NEVER_SEEN)
    private val lastServerSeenAtMillis = AtomicLong(NEVER_SEEN)

    /**
     * Highest second (in `nowMillis / 1000`) that has been recorded
     * for the given origin. Used to reject out-of-order or
     * clock-skewed `nowMillis` values that would otherwise clobber
     * newer data in the ring buffer.
     */
    private val lastClientSeenSecond = AtomicLong(NEVER_SEEN_SECOND)
    private val lastServerSeenSecond = AtomicLong(NEVER_SEEN_SECOND)

    private val clientEpochSeconds = AtomicLongArray(RING_SIZE)
    private val clientCounts = AtomicLongArray(RING_SIZE)
    private val serverEpochSeconds = AtomicLongArray(RING_SIZE)
    private val serverCounts = AtomicLongArray(RING_SIZE)

    /**
     * Records that an inbound RPC from a game client was just
     * observed. Advances both the idle timestamp and the client's
     * per-second ring buffer slot. Monotonic — a non-monotonic
     * [nowMillis] is ignored.
     */
    fun markClientSeen(nowMillis : Long = System.currentTimeMillis())
    {
        markSeen(lastClientSeenAtMillis, nowMillis)
        markInRing(clientEpochSeconds, clientCounts, lastClientSeenSecond, nowMillis)
    }

    /**
     * Records that an inbound RPC from the dedicated game server
     * (`:server`) was just observed. Advances both the idle
     * timestamp and the server's per-second ring buffer slot.
     * Monotonic — a non-monotonic [nowMillis] is ignored.
     */
    fun markServerSeen(nowMillis : Long = System.currentTimeMillis())
    {
        markSeen(lastServerSeenAtMillis, nowMillis)
        markInRing(serverEpochSeconds, serverCounts, lastServerSeenSecond, nowMillis)
    }

    /**
     * @return The last observed wall-clock millis for game-client
     *   traffic, or `null` if no client traffic has been observed
     *   since process start.
     */
    fun lastClientSeenAtMillis() : Long?
    {
        val value = lastClientSeenAtMillis.get()
        return if (value == NEVER_SEEN) null else value
    }

    /**
     * @return The last observed wall-clock millis for game-server
     *   traffic, or `null` if no server traffic has been observed
     *   since process start.
     */
    fun lastServerSeenAtMillis() : Long?
    {
        val value = lastServerSeenAtMillis.get()
        return if (value == NEVER_SEEN) null else value
    }

    /**
     * @param thresholdMillis Idle threshold in milliseconds.
     * @param nowMillis "Current" time, injected for testability.
     * @return `true` if no game-client traffic has been observed in
     *   the last [thresholdMillis], OR if no client traffic has been
     *   observed at all (fresh JVM counts as idle).
     */
    fun isClientIdle(thresholdMillis : Long, nowMillis : Long = System.currentTimeMillis()) : Boolean
    {
        val last = lastClientSeenAtMillis.get()
        return last == NEVER_SEEN || (nowMillis - last) >= thresholdMillis
    }

    /**
     * @param thresholdMillis Idle threshold in milliseconds.
     * @param nowMillis "Current" time, injected for testability.
     * @return `true` if no game-server traffic has been observed in
     *   the last [thresholdMillis], OR if no server traffic has been
     *   observed at all.
     */
    fun isServerIdle(thresholdMillis : Long, nowMillis : Long = System.currentTimeMillis()) : Boolean
    {
        val last = lastServerSeenAtMillis.get()
        return last == NEVER_SEEN || (nowMillis - last) >= thresholdMillis
    }

    /**
     * @return `true` only if both game-client and game-server traffic
     *   are idle per [isClientIdle] / [isServerIdle]. The future
     *   control plane uses this as the "spin down the whole JVM"
     *   signal.
     */
    fun isFullyIdle(thresholdMillis : Long, nowMillis : Long = System.currentTimeMillis()) : Boolean =
        isClientIdle(thresholdMillis, nowMillis) && isServerIdle(thresholdMillis, nowMillis)

    /**
     * @return The number of game-client RPCs observed in the last
     *   [window.seconds] seconds. Always non-negative. Zero on a
     *   fresh JVM or for a window that contains no recorded traffic.
     */
    fun clientCountInWindow(
        window : Window,
        nowMillis : Long = System.currentTimeMillis()
    ) : Long = countInWindow(clientEpochSeconds, clientCounts, window, nowMillis)

    /**
     * @return The number of game-server RPCs observed in the last
     *   [window.seconds] seconds. Always non-negative. Zero on a
     *   fresh JVM or for a window that contains no recorded traffic.
     */
    fun serverCountInWindow(
        window : Window,
        nowMillis : Long = System.currentTimeMillis()
    ) : Long = countInWindow(serverEpochSeconds, serverCounts, window, nowMillis)

    /**
     * @return Sum of [clientCountInWindow] and [serverCountInWindow]
     *   for the same [window]. Useful for total-throughput metrics.
     */
    fun totalCountInWindow(
        window : Window,
        nowMillis : Long = System.currentTimeMillis()
    ) : Long = clientCountInWindow(window, nowMillis) + serverCountInWindow(window, nowMillis)

    /**
     * @return The average game-client request rate in RPCs/second
     *   over the last [window.seconds] seconds. Computed as
     *   `clientCountInWindow(window) / window.seconds`. Zero on a
     *   fresh JVM.
     */
    fun clientRatePerSecond(
        window : Window,
        nowMillis : Long = System.currentTimeMillis()
    ) : Double = clientCountInWindow(window, nowMillis).toDouble() / window.seconds

    /**
     * @return The average game-server request rate in RPCs/second
     *   over the last [window.seconds] seconds. Computed as
     *   `serverCountInWindow(window) / window.seconds`. Zero on a
     *   fresh JVM.
     */
    fun serverRatePerSecond(
        window : Window,
        nowMillis : Long = System.currentTimeMillis()
    ) : Double = serverCountInWindow(window, nowMillis).toDouble() / window.seconds

    /**
     * @return The combined client + server request rate in RPCs/second
     *   over the last [window.seconds] seconds. Zero on a fresh JVM.
     */
    fun totalRatePerSecond(
        window : Window,
        nowMillis : Long = System.currentTimeMillis()
    ) : Double = totalCountInWindow(window, nowMillis).toDouble() / window.seconds

    /**
     * @return A [FrequencyStats] view that materialises per-window
     *   counts for both origins. Each returned map is guaranteed to
     *   contain all three [Window] keys (zero-valued when no traffic
     *   has been observed in that window), so callers can do
     *   `stats.clientCounts.getValue(Window.ONE_MIN)` without
     *   nullability guards.
     */
    fun frequencyStats(nowMillis : Long = System.currentTimeMillis()) : FrequencyStats =
        FrequencyStats(
            nowMillis = nowMillis,
            clientCounts = computeCountsByWindow(clientEpochSeconds, clientCounts, nowMillis),
            serverCounts = computeCountsByWindow(serverEpochSeconds, serverCounts, nowMillis)
        )

    /**
     * Returns a best-effort snapshot of the tracker's state for
     * dashboards and the future control plane. The two timestamps
     * may be from slightly different instants, and the per-window
     * counts may be from slightly different instants than the
     * timestamps.
     */
    fun snapshot(nowMillis : Long = System.currentTimeMillis()) : Snapshot =
        Snapshot(
            lastClientSeenAtMillis = lastClientSeenAtMillis(),
            lastServerSeenAtMillis = lastServerSeenAtMillis(),
            nowMillis = nowMillis,
            clientCountsByWindow = computeCountsByWindow(clientEpochSeconds, clientCounts, nowMillis),
            serverCountsByWindow = computeCountsByWindow(serverEpochSeconds, serverCounts, nowMillis)
        )

    /**
     * Resets the idle timestamps, the per-second maxes, and every
     * ring-buffer slot. Intended for tests; production code should
     * never need this.
     */
    fun resetForTest()
    {
        lastClientSeenAtMillis.set(NEVER_SEEN)
        lastServerSeenAtMillis.set(NEVER_SEEN)
        lastClientSeenSecond.set(NEVER_SEEN_SECOND)
        lastServerSeenSecond.set(NEVER_SEEN_SECOND)
        for (i in 0 until RING_SIZE)
        {
            clientEpochSeconds.set(i, 0L)
            clientCounts.set(i, 0L)
            serverEpochSeconds.set(i, 0L)
            serverCounts.set(i, 0L)
        }
    }

    private fun markSeen(slot : AtomicLong, nowMillis : Long)
    {
        // Monotonic compare-and-set loop. A `nowMillis` that is older than
        // the current value (clock skew, replay, out-of-order delivery) is
        // discarded. The sentinel `Long.MIN_VALUE` always loses to any sane
        // wall-clock value, so the first observation always lands.
        while (true)
        {
            val current = slot.get()
            if (current >= nowMillis) return
            if (slot.compareAndSet(current, nowMillis)) return
        }
    }

    /**
     * Advances the per-second ring buffer for one origin. Bumps
     * [maxSeenSecond] to `nowMillis / 1000` first (rejecting older
     * `nowMillis` values) and then either increments the count in
     * the matching slot or claims the slot for the new second.
     *
     * @param epochSeconds The `RING_SIZE`-long epoch array for this origin.
     * @param counts       The `RING_SIZE`-long count array for this origin.
     * @param maxSeenSecond Tracks the latest second this origin has seen.
     * @param nowMillis Wall-clock millis; the second is `nowMillis / 1000`.
     */
    private fun markInRing(
        epochSeconds : AtomicLongArray,
        counts : AtomicLongArray,
        maxSeenSecond : AtomicLong,
        nowMillis : Long
    )
    {
        val currentSecond = nowMillis / 1000L

        // Monotonic gate: reject `nowMillis` whose second is older than
        // the latest second we've already recorded. Without this guard, a
        // cross-second wrap race could let a much later second overwrite
        // a slightly earlier second's increment in the same slot.
        while (true)
        {
            val last = maxSeenSecond.get()
            if (currentSecond < last) return
            if (last == currentSecond) break
            if (maxSeenSecond.compareAndSet(last, currentSecond)) break
        }

        val idx = ringIndex(currentSecond)
        while (true)
        {
            val epoch = epochSeconds.get(idx)
            val count = counts.get(idx)
            when
            {
                epoch == currentSecond ->
                {
                    // Same second — increment the count. Plain CAS on
                    // the count array. If the CAS fails, another writer
                    // raced us; retry.
                    if (counts.compareAndSet(idx, count, count + 1L)) return
                }
                epoch > currentSecond ->
                {
                    // Slot is from a future second relative to this mark.
                    // Should be impossible given the maxSeenSecond guard
                    // above, but defensively: discard the mark rather than
                    // corrupt the slot.
                    return
                }
                else ->
                {
                    // Slot is from an older second — claim it. We CAS the
                    // epoch first; if that succeeds, we then CAS the
                    // count from its current value to 1. The count CAS
                    // is allowed to fail (another writer raced us after
                    // we set the epoch); on failure we re-enter the
                    // outer loop, which now sees the just-claimed slot
                    // and falls into the "same second" branch.
                    if (epochSeconds.compareAndSet(idx, epoch, currentSecond))
                    {
                        if (counts.compareAndSet(idx, count, 1L)) return
                    }
                }
            }
        }
    }

    private fun countInWindow(
        epochSeconds : AtomicLongArray,
        counts : AtomicLongArray,
        window : Window,
        nowMillis : Long
    ) : Long
    {
        val currentSecond = nowMillis / 1000L
        val startSecond = currentSecond - window.seconds + 1L
        var total = 0L
        var s = startSecond
        while (s <= currentSecond)
        {
            val idx = ringIndex(s)
            val epoch = epochSeconds.get(idx)
            if (epoch == s)
            {
                total += counts.get(idx)
            }
            s++
        }
        return total
    }

    private fun computeCountsByWindow(
        epochSeconds : AtomicLongArray,
        counts : AtomicLongArray,
        nowMillis : Long
    ) : Map<Window, Long>
    {
        // LinkedHashMap so iteration order matches the enum's
        // declaration order (ONE_MIN, FIVE_MIN, FIFTEEN_MIN). Every
        // Window key is always present, with value 0 if no traffic
        // has been observed in that window — easier for downstream
        // consumers than a sparse map.
        val result = LinkedHashMap<Window, Long>()
        for (window in Window.entries)
        {
            result[window] = countInWindow(epochSeconds, counts, window, nowMillis)
        }
        return result
    }

    /**
     * Map an epoch second to a slot index in `[0, RING_SIZE)`. Uses
     * floor-style modulo so negative seconds (e.g. when nowMillis is
     * very small relative to window.seconds) still index correctly.
     */
    private fun ringIndex(epochSecond : Long) : Int
    {
        val mod = (epochSecond % RING_SIZE).toInt()
        return if (mod < 0) mod + RING_SIZE else mod
    }

    /**
     * Immutable snapshot of the tracker's state at a single instant.
     * The two `last*SeenAtMillis` fields are `null` until the first
     * observation of their respective origin. The two
     * `*CountsByWindow` maps always contain all three [Window] keys,
     * zero-valued when no traffic has been observed in that window.
     */
    data class Snapshot(
        val lastClientSeenAtMillis : Long?,
        val lastServerSeenAtMillis : Long?,
        val nowMillis : Long,
        val clientCountsByWindow : Map<Window, Long> = emptyMap(),
        val serverCountsByWindow : Map<Window, Long> = emptyMap()
    )
    {
        /**
         * @param thresholdMillis Idle threshold in milliseconds.
         * @return `true` if no client traffic has been observed in
         *   the last [thresholdMillis], or none at all.
         */
        fun isClientIdle(thresholdMillis : Long) : Boolean
        {
            val last = lastClientSeenAtMillis ?: return true
            return (nowMillis - last) >= thresholdMillis
        }

        /**
         * @param thresholdMillis Idle threshold in milliseconds.
         * @return `true` if no server traffic has been observed in
         *   the last [thresholdMillis], or none at all.
         */
        fun isServerIdle(thresholdMillis : Long) : Boolean
        {
            val last = lastServerSeenAtMillis ?: return true
            return (nowMillis - last) >= thresholdMillis
        }

        /**
         * @return `true` only if both client and server are idle.
         */
        fun isFullyIdle(thresholdMillis : Long) : Boolean =
            isClientIdle(thresholdMillis) && isServerIdle(thresholdMillis)

        /**
         * @return Client RPCs in the last [window.seconds] seconds,
         *   or `0` when the snapshot was taken on a fresh tracker.
         */
        fun clientCountInWindow(window : Window) : Long =
            clientCountsByWindow[window] ?: 0L

        /**
         * @return Server RPCs in the last [window.seconds] seconds,
         *   or `0` when the snapshot was taken on a fresh tracker.
         */
        fun serverCountInWindow(window : Window) : Long =
            serverCountsByWindow[window] ?: 0L

        /**
         * @return Combined client + server RPCs in the last
         *   [window.seconds] seconds, or `0` on a fresh tracker.
         */
        fun totalCountInWindow(window : Window) : Long =
            clientCountInWindow(window) + serverCountInWindow(window)
    }

    /**
     * Immutable view of the frequency subsystem at a single instant.
     * Both `clientCounts` and `serverCounts` are guaranteed to
     * contain all three [Window] keys, with zero values when no
     * traffic has been observed in that window.
     */
    data class FrequencyStats(
        val nowMillis : Long,
        val clientCounts : Map<Window, Long>,
        val serverCounts : Map<Window, Long>
    )
    {
        /**
         * @return Client RPCs in the last [window.seconds] seconds.
         */
        fun clientCount(window : Window) : Long = clientCounts[window] ?: 0L

        /**
         * @return Server RPCs in the last [window.seconds] seconds.
         */
        fun serverCount(window : Window) : Long = serverCounts[window] ?: 0L

        /**
         * @return Combined client + server RPCs in the last
         *   [window.seconds] seconds.
         */
        fun totalCount(window : Window) : Long =
            clientCount(window) + serverCount(window)

        /**
         * @return Client RPCs/second over the last
         *   [window.seconds] seconds.
         */
        fun clientRatePerSecond(window : Window) : Double =
            clientCount(window).toDouble() / window.seconds

        /**
         * @return Server RPCs/second over the last
         *   [window.seconds] seconds.
         */
        fun serverRatePerSecond(window : Window) : Double =
            serverCount(window).toDouble() / window.seconds

        /**
         * @return Combined client + server RPCs/second over the last
         *   [window.seconds] seconds.
         */
        fun totalRatePerSecond(window : Window) : Double =
            totalCount(window).toDouble() / window.seconds
    }

    /**
     * Sliding time window used by the frequency subsystem. Each
     * [Window] maps to a number of seconds; the count over the
     * window is the sum of the per-second ring-buffer slots whose
     * epoch second falls within the window. All three windows share
     * the same 900-slot ring buffer per origin.
     */
    enum class Window(val seconds : Int)
    {
        /** Last 60 seconds. Useful for short-burst detection. */
        ONE_MIN(60),

        /** Last 300 seconds. Useful for sustained-rate monitoring. */
        FIVE_MIN(300),

        /** Last 900 seconds. The largest window the ring buffer
         *  covers. Useful as the "no traffic at all" baseline. */
        FIFTEEN_MIN(900)
    }
}
