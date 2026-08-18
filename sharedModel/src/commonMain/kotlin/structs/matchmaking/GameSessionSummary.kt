package structs.matchmaking

import kotlinx.serialization.Serializable

/**
 * Compact player summary for the [GameSessionSummary] query surface.
 *
 * Exposed via `server.extend.getStatus` and the Phase 3
 * `GET /admin/status` route so the operator can see who's connected
 * to each running session without pulling the full
 * [PlayerSessionBundle] (which carries the selected commander and
 * alias — fine for internal use, not what the operator wants to
 * see in a dashboard).
 */
@Serializable
data class PlayerSummary(
    val playerId: String,
    val accelByteId: String,
    val isHuman: Boolean
)

/**
 * Operator-facing summary of one active matchmaking session.
 *
 * Built by [org.ttt.autogenesis.serverextend.matchmaking.ServerConnector.activeSessionSummaries]
 * (Phase 2 cost-tracking plan). The shape is additive to
 * [GameSessionStatus]: the operator gets startedAt/ageMillis plus
 * the trimmed player list, and the internal-only fields
 * (resumeFromVfs, resumeUserId, isFull, etc.) are omitted.
 *
 * `startedAtMillis` is `null` only for legacy sessions that were
 * registered before Phase 2 shipped (their [GameSessionStatus.startedAtMillis]
 * is the default 0L). New sessions always stamp it.
 *
 * `ageMillis` is computed against the supplied `nowMillis` so the
 * summary is deterministic under test injection. A null
 * `startedAtMillis` produces a null `ageMillis`.
 */
@Serializable
data class GameSessionSummary(
    val sessionId: String,
    val serverUrl: String,
    val gameType: GameType,
    val players: List<PlayerSummary>,
    /**
     * Wall-clock millis at which the session was first registered.
     * Null for legacy records that pre-date Phase 2.
     */
    val startedAtMillis: Long?,
    /**
     * `nowMillis - startedAtMillis` computed by the summary builder.
     * Null when `startedAtMillis` is null.
     */
    val ageMillis: Long?
)