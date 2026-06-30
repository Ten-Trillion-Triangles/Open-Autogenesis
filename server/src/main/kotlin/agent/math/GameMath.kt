package agent.math

import agent.builders.validateAction.PlayType
import agent.builders.judgeOutcome.AgentAssessmentLevel
import structs.Player
import structs.Npc
import kotlin.random.Random
import agent.builders.validateAction.ActionTargetType
import agent.builders.validateAction.ActionTargetTypeObj
import enums.CommanderType
import enums.CommanderTrait
import enums.TerritoryType
import enums.ObstacleType
import gameState.WorldManager
import structs.Border
import structs.Territory
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

/**
 * Result object containing the mathematical outcome of a turn.
 *
 * @param baseScore Derived from the play type formula before any bonuses.
 * @param totalScore Sum of base, favor, momentum, asset bonuses, and any Overton window bonus.
 * @param statVictory True when the raw math (totalScore) sees the play as a win.
 * @param narrativeVictory Result pulled from the narrative assessment (`Pass/Fail` agent).
 * @param narrativeOverrideChance Percent chance that the narrative result survives a stat mismatch.
 * @param didFlip True if [finalSuccess] differs from [statVictory], used by [determineOutcomeGuidance] to decide hardened/softened messaging.
 * @param finalSuccess The canonical outcome (win/loss) that will be recorded in history and broadcast.
 * @param guidance Message that encapsulates the harden/soften interpretation of the final state.
 */
data class MathOutcome(
    val baseScore: Int,
    val totalScore: Int, // Base + Favor + Momentum (+/-) + Assets + Overton bonus
    val statVictory: Boolean,
    val narrativeVictory: Boolean,
    val narrativeOverrideChance: Int,
    val didFlip: Boolean,
    val finalSuccess: Boolean, // The ultimate result (Win/Loss)
    val guidance: String // "Major Victory", "Catastrophic Failure", etc.
)

/**
 * Deterministic-versus-narrative resolution for NPC actions that directly impact players.
 *
 * This mirrors the player fairness model, but compares NPC pressure against defender pressure
 * instead of reusing player-only formulas.
 *
 * @property npcPressure Offensive pressure calculated from NPC decay stats and point value.
 * @property defenderPressure Defensive pressure aggregated from impacted player defenders.
 * @property totalScore Final deterministic score before narrative override reconciliation.
 * @property statVictory Whether deterministic pressure declares NPC success.
 * @property narrativeVictory Whether narrative simulation declared NPC success.
 * @property narrativeOverrideChance Percent chance that narrative may override deterministic score.
 * @property didFlip True when [finalSuccess] differs from [statVictory].
 * @property finalSuccess Canonical success/failure used by orchestrators for result broadcasting.
 * @property guidance Harden/soften guidance used by downstream narrative reconciliation.
 */
data class NpcConflictMathOutcome(
    val npcPressure: Int,
    val defenderPressure: Int,
    val totalScore: Int,
    val statVictory: Boolean,
    val narrativeVictory: Boolean,
    val narrativeOverrideChance: Int,
    val didFlip: Boolean,
    val finalSuccess: Boolean,
    val guidance: String
)

/**
 * Internal tuple used while deciding if the narrative gets to override the stat verdict.
 */
private data class NarrativeOverrideResult(
    val finalSuccess: Boolean,
    val narrativeOverrideChance: Int,
)

/**
 * Core component that handles the deterministic and probabilistic math for resolving player actions.
 *
 * Formulas are derived from the Autogenesis Master Design.
 *
 * Early-round stat boost schedule (Territory actions only, no rival-held targets):
 * - Round 1: +140
 * - Round 2: +100
 * - Round 3: +60
 * - Round 4+: no boost
 */
private val EARLY_ROUND_BOOSTS: Map<Int, Int> = mapOf(1 to 140, 2 to 100, 3 to 60)
private const val NARRATIVE_MOMENTUM = 40

object GameMath {


    /**
     * Calculates the Base Score of an action using specific formulas per play type.
     * Formulas derived from Master Design "Base Stat Calculation".
     *
     * Military: Type + Trait - (100 - Readiness) + Resources
     * Diplomatic: Trait - (100 - Legitimacy) + Resources
     * Research: 100 - Stagnation + Resources
     *
     * @param player The player performing the action.
     * @param playType The type of play (Military, Diplo, etc).
     * @param targetType The target of the action (Player, Territory, etc).
     * @return The calculated base score.
     */
    fun calculateBaseScore(player: Player, playType: PlayType, targetType: ActionTargetTypeObj): Int
    {
        var score = 0
        
        // Resource Boosts (applied to all, but calculated as part of the specific formula block for clarity)
        val resourceBoost = when(playType)
        {
            PlayType.Military -> (player.might)
            PlayType.Research -> (player.wealth)
            PlayType.Diplomatic -> (player.reputation)
            else -> 0
        }

        // Identify unowned territories targeted to apply multi-target debuffs
        var unownedTargetsCount = 0
        if (targetType.type == ActionTargetType.Territory && targetType.targets.isNotEmpty()) {
            val normalizedPlayerName = player.name.trim()
            val world = WorldManager.world
            for (targetName in targetType.targets) {
                val territory = world.mapTiles.firstOrNull { it.name.equals(targetName, ignoreCase = true) }
                if (territory != null) {
                    val ruler = territory.ruler.trim()
                    if (!ruler.equals(normalizedPlayerName, ignoreCase = true)) {
                        unownedTargetsCount++
                    }
                }
            }
        }
        val extraTerritories = (unownedTargetsCount - 1).coerceAtLeast(0)

        Logger.debug(
            LogCategory.GENERAL,
            "GameMath.calculateBaseScore: player=${player.name}, playType=$playType, targetType=${targetType.type}, resourceBoost=$resourceBoost, extraTerritories=$extraTerritories"
        )

        // 1. Calculate Score based on specific Play Type Formula
        when(playType)
        {
            PlayType.Military ->
            {
                // Formula: Type + Trait - Readiness + Resource boosts
                // Readiness is a "Good" stat (0-100), but acts as a debuff as it decays.
                // Penalty = 100 - Readiness.
                // Score = TypeMod + TraitMod - Penalty + Resources.
                
                val typeMod = calculateTypeBonus(player, targetType)
                
                var traitMod = 0
                if(player.trait == CommanderTrait.Warlord) traitMod += 20
                if(player.trait == CommanderTrait.Diplomatic) traitMod -= 20
                // Researcher: Mild debuff? Spec says "Mildly debuffed". Let's say -10.
                if(player.trait == CommanderTrait.Researcher) traitMod -= 10
                
                // Decay
                val penalty = (100 - player.militaryReadiness).coerceAtLeast(0)
                
                score = typeMod + traitMod - penalty + resourceBoost

                // Multi-target debuff: 50% reduction for each territory beyond the first (unowned only)
                if (extraTerritories > 0) {
                    var multiplier = 1.0
                    repeat(extraTerritories) { multiplier *= 0.5 }
                    score = (score * multiplier).toInt()
                }

                Logger.debug(
                    LogCategory.GENERAL,
                    "GameMath.calculateBaseScore[MILITARY]: player=${player.name}, typeMod=$typeMod, traitMod=$traitMod, penalty=$penalty, resourceBoost=$resourceBoost, extraTerritories=$extraTerritories, score=$score"
                )
            }
            
            PlayType.Diplomatic ->
            {
                // Formula: Trait - Legitimacy + Resource boosts
                // Note: "Type" is NOT in the formula for Diplo.
                
                var traitMod = 0
                if(player.trait == CommanderTrait.Diplomatic) traitMod += 20
                if(player.trait == CommanderTrait.Warlord) traitMod -= 20
                if(player.trait == CommanderTrait.Researcher) traitMod -= 10
                
                // Decay
                val penalty = (100 - player.legitimacy).coerceAtLeast(0)
                
                score = traitMod - penalty + resourceBoost

                // Multi-target debuff: 25% reduction for each territory beyond the first (unowned only)
                if (extraTerritories > 0) {
                    var multiplier = 1.0
                    repeat(extraTerritories) { multiplier *= 0.75 }
                    score = (score * multiplier).toInt()
                }

                Logger.debug(
                    LogCategory.GENERAL,
                    "GameMath.calculateBaseScore[DIPLOMATIC]: player=${player.name}, traitMod=$traitMod, penalty=$penalty, resourceBoost=$resourceBoost, extraTerritories=$extraTerritories, score=$score"
                )
            }
            
            PlayType.Research ->
            {
                // Formula: 100 - Stagnation + Resource boosts
                // "100" is the base value.
                // Stagnation is a "Bad" stat (0-100). 
                // If Stagnation is 0 (Good), score is 100.
                
                // Trait impact? Spec says "Researcher... majorly buffed".
                // But formula says "100 - Stagnation...".
                // Spec text: "Researcher... majorly buffed when taking any research action".
                // Maybe the "100" base *is* the buff? Or Stagnation decay is 0 for them.
                // "Researchers decay at 0 points per turn." -> So Stagnation stays 0.
                
                score = 100 - player.stagnation + resourceBoost
                
                // Explicit Trait Buff if not covered by Stagnation mechanics?
                // Spec says "high luck stat when doing so".
                // Base formula doesn't list Trait Mod.
                // However, let's stick to the explicit "Research Plays" formula provided.
                Logger.debug(
                    LogCategory.GENERAL,
                    "GameMath.calculateBaseScore[RESEARCH]: player=${player.name}, base=100, stagnation=${player.stagnation}, resourceBoost=$resourceBoost, score=$score"
                )
            }
            
            else ->
            {
                // Default fallback
                score = 0
                Logger.debug(
                    LogCategory.GENERAL,
                    "GameMath.calculateBaseScore[DEFAULT]: player=${player.name}, playType=$playType, score=$score"
                )
            }
        }

        return score
    }

    /**
     * Calculates the Total Score by adding the Agent's Favor.
     *
     * @param baseScore The calculated base score.
     * @param assessment The assessment object containing Favor (-100 to 100).
     * @return The total score including favor points.
     */
    fun calculateTotalScore(baseScore: Int, assessment: AgentAssessmentLevel): Int
    {
        return baseScore + assessment.favorPoints
    }

    /**
     * Computes the override percentage that lets an unfavorable narrative survive stat logic.
     *
     * The user-specified behavior is “risk minus luck,” so higher luck shrinks the chance,
     * while high-risk plays have more opportunity to keep their story even when stats disagree.
     */
    private fun calculateNarrativeOverrideChance(risk: Int, playerLuck: Int): Int
    {
        return (risk - playerLuck).coerceIn(0, 100)
    }

    /**
     * Performs a single roll to decide whether the narrative outcome survives or whether the stats win.
     *
     * When the narrative already matches the stat result or the override chance is zero, no roll occurs.
     * Otherwise it compares a random 0-99 value against [narrativeOverrideChance] so high luck (low chance) resists
     * the narrative and low luck (high chance) lets the story persist.
     */
    private fun applyNarrativeOverride(
        statVictory: Boolean,
        narrativeVictory: Boolean,
        narrativeOverrideChance: Int,
        rng: Random
    ): NarrativeOverrideResult
    {
        if (statVictory == narrativeVictory || narrativeOverrideChance <= 0)
        {
            Logger.debug(
                LogCategory.GENERAL,
                "GameMath.applyNarrativeOverride: no roll needed (statVictory=$statVictory, narrativeVictory=$narrativeVictory, overrideChance=$narrativeOverrideChance)"
            )
            return NarrativeOverrideResult(statVictory, narrativeOverrideChance)
        }

        val roll = rng.nextInt(0, 100)
        val finalSuccess = if (roll < narrativeOverrideChance) narrativeVictory else statVictory
        Logger.debug(
            LogCategory.GENERAL,
            "GameMath.applyNarrativeOverride: roll=$roll, overrideChance=$narrativeOverrideChance, statVictory=$statVictory, narrativeVictory=$narrativeVictory, finalSuccess=$finalSuccess"
        )
        return NarrativeOverrideResult(finalSuccess, narrativeOverrideChance)
    }

    /**
     * Generates the final Outcome Guidance string based on the score and flip result.
     * Implements Harden/Soften logic.
     *
     * @param totalScore The total calculated score.
     * @param didFlip Whether the luck flip occurred.
     * @param assessment The agent assessment containing favor points.
     * @return Guidance string for the outcome.
     */
    fun determineOutcomeGuidance(totalScore: Int, didFlip: Boolean, assessment: AgentAssessmentLevel): String
    {
        val initiallyWinning = totalScore > 0
        // Calculate final result
        val finalWin = if (initiallyWinning) !didFlip else didFlip
        
        val favor = assessment.favorPoints
        val isFavored = favor > 0
        
        // Logic check:
        // Favored + Win = Hardened Victory (Decisive)
        // Favored + Lose = Softened Defeat (Bad Luck/Near Miss)
        // Unfavored + Lose = Hardened Defeat (As Expected/Crushed)
        // Unfavored + Win = Softened Victory (Eked Out/Lucky)
        
        return when {
            finalWin && isFavored -> "Guidance: HARDENED VICTORY. The player was favored and succeeded. Result should be a decisive, major win."
            finalWin && !isFavored -> "Guidance: SOFTENED VICTORY. The player was unfavored but managed to eke out a win. Victory is costly or minor." 
            !finalWin && isFavored -> "Guidance: SOFTENED DEFEAT. The player was favored but failed (Bad Luck). Result is a minor setback or near-miss, not a disaster."
            !finalWin && !isFavored -> "Guidance: HARDENED DEFEAT. The player was unfavored and failed. Result is a catastrophic loss or disastrous failure."
            else -> "Guidance: Standard Outcome."
        }
    }
    
    /**
     * Entry point to run the full calculation.
     * 
     * @param player The player performing the action.
     * @param playType The type of play being performed.
     * @param targetType The target of the action.
     * @param assessment The agent assessment of the action.
     * @param isSimulatedSuccess Result from the Pass/Fail agent analyzing the narrative. 
     *                           True = +${NARRATIVE_MOMENTUM} Momentum, False = -${NARRATIVE_MOMENTUM} Momentum.
     * @param usedAssets List of assets used in the action.
     * @return The complete math outcome.
     */
    /**
     * Resolves the full player action math, including the narrative override logic and the requested bonuses.
     *
     * @param player Actor whose stats and luck determine the base score and override resistance.
     * @param playType Calculated via [buildPlayDetectionAgent]; selects the appropriate base formula in [calculateBaseScore].
     * @param targetType Detector output used by [calculateTypeBonus].
     * @param assessment Geopolitical assessor output (favor, risk, Overton window flag).
     * @param isSimulatedSuccess Narrative success from [buildPassFailAgent]; contributes ±${NARRATIVE_MOMENTUM} momentum.
     * @param usedAssets Assets reported by [buildResourceUsageDetectorAgent]; five-point bonus each, capped at 25.
     * @param rng Source of randomness for the override roll, injected for determinism in tests.
     * @return Comprehensive [MathOutcome] describing the observed stat result, narrative result, override chance, and final verdict.
     */
    fun resolveAction(
        player: Player, 
        playType: PlayType, 
        targetType: ActionTargetTypeObj, 
        assessment: AgentAssessmentLevel,
        isSimulatedSuccess: Boolean,
        usedAssets: List<String>, // New Parameter
        rng: Random = Random.Default
    ): MathOutcome
    {
        Logger.info(
            LogCategory.GENERAL,
            "GameMath.resolveAction: start player=${player.name}, playType=$playType, targetType=${targetType.type}, targets=${targetType.targets.joinToString(", ")}, favor=${assessment.favorPoints}, risk=${assessment.riskLevel}, overtonConventional=${assessment.isConventionalForOvertonWindow}, narrativeSuccess=$isSimulatedSuccess, usedAssets=${usedAssets.size}"
        )
        val baseScore = calculateBaseScore(player, playType, targetType)
        
        val momentum = if(isSimulatedSuccess) NARRATIVE_MOMENTUM else -NARRATIVE_MOMENTUM
        
        val rawAssetBonus = usedAssets.size * 5
        val assetBonus = rawAssetBonus.coerceAtMost(25)

        val overtonBonus = if(!assessment.isConventionalForOvertonWindow) 20 else 0
        
        val earlyRoundBoost = earlyRoundBoostAmount(player, targetType)
        val totalScore = calculateTotalScore(baseScore, assessment) + momentum + assetBonus + overtonBonus + earlyRoundBoost
        
        val statVictory = totalScore > 0
        val narrativeVictory = isSimulatedSuccess
        val narrativeOverrideChance = calculateNarrativeOverrideChance(assessment.riskLevel, player.luckPoints)
        val overrideResult = applyNarrativeOverride(statVictory, narrativeVictory, narrativeOverrideChance, rng)

        val finalSuccess = overrideResult.finalSuccess
        val didFlip = finalSuccess != statVictory
        val guidance = determineOutcomeGuidance(totalScore, didFlip, assessment)
        Logger.info(
            LogCategory.GENERAL,
            "GameMath.resolveAction: breakdown player=${player.name}, baseScore=$baseScore, favor=${assessment.favorPoints}, momentum=$momentum, rawAssetBonus=$rawAssetBonus, assetBonus=$assetBonus, overtonBonus=$overtonBonus, earlyRoundBoost=$earlyRoundBoost, totalScore=$totalScore, statVictory=$statVictory"
        )
        Logger.info(
            LogCategory.GENERAL,
            "GameMath.resolveAction: verdict player=${player.name}, narrativeVictory=$narrativeVictory, narrativeOverrideChance=$narrativeOverrideChance, didFlip=$didFlip, finalSuccess=$finalSuccess, guidance=$guidance"
        )
        
        return MathOutcome(
            baseScore = baseScore,
            totalScore = totalScore,
            statVictory = statVictory,
            narrativeVictory = narrativeVictory,
            narrativeOverrideChance = narrativeOverrideChance,
            didFlip = didFlip,
            finalSuccess = finalSuccess,
            guidance = guidance
        )
    }

    /**
     * Calculates the Type Bonus based on Commander Type vs Territory Type and Obstacles.
     *
     * @param player The player performing the action.
     * @param targetTypeObj The target object containing target names and type.
     * @return The calculated type bonus.
     */
    fun calculateTypeBonus(player: Player, targetTypeObj: ActionTargetTypeObj): Int
    {
        // Only relevant to have a bonus/malus if we are targeting a territory
        if (targetTypeObj.type != ActionTargetType.Territory || targetTypeObj.targets.isEmpty())
        {
            Logger.debug(
                LogCategory.GENERAL,
                "GameMath.calculateTypeBonus: no territory bonus (player=${player.name}, targetType=${targetTypeObj.type}, targets=${targetTypeObj.targets.size})"
            )
            return 0
        }

        val world = WorldManager.world
        
        // Check first target only (players can only attack one territory at a time)
        val firstTargetName = targetTypeObj.targets.first()
        val targetTerritory = world.mapTiles.firstOrNull { it.name.equals(firstTargetName, ignoreCase = true) }
            ?: run {
                Logger.warn(
                    LogCategory.GENERAL,
                    "GameMath.calculateTypeBonus: target territory '$firstTargetName' not found for player=${player.name}"
                )
                return 0
            }

        // Check if target is adjacent to player-owned territory
        if (!isAdjacentToPlayerTerritory(targetTerritory, player))
        {
            // Non-adjacent attack: Use long-range campaign modifier
            // Find closest player-owned territory to target
            val playerTerritories = mutableListOf<Territory>().apply {
                add(player.startingTile)
                addAll(player.capturedTerritory)
            }
            
            val closestOwned = playerTerritories.minByOrNull { owned ->
                world.getTerritoryDistance(owned, targetTerritory).distance
            } ?: return 0
            
            val distance = world.getTerritoryDistance(closestOwned, targetTerritory).distance
            val modifier = world.calculateLongRangeModifier(player, closestOwned, targetTerritory)
            Logger.debug(
                LogCategory.GENERAL,
                "GameMath.calculateTypeBonus: long-range path player=${player.name}, target=${targetTerritory.name}, closestOwned=${closestOwned.name}, distance=$distance, modifier=$modifier"
            )
            return modifier
        }

        // Adjacent attack: Use existing obstacle-based logic
        var totalTypeScore = 0
        var validTargets = 0

        for (targetName in targetTypeObj.targets)
        {
            val territory = world.mapTiles.firstOrNull { it.name.equals(targetName, ignoreCase = true) } ?: continue
            validTargets++

            // Identify Obstacles from valid attack vectors (neighbors owned by player)
            val allNeighbors = getAllBorders(territory)
            
            // Find borders that connect to a territory owned by the player
            // NOTE: We check if the ADJACENT territory is owned by the player.
            // Current territory -> Border -> Adjacent Territory
            // If Adjacent Territory is owned by player, then this Border is an attack vector.
            val ownedNames = (player.capturedTerritory.map { it.name } + player.startingTile.name).toSet()
            val validBorders = allNeighbors.filter { border ->
                 val adjName = border.adjacentTerritory?.name
                 adjName != null && ownedNames.contains(adjName)
            }

            // Determine if we have paths with/without obstacles
            val hasWaterObstacle = validBorders.any { it.obstacleType == ObstacleType.River || it.obstacleType == ObstacleType.Ocean }
            val hasMountainObstacle = validBorders.any { it.obstacleType == ObstacleType.Mountain }
            val hasAnyObstacle = hasWaterObstacle || hasMountainObstacle
            val hasNoObstaclePath = validBorders.any { it.obstacleType == null } || validBorders.isEmpty() 
            // NOTE: If validBorders is empty (no adjacency), we treat it as "No Obstacle" (e.g. drop/remote attack) as per plan.

            var score = 0
            val tType = territory.type

            when(player.commanderType)
            {
                CommanderType.Land -> {
                    // Land Commanders: -20 vs ANY Obstacle involved in the attack
                    // If ANY obstacle must be crossed? Or if logic chooses best path?
                    // Logic: Player chooses best path. 
                    // Best Path Hierarchy for Land:
                    // 1. No Obstacle Path -> Apply Terrain Bonus (+20 Land / 0 Coast / -20 Water/Island)
                    // 2. Obstacle Path -> -20 Penalty (Always -20 regardless of terrain? Yes "hit them with -20")
                    
if (hasNoObstaclePath) {
                         score = when(tType) {
                             TerritoryType.Land -> 20
                             TerritoryType.Coastline -> 0
                             TerritoryType.Island, TerritoryType.Underwater -> -20
                             TerritoryType.Desert, TerritoryType.Void -> world.getTerrainTypeModifier(player.commanderType, tType)
                         }
                    } else {
                        // Forced to cross obstacle
                        score = -20
                    }
                }
                
                CommanderType.Aquatic -> {
                    // Aquatic Commanders:
                    // 1. Water Obstacle Path -> +20
                    // 2. No Obstacle Path -> Terrain check (+20 Water/Coast/Island, -20 Land)
                    // 3. Mountain Obstacle Path -> -20
                    
                    if (hasWaterObstacle) {
                        score = 20
} else if (hasNoObstaclePath) {
                         score = when(tType) {
                             TerritoryType.Coastline, TerritoryType.Island, TerritoryType.Underwater -> 20
                             TerritoryType.Land -> -20
                             TerritoryType.Desert, TerritoryType.Void -> world.getTerrainTypeModifier(player.commanderType, tType)
                         }
                    } else {
                        // Only Mountain left
                        score = -20
                    }
                }
                
                CommanderType.Flying -> {
                    // Flying Commanders:
                    // 1. Target Underwater -> -20 (Hard Rule)
                    // 2. Obstacle Path -> +20
                    // 3. No Obstacle Path -> Terrain check (-20 Land, 0 Coast, +20 Island)
                    
                   if (tType == TerritoryType.Underwater) {
                       score = -20
                   } else if (hasAnyObstacle) {
                       score = 20
} else {
                        // No Obstacle and Not Underwater
                        score = when(tType) {
                            TerritoryType.Land -> -20
                            TerritoryType.Coastline -> 0
                            TerritoryType.Island -> 20
                            TerritoryType.Desert, TerritoryType.Void -> world.getTerrainTypeModifier(player.commanderType, tType)
                            TerritoryType.Underwater -> 0
                        }
                    }
                }
            }
            
            Logger.debug(
                LogCategory.GENERAL,
                "GameMath.calculateTypeBonus: adjacent target=${territory.name}, commanderType=${player.commanderType}, terrain=${territory.type}, validBorders=${validBorders.size}, waterObstacle=$hasWaterObstacle, mountainObstacle=$hasMountainObstacle, score=$score"
            )
            totalTypeScore += score
        }

        val averagedScore = if (validTargets > 0) totalTypeScore / validTargets else 0
        Logger.debug(
            LogCategory.GENERAL,
            "GameMath.calculateTypeBonus: averagedScore=$averagedScore from totalTypeScore=$totalTypeScore across validTargets=$validTargets for player=${player.name}"
        )
        return averagedScore
    }

    private fun earlyRoundBoostAmount(player: Player, targetType: ActionTargetTypeObj): Int
    {
        val round = WorldManager.world.roundNumber
        val boost = EARLY_ROUND_BOOSTS[round]
        if (boost == null)
        {
            Logger.debug(
                LogCategory.GENERAL,
                "GameMath.earlyRoundBoostAmount: 0 (round=$round not in early-round window)"
            )
            return 0
        }

        if (targetType.type != ActionTargetType.Territory)
        {
            Logger.debug(
                LogCategory.GENERAL,
                "GameMath.earlyRoundBoostAmount: 0 (targetType=${targetType.type})"
            )
            return 0
        }

        val normalizedPlayerName = player.name.trim()
        val world = WorldManager.world

        targetType.targets.forEach { targetName ->
            val territory = world.mapTiles.firstOrNull { it.name.equals(targetName, ignoreCase = true) } ?: return@forEach
            val ruler = territory.ruler.trim()
            if (ruler.isNotEmpty() && !ruler.equals(normalizedPlayerName, ignoreCase = true) && !ruler.equals("Unowned", ignoreCase = true))
            {
                Logger.debug(
                    LogCategory.GENERAL,
                    "GameMath.earlyRoundBoostAmount: 0 (target=${territory.name}, ruler='$ruler', player=${player.name})"
                )
                return 0
            }
        }

        Logger.debug(
            LogCategory.GENERAL,
            "GameMath.earlyRoundBoostAmount: $boost (player=${player.name}, round=$round, targets=${targetType.targets.joinToString(", ")})"
        )
        return boost
    }

    /**
     * Checks if a territory is adjacent to any territory owned by the player.
     *
     * @param territory The territory to check
     * @param player The player whose owned territories to check against
     * @return True if territory is adjacent to any player-owned territory
     */
    private fun isAdjacentToPlayerTerritory(territory: Territory, player: Player): Boolean
    {
        val ownedNames = mutableSetOf<String>().apply {
            add(player.startingTile.name)
            addAll(player.capturedTerritory.map { it.name })
        }

        val allNeighbors = getAllBorders(territory)
        return allNeighbors.any { border ->
            val adjName = border.adjacentTerritory?.name
            adjName != null && ownedNames.contains(adjName)
        }
    }

    private fun getAllBorders(t: Territory): List<Border> {
        val list = mutableListOf<Border>()
        list.addAll(t.northBorders)
        list.addAll(t.southBorders)
        list.addAll(t.eastBorders)
        list.addAll(t.westBorders)
        list.addAll(t.northEastBorders)
        list.addAll(t.northWestBorders)
        list.addAll(t.southEastBorders)
        list.addAll(t.southWestBorders)
        return list
    }

    /**
     * Resolves an action specifically for an NPC actor.
     * NPCs have simplified stats and different scaling rules.
     *
     * @param npc The NPC performing the action.
     * @param playType The type of play being performed.
     * @param targetType The target of the action.
     * @param assessment The agent assessment of the action.
     * @param isSimulatedSuccess Whether the narrative simulation was successful.
     * @return The complete math outcome for the NPC.
     */
    /**
     * Runs the NPC variant of the resolution pipeline; NPCs still emit narrative momentum and risk-based overrides
     * but use a flattened luck bonus and simplified scoring.
     *
     * @param npc Acting NPC who provides the base score (pointValue * 2) and the luck substitute.
     * @param playType Ignored for NPC scoring but maintained for consistent logging/context.
     * @param targetType Passed along for parity with [resolveAction], even though NPCs do not leverage type bonuses.
     * @param assessment Same structure as for players; risk still drives the override roll.
     * @param isSimulatedSuccess Narrative success flag for the NPC play.
     * @param rng Random source for the override roll (kept injectable for tests).
     */
    fun resolveNpcAction(
        npc: Npc,
        playType: PlayType,
        targetType: ActionTargetTypeObj,
        assessment: AgentAssessmentLevel,
        isSimulatedSuccess: Boolean,
        rng: Random = Random.Default
    ): MathOutcome
    {
        Logger.info(
            LogCategory.GENERAL,
            "GameMath.resolveNpcAction: start npc=${npc.name}, playType=$playType, targetType=${targetType.type}, targets=${targetType.targets.joinToString(", ")}, pointValue=${npc.pointValue}, narrativeSuccess=$isSimulatedSuccess"
        )
        val baseScore = npc.pointValue * 2
        
        val momentum = if(isSimulatedSuccess) NARRATIVE_MOMENTUM else -NARRATIVE_MOMENTUM
        
        val npcLuck = 20 
        
        val riskPenalty = (assessment.riskLevel - 30).coerceAtLeast(0)
        
        val overtonBonus = if(!assessment.isConventionalForOvertonWindow) 20 else 0
        
        val totalScore = baseScore + assessment.favorPoints + momentum - riskPenalty + overtonBonus
        
        val statVictory = totalScore > 0
        val narrativeVictory = isSimulatedSuccess
        val narrativeOverrideChance = calculateNarrativeOverrideChance(assessment.riskLevel, npcLuck)
        val overrideResult = applyNarrativeOverride(statVictory, narrativeVictory, narrativeOverrideChance, rng)

        val finalSuccess = overrideResult.finalSuccess
        val didFlip = finalSuccess != statVictory
        val guidance = determineOutcomeGuidance(totalScore, didFlip, assessment)
        Logger.info(
            LogCategory.GENERAL,
            "GameMath.resolveNpcAction: breakdown npc=${npc.name}, baseScore=$baseScore, favor=${assessment.favorPoints}, momentum=$momentum, riskPenalty=$riskPenalty, overtonBonus=$overtonBonus, totalScore=$totalScore, statVictory=$statVictory"
        )
        Logger.info(
            LogCategory.GENERAL,
            "GameMath.resolveNpcAction: verdict npc=${npc.name}, narrativeVictory=$narrativeVictory, narrativeOverrideChance=$narrativeOverrideChance, didFlip=$didFlip, finalSuccess=$finalSuccess, guidance=$guidance"
        )

        return MathOutcome(
            baseScore = baseScore,
            totalScore = totalScore,
            statVictory = statVictory,
            narrativeVictory = narrativeVictory,
            narrativeOverrideChance = narrativeOverrideChance,
            didFlip = didFlip,
            finalSuccess = finalSuccess,
            guidance = guidance
        )
    }

    /**
     * Resolves NPC actions that clash with one or more players using defender-aware fairness math.
     *
     * NPCs do not use the player economy model, but defender stats still influence whether an NPC action lands.
     * When no defenders are present, defender pressure is zero and the action resolves from NPC pressure alone.
     * The method keeps narrative override behavior consistent with [resolveAction] by using defender luck as the
     * resistance factor.
     *
     * @param npc NPC taking the action.
     * @param defenders Defending players directly impacted by the action.
     * @param playType Action category used to select pressure formulas.
     * @param isSimulatedSuccess Narrative success signal from the simulation layer.
     * @param usedAssets Optional asset usage list for bounded pressure bonus.
     * @param riskLevel Conflict risk used for narrative override probability.
     * @param rng Random source for deterministic testing.
     * @return Combined deterministic and narrative outcome for NPC-vs-player clashes.
     */
    fun resolveNpcVsPlayerConflict(
        npc: Npc,
        defenders: List<Player>,
        playType: PlayType,
        isSimulatedSuccess: Boolean,
        usedAssets: List<String> = emptyList(),
        riskLevel: Int = 50,
        rng: Random = Random.Default
    ): NpcConflictMathOutcome
    {
        Logger.info(
            LogCategory.GENERAL,
            "GameMath.resolveNpcVsPlayerConflict: start npc=${npc.name}, playType=$playType, defenders=${defenders.size}, narrativeSuccess=$isSimulatedSuccess, usedAssets=${usedAssets.size}, riskLevel=$riskLevel"
        )
        val npcPressure = when(playType)
        {
            PlayType.Military -> npc.militaryReadiness + (npc.pointValue * 3)
            PlayType.Diplomatic -> npc.legitimacy + (npc.pointValue * 2)
            PlayType.Research -> (100 - npc.stagnation).coerceAtLeast(0) + (npc.pointValue * 2)
            PlayType.Summit -> ((npc.militaryReadiness + npc.legitimacy) / 2) + (npc.pointValue * 2)
        }

        val defenderPressure = if(defenders.isEmpty())
        {
            0
        }
        else
        {
            when(playType)
            {
                PlayType.Military ->
                {
                    val readiness = defenders.map { it.militaryReadiness }.average().toInt()
                    val might = defenders.map { it.might }.average().toInt() / 2
                    readiness + might
                }
                PlayType.Diplomatic ->
                {
                    val legitimacy = defenders.map { it.legitimacy }.average().toInt()
                    val reputation = defenders.map { it.reputation }.average().toInt() / 2
                    legitimacy + reputation
                }
                PlayType.Research ->
                {
                    val antiStagnation = defenders.map { (100 - it.stagnation).coerceAtLeast(0) }.average().toInt()
                    val wealth = defenders.map { it.wealth }.average().toInt() / 2
                    antiStagnation + wealth
                }
                PlayType.Summit ->
                {
                    val authority = defenders.map { (it.legitimacy + it.militaryReadiness) / 2 }.average().toInt()
                    val reputation = defenders.map { it.reputation }.average().toInt() / 3
                    authority + reputation
                }
            }
        }

        val momentum = if(isSimulatedSuccess) NARRATIVE_MOMENTUM else -NARRATIVE_MOMENTUM
        val assetBonus = (usedAssets.size * 5).coerceAtMost(25)
        val totalScore = npcPressure - defenderPressure + momentum + assetBonus

        val statVictory = totalScore > 0
        val narrativeVictory = isSimulatedSuccess
        val defenderLuck = if(defenders.isEmpty()) 0 else defenders.map { it.luckPoints }.average().toInt()
        val narrativeOverrideChance = calculateNarrativeOverrideChance(riskLevel, defenderLuck)
        val overrideResult = applyNarrativeOverride(statVictory, narrativeVictory, narrativeOverrideChance, rng)
        val finalSuccess = overrideResult.finalSuccess
        val didFlip = finalSuccess != statVictory

        val syntheticAssessment = AgentAssessmentLevel(
            favorPoints = (npcPressure - defenderPressure).coerceIn(-100, 100),
            riskLevel = riskLevel
        )
        val guidance = determineOutcomeGuidance(totalScore, didFlip, syntheticAssessment)
        Logger.info(
            LogCategory.GENERAL,
            "GameMath.resolveNpcVsPlayerConflict: breakdown npc=${npc.name}, npcPressure=$npcPressure, defenderPressure=$defenderPressure, momentum=$momentum, assetBonus=$assetBonus, totalScore=$totalScore, statVictory=$statVictory"
        )
        Logger.info(
            LogCategory.GENERAL,
            "GameMath.resolveNpcVsPlayerConflict: verdict npc=${npc.name}, narrativeVictory=$narrativeVictory, narrativeOverrideChance=$narrativeOverrideChance, didFlip=$didFlip, finalSuccess=$finalSuccess, guidance=$guidance"
        )

        return NpcConflictMathOutcome(
            npcPressure = npcPressure,
            defenderPressure = defenderPressure,
            totalScore = totalScore,
            statVictory = statVictory,
            narrativeVictory = narrativeVictory,
            narrativeOverrideChance = narrativeOverrideChance,
            didFlip = didFlip,
            finalSuccess = finalSuccess,
            guidance = guidance
        )
    }
}
