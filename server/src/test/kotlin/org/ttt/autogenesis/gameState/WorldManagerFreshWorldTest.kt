package org.ttt.autogenesis.gameState

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import gameState.WorldManager
import structs.GameHistory
import structs.Player
import structs.World

/**
 * Regression tests for the "fresh world" predicate.
 *
 * BUG #1 in production: the auto-restore guard at
 * [org.ttt.autogenesis.server.Server.onConnected] required
 * `activePlayers.isEmpty() && roundNumber <= 1 && history.isEmpty()`.
 * The seed at `Server.kt:198` adds a "Player 1" placeholder so the
 * first term was always false and auto-restore never fired.
 *
 * The fix moves the "no game has started" semantic into
 * [WorldManager.isWorldEmpty], which intentionally ignores
 * `activePlayers` (the seed player keeps that list non-empty on a
 * fresh server) and only checks the round/history invariant. The
 * threshold `roundNumber <= 1` mirrors the previous guard so a
 * `World()` constructed with its default `roundNumber = 1` is
 * correctly considered empty.
 */
class WorldManagerFreshWorldTest
{
    @BeforeTest
    fun resetWorldState()
    {
        WorldManager.world = World()
        WorldManager.history.clear()
        WorldManager.actionHistoryLog.clear()
        WorldManager.pendingActionHistoryByTurn.clear()
        WorldManager.playerStats.clear()
    }

    @Test
    fun isWorldEmpty_returnsTrue_onFreshWorld_evenWithSeedPlayerOne()
    {
        // The seed "Player 1" lives in activePlayers, but the world has
        // not actually started yet — roundNumber is still at its default 1
        // (the implementation must treat <= 1 as "no game has started")
        // and history is empty.
        WorldManager.world.activePlayers.add(Player(name = "Player 1"))
        assertTrue(WorldManager.isWorldEmpty(), "fresh world with the seed Player 1 should still be considered empty")
    }

    @Test
    fun isWorldEmpty_returnsFalse_afterATurnCompletes()
    {
        WorldManager.world.activePlayers.add(Player(name = "Player 1"))
        // After a turn completes, roundNumber advances beyond 1.
        WorldManager.world.roundNumber = 2
        assertFalse(WorldManager.isWorldEmpty(), "world with roundNumber>1 should not be empty")
    }

    @Test
    fun isWorldEmpty_returnsFalse_whenHistoryIsNonEmpty()
    {
        WorldManager.world.activePlayers.add(Player(name = "Player 1"))
        // history is non-empty — once a turn has run, the world is no
        // longer "empty" for restore purposes, even if roundNumber
        // is still at the seed default.
        WorldManager.history.add(GameHistory(turnPlayer = "Player 1"))
        assertFalse(WorldManager.isWorldEmpty(), "world with non-empty history should not be empty")
    }
}
