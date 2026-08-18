package structs.account

/**
 * Decision returned by [QuotaGate.evaluate]. The game server branches on the
 * concrete subtype: Allow continues normally, SoftWarn shows a warning
 * overlay, BlockNewGame refuses to start a new game (with a "buy credits
 * or upgrade" message), InterruptAndEnd saves the in-progress single-player
 * game and ends it.
 */
sealed class QuotaDecision
{
    /** Proceed. */
    data object Allow : QuotaDecision()

    /**
     * Proceed, but show a soft warning overlay. Used when usage crosses 80%
     * but the player is still under cap.
     */
    data class SoftWarn(val usedFraction: Double, val remainingTokens: Long) : QuotaDecision()

    /** Refuse to start a new game. In-progress multiplayer games are unaffected. */
    data object BlockNewGame : QuotaDecision()

    /**
     * Single-player game at cap — interrupt the current game, save it, end it.
     * Multiplayer never sees this branch (it uses BlockNewGame at game start).
     */
    data object InterruptAndEnd : QuotaDecision()
}

/**
 * Single source of truth for "may this player run a turn in this mode?".
 *
 * Pure function over [AccountSettings] + token count + game mode. Lives in
 * commonMain so both the game server (JVM) and server-extend (JVM) call the
 * same evaluator — no per-module duplication.
 */
object QuotaGate
{
    /** Warn the player at 80% of cap. */
    private const val SOFT_WARN_THRESHOLD = 0.80

    /**
     * Decides whether [player] may run a turn in [gameMode].
     *
     * @param player the player's current AccountSettings (post-cycle-reset)
     * @param gameMode which mode the player is attempting
     * @param isGameStart true if this is the first turn of a new game; false
     *   for an in-progress game (multiplayer lets in-progress finish).
     * @param isSinglePlayer true if [gameMode] is `ONE_V_ONE` or `ONE_V_THREE`.
     *   Single-player games interrupt + save + end at cap (per operator
     *   decision in the 2026-07-31 design).
     * @param isDeveloper true when the developer-mode detector flagged the
     *   service as local. Overrides every other gate.
     */
    fun evaluate(
        player: AccountSettings,
        gameMode: GameMode,
        isGameStart: Boolean,
        isSinglePlayer: Boolean,
        isDeveloper: Boolean
    ): QuotaDecision
    {
        if (isDeveloper) return QuotaDecision.Allow

        val plan = player.billingStatus.plan
        if (gameMode !in plan.unlocksModes) return QuotaDecision.BlockNewGame

        val used = player.billingStatus.tokensUsedThisCycle
        val cap = player.billingStatus.tokensCapPerCycle
        if (cap <= 0L) return QuotaDecision.Allow // uninitialised cycle — let the first flush populate

        val fraction = used.toDouble() / cap.toDouble()

        if (used >= cap)
        {
            return when
            {
                isGameStart && isSinglePlayer -> QuotaDecision.InterruptAndEnd
                isGameStart && !isSinglePlayer -> QuotaDecision.BlockNewGame
                else -> QuotaDecision.Allow // in-progress game continues
            }
        }

        return if (fraction >= SOFT_WARN_THRESHOLD)
        {
            QuotaDecision.SoftWarn(fraction, cap - used)
        }
        else
        {
            QuotaDecision.Allow
        }
    }
}
