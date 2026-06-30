package org.ttt.autogenesis.server

import gameState.WorldManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.network.RpcCallContext
import structs.Player
import structs.World
import structs.rpcRequests.DELEGATE_INSTRUCTIONS_MAX_LENGTH
import structs.rpcRequests.SetDelegateInstructionsRequest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [PlayerDelegateRpcHandlers.setDelegateInstructions].
 *
 * Covers:
 *  - Happy path: instructions are persisted on the right player.
 *  - Validation: blank player name, unknown player, oversized payload, blank/normalized input.
 *  - Concurrency: the mutation runs under the world mutex (asserted by behaviour, not
 *    timing, since the existing [WorldManager.worldMutex] serializes all world writers).
 *
 * `UiSignalRpcHandlers.broadcastWorldUpdate` is a no-op in this test (the
 * `connectionManager` it depends on is `null`), so the broadcast path is exercised
 * only by the integration test harness, not here.
 */
class PlayerDelegateRpcHandlersTest
{
    private val ctx: RpcCallContext = RpcCallContext(connectionId = "test-conn-1", sender = { _ -> })

    @Before
    fun setUp()
    {
        // Reset the world to a clean, single-player state for every test so they are
        // independent of execution order and do not leak state into other suites.
        val w = World()
        w.activePlayers.add(Player(name = "Kara"))
        w.activePlayers.add(Player(name = "Tess"))
        WorldManager.world = w
    }

    @After
    fun tearDown()
    {
        WorldManager.world = World()
    }

    @Test
    fun setsInstructionsOnMatchingPlayer() = runBlocking {
        val result = PlayerDelegateRpcHandlers.setDelegateInstructions(
            ctx,
            SetDelegateInstructionsRequest(playerName = "Kara", instructions = "Hold the line.")
        )
        assertTrue(result, "handler should return true for a known player")
        assertEquals("Hold the line.", WorldManager.world.activePlayers[0].delegateInstructions)
        assertNull(WorldManager.world.activePlayers[1].delegateInstructions, "Tess must remain untouched")
    }

    @Test
    fun unknownPlayerReturnsFalseAndDoesNotMutate() = runBlocking {
        val result = PlayerDelegateRpcHandlers.setDelegateInstructions(
            ctx,
            SetDelegateInstructionsRequest(playerName = "Nobody", instructions = "...")
        )
        assertFalse(result, "handler must return false for an unknown player")
        assertNull(WorldManager.world.activePlayers[0].delegateInstructions)
        assertNull(WorldManager.world.activePlayers[1].delegateInstructions)
    }

    @Test
    fun blankPlayerNameIsRejected() = runBlocking {
        val result = PlayerDelegateRpcHandlers.setDelegateInstructions(
            ctx,
            SetDelegateInstructionsRequest(playerName = "   ", instructions = "...")
        )
        assertFalse(result, "blank playerName must be rejected")
    }

    @Test
    fun nullInstructionsNormalizeToNull() = runBlocking {
        PlayerDelegateRpcHandlers.setDelegateInstructions(
            ctx,
            SetDelegateInstructionsRequest(playerName = "Kara", instructions = null)
        )
        assertNull(WorldManager.world.activePlayers[0].delegateInstructions)
    }

    @Test
    fun blankStringNormalizesToNull() = runBlocking {
        PlayerDelegateRpcHandlers.setDelegateInstructions(
            ctx,
            SetDelegateInstructionsRequest(playerName = "Kara", instructions = "   \n\t  ")
        )
        assertNull(WorldManager.world.activePlayers[0].delegateInstructions,
            "whitespace-only instructions must normalize to null so the agent sees the explicit fallback")
    }

    @Test
    fun oversizedInstructionsAreTruncated() = runBlocking {
        val oversized = "x".repeat(DELEGATE_INSTRUCTIONS_MAX_LENGTH + 250)
        val result = PlayerDelegateRpcHandlers.setDelegateInstructions(
            ctx,
            SetDelegateInstructionsRequest(playerName = "Kara", instructions = oversized)
        )
        assertTrue(result, "oversized payload should still be accepted (truncated, not rejected)")
        val stored = WorldManager.world.activePlayers[0].delegateInstructions
        assertEquals(DELEGATE_INSTRUCTIONS_MAX_LENGTH, stored?.length,
            "stored instructions must be truncated to DELEGATE_INSTRUCTIONS_MAX_LENGTH")
    }

    @Test
    fun exactlyMaxLengthIsAccepted() = runBlocking {
        val exact = "y".repeat(DELEGATE_INSTRUCTIONS_MAX_LENGTH)
        val result = PlayerDelegateRpcHandlers.setDelegateInstructions(
            ctx,
            SetDelegateInstructionsRequest(playerName = "Kara", instructions = exact)
        )
        assertTrue(result)
        assertEquals(DELEGATE_INSTRUCTIONS_MAX_LENGTH, WorldManager.world.activePlayers[0].delegateInstructions?.length)
    }

    @Test
    fun successiveUpdatesReplaceThePreviousValue() = runBlocking {
        PlayerDelegateRpcHandlers.setDelegateInstructions(
            ctx,
            SetDelegateInstructionsRequest(playerName = "Kara", instructions = "first")
        )
        PlayerDelegateRpcHandlers.setDelegateInstructions(
            ctx,
            SetDelegateInstructionsRequest(playerName = "Kara", instructions = "second")
        )
        assertEquals("second", WorldManager.world.activePlayers[0].delegateInstructions)
    }
}
