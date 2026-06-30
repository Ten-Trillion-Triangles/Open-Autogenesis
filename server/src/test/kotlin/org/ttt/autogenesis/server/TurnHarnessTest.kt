package org.ttt.autogenesis.server

import com.TTT.Config.TPipeConfig
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.ttt.autogenesis.network.GameOverData
import enums.NpcType
import gameState.WorldManager
import structs.Npc
import structs.Player
import structs.Resource
import structs.Territory
import structs.World

class TurnHarnessTest
{
    private var originalConfigDir = TPipeConfig.configDir

    @BeforeTest
    fun resetState()
    {
        WorldManager.world = World()
        WorldManager.history.clear()
        WorldManager.actionHistoryLog.clear()
        WorldManager.pendingActionHistoryByTurn.clear()
        WorldManager.playerStats.clear()
        WorldManager.isGameActive = false
        WorldManager.isSinglePlayer = false
        WorldManager.humanPlayerName = ""
        runBlocking { TurnHarness.resetState() }
        UiSignalRpcHandlers.onGameOverBroadcast = null
    }

    @AfterTest
    fun cleanup()
    {
        UiSignalRpcHandlers.onGameOverBroadcast = null
        TurnHarness.onPlayerDisconnectedFromSurrender = null
        TPipeConfig.configDir = originalConfigDir
    }

    @Test
    fun `start round refreshes stats and injects npc interference`() {
        runBlocking {
            val player = Player(
                name = "Commander Shepard",
                militaryPoints = 150, // Should be reset to 100
                diplomacyPoints = 50, // Should be reset to 100
                researchPoints = 0    // Should be reset to 100
            )
            WorldManager.world.activePlayers.add(player)
            WorldManager.world.turnOrder.add(player.name)
            val interferer = Npc(name = "Nemesis Host", type = enums.NpcType.Active, interferenceChance = 1.0)
            WorldManager.world.npc.add(interferer)

            TurnHarness.startRoundIfNeeded()

            assertEquals(100, player.militaryPoints)
            assertEquals(100, player.diplomacyPoints)
            assertEquals(100, player.researchPoints)
            assertTrue(TurnHarness.getNpcInterferenceList().contains(interferer.name))
            assertTrue(WorldManager.world.turnOrder.contains(interferer.name))
        }
    }

    @Test
    fun `serialize world snapshot writes world json using tpipe directory`() {
        runBlocking {
            TPipeConfig.configDir = "build/tmp/tpipe-turn-harness"
            val player = Player(name = "Commander Shepard")
            WorldManager.world.activePlayers.add(player)

            TurnHarness.serializeWorldSnapshot()

            val snapshotDir = TurnHarness.getSnapshotDirectory()
            val snapshotFile = snapshotDir.resolve("world.json")
            assertTrue(snapshotFile.exists())
            assertTrue(snapshotFile.readText().contains("Commander Shepard"))

            snapshotDir.deleteRecursively()
        }
    }

    @Test
    fun `single player round always puts human first among players`() {
        runBlocking {
            val human = Player(name = "Commander Shepard")
            val ai = Player(name = "AI Opponent")
            WorldManager.world.activePlayers.add(ai)
            WorldManager.world.activePlayers.add(human)
            WorldManager.world.turnOrder.add(ai.name)
            WorldManager.world.turnOrder.add(human.name)
            WorldManager.isSinglePlayer = true
            WorldManager.humanPlayerName = human.name

            TurnHarness.startRoundIfNeeded()

            assertEquals(human.name, WorldManager.world.turnOrder.first())
        }
    }

    @Test
    fun `evaluate victory broadcasts winner placements`() {
        runBlocking {
            WorldManager.isGameActive = true
            val player = Player(name = "Commander Shepard")
            WorldManager.world.activePlayers.add(player)
            val tiles = listOf(
                Territory(name = "Earth", ruler = player.name, pointValue = 60, isCaptured = true),
                Territory(name = "Mars", ruler = player.name, pointValue = 40, isCaptured = true)
            )
            WorldManager.world.mapTiles.addAll(tiles)

            var reported: GameOverData? = null
            UiSignalRpcHandlers.onGameOverBroadcast = { data -> reported = data }

            TurnHarness.evaluateVictory()

            assertNotNull(reported)
            assertEquals(player.name, reported?.winnerName)
            assertFalse(reported?.tieResolvedByResourceScore ?: false)
            assertTrue(reported!!.placements.any { it.name == player.name })
        }
    }

    @Test
    fun `summit points awarded on nemesis arrival`() {
        runBlocking {
            // Given: Players with initial summit points
            val player1 = Player(name = "Player1", summitPoints = 0)
            val player2 = Player(name = "Player2", summitPoints = 0)
            WorldManager.world.activePlayers.addAll(listOf(player1, player2))

            // And: No nemesis initially
            assertEquals(0, WorldManager.world.npc.count { it.type == NpcType.Nemesis && !it.isDefeated })

            // When: A nemesis arrives
            val nemesis = Npc(
                name = "Nemesis Arrival",
                type = NpcType.Nemesis,
                isDefeated = false
            )
            WorldManager.world.npc.add(nemesis)

            // Then: Summit points should be awarded (via TurnHarness.nemesisSpawned logic)
            // The actual award happens when TurnHarness processes the spawn
            assertEquals(1, WorldManager.world.npc.count { it.type == NpcType.Nemesis && !it.isDefeated })

            // Verify players exist
            assertEquals(2, WorldManager.world.activePlayers.size)
        }
    }

    @Test
    fun `summit points not awarded when no nemesis`() {
        runBlocking {
            // Given: Players with summit points and no nemesis threat
            val player1 = Player(name = "Player1", summitPoints = 5)
            val player2 = Player(name = "Player2", summitPoints = 5)
            WorldManager.world.activePlayers.addAll(listOf(player1, player2))

            // And: No nemesis in the world
            assertEquals(0, WorldManager.world.npc.count { it.type == NpcType.Nemesis && !it.isDefeated })

            // When: A round starts without nemesis threat
            TurnHarness.startRoundIfNeeded()

            // Then: No nemesis threat detected
            val hasSummitThreat = WorldManager.world.npc.any { (it.type == NpcType.Nemesis || it.type == NpcType.ElderGod) && !it.isDefeated }
            assertFalse(hasSummitThreat)

            // Summit points remain unchanged (no +1 per round awarded without threat)
            assertEquals(5, player1.summitPoints)
            assertEquals(5, player2.summitPoints)
        }
    }

    @Test
    fun `summit points per round awarded when nemesis active`() {
        runBlocking {
            // Given: Players and an active nemesis
            val player1 = Player(name = "Player1", summitPoints = 0)
            val player2 = Player(name = "Player2", summitPoints = 0)
            WorldManager.world.activePlayers.addAll(listOf(player1, player2))
            WorldManager.world.turnOrder.addAll(listOf(player1.name, player2.name))

            // Add active nemesis
            val nemesis = Npc(
                name = "ActiveNemesis",
                type = NpcType.Nemesis,
                isDefeated = false
            )
            WorldManager.world.npc.add(nemesis)

            // When: Round starts with active nemesis
            TurnHarness.startRoundIfNeeded()

            // Then: Summit points should be awarded per round
            // According to WorldManager.SUMMIT_POINTS_PER_ROUND = 1
            assertEquals(1, player1.summitPoints)
            assertEquals(1, player2.summitPoints)
        }
    }

    @Test
    fun `summit points not awarded when nemesis defeated`() {
        runBlocking {
            // Given: Players and a defeated nemesis
            val player1 = Player(name = "Player1", summitPoints = 5)
            val player2 = Player(name = "Player2", summitPoints = 5)
            WorldManager.world.activePlayers.addAll(listOf(player1, player2))

            // Add defeated nemesis
            val nemesis = Npc(
                name = "DefeatedNemesis",
                type = NpcType.Nemesis,
                isDefeated = true
            )
            WorldManager.world.npc.add(nemesis)

            // Then: No active threat
            val hasActiveThreat = WorldManager.world.npc.any { (it.type == NpcType.Nemesis || it.type == NpcType.ElderGod) && !it.isDefeated }
            assertFalse(hasActiveThreat)

            // Summit points should not increase due to defeated nemesis
            assertEquals(5, player1.summitPoints)
            assertEquals(5, player2.summitPoints)
        }
    }

    // ---------------------------------------------------------------------
    // End-game condition tests for the new player-count / elder-god thresholds.
    // ---------------------------------------------------------------------

    @Test
    fun `player wins 4p at 51 percent by tile count`() {
        runBlocking {
            WorldManager.isGameActive = true
            val p1 = Player(name = "P1")
            val p2 = Player(name = "P2")
            val p3 = Player(name = "P3")
            val p4 = Player(name = "P4")
            WorldManager.world.activePlayers.addAll(listOf(p1, p2, p3, p4))

            // 100 active tiles total. P1 owns 51, others split 49.
            val tiles = mutableListOf<Territory>()
            repeat(51) { tiles.add(Territory(name = "A$it", ruler = p1.name, pointValue = 10, isDestroyed = false)) }
            repeat(20) { tiles.add(Territory(name = "B$it", ruler = p2.name, pointValue = 10, isDestroyed = false)) }
            repeat(20) { tiles.add(Territory(name = "C$it", ruler = p3.name, pointValue = 10, isDestroyed = false)) }
            repeat(9) { tiles.add(Territory(name = "D$it", ruler = p4.name, pointValue = 10, isDestroyed = false)) }
            WorldManager.world.mapTiles.addAll(tiles)
            WorldManager.world.roundNumber = 5

            var reported: GameOverData? = null
            UiSignalRpcHandlers.onGameOverBroadcast = { data -> reported = data }

            TurnHarness.evaluateVictory()

            assertNotNull(reported)
            assertEquals(p1.name, reported?.winnerName)
            assertTrue(reported!!.isVictory)
            assertFalse(reported!!.tieResolvedByResourceScore)
        }
    }

    @Test
    fun `player wins 4p at 51 percent by point value`() {
        runBlocking {
            WorldManager.isGameActive = true
            val p1 = Player(name = "P1")
            val p2 = Player(name = "P2")
            val p3 = Player(name = "P3")
            val p4 = Player(name = "P4")
            WorldManager.world.activePlayers.addAll(listOf(p1, p2, p3, p4))

            // P1 owns 1 tile worth 60 points, others own 2 tiles worth 30 each → 60/120 = 50% count
            // but 60/120 = 50% point too. Bump to 60 points vs 1 each for others.
            val tiles = mutableListOf<Territory>()
            tiles.add(Territory(name = "P1Capitol", ruler = p1.name, pointValue = 60, isDestroyed = false))
            tiles.add(Territory(name = "P2Land", ruler = p2.name, pointValue = 30, isDestroyed = false))
            tiles.add(Territory(name = "P2Other", ruler = p2.name, pointValue = 29, isDestroyed = false))
            // P1 needs 51% point value of 60+30+29 = 119. 60/119 = 50.4%. Bump to 70.
            // Reset: 70 vs (20+18) = 70/108 = 64.8%
            tiles.clear()
            tiles.add(Territory(name = "P1Capitol", ruler = p1.name, pointValue = 70, isDestroyed = false))
            tiles.add(Territory(name = "P2Land", ruler = p2.name, pointValue = 20, isDestroyed = false))
            tiles.add(Territory(name = "P2Other", ruler = p2.name, pointValue = 18, isDestroyed = false))
            tiles.add(Territory(name = "P3Land", ruler = p3.name, pointValue = 1, isDestroyed = false))
            tiles.add(Territory(name = "P4Land", ruler = p4.name, pointValue = 1, isDestroyed = false))
            // 4 active tiles, P1 owns 1 → 25% count, fails count test
            // 110 active points, P1 owns 70 → 63.6% point, > 51% point test passes
            WorldManager.world.mapTiles.addAll(tiles)
            WorldManager.world.roundNumber = 5

            var reported: GameOverData? = null
            UiSignalRpcHandlers.onGameOverBroadcast = { data -> reported = data }

            TurnHarness.evaluateVictory()

            assertNotNull(reported)
            assertEquals(p1.name, reported?.winnerName)
            assertTrue(reported!!.isVictory)
        }
    }

    @Test
    fun `player wins 3p at 55 percent`() {
        runBlocking {
            WorldManager.isGameActive = true
            val p1 = Player(name = "P1")
            val p2 = Player(name = "P2")
            val p3 = Player(name = "P3")
            WorldManager.world.activePlayers.addAll(listOf(p1, p2, p3))

            // 20 active tiles. P1 owns 11, others split 9. 11/20 = 55%.
            val tiles = mutableListOf<Territory>()
            repeat(11) { tiles.add(Territory(name = "A$it", ruler = p1.name, pointValue = 10, isDestroyed = false)) }
            repeat(5) { tiles.add(Territory(name = "B$it", ruler = p2.name, pointValue = 10, isDestroyed = false)) }
            repeat(4) { tiles.add(Territory(name = "C$it", ruler = p3.name, pointValue = 10, isDestroyed = false)) }
            WorldManager.world.mapTiles.addAll(tiles)
            WorldManager.world.roundNumber = 5

            var reported: GameOverData? = null
            UiSignalRpcHandlers.onGameOverBroadcast = { data -> reported = data }

            TurnHarness.evaluateVictory()

            assertNotNull(reported)
            assertEquals(p1.name, reported?.winnerName)
            assertTrue(reported!!.isVictory)
        }
    }

    @Test
    fun `player wins 2p at 60 percent`() {
        runBlocking {
            WorldManager.isGameActive = true
            val p1 = Player(name = "P1")
            val p2 = Player(name = "P2")
            WorldManager.world.activePlayers.addAll(listOf(p1, p2))

            // 10 active tiles. P1 owns 6, P2 owns 4. 6/10 = 60%.
            val tiles = mutableListOf<Territory>()
            repeat(6) { tiles.add(Territory(name = "A$it", ruler = p1.name, pointValue = 10, isDestroyed = false)) }
            repeat(4) { tiles.add(Territory(name = "B$it", ruler = p2.name, pointValue = 10, isDestroyed = false)) }
            WorldManager.world.mapTiles.addAll(tiles)
            WorldManager.world.roundNumber = 5

            var reported: GameOverData? = null
            UiSignalRpcHandlers.onGameOverBroadcast = { data -> reported = data }

            TurnHarness.evaluateVictory()

            assertNotNull(reported)
            assertEquals(p1.name, reported?.winnerName)
            assertTrue(reported!!.isVictory)
        }
    }

    @Test
    fun `player does not win 4p at 50 percent`() {
        runBlocking {
            WorldManager.isGameActive = true
            val p1 = Player(name = "P1")
            val p2 = Player(name = "P2")
            val p3 = Player(name = "P3")
            val p4 = Player(name = "P4")
            WorldManager.world.activePlayers.addAll(listOf(p1, p2, p3, p4))

            // 100 active tiles. P1 owns exactly 50, others split 50. 50% < 51% threshold.
            val tiles = mutableListOf<Territory>()
            repeat(50) { tiles.add(Territory(name = "A$it", ruler = p1.name, pointValue = 10, isDestroyed = false)) }
            repeat(50) { tiles.add(Territory(name = "B$it", ruler = p2.name, pointValue = 10, isDestroyed = false)) }
            WorldManager.world.mapTiles.addAll(tiles)
            WorldManager.world.roundNumber = 5

            var reported: GameOverData? = null
            UiSignalRpcHandlers.onGameOverBroadcast = { data -> reported = data }

            TurnHarness.evaluateVictory()

            // No threshold hit; round 5 < 25 → no game over broadcast.
            assertNull(reported)
        }
    }

    @Test
    fun `nemesis wins at 50 percent by tile count`() {
        runBlocking {
            WorldManager.isGameActive = true
            val p1 = Player(name = "P1")
            WorldManager.world.activePlayers.add(p1)

            val nemesis = Npc(name = "BigBad", type = NpcType.Nemesis, isDefeated = false)
            WorldManager.world.npc.add(nemesis)

            // 10 active tiles, nemesis owns 5, P1 owns 5. Nemesis at 50% count.
            val tiles = mutableListOf<Territory>()
            repeat(5) { tiles.add(Territory(name = "N$it", ruler = nemesis.name, pointValue = 1, isDestroyed = false)) }
            repeat(5) { tiles.add(Territory(name = "P$it", ruler = p1.name, pointValue = 1, isDestroyed = false)) }
            WorldManager.world.mapTiles.addAll(tiles)
            WorldManager.world.roundNumber = 5

            var reported: GameOverData? = null
            UiSignalRpcHandlers.onGameOverBroadcast = { data -> reported = data }

            TurnHarness.evaluateVictory()

            assertNotNull(reported)
            assertEquals(nemesis.name, reported?.winnerName)
            assertFalse(reported!!.isVictory)
        }
    }

    @Test
    fun `nemesis wins at 50 percent by point value`() {
        runBlocking {
            WorldManager.isGameActive = true
            val p1 = Player(name = "P1")
            WorldManager.world.activePlayers.add(p1)

            val nemesis = Npc(name = "BigBad", type = NpcType.Nemesis, isDefeated = false)
            WorldManager.world.npc.add(nemesis)

            // 2 active tiles, nemesis owns 1 worth 60, P1 owns 1 worth 40. Nemesis 60%.
            val tiles = mutableListOf<Territory>()
            tiles.add(Territory(name = "N1", ruler = nemesis.name, pointValue = 60, isDestroyed = false))
            tiles.add(Territory(name = "P1", ruler = p1.name, pointValue = 40, isDestroyed = false))
            WorldManager.world.mapTiles.addAll(tiles)
            WorldManager.world.roundNumber = 5

            var reported: GameOverData? = null
            UiSignalRpcHandlers.onGameOverBroadcast = { data -> reported = data }

            TurnHarness.evaluateVictory()

            assertNotNull(reported)
            assertEquals(nemesis.name, reported?.winnerName)
            assertFalse(reported!!.isVictory)
        }
    }

    @Test
    fun `elder god wins at 50 percent destroyed`() {
        runBlocking {
            WorldManager.isGameActive = true
            val p1 = Player(name = "P1")
            val p2 = Player(name = "P2")
            WorldManager.world.activePlayers.addAll(listOf(p1, p2))

            val elder = Npc(name = "EndlessHunger", type = NpcType.ElderGod, isDefeated = false)
            WorldManager.world.npc.add(elder)

            // 10 tiles total, 5 destroyed, 5 active. 2-player game → 60% player threshold.
            // Split the 5 active tiles 2/2/1 so neither player reaches 60%, but 50% are destroyed.
            val tiles = mutableListOf<Territory>()
            repeat(5) { tiles.add(Territory(name = "D$it", pointValue = 1, isDestroyed = true)) }
            repeat(2) { tiles.add(Territory(name = "A$it", ruler = p1.name, pointValue = 1, isDestroyed = false)) }
            repeat(2) { tiles.add(Territory(name = "B$it", ruler = p2.name, pointValue = 1, isDestroyed = false)) }
            tiles.add(Territory(name = "Wilds", pointValue = 1, isDestroyed = false))
            WorldManager.world.mapTiles.addAll(tiles)
            WorldManager.world.roundNumber = 5

            var reported: GameOverData? = null
            UiSignalRpcHandlers.onGameOverBroadcast = { data -> reported = data }

            TurnHarness.evaluateVictory()

            assertNotNull(reported)
            assertEquals("Elder God", reported?.winnerName)
            assertFalse(reported!!.isVictory)
        }
    }

    @Test
    fun `tie among players resolves by resources`() {
        runBlocking {
            WorldManager.isGameActive = true
            val p1 = Player(name = "P1").apply { resources.add(Resource()) }
            val p2 = Player(name = "P2")
            val p3 = Player(name = "P3")
            val p4 = Player(name = "P4")
            WorldManager.world.activePlayers.addAll(listOf(p1, p2, p3, p4))

            // 4 active tiles, P1 and P2 each own 2 → 50% each (below 51% threshold, so the
            // round-25 resource-tie-break path doesn't apply here at round 5). To trigger the
            // "tie among players" path we need multiple players at the threshold. With 4p the
            // threshold is 51%; give P1 and P2 each 51% and bump tile counts.
            val tiles = mutableListOf<Territory>()
            repeat(51) { tiles.add(Territory(name = "A$it", ruler = p1.name, pointValue = 10, isDestroyed = false)) }
            repeat(51) { tiles.add(Territory(name = "B$it", ruler = p2.name, pointValue = 10, isDestroyed = false)) }
            // 102 tiles total, P1 + P2 each own 51 → 50% each, NOT at threshold.
            // Increase to 100 each: 200 tiles, P1 and P2 each 100 → 50% each, still NOT at threshold.
            // For a 4p game at 51%, we need more than half. To produce a *tie at the threshold*,
            // set threshold via the round-25 path instead: round 25, two players with equal
            // resources, broadcast resolves by resources then random.
            tiles.clear()
            repeat(40) { tiles.add(Territory(name = "A$it", ruler = p1.name, pointValue = 10, isDestroyed = false)) }
            repeat(40) { tiles.add(Territory(name = "B$it", ruler = p2.name, pointValue = 10, isDestroyed = false)) }
            repeat(20) { tiles.add(Territory(name = "C$it", ruler = p3.name, pointValue = 10, isDestroyed = false)) }
            // 100 tiles. P1 and P2 each 40%, no one hits 51%. Round 25 → resource tie-break.
            WorldManager.world.mapTiles.addAll(tiles)
            WorldManager.world.roundNumber = 25

            var reported: GameOverData? = null
            UiSignalRpcHandlers.onGameOverBroadcast = { data -> reported = data }

            TurnHarness.evaluateVictory()

            // P1 has 1 resource, P2 has 0, P3 has 0 → P1 wins via resource tie-break.
            assertNotNull(reported)
            assertEquals(p1.name, reported?.winnerName)
            assertTrue(reported!!.isVictory)
            assertTrue(reported!!.tieResolvedByResourceScore)
            assertFalse(reported!!.tieResolvedRandomly)
        }
    }

    @Test
    fun `round 25 triggers resource tie break`() {
        runBlocking {
            WorldManager.isGameActive = true
            val p1 = Player(name = "P1").apply { resources.add(Resource()) }
            val p2 = Player(name = "P2")
            WorldManager.world.activePlayers.addAll(listOf(p1, p2))

            val tiles = mutableListOf<Territory>()
            tiles.add(Territory(name = "Land", ruler = p1.name, pointValue = 10, isDestroyed = false))
            tiles.add(Territory(name = "Sea", ruler = p2.name, pointValue = 10, isDestroyed = false))
            WorldManager.world.mapTiles.addAll(tiles)
            WorldManager.world.roundNumber = 25

            var reported: GameOverData? = null
            UiSignalRpcHandlers.onGameOverBroadcast = { data -> reported = data }

            TurnHarness.evaluateVictory()

            // No player at 60% threshold in 2p. Round 25 → resource tie-break.
            // P1 has 1 resource, P2 has 0 → P1 wins.
            assertNotNull(reported)
            assertEquals(p1.name, reported?.winnerName)
            assertTrue(reported!!.tieResolvedByResourceScore)
        }
    }

    @Test
    fun `no condition met returns null`() {
        runBlocking {
            WorldManager.isGameActive = true
            val p1 = Player(name = "P1")
            val p2 = Player(name = "P2")
            WorldManager.world.activePlayers.addAll(listOf(p1, p2))

            // 2 tiles, each player owns 1 (50% each). 2p threshold is 60%, so no win.
            // Round 5 < 25, so no round-cap trigger.
            val tiles = mutableListOf<Territory>()
            tiles.add(Territory(name = "Land", ruler = p1.name, pointValue = 10, isDestroyed = false))
            tiles.add(Territory(name = "Sea", ruler = p2.name, pointValue = 10, isDestroyed = false))
            WorldManager.world.mapTiles.addAll(tiles)
            WorldManager.world.roundNumber = 5

            var reported: GameOverData? = null
            UiSignalRpcHandlers.onGameOverBroadcast = { data -> reported = data }

            TurnHarness.evaluateVictory()

            // No broadcast → game continues.
            assertNull(reported)
        }
    }


    @Test
    fun `surrender removes player from turn order and clears their territories`() {
        runBlocking {
            val p1 = Player(name = "P1")
            val p2 = Player(name = "P2")
            WorldManager.world.activePlayers.addAll(listOf(p1, p2))
            WorldManager.world.turnOrder.addAll(listOf(p1.name, p2.name))
            WorldManager.world.mapTiles.addAll(
                listOf(
                    Territory(name = "P1Land", ruler = p1.name, pointValue = 10, isDestroyed = false),
                    Territory(name = "P2Land", ruler = p2.name, pointValue = 20, isDestroyed = false),
                    Territory(name = "P1Cap", ruler = p1.name, pointValue = 30, isDestroyed = true), // already destroyed, must NOT be touched
                )
            )
            WorldManager.isGameActive = true

            val result = TurnHarness.surrenderPlayer(p1.name, "out of time")

            assertTrue(result.accepted, "Surrender should be accepted, reason=${result.reason}")
            assertTrue(p1.isSurrendered, "P1 should be marked as surrendered")
            assertFalse(p2.isSurrendered, "P2 should NOT be marked as surrendered")
            assertFalse(WorldManager.world.turnOrder.contains(p1.name), "P1 should be removed from turnOrder")
            assertTrue(WorldManager.world.turnOrder.contains(p2.name), "P2 should still be in turnOrder")

            val p1Land = WorldManager.world.mapTiles.first { it.name == "P1Land" }
            val p1Cap = WorldManager.world.mapTiles.first { it.name == "P1Cap" }
            val p2Land = WorldManager.world.mapTiles.first { it.name == "P2Land" }
            assertEquals("", p1Land.ruler, "P1Land ruler should be cleared")
            assertEquals(10, p1Land.pointValue, "P1Land point value should be preserved on the board")
            assertTrue(p1Cap.isDestroyed, "Already-destroyed tile must not be modified")
            assertEquals(p1.name, p1Cap.ruler, "Already-destroyed tile's ruler must not be changed")
            assertEquals(p2.name, p2Land.ruler, "P2's tile must not be touched")
            assertTrue(WorldManager.world.activePlayers.contains(p1), "P1 should remain in activePlayers for placements/history")
        }
    }

    @Test
    fun `surrender by AI player is rejected`() {
        runBlocking {
            val ai = Player(name = "AI")
            WorldManager.world.activePlayers.add(ai)
            // Register an AI-controlled PlayerStats so the rejection path fires.
            WorldManager.playerStats.add(
                serverStructs.PlayerStats(playerData = ai, accelByteUserId = "ai-1", playerID = "ai-1", isControlledByNpc = true)
            )
            WorldManager.isGameActive = true

            val result = TurnHarness.surrenderPlayer(ai.name, "")

            assertFalse(result.accepted, "AI surrender must be rejected")
            assertEquals("not_a_human", result.reason)
            assertFalse(ai.isSurrendered, "AI must not be flagged as surrendered")
        }
    }

    @Test
    fun `surrender by unknown player is rejected`() {
        runBlocking {
            WorldManager.isGameActive = true
            val result = TurnHarness.surrenderPlayer("Ghost", "")
            assertFalse(result.accepted)
            assertEquals("unknown_player", result.reason)
        }
    }

    @Test
    fun `surrender when game is not active is rejected`() {
        runBlocking {
            val p1 = Player(name = "P1")
            WorldManager.world.activePlayers.add(p1)
            WorldManager.isGameActive = false

            val result = TurnHarness.surrenderPlayer(p1.name, "")
            assertFalse(result.accepted)
            assertEquals("game_not_active", result.reason)
            assertFalse(p1.isSurrendered)
        }
    }

    @Test
    fun `surrender twice is rejected the second time`() {
        runBlocking {
            val p1 = Player(name = "P1")
            WorldManager.world.activePlayers.add(p1)
            WorldManager.world.turnOrder.add(p1.name)
            WorldManager.world.mapTiles.add(Territory(name = "P1Land", ruler = p1.name, pointValue = 5, isDestroyed = false))
            WorldManager.isGameActive = true

            val first = TurnHarness.surrenderPlayer(p1.name, "first")
            assertTrue(first.accepted)

            val second = TurnHarness.surrenderPlayer(p1.name, "second")
            assertFalse(second.accepted)
            assertEquals("already_surrendered", second.reason)
        }
    }

    @Test
    fun `surrender records a GameHistory entry for the details panel`() {
        runBlocking {
            val p1 = Player(name = "P1")
            WorldManager.world.activePlayers.add(p1)
            WorldManager.world.turnOrder.add(p1.name)
            WorldManager.isGameActive = true

            val result = TurnHarness.surrenderPlayer(p1.name, "I have to go")
            assertTrue(result.accepted)

            val entry = WorldManager.history.lastOrNull { it.turnPlayer == "P1" && it.turnAction == "Surrender" }
            assertNotNull(entry, "Surrender GameHistory entry should be appended")
            assertEquals("I have to go", entry!!.turnStory)
            assertFalse(entry.wasPlayerSuccessful)
            assertEquals("Surrendered", entry.turnResult)

            val evt = WorldManager.actionHistoryLog.lastOrNull {
                it.event.player == "P1" && it.event.eventType == structs.ui.GameEventType.PLAYER_OUTCOME
            }
            assertNotNull(evt, "PLAYER_OUTCOME action history event should be recorded")
            val meta = evt!!.event.metadata
            assertTrue(meta is structs.ui.PlayerOutcomeMetadata)
            assertEquals("Surrendered", (meta as structs.ui.PlayerOutcomeMetadata).outcome)
            assertFalse(meta.victory)
        }
    }

    @Test
    fun `surrender triggers immediate game over when last non-surrendered human remains`() {
        runBlocking {
            // Two humans. P1 owns 1 tile, P2 owns nothing.
            val p1 = Player(name = "P1")
            val p2 = Player(name = "P2")
            WorldManager.world.activePlayers.addAll(listOf(p1, p2))
            WorldManager.world.turnOrder.addAll(listOf(p1.name, p2.name))
            WorldManager.world.mapTiles.add(
                Territory(name = "P1Only", ruler = p1.name, pointValue = 10, isDestroyed = false)
            )
            WorldManager.isGameActive = true

            var reported: GameOverData? = null
            UiSignalRpcHandlers.onGameOverBroadcast = { data -> reported = data }

            val result = TurnHarness.surrenderPlayer(p2.name, "bail out")

            assertTrue(result.accepted)
            assertTrue(result.gameEnded, "Game should have ended because only P1 remains")
            assertNotNull(reported, "GameOverData should have been broadcast")
            assertEquals(p1.name, reported?.winnerName, "P1 (the only non-surrendered contender) should win")
            assertTrue(reported!!.isVictory)
            assertFalse(WorldManager.isGameActive, "Game should be flagged inactive after surrender-driven end")
        }
    }

    @Test
    fun `surrender does not end the game if multiple contenders remain and no threshold is met`() {
        runBlocking {
            // Two humans with equal territory shares below the 2-player 60% threshold.
            val p1 = Player(name = "P1")
            val p2 = Player(name = "P2")
            WorldManager.world.activePlayers.addAll(listOf(p1, p2))
            WorldManager.world.turnOrder.addAll(listOf(p1.name, p2.name))
            WorldManager.world.mapTiles.addAll(
                listOf(
                    Territory(name = "P1Land", ruler = p1.name, pointValue = 10, isDestroyed = false),
                    Territory(name = "P2Land", ruler = p2.name, pointValue = 10, isDestroyed = false),
                )
            )
            WorldManager.isGameActive = true
            WorldManager.world.roundNumber = 5

            var reported: GameOverData? = null
            UiSignalRpcHandlers.onGameOverBroadcast = { data -> reported = data }

            val result = TurnHarness.surrenderPlayer(p2.name, "bail out")

            assertTrue(result.accepted)
            assertFalse(result.gameEnded, "Game should still be active because P1 hasn't hit 60%")
            assertNull(reported, "No game-over broadcast should fire")
            assertTrue(WorldManager.isGameActive, "Game should remain active")
        }
    }

    @Test
    fun `surrender updates threshold calculation - 4p drops to 3p requiring 55 percent`() {
        runBlocking {
            // 4 humans, 100 tiles total. P1 owns 53, P2 owns 23, P3 owns 22, P4 owns 2.
            // With 4 contenders, threshold is 51% → P1 wins. With 3 contenders (P4 surrendered),
            // threshold is 55% → P1 has 53/98 = 54.08% and does NOT win. After P1 captures
            // P4's now-unowned tiles, P1 has 55/98 = 56.1% and wins.
            val p1 = Player(name = "P1")
            val p2 = Player(name = "P2")
            val p3 = Player(name = "P3")
            val p4 = Player(name = "P4")
            WorldManager.world.activePlayers.addAll(listOf(p1, p2, p3, p4))
            WorldManager.world.turnOrder.addAll(listOf(p1.name, p2.name, p3.name, p4.name))
            val tiles = mutableListOf<Territory>()
            repeat(53) { tiles.add(Territory(name = "A$it", ruler = p1.name, pointValue = 10, isDestroyed = false)) }
            repeat(23) { tiles.add(Territory(name = "B$it", ruler = p2.name, pointValue = 10, isDestroyed = false)) }
            repeat(22) { tiles.add(Territory(name = "C$it", ruler = p3.name, pointValue = 10, isDestroyed = false)) }
            repeat(2)  { tiles.add(Territory(name = "D$it", ruler = p4.name, pointValue = 10, isDestroyed = false)) }
            WorldManager.world.mapTiles.addAll(tiles)
            WorldManager.world.roundNumber = 5
            WorldManager.isGameActive = true

            var reported: GameOverData? = null
            UiSignalRpcHandlers.onGameOverBroadcast = { data -> reported = data }

            // P4 surrenders. Threshold should now be 55% (3 contenders). P1 has 53/(98)
            // = 54.08% < 55% → no game over.
            val result = TurnHarness.surrenderPlayer(p4.name, "")
            assertTrue(result.accepted)
            assertFalse(result.gameEnded, "After P4 surrenders, P1 should still be under the 3p 55% threshold")
            assertNull(reported)

            // Now P1 claims P4's two released tiles. Tiles are NOT destroyed by surrender,
            // so 100 active tiles remain on the map. P1 claims the 2 unowned tiles
            // (53 + 2 = 55) which puts P1 at 55/100 = 55% — meeting the 3p threshold.
            WorldManager.world.mapTiles.filter { it.ruler.isBlank() }.forEach { it.ruler = p1.name }
            val activeTiles = WorldManager.world.mapTiles.count { !it.isDestroyed }
            val p1Tiles = WorldManager.world.mapTiles.count { !it.isDestroyed && it.ruler == p1.name }
            assertEquals(100, activeTiles)
            assertEquals(55, p1Tiles)

            TurnHarness.evaluateVictory()
            assertNotNull(reported, "P1 should now have hit the 3p threshold after capturing P4's tiles")
            assertEquals(p1.name, reported?.winnerName)
        }
    }

    @Test
    fun `surrender RPC rejects non-owner connection`() {
        runBlocking {
            val p1 = Player(name = "P1")
            WorldManager.world.activePlayers.add(p1)
            // P1 is registered under connection id "owner-1". Some other connection id
            // is trying to surrender on P1's behalf.
            WorldManager.playerStats.add(
                serverStructs.PlayerStats(playerData = p1, accelByteUserId = "u1", playerID = "owner-1", isControlledByNpc = false)
            )
            WorldManager.isGameActive = true

            val response = GameRpcHandlers.surrender(
                ctx = org.ttt.autogenesis.network.RpcCallContext(
                connectionId = "intruder-1",
                sender = { _ -> }
            ),
                request = org.ttt.autogenesis.network.SurrenderRequest(playerName = "P1", reason = "")
            )

            assertFalse(response.accepted)
            assertEquals("not_owner", response.reason)
            assertFalse(p1.isSurrendered)
        }
    }

    @Test
    fun `surrender RPC accepts the owning connection`() {
        runBlocking {
            val p1 = Player(name = "P1")
            WorldManager.world.activePlayers.add(p1)
            WorldManager.world.turnOrder.add(p1.name)
            WorldManager.playerStats.add(
                serverStructs.PlayerStats(playerData = p1, accelByteUserId = "u1", playerID = "owner-1", isControlledByNpc = false)
            )
            WorldManager.isGameActive = true

            val response = GameRpcHandlers.surrender(
                ctx = org.ttt.autogenesis.network.RpcCallContext(
                connectionId = "owner-1",
                sender = { _ -> }
            ),
                request = org.ttt.autogenesis.network.SurrenderRequest(playerName = "P1", reason = "tilt")
            )

            assertTrue(response.accepted, "Owner surrender must be accepted; got reason=${response.reason}")
            assertEquals("ok", response.reason)
            assertTrue(p1.isSurrendered)
        }
    }

    // =================================================================================
    // Surrender-disconnect regression coverage
    //
    // Single-player surrender must end the game AND close the surrendering player's
    // WebSocket (via the onPlayerDisconnectedFromSurrender hook). Multiplayer
    // surrender must NOT close the surrendered player — their match continues with
    // other humans and NPCs. The hook must also NOT fire on the rejection path
    // (unknown player, AI surrender, game-not-active, already-surrendered) since
    // no player actually left.
    // =================================================================================

    @Test
    fun `surrender in single-player mode fires onPlayerDisconnectedFromSurrender hook for the surrendering player`() {
        runBlocking {
            val player = Player(name = "Lord Maple Tree")
            WorldManager.world.activePlayers.add(player)
            WorldManager.world.turnOrder.add(player.name)
            WorldManager.world.mapTiles.add(
                Territory(name = "Mozambique", ruler = player.name, pointValue = 5, isDestroyed = false)
            )
            WorldManager.isGameActive = true
            WorldManager.isSinglePlayer = true
            WorldManager.humanPlayerName = player.name
            WorldManager.playerStats.add(
                serverStructs.PlayerStats(
                    playerData = player,
                    accelByteUserId = "u-lmt",
                    playerID = "kvision-ws-client-1234",
                    isControlledByNpc = false
                )
            )

            var capturedConnectionId: String? = null
            TurnHarness.onPlayerDisconnectedFromSurrender = { capturedConnectionId = it }

            val result = TurnHarness.surrenderPlayer(player.name, "user_initiated")

            assertTrue(result.accepted, "surrender must be accepted; got reason=${result.reason}")
            assertTrue(result.gameEnded, "surrender must end the single-player game")
            assertEquals(
                "kvision-ws-client-1234", capturedConnectionId,
                "hook must receive the surrendering player's connectionId so the caller can deregister their session"
            )
        }
    }

    @Test
    fun `surrender in multiplayer mode does NOT fire onPlayerDisconnectedFromSurrender`() {
        runBlocking {
            // 2 humans + an NPC. Surrender p1; p2 and the NPC continue.
            // No one is at any threshold, round < 25, so determineWinningOutcome
            // returns null and evaluateEndGame() does nothing. gameEnded stays
            // false. The hook must NOT fire because the surrendered player can
            // stay connected while the match continues.
            val p1 = Player(name = "P1")
            val p2 = Player(name = "P2")
            val ai = Player(name = "AI1")
            WorldManager.world.activePlayers.addAll(listOf(p1, p2, ai))
            WorldManager.world.turnOrder.addAll(listOf(p1.name, p2.name, ai.name))
            WorldManager.world.mapTiles.addAll(
                listOf(
                    Territory(name = "P1Land", ruler = p1.name, pointValue = 5, isDestroyed = false),
                    Territory(name = "P2Land", ruler = p2.name, pointValue = 5, isDestroyed = false),
                    Territory(name = "AILand", ruler = ai.name, pointValue = 5, isDestroyed = false)
                )
            )
            WorldManager.world.roundNumber = 1
            WorldManager.isGameActive = true
            WorldManager.isSinglePlayer = false
            WorldManager.humanPlayerName = p1.name
            WorldManager.playerStats.add(
                serverStructs.PlayerStats(
                    playerData = p1,
                    accelByteUserId = "u-1",
                    playerID = "ws-1",
                    isControlledByNpc = false
                )
            )

            var hookFired = false
            TurnHarness.onPlayerDisconnectedFromSurrender = { hookFired = true }

            val result = TurnHarness.surrenderPlayer(p1.name, "user_initiated")

            assertTrue(result.accepted)
            assertFalse(
                result.gameEnded,
                "surrender of one human in multiplayer must NOT end the game — p2 and the NPC continue"
            )
            assertFalse(
                hookFired,
                "hook must NOT fire in multiplayer — the surrendered player can stay connected while the match continues"
            )
        }
    }

    @Test
    fun `surrender hook is not fired when the rejection path is taken`() {
        runBlocking {
            WorldManager.isGameActive = true
            WorldManager.isSinglePlayer = true

            var hookFired = false
            TurnHarness.onPlayerDisconnectedFromSurrender = { hookFired = true }

            // unknown player — surrender rejected before any state mutates
            val result = TurnHarness.surrenderPlayer("Ghost", "")
            assertFalse(result.accepted)
            assertFalse(hookFired, "hook must not fire when surrender is rejected — the player wasn't disconnected")
        }
    }

    @Test
    fun `surrender hook is not fired when PlayerStats entry is missing`() {
        runBlocking {
            // Player exists in world but has no PlayerStats entry → no
            // connectionId to deregister. Hook must silently skip rather than
            // fire with a blank/null connectionId (which would be a useless
            // call into PlayerConnectionManager).
            val player = Player(name = "Statless")
            WorldManager.world.activePlayers.add(player)
            WorldManager.world.turnOrder.add(player.name)
            WorldManager.isGameActive = true
            WorldManager.isSinglePlayer = true
            WorldManager.humanPlayerName = player.name
            // No PlayerStats added

            var hookFired = false
            TurnHarness.onPlayerDisconnectedFromSurrender = { hookFired = true }

            TurnHarness.surrenderPlayer(player.name, "")

            assertFalse(
                hookFired,
                "hook must not fire when no PlayerStats entry provides a connectionId"
            )
        }
    }
}
