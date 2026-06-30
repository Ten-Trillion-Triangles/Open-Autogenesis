package accelbyte.session

/**
 * In-process event emitted by the [gameState.WorldManager] when a match
 * transitions from `isGameActive = true` to `false` and the session should
 * be persisted via [SessionStorageHandler.writeSessionStorage]. Carries the
 * fields the storage handler needs to build a [SessionStorageHandler.GameState].
 *
 * @property sessionId The AccelByte game session ID this match belonged to.
 *                     Mirrors [serverStructs.MatchResult.sessionId].
 * @property outcome Human-readable outcome string. Mirrors the `matchOutcome`
 *                    field on the persisted [SessionStorageHandler.GameState];
 *                    typical values are `"victory"`, `"defeat"`, `"draw"`,
 *                    `"forced_end"`, `"abandoned"`. Callers may pass any
 *                    non-blank string; the storage layer does not validate
 *                    the set of values.
 * @property winnerName Display name of the winning player, or `null` if the
 *                      match ended without a winner (e.g. draw or forced
 *                      end). Mirrors [SessionStorageHandler.buildGameState]'s
 *                      `winnerName` parameter.
 */
data class MatchEndedEvent(
    val sessionId: String,
    val outcome: String,
    val winnerName: String? = null
)
