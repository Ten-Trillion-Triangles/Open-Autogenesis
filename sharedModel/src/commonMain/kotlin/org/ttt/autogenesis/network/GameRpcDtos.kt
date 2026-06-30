package org.ttt.autogenesis.network

import kotlinx.serialization.Serializable
import structs.Player

/**
 * Response for ping RPC calls.
 *
 * @property echo Echo string from the request
 * @property timestamp Timestamp when response was generated
 */
@Serializable
data class PingResponse(
    val echo : String,
    val timestamp : Long
)

/**
 * Request for looking up a player by name.
 *
 * @property playerName Name of the player to find
 */
@Serializable
data class PlayerLookupRequest(
    val playerName : String
)

/**
 * Response for player lookup requests.
 *
 * @property found Whether the player was found
 * @property player Player data if found, null otherwise
 */
@Serializable
data class PlayerLookupResponse(
    val found : Boolean,
    val player : Player? = null
)
@Serializable
data class ActionSubmitRequest(
    val action : String,
    val playerName : String
)

@Serializable
data class CounterActionSubmitRequest(
    val playerName : String,
    val response : String?
)

@Serializable
data class WorldUpdateData(
    val world : structs.World
)

@Serializable
data class MapUploadRequest(
    val mapPackBytes: ByteArray,
    val mapName: String = ""
)

@Serializable
data class MapLoadInstruction(
    val mapPackBytes: ByteArray
)

/**
 * Request for the `game.surrender` RPC. The calling client must be the
 * connection that owns the target [playerName]; the server rejects
 * attempts to surrender another player's slot. Reason is optional and is
 * stored in the action history / Details panel.
 *
 * @property playerName Name of the human player who is conceding the match.
 * @property reason Optional short reason for the surrender (shown in the Details panel).
 */
@Serializable
data class SurrenderRequest(
    val playerName: String,
    val reason: String = ""
)

/**
 * Response for the `game.surrender` RPC.
 *
 * @property accepted Whether the surrender request was applied. False for any
 *   rejection reason (e.g. unknown player, AI-controlled, already surrendered,
 *   game not active, caller is not the connection that owns the player).
 * @property reason Machine-readable rejection / status code. Empty on success.
 * @property gameEnded True if the surrender caused the match to end.
 * @property winnerName Name of the winning player/NPC, populated only when
 *   [gameEnded] is true.
 */
@Serializable
data class SurrenderResponse(
    val accepted: Boolean,
    val reason: String = "",
    val gameEnded: Boolean = false,
    val winnerName: String? = null
)

/**
 * Response for the `server.restoreStatus` RPC.
 *
 * Tells a server-extend caller (e.g. [ResumeAvailabilityPushService]) whether
 * the main server has finished the auto-restore pass for this user, and
 * whether the rehydrated flag is currently set.
 *
 * @property restoreInProgress True while the auto-restore coroutine for this
 *   user is still running on `Dispatchers.IO`. False once it has completed
 *   (or was a no-op because the world was already non-empty). Used by
 *   server-extend to wait before pushing `client.resumeAvailable`, so the
 *   push is never based on a snapshot the main server is about to consume.
 * @property rehydrated True if [WorldManager.lastRehydratedAccelByteUserId]
 *   matches the queried user. Useful for differentiating "restore completed
 *   and the world is restored" from "no restore needed (world was empty AND
 *   no snapshot existed)".
 */
@Serializable
data class RestoreStatusResponse(
    val restoreInProgress: Boolean,
    val rehydrated: Boolean
)

/**
 * Request for the `server.restoreStatus` RPC.
 *
 * @property userId The AccelByte user id to query. The main server looks up
 *   the in-flight restore registry and the rehydrated flag for this user
 *   (no auth check on this RPC — server-extend and the main server are
 *   trusted components in the same deployment).
 */
@Serializable
data class RestoreStatusRequest(
    val userId: String
)
