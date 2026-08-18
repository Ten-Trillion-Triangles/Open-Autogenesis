package org.ttt.autogenesis.server

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression test for the perspective-shift bug.
 *
 * The bug: when the human's turn ends in single-player mode, the loop
 * advances to the next actor (an AI opponent). `executeSingleTurn` then
 * broadcasts `ActiveTurnData(actorName = actor)` where `actor` is the
 * *currently-executing* turn's actor — NOT the human viewer. The UI's
 * `handleActiveTurn` receives `actorName` for the AI opponent and
 * shifts the perspective to that character, even though the user is the
 * only human in a single-player game and the perspective should stay
 * anchored to the human.
 *
 * Fixed at server/src/main/kotlin/org/ttt/autogenesis/server/TurnHarness.kt:1381-1386.
 * The fix gates the broadcast on `WorldManager.isSinglePlayer` so the
 * perspective anchor is always the human's name (the only reachable
 * defender in single-player mode) regardless of which NPC/AI opponent
 * is currently executing.
 *
 * Contract tested here:
 *   1. The broadcast block in `executeSingleTurn` must contain an
 *      `isSinglePlayer` guard OR must read `actorName` from the human
 *      player-stats lookup, not from the bare `actor` local variable.
 *   2. The `advanceTurnIndexAndRoundIfNeeded` function must also gate
 *      its `activeTurnActor` assignment on `isSinglePlayer` so the
 *      human-locked field stays human-locked across turn advances.
 *
 * Contract surface pinned via source-grep on the production source.
 * If a future edit removes the guard or removes the human-anchor lookup,
 * this test fails.
 */
class TurnHarnessSinglePlayerPerspectiveBroadcastTest
{
    private val turnHarnessPath: String =
        "src/main/kotlin/org/ttt/autogenesis/server/TurnHarness.kt"

    private fun loadSource(): String
    {
        val file = File(turnHarnessPath)
        require(file.exists()) { "TurnHarness.kt not found at $turnHarnessPath (cwd=${File(".").absolutePath})" }
        return file.readText()
    }

    /**
     * Locate the broadcast active-turn block. The expected anchor is the
     * `UiSignalRpcHandlers.broadcastActiveTurn(` call inside
     * `executeSingleTurn`. We use a unique substring to bound the search.
     */
    private fun extractBroadcastBlock(source: String): String
    {
        val anchor = "Broadcasting ActiveTurnData for actor="
        val start = source.indexOf(anchor)
        require(start >= 0) { "Broadcast block anchor not found in TurnHarness.kt" }
        // Find the next unconditional branch — typically the next function
        // boundary or a known post-broadcast sentinel. Use the `Web Push`
        // comment as the upper bound because it consistently follows the
        // broadcast in the source.
        val end = source.indexOf("// Web Push notification:", start)
        require(end > start) { "End-of-broadcast anchor not found after broadcast block" }
        return source.substring(start, end)
    }

    /**
     * Extract the block between the `advanceTurnIndexAndRoundIfNeeded`
     * function signature and the next top-level `private suspend fun` or
     * `private fun` declaration.
     */
    private fun extractAdvanceBlock(source: String): String
    {
        val anchor = "private suspend fun advanceTurnIndexAndRoundIfNeeded()"
        val start = source.indexOf(anchor)
        require(start >= 0) { "advanceTurnIndexAndRoundIfNeeded not found in TurnHarness.kt" }
        // Find the next top-level private function after the advance one.
        val rest = source.substring(start + anchor.length)
        val nextDecl = Regex("""^\s*(private fun|private suspend fun|fun |internal fun|internal suspend fun)""", RegexOption.MULTILINE)
            .find(rest)
        return if (nextDecl != null) {
            source.substring(start, start + anchor.length + nextDecl.range.first)
        } else {
            source.substring(start)
        }
    }

    /**
     * Contract: the broadcast block must gate on `isSinglePlayer` so the
     * perspective anchor is the human's name, not the bare `actor` local.
     * The gate can be expressed as either:
     *   (a) `if (WorldManager.isSinglePlayer) { ... } else { ... }` form
     *   (b) `actorName = if (isSinglePlayer) { humanName } else { actor }` ternary
     *   (c) any shape that reads `WorldManager.isSinglePlayer` and resolves
     *       `actorName` to the human-player name in the single-player branch.
     */
    @Test
    fun broadcastActiveTurn_mustGateOnSinglePlayer()
    {
        val source = loadSource()
        val block = extractBroadcastBlock(source)

        val isSinglePlayerPresent = block.contains("WorldManager.isSinglePlayer")
        val humanAnchorPresent = block.contains("humanPlayerName") ||
            block.contains("!it.isControlledByNpc") ||
            block.contains("firstOrNull { !it.isControlledByNpc }")

        assertTrue(
            isSinglePlayerPresent,
            "executeSingleTurn broadcast block does not gate on WorldManager.isSinglePlayer. " +
                "Without the gate, the perspective shifts to whichever AI opponent's turn is executing. " +
                "Block under inspection:\n$block"
        )
        assertTrue(
            humanAnchorPresent,
            "executeSingleTurn broadcast block does not anchor actorName to the human's name. " +
                "The gate must resolve actorName to the human's name (via humanPlayerName or the first " +
                "playerStats entry where !isControlledByNpc). Block under inspection:\n$block"
        )
    }

    /**
     * Contract: the advance-turn block must also gate on `isSinglePlayer`
     * so the `activeTurnActor` field stays locked to the human in single-player
     * mode. Without this, the field flips to the next actor's name on
     * `advanceTurnIndexAndRoundIfNeeded`, and any downstream code that
     * reads `WorldManager.activeTurnActor` for the human's perspective
     * gets the wrong answer.
     */
    @Test
    fun advanceTurnIndex_mustGateOnSinglePlayer()
    {
        val source = loadSource()
        val block = extractAdvanceBlock(source)

        val isSinglePlayerPresent = block.contains("WorldManager.isSinglePlayer")
        val humanAnchorPresent = block.contains("humanPlayerName") ||
            block.contains("!it.isControlledByNpc") ||
            block.contains("firstOrNull { !it.isControlledByNpc }")

        assertTrue(
            isSinglePlayerPresent,
            "advanceTurnIndexAndRoundIfNeeded does not gate on WorldManager.isSinglePlayer. " +
                "Without the gate, WorldManager.activeTurnActor flips to the next actor's name on " +
                "every turn advance, breaking the perspective anchor for any downstream reader. " +
                "Block under inspection:\n$block"
        )
        assertTrue(
            humanAnchorPresent,
            "advanceTurnIndexAndRoundIfNeeded does not anchor activeTurnActor to the human's name " +
                "in single-player mode. Block under inspection:\n$block"
        )
    }

    /**
     * Contract: the perspective-shift anchor must be consistent across
     * the broadcast and the advance function. If the broadcast is gated
     * but the advance is not, the activeTurnActor field flips and the
     * gate is wasted. If the advance is gated but the broadcast is not,
     * the broadcast still carries the wrong name. Both must be present.
     */
    @Test
    fun broadcast_and_advance_gates_are_consistent()
    {
        val source = loadSource()
        val broadcast = extractBroadcastBlock(source)
        val advance = extractAdvanceBlock(source)

        val broadcastHasGate = broadcast.contains("WorldManager.isSinglePlayer")
        val advanceHasGate = advance.contains("WorldManager.isSinglePlayer")

        assertTrue(
            broadcastHasGate && advanceHasGate,
            "Perspective-shift fix is incomplete. Both broadcast and advance must be gated. " +
                "broadcastHasGate=$broadcastHasGate, advanceHasGate=$advanceHasGate"
        )
    }
}
