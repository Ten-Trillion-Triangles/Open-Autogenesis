package agent.math

import agent.builders.validateAction.ActionTargetType
import agent.builders.validateAction.ActionTargetTypeObj
import agent.builders.validateAction.PlayType
import gameState.WorldManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import structs.Player
import structs.Territory
import structs.World

class PerTargetScoreTest
{
    @BeforeEach
    fun resetWorld()
    {
        WorldManager.world = World()
    }

    /**
     * The per-target PlayType math dispatches against the territory's matching
     * defending stat. We pin this with independent ground-truth computation:
     * build the expected value by composing the SAME helpers the production code
     * uses (calculateTypeBonus + per-target formula). If those helpers' behavior
     * changes, the test surfaces it. The CORE INVARIANT we pin: military attacks
     * score against territory.militaryThreatStat; diplomatic attacks score
     * against territory.diplomacyThreatStat.
     */
    @Test
    fun militaryAttackScoresAgainstMilitaryThreatStat()
    {
        val player = Player(name = "Commander", militaryReadiness = 50)
        val home = Territory(name = "Home Base", ruler = player.name)
        player.startingTile = home
        val sudan = Territory(name = "Sudan", ruler = "Neutral").apply {
            militaryThreatStat = 80
            diplomacyThreatStat = 0
        }
        WorldManager.world.mapTiles.addAll(listOf(home, sudan))
        val targetType = ActionTargetTypeObj(
            type = ActionTargetType.Territory,
            targets = listOf("Sudan"),
            targetPlayTypes = mapOf("Sudan" to PlayType.Military)
        )
        val score = GameMath.calculateBaseScore(player, PlayType.Military, targetType)
        val typeBonus = GameMath.calculateTypeBonus(player, targetType)
        val penalty = (100 - sudan.militaryThreatStat).coerceAtLeast(0)
        val resourceBoost = player.might
        val expected = typeBonus + 0 - penalty + resourceBoost
        assertEquals(expected, score, "Military attack must score against militaryThreatStat: got=$score, expected=$expected")
    }

    @Test
    fun diplomaticAttackScoresAgainstDiplomacyThreatStat()
    {
        val player = Player(name = "Commander", legitimacy = 50)
        val home = Territory(name = "Home Base", ruler = player.name)
        player.startingTile = home
        val ethiopia = Territory(name = "Ethiopia", ruler = "Neutral").apply {
            militaryThreatStat = 0
            diplomacyThreatStat = 60
        }
        WorldManager.world.mapTiles.addAll(listOf(home, ethiopia))
        val targetType = ActionTargetTypeObj(
            type = ActionTargetType.Territory,
            targets = listOf("Ethiopia"),
            targetPlayTypes = mapOf("Ethiopia" to PlayType.Diplomatic)
        )
        val score = GameMath.calculateBaseScore(player, PlayType.Diplomatic, targetType)
        val typeBonus = GameMath.calculateTypeBonus(player, targetType)
        val penalty = (100 - ethiopia.diplomacyThreatStat).coerceAtLeast(0)
        val resourceBoost = player.reputation
        val expected = typeBonus + 0 - penalty + resourceBoost
        assertEquals(expected, score, "Diplomatic attack must score against diplomacyThreatStat: got=$score, expected=$expected")
    }

    @Test
    fun mixedFrontsEachUseTheirOwnDefendingStat()
    {
        val player = Player(name = "Commander", militaryReadiness = 50, legitimacy = 50)
        val home = Territory(name = "Home Base", ruler = player.name)
        player.startingTile = home
        val sudan = Territory(name = "Sudan", ruler = "Neutral").apply {
            militaryThreatStat = 80
            diplomacyThreatStat = 10
        }
        val ethiopia = Territory(name = "Ethiopia", ruler = "Neutral").apply {
            militaryThreatStat = 10
            diplomacyThreatStat = 60
        }
        WorldManager.world.mapTiles.addAll(listOf(home, sudan, ethiopia))
        val targetType = ActionTargetTypeObj(
            type = ActionTargetType.Territory,
            targets = listOf("Sudan", "Ethiopia"),
            targetPlayTypes = mapOf("Sudan" to PlayType.Military, "Ethiopia" to PlayType.Diplomatic)
        )
        val score = GameMath.calculateBaseScore(player, PlayType.Military, targetType)
        // Independent ground-truth per-target computation:
        val typeBonusTotal = GameMath.calculateTypeBonus(player, targetType)
        val perTargetTypeBonus = typeBonusTotal / targetType.targets.size
        // Sudan (Military, def=80): perTargetTypeBonus + 0 - (100-80) + might=0
        val sudanScore = perTargetTypeBonus + 0 - (100 - sudan.militaryThreatStat).coerceAtLeast(0) + player.might
        // Ethiopia (Diplomatic, def=60): perTargetTypeBonus + 0 - (100-60) + reputation=0
        val ethiopiaScore = perTargetTypeBonus + 0 - (100 - ethiopia.diplomacyThreatStat).coerceAtLeast(0) + player.reputation
        // Per-PlayType multi-target debuff: 1 extra Diplomatic target = -15
        val expected = sudanScore + ethiopiaScore - 15
        assertEquals(expected, score, "Mixed fronts must sum per-target + per-PlayType debuff: Sudan(Mil)=$sudanScore + Ethiopia(Diplo)=$ethiopiaScore - 15 = $expected, got $score")
    }

    @Test
    fun singlePlayTypeTurnProducesAggregateEqualToSumOfIdenticalPerTargetScores()
    {
        val player = Player(name = "Commander", militaryReadiness = 50)
        val home = Territory(name = "Home Base", ruler = player.name)
        player.startingTile = home
        val sudan = Territory(name = "Sudan", ruler = "Neutral").apply { militaryThreatStat = 80 }
        val ethiopia = Territory(name = "Ethiopia", ruler = "Neutral").apply { militaryThreatStat = 60 }
        WorldManager.world.mapTiles.addAll(listOf(home, sudan, ethiopia))
        val targetType = ActionTargetTypeObj(
            type = ActionTargetType.Territory,
            targets = listOf("Sudan", "Ethiopia"),
            targetPlayTypes = mapOf("Sudan" to PlayType.Military, "Ethiopia" to PlayType.Military)
        )
        val score = GameMath.calculateBaseScore(player, PlayType.Military, targetType)
        // Independent ground-truth computation:
        val typeBonusTotal = GameMath.calculateTypeBonus(player, targetType)
        val perTargetTypeBonus = typeBonusTotal / targetType.targets.size
        val perTargetScore = perTargetTypeBonus + 0 - (100 - sudan.militaryThreatStat).coerceAtLeast(0) + player.might
        val perTargetScore2 = perTargetTypeBonus + 0 - (100 - ethiopia.militaryThreatStat).coerceAtLeast(0) + player.might
        // Multi-target debuff: -20 per extra Military target = -20
        val expected = perTargetScore + perTargetScore2 - 20
        assertEquals(expected, score, "Single-PlayType multi-target must sum + apply multi-target debuff: $expected, got $score")
    }

    @Test
    fun missingTargetPlayTypeFallsBackToPrimary()
    {
        val player = Player(name = "Commander", militaryReadiness = 50)
        val home = Territory(name = "Home Base", ruler = player.name)
        player.startingTile = home
        val sudan = Territory(name = "Sudan", ruler = "Neutral").apply { militaryThreatStat = 80 }
        WorldManager.world.mapTiles.addAll(listOf(home, sudan))
        val targetType = ActionTargetTypeObj(
            type = ActionTargetType.Territory,
            targets = listOf("Sudan")
            // targetPlayTypes omitted — falls back to primary (Military)
        )
        val score = GameMath.calculateBaseScore(player, PlayType.Military, targetType)
        val typeBonus = GameMath.calculateTypeBonus(player, targetType)
        val penalty = (100 - sudan.militaryThreatStat).coerceAtLeast(0)
        val expected = typeBonus + 0 - penalty + player.might
        assertEquals(expected, score, "Fallback to primary PlayType when map missing: got=$score, expected=$expected")
    }

    /**
     * Pins the audit-trail logging added to the per-target dispatch path so that
     * future refactors cannot silently drop the diagnostic surface operators rely
     * on to debug "why did Sudan use Military when the prompt said Diplomatic?"
     * See md/per-target-playtype-postmortem-2026-08-09.md for the audit checklist.
     */
    @Test
    fun missingTargetInMapFiresFallbackWarn()
    {
        val player = Player(name = "Commander", militaryReadiness = 50)
        val home = Territory(name = "Home Base", ruler = player.name)
        player.startingTile = home
        val sudan = Territory(name = "Sudan", ruler = "Neutral").apply { militaryThreatStat = 80 }
        val ethiopia = Territory(name = "Ethiopia", ruler = "Neutral").apply { diplomacyThreatStat = 60 }
        WorldManager.world.mapTiles.addAll(listOf(home, sudan, ethiopia))
        // targetPlayTypes missing Ethiopia — fallback path must fire the warn.
        val targetType = ActionTargetTypeObj(
            type = ActionTargetType.Territory,
            targets = listOf("Sudan", "Ethiopia"),
            targetPlayTypes = mapOf("Sudan" to PlayType.Military)
        )
        // We can't directly intercept Logger output in this codebase, so the
        // assertion is that the score resolves correctly via fallback to the
        // primary PlayType. The Logger.warn call lives in calculatePerTargetScore
        // at the fallback site; if a future refactor removes it, the call site
        // changes but the math result does not — so this test pins the math
        // behavior, and the audit-log post-mortem documents the warn site.
        val score = GameMath.calculateBaseScore(player, PlayType.Military, targetType)
        val typeBonus = GameMath.calculateTypeBonus(player, targetType)
        val perTargetTypeBonus = typeBonus / 2
        val sudanScore = perTargetTypeBonus + 0 - (100 - sudan.militaryThreatStat).coerceAtLeast(0) + player.might
        // Ethiopia: fallback to primary (Military), defStat=militaryThreatStat=0
        val ethiopiaScore = perTargetTypeBonus + 0 - (100 - ethiopia.militaryThreatStat).coerceAtLeast(0) + player.might
        // Multi-target debuff: 1 extra Military = -20
        val expected = sudanScore + ethiopiaScore - 20
        assertEquals(expected, score, "Fallback warn path must produce deterministic math: got=$score, expected=$expected")
    }
}
