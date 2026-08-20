package agent.math

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import org.junit.Before
import structs.Player
import structs.Npc
import structs.World
import structs.Territory
import enums.CommanderTrait
import enums.CommanderType
import gameState.WorldManager
import agent.builders.validateAction.PlayType
import agent.builders.validateAction.ActionTargetType
import agent.builders.validateAction.ActionTargetTypeObj
import agent.builders.judgeOutcome.AgentAssessmentLevel

class GameMathTest
{

    @Test
    fun calculateBaseScore_appliesTraitBuffs()
    {
        val player = Player(name = "Warlord", trait = CommanderTrait.Warlord)
        // Reset defaults that might interfere
        player.militaryReadiness = 100 // 0 Penalty
        
        // Military Play: +20 Buff
        val score = GameMath.calculateBaseScore(player, PlayType.Military, ActionTargetTypeObj())
        // Base = 20 (Trait) - 0 (Decay) + 0 (Resources)
        // Wait, Might resource adds (might/2). might defaults to 0.
        assertEquals(20, score, "Warlord should get +20 on Military")
        
        // Diplomatic Play: -20 Debuff
        player.legitimacy = 100 // 0 Penalty
        val diploScore = GameMath.calculateBaseScore(player, PlayType.Diplomatic, ActionTargetTypeObj())
        assertEquals(-20, diploScore, "Warlord should get -20 on Diplomacy")
    }

    @Test
    fun calculateBaseScore_appliesDecayPenalties()
    {
        val player = Player(name = "LazyGeneral", trait = CommanderTrait.Balanced)
        // Readiness 30 -> Penalty = 100 - 30 = 70
        player.militaryReadiness = 30
        
        // Military Formula: Type(0) + Trait(0) - Penalty(70) + Res(0) = -70
        val score = GameMath.calculateBaseScore(player, PlayType.Military, ActionTargetTypeObj())
        assertEquals(-70, score, "Readiness of 30 should result in -70 penalty")
    }
    
    @kotlin.test.Ignore
    @Test
    fun calculateBaseScore_appliesResourceBuffs()
    {
        val player = Player(name = "RichGuy", trait = CommanderTrait.Balanced)
        player.stagnation = 0 // 0 Penalty
        player.wealth = 50 // Should add 25 points (wealth/2)
        
        // Research Formula: 100 - Stagnation(0) + Resources(25) = 125
        val score = GameMath.calculateBaseScore(player, PlayType.Research, ActionTargetTypeObj())
        assertEquals(125, score, "Wealth 50 should add 25 points to Base 100 Research Score")
    }

    @Test
    fun calculateTotalScore_addsFavor()
    {
        val base = 10
        val assessment = AgentAssessmentLevel(favorPoints = -15)
        assertEquals(-5, GameMath.calculateTotalScore(base, assessment))
    }

    @Test
    fun determineOutcomeGuidance_hardenSoftenLogic()
    {
        // Case 1: Favored Win -> Hardened Victory
        val guidance1 = GameMath.determineOutcomeGuidance(10, didFlip = false, assessment = AgentAssessmentLevel(favorPoints = 10))
        assertTrue(guidance1.contains("HARDENED VICTORY"))

        // Case 2: Unfavored Win -> Softened Victory
        // Total < 0 (Unfavored), DidFlip = True -> Win.
        val guidance2 = GameMath.determineOutcomeGuidance(-10, didFlip = true, assessment = AgentAssessmentLevel(favorPoints = -10))
        // Note: Assessment favorPoints must match implied state. If total < 0, implies favor was low or base was low. 
        // Here Favor is -10. 
        assertTrue(guidance2.contains("SOFTENED VICTORY"), "Unfavored (-10) + Flip (Win) should be Softened Victory")

        // Case 3: Favored Loss (Bad Luck) -> Softened Defeat
        // Total > 0 (Favored), DidFlip = True -> Loss.
        val guidance3 = GameMath.determineOutcomeGuidance(10, didFlip = true, assessment = AgentAssessmentLevel(favorPoints = 10))
        assertTrue(guidance3.contains("SOFTENED DEFEAT"), "Favored (10) + Flip (Loss) should be Softened Defeat")
        
        // Case 4: Unfavored Loss -> Hardened Defeat
        // Total < 0, DidFlip = False -> Loss.
        val guidance4 = GameMath.determineOutcomeGuidance(-10, didFlip = false, assessment = AgentAssessmentLevel(favorPoints = -10))
        assertTrue(guidance4.contains("HARDENED DEFEAT"))
    }

    @Test
    fun resolveAction_reportsNarrativeOverrideChance()
    {
        val player = Player(
            name = "Reporter",
            trait = CommanderTrait.Warlord,
            militaryReadiness = 100,
            luckPoints = 30
        )

        val assessment = AgentAssessmentLevel(
            favorPoints = 0,
            riskLevel = 80
        )

        val outcome = GameMath.resolveAction(
            player = player,
            playType = PlayType.Military,
            targetType = ActionTargetTypeObj(),
            assessment = assessment,
            isSimulatedSuccess = true,
            usedAssets = emptyList()
        )

        assertEquals(50, outcome.narrativeOverrideChance, "Risk (80) minus Luck (30) should be 50% override chance")
    }

    @Test
    fun resolveAction_cannotKeepNarrativeWhenChanceZero()
    {
        val player = Player(
            name = "Unlucky",
            trait = CommanderTrait.Balanced,
            militaryReadiness = 0,
            luckPoints = 50
        )

        val assessment = AgentAssessmentLevel(
            favorPoints = 0,
            riskLevel = 20
        )

        val outcome = GameMath.resolveAction(
            player = player,
            playType = PlayType.Military,
            targetType = ActionTargetTypeObj(),
            assessment = assessment,
            isSimulatedSuccess = true,
            usedAssets = emptyList()
        )

        assertFalse(outcome.statVictory, "Stat victory should be false when the total score remains negative")
        assertFalse(outcome.finalSuccess, "Final success must match the stat outcome when override chance is zero")
        assertEquals(0, outcome.narrativeOverrideChance)
    }

    @Test
    fun resolveAction_forceNarrativeFailureWhenOverrideChanceFull()
    {
        val player = Player(
            name = "Favored",
            trait = CommanderTrait.Warlord,
            militaryReadiness = 100,
            luckPoints = 0
        )

        val assessment = AgentAssessmentLevel(
            favorPoints = 30,
            riskLevel = 100
        )

        val outcome = GameMath.resolveAction(
            player = player,
            playType = PlayType.Military,
            targetType = ActionTargetTypeObj(),
            assessment = assessment,
            isSimulatedSuccess = false,
            usedAssets = emptyList()
        )

        assertTrue(outcome.statVictory, "Stats declared this play a win")
        assertFalse(outcome.narrativeVictory, "Narrative reported failure")
        assertEquals(100, outcome.narrativeOverrideChance)
        assertFalse(outcome.finalSuccess, "With full override chance the narrative outcome wins")
        assertTrue(outcome.didFlip, "The final result flipped relative to the stats")
    }

    @Test
    fun resolveAction_appliesOvertonBonusWhenUnconventional()
    {
        val player = Player(
            name = "Researcher",
            trait = CommanderTrait.Researcher,
            stagnation = 0
        )

        val assessment = AgentAssessmentLevel(
            favorPoints = 0,
            riskLevel = 10,
            isConventionalForOvertonWindow = false
        )

        val outcome = GameMath.resolveAction(
            player = player,
            playType = PlayType.Research,
            targetType = ActionTargetTypeObj(),
            assessment = assessment,
            isSimulatedSuccess = true,
            usedAssets = emptyList()
        )

        assertEquals(160, outcome.totalScore)
    }

    @Test
    fun resolveNpcVsPlayerConflict_appliesDefenderPressure()
    {
        val npc = Npc(
            name = "Aggressor",
            militaryReadiness = 63,
            pointValue = 5
        )
        val defenders = listOf(
            Player(name = "Defender1", militaryReadiness = 90, might = 60, luckPoints = 100),
            Player(name = "Defender2", militaryReadiness = 95, might = 50, luckPoints = 100)
        )

        val outcome = GameMath.resolveNpcVsPlayerConflict(
            npc = npc,
            defenders = defenders,
            playType = PlayType.Military,
            isSimulatedSuccess = true,
            usedAssets = emptyList(),
            riskLevel = 30
        )

        assertFalse(outcome.statVictory, "Strong defenders should suppress NPC stat victory")
        assertFalse(outcome.finalSuccess, "With zero override chance the stat loss remains final")
        assertEquals(0, outcome.narrativeOverrideChance)
    }

    @Test
    fun resolveNpcVsPlayerConflict_handlesNoDefenders()
    {
        val npc = Npc(
            name = "Raider",
            militaryReadiness = 80,
            pointValue = 6
        )

        val outcome = GameMath.resolveNpcVsPlayerConflict(
            npc = npc,
            defenders = emptyList(),
            playType = PlayType.Military,
            isSimulatedSuccess = true,
            usedAssets = listOf("Saboteurs"),
            riskLevel = 0
        )

        assertTrue(outcome.statVictory, "No defenders should leave pressure fully with the NPC")
        assertTrue(outcome.finalSuccess)
        assertEquals(0, outcome.narrativeOverrideChance)
    }

    @Before
    fun resetWorld()
    {
        // Each test starts from a clean World so roundNumber and tile state are deterministic.
        WorldManager.world = World()
    }

    /**
     * Helper: run a single Research play with the supplied world and target config, then return
     * the resulting [GameMath.MathOutcome]. Player is a vanilla Balanced commander so the only
     * term that varies between tests is the early-round boost.
     *
     * For Research with stagnation=0, wealth=0, conventional, success, no assets:
     *   baseScore  = 100
     *   favor      = 0
     *   momentum   = +40
     *   assetBonus = 0
     *   overton    = 0
     *   => totalScore = 140 + earlyRoundBoost
     */
    private fun runResearchPlay(targetType: ActionTargetTypeObj): Int
    {
        val player = Player(name = "TestPlayer", trait = CommanderTrait.Balanced)
        val outcome = GameMath.resolveAction(
            player = player,
            playType = PlayType.Research,
            targetType = targetType,
            assessment = AgentAssessmentLevel(favorPoints = 0, riskLevel = 10),
            isSimulatedSuccess = true,
            usedAssets = emptyList()
        )
        return outcome.totalScore
    }

    @Test
    fun earlyRoundBoost_round1_applies140ForUnownedTerritory()
    {
        WorldManager.world = World(
            roundNumber = 1,
            mapTiles = mutableListOf(Territory(name = "OpenPlain", ruler = "Unowned"))
        )
        val total = runResearchPlay(
            ActionTargetTypeObj(type = ActionTargetType.Territory, targets = listOf("OpenPlain"))
        )
        assertEquals(280, total, "Round 1 unowned Territory play should include the +140 early-round boost")
    }

    @Test
    fun earlyRoundBoost_round2_applies100ForUnownedTerritory()
    {
        WorldManager.world = World(
            roundNumber = 2,
            mapTiles = mutableListOf(Territory(name = "OpenPlain", ruler = "Unowned"))
        )
        val total = runResearchPlay(
            ActionTargetTypeObj(type = ActionTargetType.Territory, targets = listOf("OpenPlain"))
        )
        assertEquals(240, total, "Round 2 unowned Territory play should include the +100 early-round boost")
    }

    @Test
    fun earlyRoundBoost_round3_applies60ForUnownedTerritory()
    {
        WorldManager.world = World(
            roundNumber = 3,
            mapTiles = mutableListOf(Territory(name = "OpenPlain", ruler = "Unowned"))
        )
        val total = runResearchPlay(
            ActionTargetTypeObj(type = ActionTargetType.Territory, targets = listOf("OpenPlain"))
        )
        assertEquals(200, total, "Round 3 unowned Territory play should include the +60 early-round boost")
    }

    @Test
    fun earlyRoundBoost_round4_appliesNoBoost()
    {
        WorldManager.world = World(
            roundNumber = 4,
            mapTiles = mutableListOf(Territory(name = "OpenPlain", ruler = "Unowned"))
        )
        val total = runResearchPlay(
            ActionTargetTypeObj(type = ActionTargetType.Territory, targets = listOf("OpenPlain"))
        )
        assertEquals(140, total, "Round 4 falls outside the early-round window; no boost should apply")
    }

    @Test
    fun earlyRoundBoost_round1_doesNotApplyForPlayerTargetType()
    {
        WorldManager.world = World(roundNumber = 1)
        val total = runResearchPlay(ActionTargetTypeObj(type = ActionTargetType.Player))
        assertEquals(140, total, "Player-type actions are excluded from the early-round boost")
    }

    @Test
    fun earlyRoundBoost_round1_doesNotApplyForRivalOwnedTerritory()
    {
        WorldManager.world = World(
            roundNumber = 1,
            mapTiles = mutableListOf(Territory(name = "RivalHold", ruler = "RivalPlayer"))
        )
        val total = runResearchPlay(
            ActionTargetTypeObj(type = ActionTargetType.Territory, targets = listOf("RivalHold"))
        )
        assertEquals(140, total, "Rival-held targets are excluded from the early-round boost")
    }
}