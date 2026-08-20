package org.ttt.autogenesis.server

import gameState.WorldManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcDirection
import org.ttt.autogenesis.network.RpcMethod
import structs.Player
import org.ttt.autogenesis.server.vfs.VirtualFileSystemManager
import structs.storage.RUNNING_GAME_KEY

/**
 * RPC handlers that drive the single-player "resume saved game" flow.
 *
 * The disconnect→save→restore loop lives on the server side. The player
 * triggers it from the [ui.MainMenu] (Kotlin/JS) on the dev bundle, or via
 * the standard matchmaking path on the live bundle — both of which need a
 * way to:
 *
 * 1. Check whether a saved running-game record exists for the current
 *    human player (so the UI can decide whether to render a "Resume?"
 *    modal). See [hasRunningGame].
 * 2. Force a rehydrate of the saved state on a server that already has
 *    stale [WorldManager] data — this is the dev path where the in-process
 *    server is still alive between sessions. See [restoreRunningGame].
 *
 * Live mode does not call [restoreRunningGame]: the new dedicated server
 * the matchmaker provisions auto-restores on first connect. But the modal
 * uses [hasRunningGame] against the active WebSocket (which the matchmaker
 * routes to a DS), so this file needs to work for either transport.
 */
object GameRestoreRpcHandlers
{
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Returns `true` when the calling human player has a saved running-game
     * record. Used by the KVision [ui.MainMenu] to decide whether to show
     * a "Resume saved game?" modal.
     *
     * The lookup is best-effort: if anything throws (missing VFS, no
     * player mapping for the WS connection, transport error) we treat it
     * as "no saved game" so the modal stays out of the way.
     *
     * The "exists" check is more than just "is there a value in the
     * record". We attempt to deserialize the value as a [gameState.GameSnapshot]
     * — the consumed-sentinel written by [TurnHarness.invalidateRunningGameRecord]
     * is also a present value, but it intentionally fails that
     * deserialization so we treat it as "no saved game". Only a record
     * whose value is a real, parseable [GameSnapshot] is reported as a
     * running game.
     */
    @RpcMethod("server.hasRunningGame", RpcDirection.SERVER)
    suspend fun hasRunningGame(ctx: RpcCallContext): Boolean
    {
        val userId = resolveHumanUserId(ctx)
        if (userId.isBlank())
        {
            Logger.debug(LogCategory.SYSTEM, "GameRestoreRpcHandlers.hasRunningGame: no human userId for connection=${ctx.connectionId}, returning false")
            return false
        }

        // Phase A: capture the VFS-lookup result, then check world-state
        // race-recovery. The auto-restore on connect (Server.kt:295) consumes
        // the record (consumed-sentinel after delete-fail) before this RPC
        // arrives, so the VFS-lookup alone returns false. If the world is
        // already restored for this user, treat the modal as eligible.
        val vfsReported = try
        {
            val vfs = VirtualFileSystemManager.forUser(userId)
            val fetch = vfs.fetchUserRecord(userId, RUNNING_GAME_KEY)
            fetch.fold(
                onSuccess = { response ->
                    val raw = response.value
                    val jsonString = if (raw is kotlinx.serialization.json.JsonObject && raw.containsKey("value"))
                    {
                        raw["value"].toString()
                    }
                    else
                    {
                        raw?.toString()
                    }
                    if (jsonString.isNullOrBlank())
                    {
                        Logger.debug(LogCategory.SYSTEM, "GameRestoreRpcHandlers.hasRunningGame: user=$userId value blank, exists=false")
                        return@fold false
                    }
                    val snapshot = runCatching { com.TTT.Util.deserialize<gameState.GameSnapshot>(jsonString) }.getOrNull()
                    val exists = snapshot != null
                    Logger.debug(LogCategory.SYSTEM, "GameRestoreRpcHandlers.hasRunningGame: user=$userId vfs-exists=$exists (round=${snapshot?.world?.roundNumber})")
                    exists
                },
                onFailure = { err ->
                    val msg = err.message ?: ""
                    val isNotFound = err is org.ttt.autogenesis.server.vfs.RecordNotFoundException ||
                        msg.contains("not found", ignoreCase = true)
                    Logger.debug(LogCategory.SYSTEM, "GameRestoreRpcHandlers.hasRunningGame: user=$userId notFound=$isNotFound (msg=$msg)")
                    false
                }
            )
        }
        catch (e: Exception)
        {
            Logger.warn(LogCategory.SYSTEM, "GameRestoreRpcHandlers.hasRunningGame: lookup failed for user=$userId: ${e.message}")
            false
        }

        if (vfsReported)
        {
            return true
        }

        // Race-recovery: auto-restore on connect may have already applied the
        // snapshot. If the world is non-empty AND this user is in playerStats,
        // the resume path is still valid even though the VFS record is now
        // a consumed-sentinel.
        if (isWorldAlreadyRestoredForUser(userId))
        {
            Logger.info(
                LogCategory.SYSTEM,
                "GameRestoreRpcHandlers.hasRunningGame: user=$userId — auto-restore already applied the snapshot; race-recovered to exists=true (round=${WorldManager.world.roundNumber})"
            )
            return true
        }

        return false
    }

    /**
     * Rehydrates [WorldManager]/[TurnHarness] from the calling human player's
     * saved running-game record. The record is deleted on success (delete-on-restore
     * TTL), the [UiSignalRpcHandlers.sendInitialSync] notification is fired to
     * the calling connection, and the resumed round number is returned to the
     * caller so the UI can decide whether to mount gameplay or stay in the menu.
     *
     * Returns `false` when no saved game exists; the caller should keep the user
     * in the menu and offer a "New Game" path instead. Errors (VFS unreachable,
     * deserialization, etc.) also return `false` so the UI shows a recoverable
     * "Failed to resume" message rather than a hard crash.
     */
    @RpcMethod("server.restoreRunningGame", RpcDirection.SERVER)
    suspend fun restoreRunningGame(ctx: RpcCallContext): Boolean
    {
        val userId = resolveHumanUserId(ctx)
        if (userId.isBlank())
        {
            Logger.warn(LogCategory.SYSTEM, "GameRestoreRpcHandlers.restoreRunningGame: no human userId for connection=${ctx.connectionId}, returning false")
            return false
        }

        val restored = TurnHarness.restoreWorldFromUserRecord(userId, ctx.connectionId)
        val success = restored.getOrDefault(false)
        if (success)
        {
            return applyRestoredWorldAndSync(ctx, userId, "fresh-restore")
        }

        // Race recovery: auto-restore on connect (Server.kt:295) and the
        // Resume click in ResumeOrNewDialog (MainMenu.kt:127) race each
        // other on the WS connect. When auto-restore wins, the snapshot
        // has already been applied to WorldManager AND the consumed-sentinel
        // has replaced the real snapshot in VFS. The fetch inside
        // restoreWorldFromUserRecord above now returns the sentinel, which
        // intentionally fails GameSnapshot deserialization, so the helper
        // returns Result.failure even though the world is in fact resumed.
        //
        // Detect that state via two cheap WorldManager predicates — a
        // non-empty world plus a playerStats entry for the calling user —
        // and treat it as idempotent success. The user already has the
        // resumed game on the server; we just need to push initial sync
        // so the client knows it can mount gameplay.
        val raceRecovered = isWorldAlreadyRestoredForUser(userId)
        if (raceRecovered)
        {
            Logger.info(
                LogCategory.SYSTEM,
                "GameRestoreRpcHandlers.restoreRunningGame: user=$userId — auto-restore already applied the snapshot; treating Resume click as idempotent success."
            )
            return applyRestoredWorldAndSync(ctx, userId, "race-recovered")
        }

        restored.exceptionOrNull()?.let { err ->
            Logger.warn(LogCategory.SYSTEM, "GameRestoreRpcHandlers.restoreRunningGame: restore failed for user=$userId: ${err.message}")
        }
        return false
    }

    /**
     * Shared tail for both the fresh-restore and race-recovered paths:
     * push the resumed world to the calling connection, returning
     * `true` once the sync notification is dispatched (or best-effort
     * logged if the session cannot be resolved).
     *
     * CRITICAL: must send to the CURRENT calling WS connection (via
     * `ctx.connectionId`), NOT the saved `stats.playerID`. The snapshot's
     * `playerID` is the WS playerId that was alive at the time of
     * capture — it does NOT match the new browser's WS playerId after
     * the user logs back in. Sending to `stats.playerID` would dispatch
     * the initial sync to a stale (likely disconnected) session, so the
     * new browser would mount an empty GameplayUI ("the turn resumed"
     * would be FALSE because the world data never arrived).
     */
    private suspend fun applyRestoredWorldAndSync(ctx: RpcCallContext, userId: String, path: String): Boolean
    {
        // Prefer the calling connection id — that is the WS the user's
        // NEW browser is connected on right now. The human player's
        // WorldManager.playerStats entry has the SAVED playerId, which
        // is stale and would dispatch the sync to a non-existent
        // session.
        val stats = WorldManager.playerStats.firstOrNull { it.accelByteUserId == userId }
        if (stats == null)
        {
            Logger.warn(
                LogCategory.SYSTEM,
                "GameRestoreRpcHandlers.restoreRunningGame ($path): no playerStats for user=$userId after restore; initial sync skipped (UI may need a manual refresh)"
            )
        }
        try
        {
            UiSignalRpcHandlers.sendInitialSync(
                connectionId = ctx.connectionId,
                localPlayer = stats?.playerData ?: Player(),
                mapPackBytes = WorldManager.activeMapPackBytes,
                world = WorldManager.world,
                history = WorldManager.history,
                accelByteUserId = userId
            )
        }
        catch (e: Exception)
        {
            Logger.warn(LogCategory.SYSTEM, "GameRestoreRpcHandlers.restoreRunningGame ($path): sendInitialSync failed for user=$userId: ${e.message}")
        }

        Logger.info(
            LogCategory.SYSTEM,
            "GameRestoreRpcHandlers.restoreRunningGame ($path): user=$userId resumed round=${WorldManager.world.roundNumber} (sync sent to connectionId=${ctx.connectionId})"
        )

        // Clear the rehydrated flag now that the initial sync has been
        // dispatched. The WS session now knows the world is restored, so
        // subsequent race-recovery checks should NOT consider this user
        // "still being restored" (which would cause double-sync if the
        // user disconnects and reconnects within the flag's TTL).
        WorldManager.clearRehydratedFlag()

        return true
    }

    /**
     * Variant of [restoreRunningGame] that takes a userId directly instead
     * of an [RpcCallContext]. Used by [gameInit.GameInit.defineGameRules]
     * when a resume session is being bootstrapped (Phase D) — the
     * `resumeUserId` arrives in the [GameSessionStatus] payload, not in
     * the calling WS connection.
     *
     * Returns `true` on successful rehydrate (including the race-recovered
     * path where the world was already restored by the auto-restore on
     * connect), `false` if the VFS has no record or the world is empty.
     */
    suspend fun restoreRunningGameForUser(userId: String): Boolean
    {
        if (userId.isBlank())
        {
            Logger.warn(LogCategory.SYSTEM, "GameRestoreRpcHandlers.restoreRunningGameForUser: blank userId, returning false")
            return false
        }
        val synthetic = RpcCallContext(
            connectionId = WorldManager.playerStats
                .firstOrNull { it.accelByteUserId == userId }
                ?.playerID
                ?: "",
            sender = { _ -> }
        )
        return restoreRunningGame(synthetic)
    }

    /**
     * True when [WorldManager] already reflects the calling user's
     * restored snapshot. Used to distinguish the auto-restore race from
     * a genuine restore failure: a non-empty world with a
     * [serverStructs.PlayerStats] entry for the same AccelByte user id
     * is the post-condition [TurnHarness.restoreWorldFromUserRecord]
     * leaves behind, so it is the cleanest signal that auto-restore got
     * there first.
     */
    private fun isWorldAlreadyRestoredForUser(userId: String): Boolean
    {
        // The rehydrated flag is the authoritative race-recovery signal.
        // `WorldManager.isWorldEmpty()` (the previous check) cannot
        // distinguish "round-1 snapshot just rehydrated with no history"
        // from "fresh server with no game" — both have `roundNumber <= 1
        // && history.isEmpty()`. The race-recovery branch needs to detect
        // the resumed state, so we check the flag set by
        // `TurnHarness.applyGameSnapshot` instead.
        //
        // See `WorldManager.lastRehydratedAccelByteUserId` docstring for
        // the full rationale.
        return WorldManager.lastRehydratedAccelByteUserId == userId
    }

    /**
     * Deletes the calling human player's saved running-game record.
     *
     * Called by the KVision "New Game" flow right before kicking off a new
     * single-player match, so the next disconnect captures the new game's
     * state cleanly instead of resurrecting the prior session.
     *
     * Returns `true` on a successful delete (including the not-found no-op),
     * `false` on a transport or VFS error so the UI can surface a failure
     * message. Game flow is not gated on the result — a stale snapshot
     * just means the next disconnect will overwrite it.
     */
    @RpcMethod("server.clearRunningGame", RpcDirection.SERVER)
    suspend fun clearRunningGame(ctx: RpcCallContext): Boolean
    {
        val userId = resolveHumanUserId(ctx)
        if (userId.isBlank())
        {
            Logger.debug(LogCategory.SYSTEM, "GameRestoreRpcHandlers.clearRunningGame: no human userId for connection=${ctx.connectionId}, returning true (no-op)")
            return true
        }
        val result = TurnHarness.clearRunningGameForUser(userId)
        return result.isSuccess
    }

    /**
     * Reports the auto-restore status for a user. Called by server-extend's
     * `ResumeAvailabilityPushService` to wait until the main server has
     * finished processing a snapshot before pushing `client.resumeAvailable`.
     *
     * Without this RPC, server-extend reads the snapshot at one moment and
     * pushes based on it, but the main server's auto-restore (running on
     * `Dispatchers.IO`) may consume the snapshot milliseconds later. The
     * user's Resume click then hits the consumed-sentinel and surfaces as
     * "No saved game found."
     *
     * @return [RestoreStatusResponse] with `restoreInProgress=true` if a
     *   restore is still in flight, and `rehydrated=true` if the rehydrated
     *   flag is set for this user.
     */
    @RpcMethod("server.restoreStatus", RpcDirection.SERVER)
    suspend fun restoreStatus(ctx: RpcCallContext, request: org.ttt.autogenesis.network.RestoreStatusRequest): org.ttt.autogenesis.network.RestoreStatusResponse
    {
        val userId = request.userId
        if (userId.isBlank())
        {
            Logger.debug(LogCategory.SYSTEM, "GameRestoreRpcHandlers.restoreStatus: blank userId, returning both=false")
            return org.ttt.autogenesis.network.RestoreStatusResponse(restoreInProgress = false, rehydrated = false)
        }
        val inProgress = WorldManager.isRestoreInProgress(userId)
        val rehydrated = WorldManager.lastRehydratedAccelByteUserId == userId
        return org.ttt.autogenesis.network.RestoreStatusResponse(
            restoreInProgress = inProgress,
            rehydrated = rehydrated
        )
    }

    /**
     * Resolves the AccelByte user id backing the WebSocket session that fired
     * the RPC.
     *
     * Resolution order:
     *  1. `ctx.metadata["accelbyteId"]` — populated by
     *     [PlayerSession.handleFrame] from the WebSocket query string. This is
     *     the only signal that is available on a fresh dedicated server
     *     before the matchmaker injects the human player's stats, so it MUST
     *     be checked first.
     *  2. The [serverStructs.PlayerStats] entry whose
     *     [serverStructs.PlayerStats.playerID] matches the WS connection id.
     *  3. The human player's name in [WorldManager.humanPlayerName].
     */
    private fun resolveHumanUserId(ctx: RpcCallContext): String
    {
        val fromMetadata = ctx.metadata["accelbyteId"].orEmpty()
        if (fromMetadata.isNotBlank())
        {
            return fromMetadata
        }
        val byConnection = WorldManager.playerStats.firstOrNull { it.playerID == ctx.connectionId }
        if (byConnection != null && byConnection.accelByteUserId.isNotBlank())
        {
            return byConnection.accelByteUserId
        }
        val byName = WorldManager.findPlayerFromStats(WorldManager.humanPlayerName)
        return byName?.accelByteUserId.orEmpty()
    }
}