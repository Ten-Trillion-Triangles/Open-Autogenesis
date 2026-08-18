package org.ttt.autogenesis.serverextend

import kotlinx.serialization.Serializable
import structs.matchmaking.GameSessionSummary

/**
 * Operator-facing JSON shape for the cost-control query surface
 * (Phase 3 of the cost-tracking plan).
 *
 * Wire shape:
 * {
 *   "nowMillis": 1700000000000,
 *   "activeSessionCount": 2,
 *   "oldestSessionAgeMillis": 300000,
 *   "sessions": [ { GameSessionSummary } ],
 *   "realActivity": {
 *     "lastClientRealActivityAtMillis": 1700000000000 | null,
 *     "lastServerRealActivityAtMillis": 1700000000000 | null,
 *     "isClientIdleForRealActivity": true,
 *     "isServerIdleForRealActivity": true,
 *     "idleThresholdMinutes": 15
 *   },
 *   "isFullyIdleForRealActivity": false
 * }
 *
 * The `sessions` array is empty when no matchmaking sessions are
 * active. The `realActivity` block is populated from
 * [globals.RpcUsageQuery] + [globals.RpcUsageTracker]. The two
 * booleans are derived from the real-activity idle signals with the
 * same threshold [globals.ExtendConfig.idleThresholdMinutes].
 *
 * `isFullyIdleForRealActivity` is the cost-control spin-down signal:
 * when it flips `false → true`, the JVM is safe to scale to zero.
 */
@Serializable
data class StatusSnapshotDto(
    val nowMillis : Long,
    val activeSessionCount : Int,
    val oldestSessionAgeMillis : Long?,
    val sessions : List<GameSessionSummary>,
    val realActivity : RealActivityBlock,
    val isFullyIdleForRealActivity : Boolean
)

/**
 * Real-activity subset of the status snapshot. Mirrors the
 * [globals.RpcUsageTracker.Snapshot] fields that drive the
 * spin-down decision.
 */
@Serializable
data class RealActivityBlock(
    val lastClientRealActivityAtMillis : Long?,
    val lastServerRealActivityAtMillis : Long?,
    val isClientIdleForRealActivity : Boolean,
    val isServerIdleForRealActivity : Boolean,
    val idleThresholdMinutes : Long
)