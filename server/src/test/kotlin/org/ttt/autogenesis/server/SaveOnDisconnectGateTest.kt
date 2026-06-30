package org.ttt.autogenesis.server

import gameState.WorldManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression test for the bug where Server.onDisconnected would persist a
 * never-played fresh-init snapshot to the user's cloud save record. The bug
 * surfaced on 2026-06-27 when the user reloaded and saw an empty round-1
 * game with score=0 and no history. Root cause: Server.kt:558 gated the
 * save on `isGameActive` alone, but `isGameActive` is set true by GameInit
 * the moment the world is initialized — long before the human player has
 * actually connected and played a turn.
 *
 * The fix introduces [WorldManager.humanPlayerHasJoinedOnce] which is set
 * inside [TurnHarness.awaitPlayerAction] once the player is reachable and
 * the turn can begin. The save-on-disconnect gate must check this flag
 * before persisting.
 *
 * The gate predicate is intentionally extracted as a pure function
 * ([shouldPersistOnDisconnect]) so it can be unit-tested without spinning
 * up the full ktor server lifecycle.
 */
class SaveOnDisconnectGateTest
{
    @BeforeTest
    fun setUp()
    {
        // The flag is process-global mutable state on WorldManager. Reset
        // both the flag and isGameActive so test ordering doesn't pollute.
        WorldManager.humanPlayerHasJoinedOnce = false
        WorldManager.isGameActive = true
        WorldManager.isSinglePlayer = true
    }

    @AfterTest
    fun tearDown()
    {
        WorldManager.humanPlayerHasJoinedOnce = false
        WorldManager.isGameActive = false
        WorldManager.isSinglePlayer = true
    }

    /**
     * Bug repro: a game that GameInit initialized but the human never
     * joined (e.g. server-extend bridge disconnected before the user
     * connected) must NOT trigger a cloud-save write. Before the fix this
     * wrote `round=1, turnIndex=0, historyEntries=0` to the user's record
     * — the empty-state snapshot that "resumed" as a phantom fresh game.
     */
    @Test
    fun shouldPersistOnDisconnect_returns_false_when_human_never_joined()
    {
        // Simulate the post-GameInit state: game is "active" but no human
        // ever connected. WorldManager.humanPlayerHasJoinedOnce stays false
        // because awaitPlayerAction was never reached for a reachable
        // player.
        WorldManager.humanPlayerHasJoinedOnce = false
        WorldManager.isGameActive = true

        val humanUserId = "00000000000000000000000000000000"
        val decision = shouldPersistOnDisconnect(
            humanAccelByteUserId = humanUserId,
            isSinglePlayer = true,
            isGameActive = WorldManager.isGameActive,
            humanPlayerHasJoinedOnce = WorldManager.humanPlayerHasJoinedOnce,
            historySize = 0
        )

        assertFalse(
            decision,
            "must NOT persist when humanPlayerHasJoinedOnce=false (was the BUG-25 root cause — empty round-1 snapshot written before user joined)"
        )
    }

    /**
     * The intended path: human joined once, took a turn (or at minimum was
     * reachable for awaitPlayerAction), then disconnected. Persist.
     */
    @Test
    fun shouldPersistOnDisconnect_returns_true_when_human_has_joined_once()
    {
        WorldManager.humanPlayerHasJoinedOnce = true
        WorldManager.isGameActive = true

        val humanUserId = "00000000000000000000000000000000"
        val decision = shouldPersistOnDisconnect(
            humanAccelByteUserId = humanUserId,
            isSinglePlayer = true,
            isGameActive = WorldManager.isGameActive,
            humanPlayerHasJoinedOnce = WorldManager.humanPlayerHasJoinedOnce,
            historySize = 1
        )

        assertTrue(
            decision,
            "must persist when humanPlayerHasJoinedOnce=true AND historySize > 0 (user actually played and disconnected)"
        )
    }

    /**
     * Edge case: blank humanUserId (no playerStats match for the human
     * player name). Even if the game is active and the human joined, the
     * save target is invalid — skip.
     */
    @Test
    fun shouldPersistOnDisconnect_returns_false_when_human_user_id_is_blank()
    {
        WorldManager.humanPlayerHasJoinedOnce = true
        WorldManager.isGameActive = true

        val decision = shouldPersistOnDisconnect(
            humanAccelByteUserId = "",
            isSinglePlayer = true,
            isGameActive = WorldManager.isGameActive,
            humanPlayerHasJoinedOnce = WorldManager.humanPlayerHasJoinedOnce,
            historySize = 1
        )

        assertFalse(
            decision,
            "must NOT persist with blank humanUserId (no valid cloud-save target)"
        )
    }

    /**
     * BUG 26 (2026-06-27): the previous behavior was to skip the save
     * when historySize=0 (the user joined but AI took the first turn and
     * disconnected mid-AI). That heuristic over-skipped legitimate
     * saves — a user who joined, then closed the browser before
     * submitting a turn, would see no Resume dialog on next login.
     *
     * The new behavior: historySize=0 is still a valid save point. The
     * snapshot represents a game where the human joined but didn't act;
     * resuming shows a round-1 no-history world, which is exactly the
     * fresh-init state a "New Game" would produce — same UX, just
     * through the Resume button instead of New Game. The user can
     * always click New Game if they want a different commander.
     *
     * The save-on-disconnect gate now only requires humanPlayerHasJoinedOnce
     * (plus isSinglePlayer + isGameActive + non-blank userId). historySize
     * is still a parameter on the function for symmetry and future use,
     * but no longer gates the decision.
     */
    @Test
    fun shouldPersistOnDisconnect_returns_true_when_human_joined_but_history_is_empty()
    {
        WorldManager.humanPlayerHasJoinedOnce = true
        WorldManager.isGameActive = true

        val humanUserId = "00000000000000000000000000000000"
        val decision = shouldPersistOnDisconnect(
            humanAccelByteUserId = humanUserId,
            isSinglePlayer = true,
            isGameActive = WorldManager.isGameActive,
            humanPlayerHasJoinedOnce = WorldManager.humanPlayerHasJoinedOnce,
            historySize = 0
        )

        assertTrue(
            decision,
            "BUG 26 (2026-06-27): must persist when humanPlayerHasJoinedOnce=true even if historySize=0. " +
                "The user joined (verified by the gate), the snapshot is a valid round-1 no-history state, " +
                "and the explicit-Resume path will handle the restore correctly. Suppressing this save " +
                "creates a phantom-resume bug (user sees no Resume dialog even though they played)."
        )
    }
}
