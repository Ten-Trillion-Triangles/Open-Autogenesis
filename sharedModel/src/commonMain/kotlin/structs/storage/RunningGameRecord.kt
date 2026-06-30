package structs.storage

/**
 * Reusable key for the per-user "running game" record.
 *
 * In single-player mode the server captures a [gameState.GameSnapshot] of the
 * currently running match and stores it under this key on the player's
 * AccelByte account record. When the player reconnects (or the DS restarts)
 * the server reads this key and rehydrates [gameState.WorldManager] so the
 * match can be resumed from the exact point where it was paused.
 *
 * @see gameState.GameSnapshot
 */
const val RUNNING_GAME_KEY: String = "running-game"
