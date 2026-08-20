package org.ttt.autogenesis.server.audio

import enums.NpcType
import org.junit.Test
import org.ttt.autogenesis.server.audio.MusicSelector
import structs.Npc
import structs.Territory
import structs.World
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [MusicSelector.canWinInNext4Rounds].
 *
 * The actual end-of-game check is 75% territory point share; the
 * lookahead is necessarily fuzzy. The current heuristic is "any owner
 * with ≥60% of active territory points, OR any undefeated Nemesis /
 * Elder God with ≥50% destroyed territory share". These tests pin the
 * matrix at and around the threshold plus the Elder-God branch.
 */
class CanWinInNext4RoundsTest
{
    // ─── empty / trivial maps ─────────────────────────────────────────────

    @Test
    fun `empty map returns false`()
    {
        val world = World(roundNumber = 1, mapTiles = mutableListOf())
        assertFalse(MusicSelector.canWinInNext4Rounds(world))
    }

    @Test
    fun `map with one tile below threshold returns false`()
    {
        val world = World(roundNumber = 1, mapTiles = mutableListOf(
            territory("T1", ruler = "Alice", pointValue = 4, isDestroyed = false),
            territory("T2", ruler = "Bob", pointValue = 4, isDestroyed = false),
            territory("T3", ruler = "Carol", pointValue = 4, isDestroyed = false),
            territory("T4", ruler = "Alice", pointValue = 4, isDestroyed = false),
            territory("T5", ruler = "Bob", pointValue = 4, isDestroyed = false)
        ))
        // Alice has 2/5 = 40% — below 60% threshold.
        assertFalse(MusicSelector.canWinInNext4Rounds(world))
    }

    // ─── territory-share branch ───────────────────────────────────────────

    @Test
    fun `owner crossing 60 percent territory share returns true`()
    {
        // 7 tiles, 5 point value each = 35 total. Alice has 4 tiles = 20
        // points = 57.1%, Bob has 3 tiles = 15 points = 42.9%.
        // Need to push Alice to 60%. Switch 2 tiles from Bob to Alice.
        val world = World(roundNumber = 1, mapTiles = mutableListOf(
            territory("T1", ruler = "Alice", pointValue = 5),
            territory("T2", ruler = "Alice", pointValue = 5),
            territory("T3", ruler = "Alice", pointValue = 5),
            territory("T4", ruler = "Alice", pointValue = 5),
            territory("T5", ruler = "Bob", pointValue = 5),
            territory("T6", ruler = "Bob", pointValue = 5),
            territory("T7", ruler = "Bob", pointValue = 5)
        ))
        // Alice: 4/7 = 57.1% — still below.
        assertFalse(MusicSelector.canWinInNext4Rounds(world), "4/7 = 57.1% should be below 60%")
    }

    @Test
    fun `owner at exactly 60 percent territory share returns true`()
    {
        // 5 tiles, 5 pts each = 25 total. Alice has 3 = 15 pts = 60%.
        val world = World(roundNumber = 1, mapTiles = mutableListOf(
            territory("T1", ruler = "Alice", pointValue = 5),
            territory("T2", ruler = "Alice", pointValue = 5),
            territory("T3", ruler = "Alice", pointValue = 5),
            territory("T4", ruler = "Bob", pointValue = 5),
            territory("T5", ruler = "Bob", pointValue = 5)
        ))
        // Alice: 3/5 = 60.0% — at threshold.
        assertTrue(MusicSelector.canWinInNext4Rounds(world), "3/5 = 60.0% should meet 60% threshold")
    }

    @Test
    fun `owner well above 60 percent returns true`()
    {
        val world = World(roundNumber = 1, mapTiles = mutableListOf(
            territory("T1", ruler = "Alice", pointValue = 10),
            territory("T2", ruler = "Bob", pointValue = 5),
            territory("T3", ruler = "Bob", pointValue = 5)
        ))
        // Alice: 10/20 = 50%. Need to push higher. Adjust.
        val adjusted = World(roundNumber = 1, mapTiles = mutableListOf(
            territory("T1", ruler = "Alice", pointValue = 15),
            territory("T2", ruler = "Alice", pointValue = 15),
            territory("T3", ruler = "Bob", pointValue = 5)
        ))
        // Alice: 30/35 = 85.7%
        assertTrue(MusicSelector.canWinInNext4Rounds(adjusted))
    }

    @Test
    fun `destroyed tiles are excluded from active point totals`()
    {
        // 5 tiles = 25 total. 2 are destroyed (Bob's) so active total
        // is 15. Alice has 3 active = 15 = 100% of active.
        val world = World(roundNumber = 1, mapTiles = mutableListOf(
            territory("T1", ruler = "Alice", pointValue = 5),
            territory("T2", ruler = "Alice", pointValue = 5),
            territory("T3", ruler = "Alice", pointValue = 5),
            territory("T4", ruler = "Bob", pointValue = 5, isDestroyed = true),
            territory("T5", ruler = "Bob", pointValue = 5, isDestroyed = true)
        ))
        assertTrue(MusicSelector.canWinInNext4Rounds(world))
    }

    @Test
    fun `custom threshold parameter is honored`()
    {
        // Owner at 50% — would pass a 50% threshold but not the default 60%.
        val world = World(roundNumber = 1, mapTiles = mutableListOf(
            territory("T1", ruler = "Alice", pointValue = 5),
            territory("T2", ruler = "Alice", pointValue = 5),
            territory("T3", ruler = "Bob", pointValue = 5),
            territory("T4", ruler = "Bob", pointValue = 5)
        ))
        // Alice: 10/20 = 50%
        assertTrue(MusicSelector.canWinInNext4Rounds(world, thresholdPercent = 50.0))
        assertFalse(MusicSelector.canWinInNext4Rounds(world, thresholdPercent = 51.0))
    }

    // ─── Elder-God / Nemesis branch ───────────────────────────────────────

    @Test
    fun `undefeated Nemesis with high destruction share returns true`()
    {
        // 4 tiles, 2 destroyed by Nemesis action = 50% destroyed share.
        // Active total = 10. Alice: 10/10 = 100% — also trips the
        // territory branch, so use a less lopsided layout.
        val world = World(
            roundNumber = 8,
            mapTiles = mutableListOf(
                territory("T1", ruler = "Alice", pointValue = 5),
                territory("T2", ruler = "Bob", pointValue = 5),
                territory("T3", ruler = "Bob", pointValue = 5, isDestroyed = true),
                territory("T4", ruler = "Bob", pointValue = 5, isDestroyed = true)
            ),
            npc = mutableListOf(
                Npc(name = "Xilaron", type = NpcType.Nemesis, isDefeated = false)
            )
        )
        // Alice: 5/15 = 33%. Below 60% — would not trip territory branch.
        // Destroyed: 2/4 = 50% — at the elder-god threshold.
        assertTrue(MusicSelector.canWinInNext4Rounds(world))
    }

    @Test
    fun `undefeated ElderGod with high destruction share returns true`()
    {
        val world = World(
            roundNumber = 8,
            mapTiles = mutableListOf(
                territory("T1", ruler = "Alice", pointValue = 5),
                territory("T2", ruler = "Bob", pointValue = 5),
                territory("T3", ruler = "Bob", pointValue = 5, isDestroyed = true),
                territory("T4", ruler = "Bob", pointValue = 5, isDestroyed = true)
            ),
            npc = mutableListOf(
                Npc(name = "Eleuryiyidict", type = NpcType.ElderGod, isDefeated = false)
            )
        )
        assertTrue(MusicSelector.canWinInNext4Rounds(world))
    }

    @Test
    fun `defeated Nemesis does not trigger the elder-god branch`()
    {
        val world = World(
            roundNumber = 8,
            mapTiles = mutableListOf(
                territory("T1", ruler = "Alice", pointValue = 5),
                territory("T2", ruler = "Bob", pointValue = 5),
                territory("T3", ruler = "Bob", pointValue = 5, isDestroyed = true),
                territory("T4", ruler = "Bob", pointValue = 5, isDestroyed = true)
            ),
            npc = mutableListOf(
                Npc(name = "Xilaron", type = NpcType.Nemesis, isDefeated = true)
            )
        )
        // Alice: 5/15 = 33%. Below 60% — territory branch false.
        // Defeated Nemesis — elder-god branch also false.
        assertFalse(MusicSelector.canWinInNext4Rounds(world))
    }

    @Test
    fun `Nemesis alive but destruction share below 50 percent returns false`()
    {
        // Three owners split 60% of active points evenly, so no one
        // crosses the 60% territory-share threshold; destroyed share
        // is also below 50%, so the elder-god branch must not fire.
        val world = World(
            roundNumber = 4,
            mapTiles = mutableListOf(
                territory("T1", ruler = "Alice", pointValue = 5),
                territory("T2", ruler = "Bob", pointValue = 5),
                territory("T3", ruler = "Carol", pointValue = 5),
                territory("T4", ruler = "Alice", pointValue = 5, isDestroyed = true),
                territory("T5", ruler = "Bob", pointValue = 5)
            ),
            npc = mutableListOf(
                Npc(name = "Xilaron", type = NpcType.Nemesis, isDefeated = false)
            )
        )
        // Active: T1/T2/T3/T5 = 20 pts. Alice 5, Bob 10, Carol 5.
        // Max owner share = Bob 10/20 = 50% (below 60%).
        // Destroyed: 1/5 = 20% (below 50%).
        assertFalse(MusicSelector.canWinInNext4Rounds(world))
    }

    @Test
    fun `no active map points returns false`()
    {
        // All tiles destroyed — no active total → no threshold can be met.
        val world = World(
            roundNumber = 8,
            mapTiles = mutableListOf(
                territory("T1", ruler = "Alice", pointValue = 5, isDestroyed = true),
                territory("T2", ruler = "Bob", pointValue = 5, isDestroyed = true)
            ),
            npc = mutableListOf(
                Npc(name = "Xilaron", type = NpcType.Nemesis, isDefeated = false)
            )
        )
        assertFalse(MusicSelector.canWinInNext4Rounds(world))
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private fun territory(
        name: String,
        ruler: String,
        pointValue: Int,
        isDestroyed: Boolean = false
    ): Territory = Territory(name = name, ruler = ruler, pointValue = pointValue, isDestroyed = isDestroyed)
}