package agent.math

import agent.builders.validateAction.ActionTargetType
import agent.builders.validateAction.ActionTargetTypeObj
import agent.builders.validateAction.PlayType
import enums.CommanderTrait
import enums.CommanderType
import enums.TerritoryType
import gameState.WorldManager
import io.mockk.MockK
import io.mockk.unmockkAll
import org.junit.Before
import org.junit.Test
import structs.Border
import structs.Player
import structs.Territory
import structs.World
import kotlin.test.assertEquals

/**
 * Pins the multi-target penalty shape in [GameMath.calculateBaseScore].
 *
 * Design rationale: the system already charges multi-target plays through counter-play
 * (2 extra rivals invited per extra target), narrative-cooperation risk (~15% lower
 * capture rate for 3 targets vs 1), assessor penalty (~-10 favor), and override chance
 * (~+15% risk). The base-score debuff is meant to size *against* that implicit friction,
 * not stack on top of it. Exponential 0.5x debuff reduces 3-target baseScore by 75% on
 * top of the system's existing ~30-45% capture-rate penalty, locking multi-target plays
 * out past round 3. Linear -20 per extra target gives a ~30% reduction, matching the
 * system's implicit friction.
 *
 * Strategy: for each target count N, compute the score with all targets unowned (debuff
 * applies) and the score with all targets marked as player-owned (debuff does not apply).
 * The diff isolates the debuff magnitude and is independent of typeMod / target name
 * resolution.
 */
class MultiTargetPenaltyTest
{
    private fun buildPlayer(): Player
    {
        val home = Territory(name = "Home", type = TerritoryType.Land, ruler = "Commander", isCaptured = true)
        return Player(
            name = "Commander",
            commanderType = CommanderType.Land,
            trait = CommanderTrait.Warlord,
            startingTile = home,
            militaryReadiness = 70,
            legitimacy = 80,
            might = 15,
            reputation = 10
        ).apply {
            capturedTerritory.add(home)
        }
    }

    private fun target(name: String, unowned: Boolean): Territory
    {
        return Territory(
            name = name,
            type = TerritoryType.Land,
            ruler = if(unowned) "" else "Commander",
            isCaptured = !unowned
        )
    }

    private fun scoreWithTargetsOwned(player: Player, names: List<String>): Int
    {
        // All targets owned by player -> unownedTargetsCount = 0 -> no debuff.
        WorldManager.world = World(mapTiles = mutableListOf(player.startingTile).apply {
            addAll(names.map { target(it, unowned = false) })
        })
        return GameMath.calculateBaseScore(
            player,
            PlayType.Military,
            ActionTargetTypeObj(type = ActionTargetType.Territory, targets = names)
        )
    }

    private fun scoreWithTargetsUnowned(player: Player, names: List<String>): Int
    {
        // All targets unowned -> debuff applies (extraTerritories = N-1).
        WorldManager.world = World(mapTiles = mutableListOf(player.startingTile).apply {
            addAll(names.map { target(it, unowned = true) })
        })
        return GameMath.calculateBaseScore(
            player,
            PlayType.Military,
            ActionTargetTypeObj(type = ActionTargetType.Territory, targets = names)
        )
    }

    private fun diplomaticScoreWithTargetsOwned(player: Player, names: List<String>): Int
    {
        WorldManager.world = World(mapTiles = mutableListOf(player.startingTile).apply {
            addAll(names.map { target(it, unowned = false) })
        })
        return GameMath.calculateBaseScore(
            player,
            PlayType.Diplomatic,
            ActionTargetTypeObj(type = ActionTargetType.Territory, targets = names)
        )
    }

    private fun diplomaticScoreWithTargetsUnowned(player: Player, names: List<String>): Int
    {
        WorldManager.world = World(mapTiles = mutableListOf(player.startingTile).apply {
            addAll(names.map { target(it, unowned = true) })
        })
        return GameMath.calculateBaseScore(
            player,
            PlayType.Diplomatic,
            ActionTargetTypeObj(type = ActionTargetType.Territory, targets = names)
        )
    }

    @Before
    fun resetWorld()
    {
        // Clear any lingering MockK stubs from a prior test in the same JVM.
        // SummitOrchestratorTest sets up broad slot-mock stubs that can
        // interfere with GameMath's internal coroutine scopes if not cleaned.
        unmockkAll()
        WorldManager.world = World()
    }

    @Test
    fun military_singleTarget_noDebuff()
    {
        // 1 target unowned vs 1 target owned: debuff must be 0 (no extra targets).
        val player = buildPlayer()
        val unownedScore = scoreWithTargetsUnowned(player, listOf("T1"))
        val ownedScore = scoreWithTargetsOwned(player, listOf("T1"))

        assertEquals(0, unownedScore - ownedScore, "Single-target must NOT apply any debuff")
    }

    @Test
    fun military_twoTargets_linearDebuff20()
    {
        val player = buildPlayer()
        val unownedScore = scoreWithTargetsUnowned(player, listOf("T1", "T2"))
        val ownedScore = scoreWithTargetsOwned(player, listOf("T1", "T2"))

        // Linear -20 per extra target. 2 targets => -20.
        // Pre-fix (exponential 0.5x): debuff magnitude would be 50% of the score.
        assertEquals(-20, unownedScore - ownedScore, "Two-target military debuff must be exactly -20 (linear)")
    }

    @Test
    fun military_threeTargets_linearDebuff40()
    {
        val player = buildPlayer()
        val unownedScore = scoreWithTargetsUnowned(player, listOf("T1", "T2", "T3"))
        val ownedScore = scoreWithTargetsOwned(player, listOf("T1", "T2", "T3"))

        // Linear -20 per extra target. 3 targets => -40.
        // Pre-fix (exponential 0.5^2 = 0.25x): debuff magnitude would be 75% of the score.
        assertEquals(-40, unownedScore - ownedScore, "Three-target military debuff must be exactly -40 (linear)")
    }

    @Test
    fun military_fourTargets_linearDebuff60()
    {
        val player = buildPlayer()
        val unownedScore = scoreWithTargetsUnowned(player, listOf("T1", "T2", "T3", "T4"))
        val ownedScore = scoreWithTargetsOwned(player, listOf("T1", "T2", "T3", "T4"))

        // Linear -20 per extra target. 4 targets => -60.
        // Pre-fix (exponential 0.5^3 = 0.125x): debuff magnitude would be 87.5% of the score.
        assertEquals(-60, unownedScore - ownedScore, "Four-target military debuff must be exactly -60 (linear)")
    }

    @Test
    fun diplomatic_twoTargets_linearDebuff15()
    {
        val player = buildPlayer()
        val unownedScore = diplomaticScoreWithTargetsUnowned(player, listOf("T1", "T2"))
        val ownedScore = diplomaticScoreWithTargetsOwned(player, listOf("T1", "T2"))

        // Linear -15 per extra target. 2 targets => -15.
        // Pre-fix (exponential 0.75x): debuff magnitude would be 25% of the score.
        assertEquals(-15, unownedScore - ownedScore, "Two-target diplomatic debuff must be exactly -15 (linear)")
    }

    @Test
    fun diplomatic_threeTargets_linearDebuff30()
    {
        val player = buildPlayer()
        val unownedScore = diplomaticScoreWithTargetsUnowned(player, listOf("T1", "T2", "T3"))
        val ownedScore = diplomaticScoreWithTargetsOwned(player, listOf("T1", "T2", "T3"))

        // Linear -15 per extra target. 3 targets => -30.
        // Pre-fix (exponential 0.75^2 = 0.5625x): debuff magnitude would be ~44% of the score.
        assertEquals(-30, unownedScore - ownedScore, "Three-target diplomatic debuff must be exactly -30 (linear)")
    }
}