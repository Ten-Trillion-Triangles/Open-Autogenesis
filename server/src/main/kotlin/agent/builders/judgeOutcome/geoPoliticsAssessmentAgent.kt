package agent.builders.judgeOutcome

import bedrockPipe.BedrockMultimodalPipe
import bedrockPipe.BedrockPriorityTier
import com.TTT.Context.ContextWindow
import com.TTT.Pipeline.Pipeline
import com.TTT.Util.extractJson
import com.TTT.Util.serialize
import gameState.WorldManager
import globals.BedrockConfig
import kotlinx.coroutines.sync.withLock
import serverStructs.PlayerAction
import serverStructs.TrueFalse
import structs.Player
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.logging.LogCategory

/**
 * Defines the total numeric assessment of the geopolitical agent's bet against the player, and the game narrative.
 * This result will directly affect player stats, which in turn may modify the outcome of the story regardless
 * of the result of the game narrative itself.
 */
@kotlinx.serialization.Serializable
data class AgentAssessmentLevel(
    var favorPoints: Int = 0,
    var riskLevel: Int = 0,
    var isConventionalForOvertonWindow: Boolean = true,
    var playTargetDefensePoints: Int = 0
)

@kotlinx.serialization.Serializable
data class GeopoliticalStoryInput(
    val storyTurns: List<String> = listOf()
)

@kotlinx.serialization.Serializable
data class ConflictLevelResponse(
    val conflictLevel: Int = 0
)

@kotlinx.serialization.Serializable
data class PlayerActionInput(
    val action: serverStructs.PlayerAction = serverStructs.PlayerAction()
)

/**
 * Intermediate data class to help format input for the next pipe that generates the stat numbers
 * of the assessment.
 */
@kotlinx.serialization.Serializable
data class AgentIntermediaryAssessmentContainer(
    var playerData: Player = Player(),
    var agentAssessment: String = ""
)

@kotlinx.serialization.Serializable
data class TerritoryThreatInfo(
    val name: String = "",
    val militaryLevel: Int = 0,
    val diplomacyLevel: Int = 0
)

/**
 * Calculates combined stats for an actor (wealth + might + reputation for Players, militaryReadiness + legitimacy for NPCs).
 *
 * Used when intent mismatch is detected (e.g., diplomatic action → military response).
 * Represents full escalation where all resources are brought to bear.
 *
 * @param actor The actor (Player or NPC) whose combined stats to calculate
 * @return Sum of relevant stats
 */
fun calculateCombinedStats(actor: interfaces.Actor): Int
{
    return when (actor) {
        is structs.Player -> actor.wealth + actor.might + actor.reputation
        is structs.Npc -> actor.militaryReadiness + actor.legitimacy
        else -> 0
    }
}

/**
 * Builds a comprehensive geopolitical assessment pipeline that evaluates player actions
 * against the current world state and overton window.
 *
 * @param player The player whose action is being assessed
 * @param actionIntent The intent of the action (Hostile or Friendly)
 * @param targetCount Number of targets affected by the action (for multi-target scaling)
 * @param intentMismatch Whether counter-response intents differ from original action intent
 * @return A [Pipeline] containing assessment, overton window analysis, normalcy checking,
 *         conflict level evaluation, written assessment, and numeric scoring pipes.
 */
fun buildAssessmentAgent(
    player: Player,
    actionIntent: String = "Hostile",
    targetCount: Int = 1,
    intentMismatch: Boolean = false
): Pipeline
{
    /**
     * First stage. Examine the previous 12 turns of the story and write a geopolitical essay assessing the state
     * of the world. This is treated like a sort of seed value both to define how well or poorly a play could go
     * and how that contrasts with the existing overton window.
     */
    val assessmentPipe = BedrockMultimodalPipe().apply {
        useConverseApi()
        setRegion("us-west-2")

        setModel(BedrockConfig.PalmyraX5)
        setTokenBudget(BedrockConfig.palmyraBudgetSettings)
        setTemperature(1.0)
        setTopP(0.9)
        allowEmptyUserPrompt()
        requireJsonPromptInjection()
        setJsonInput(GeopoliticalStoryInput::class)
        setPipeName("essay pipe")

        setSystemPrompt("""write an essay that fully covers the and assesses the geopolitical state of this game's world.
            | The essay must be written in the style of writing a political scholar would use.
            |  Do a full assessment of the geopolitical state of this game's world""".trimMargin())

        autoInjectContext("""You have been provided with additional context about the game world.
            |The "assessment" key is the previous geopolitical essay you wrote. If it exists, update it with any
            |new findings. If it does not exist, create it.
        """.trimMargin())

        setFooterPrompt(BedrockConfig.antiCensorship)

        /**
         * Cache user prompt with should be the turn play. Then replace user prompt with the previous 12 turns
         * of the game to form our next assessment window.
         */
        setPreInitFunction { it ->
            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: assessmentPipe.setPreInitFunction entry")
            // Save user prompt into the parent pipeline's mini bank to cache it and make it visible to all forward pipes.
            val parentPipe = it.currentPipe ?: throw Exception("No parent pipe found in assessmentPipe @geoPoliticsAssessmentAgent.kt")
            val parentPipeline = parentPipe.getPipelinesFromInterface().last()
            val newContextWindow = ContextWindow().apply {
                contextElements.add(it.text)
            }
            parentPipeline.miniBank.contextMap["player action"] = newContextWindow

            // Replace user prompt with the previous 3 rounds of the story.
            if(!WorldManager.hasUpdatedAssessmentThisRound)
            {
                val previousSet = WorldManager.getRecentHistory(3)
                val rawStoryContent = mutableListOf<String>()
                
                if(previousSet.isEmpty())
                {
                    // Fallback for Turn 1/Genesis to prevent empty context refusal
                    rawStoryContent.add("The world has just been formed. Nations are rising. The history is blank, waiting to be written.")
                }
                else
                {
                    previousSet.forEach {
                        rawStoryContent.add(it.turnStory)
                    }
                }

                // Write story into the user prompt space.
                val storyAsJson = serialize(GeopoliticalStoryInput(rawStoryContent))
                it.text = storyAsJson

                /**
                 * Construct assessment object that will be used for the final pipeline output and save it for
                 * future access later on in this pipeline.
                 */
                val assessmentObj = AgentAssessmentLevel()
                parentPipeline.pipeMetaData["assessment"] = assessmentObj
            }
            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: assessmentPipe.setPreInitFunction success")
        }

        /**
         * Load prior assessment in if it exists.
         */
        setPreValidationMiniBankFunction { context, content ->
            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: assessmentPipe.setPreValidationMiniBankFunction entry")
            val priorWorldState = WorldManager.geopoliticalAssessment
            val newContextWindow = ContextWindow().apply {
                contextElements.add(priorWorldState)
            }
            context.contextMap["assessment"] = newContextWindow

            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: assessmentPipe.setPreValidationMiniBankFunction success")
            return@setPreValidationMiniBankFunction context
        }

        /**
         * Always skip if the assessment has already been updated this round.
         * EXCEPTION: Force update on Turn 1 (history size == 0) to ensure we have initial data.
         */
        setPreInvokeFunction {
            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: assessmentPipe.setPreInvokeFunction entry")
            // Force assessment ONLY on Turn 1 (history is empty when this pipe executes)
            if (WorldManager.history.size == 0)
            {
                 Logger.info(LogCategory.LLM, "[GEOPOLITICS DEBUG] Forcing assessment update for Turn 1 / Genesis.")
                 Logger.debug(LogCategory.SYSTEM, "GeoPolitics: assessmentPipe.setPreInvokeFunction success (false - force update)")
                 return@setPreInvokeFunction false  // false = execute the pipe
            }

            if(WorldManager.hasUpdatedAssessmentThisRound)
            {
                Logger.info(LogCategory.LLM, "[GEOPOLITICS DEBUG] Skipping assessment: Already updated this round.")
                Logger.debug(LogCategory.SYSTEM, "GeoPolitics: assessmentPipe.setPreInvokeFunction success (true - skip)")
                return@setPreInvokeFunction true  // true = skip the pipe
            }

            Logger.info(LogCategory.LLM, "[GEOPOLITICS DEBUG] Proceeding with assessment update.")
            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: assessmentPipe.setPreInvokeFunction success (false - update)")
            return@setPreInvokeFunction false  // false = execute the pipe
        }

        /**
         * Update the assessment in the world manager. This is blind so in the unusual event of a refusal we would
         * have a broken world state. So in that case we should cache it for safety.
         */
        setTransformationFunction {
            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: assessmentPipe.setTransformationFunction entry")
            // Copy assessment into parent pipe for safety. If at the end we have evidence of a refusal we can restore it.
            val parentPipe = it.currentPipe ?: throw Exception("No parent pipe found in assessmentPipe @geoPoliticsAssessmentAgent.kt")
            val parentPipeline = parentPipe.getPipelinesFromInterface().last()
            val newContextWindow = ContextWindow().apply {
                contextElements.add(it.text)
            }
            parentPipeline.miniBank.contextMap["assessment"] = newContextWindow

            WorldManager.geopoliticalAssessment = it.text
            WorldManager.hasUpdatedAssessmentThisRound = true

            Logger.info(LogCategory.GENERAL, "[GEOPOLITICS DEBUG] Assessment Agent Updated WorldManager. Length: ${it.text.length}")
            Logger.info(LogCategory.GENERAL, "[GEOPOLITICS DEBUG] Current history.size: ${WorldManager.history.size}")
            Logger.info(LogCategory.GENERAL, "[GEOPOLITICS DEBUG] Current round: ${WorldManager.world.roundNumber}")
            Logger.info(LogCategory.GENERAL, "[GEOPOLITICS DEBUG] Agent Output Preview: ${it.text.take(100)}...")
            if(it.text.isBlank()) Logger.error(LogCategory.GENERAL, "[GEOPOLITICS DEBUG] Assessment Agent wrote BLANK text!")

            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: assessmentPipe.setTransformationFunction success")
            return@setTransformationFunction it
        }
    }

    /**
     * Pipe that scans and assess the state of the overton window. This is used to define normal and help us
     * score plays that are less conventional for game balance reasons.
     */
    val overtonWindowPipe = BedrockMultimodalPipe().apply {
        useConverseApi()
        setRegion("us-west-2")

        setModel(BedrockConfig.PalmyraX5)
        setTokenBudget(BedrockConfig.palmyraBudgetSettings)
        setTemperature(.8)
        setTopP(0.7)
        allowEmptyContentObject()
        allowEmptyUserPrompt()
        setPipeName("overton window pipe")

        setSystemPrompt("""You are a political analyst that examines the geopolitical state of a fictional world
            |and determines what the current overton window of that world is. You must write up an explanation of
            |the current overton window and provide examples of what you feel backs up that claim. 
            |You will be given an essay that explains the current geopolitcal state. 
            |Using it: you must determine what the overton window for this world currently looks like and why.
        """.trimMargin())

        setPreInitFunction {
            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: overtonWindowPipe.setPreInitFunction entry")
            if(it.text.isEmpty())
            {
                it.text = WorldManager.geopoliticalAssessment.ifBlank { "The geopolitical state is currently undefined." }
            }
            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: overtonWindowPipe.setPreInitFunction success")
        }

        /**
         * Only update the overton window if the geopolitical assessment was updated this round.
         */
        setPreInvokeFunction {
            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: overtonWindowPipe.setPreInvokeFunction entry")
            if(WorldManager.hasUpdatedAssessmentThisRound)
            {
                Logger.debug(LogCategory.SYSTEM, "GeoPolitics: overtonWindowPipe.setPreInvokeFunction success (true)")
                return@setPreInvokeFunction true
            }

            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: overtonWindowPipe.setPreInvokeFunction success (false)")
            return@setPreInvokeFunction false
        }

        setTransformationFunction {
            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: overtonWindowPipe.setTransformationFunction entry")
            WorldManager.overtonWindow = it.text
            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: overtonWindowPipe.setTransformationFunction success")
            return@setTransformationFunction it
        }

    }

    val conflictLevelPipe = BedrockMultimodalPipe().apply {
        useConverseApi()
        setRegion("us-west-2")

        setModel(BedrockConfig.PalmyraX5)
        setTokenBudget(BedrockConfig.palmyraBudgetSettings)
        setTemperature(.7)
        setTopP(.7)
        allowEmptyContentObject()
        allowEmptyUserPrompt()
        requireJsonPromptInjection()
        setJsonOutput(ConflictLevelResponse())
        setPipeName("conflict level pipe")

        setSystemPrompt("""You are a political analyst for a fictional game world. Your job is to evaluate 
            |the conflict level of the world by rating it between 0 and 100. 0 being peaceful and stable
            |and 100 being an apocalyptic and doomed war zone. You will be provided with a geopolitical report
            |explaining the current state the game world's geopolitics. You must use this to determine this value.
        """.trimMargin())

        setPreInitFunction {
            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: conflictLevelPipe.setPreInitFunction entry")
            val currentAssessment = WorldManager.geopoliticalAssessment
            if(currentAssessment.isBlank())
            {
                it.text = """
                    The geopolitical state is currently undefined. The world is in a neutral state of early development.
                    Conflict is minimal as nations are just beginning to form.
                """.trimIndent()
            }
            else
            {
                it.text = currentAssessment
            }
            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: conflictLevelPipe.setPreInitFunction success")
        }

        setPreInvokeFunction {
            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: conflictLevelPipe.setPreInvokeFunction entry")
            val res = WorldManager.hasUpdatedAssessmentThisRound
            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: conflictLevelPipe.setPreInvokeFunction success ($res)")
            return@setPreInvokeFunction res
        }

        setTransformationFunction {
            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: conflictLevelPipe.setTransformationFunction entry")
            val conflictLevel = extractJson<ConflictLevelResponse>(it.text)?.conflictLevel ?: 0
            WorldManager.worldMutex.withLock {
                WorldManager.world.conflictLevel = conflictLevel
            }
            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: conflictLevelPipe.setTransformationFunction success (level=$conflictLevel)")
            return@setTransformationFunction it
        }


    }

    /**
     * Scan for, and determine if the attempted play is conventional or unconventional.
     */
    val playNormalcyPipe = BedrockMultimodalPipe().apply {
        useConverseApi()
        setRegion("us-west-2")
        setRegion("us-west-2")

        setModel(BedrockConfig.qwen235B)
        setTemperature(.7)
        setTopP(.7)
        requireJsonPromptInjection()
        allowEmptyContentObject()
        allowEmptyUserPrompt()
        setJsonOutput(TrueFalse())
        setReasoningPipe(BedrockConfig.explicitCotBuilder())
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        setPipeName("play normalcy pipe")

        setSystemPrompt("""You are a political analyst and assessor the game Autogenesis. 
            |You must examine a given play as your user prompt. Then determine if the play is 
            |conventional in the confines of the game's overton window. 
        """.trimMargin())

        autoInjectContext("""You have been provided with the overton window as context. This
            |data contains a complete breakdown on what is considered normal in this game's geopolitical
            |landscape.
        """.trimMargin())

        setFooterPrompt("""Return true if the play is inside the overton window. Return false if the play
            |is unconventional in the framing of the game's overton window and it's geopolitics.
        """.trimMargin())

        /**
         * Fetch the original user prompt which is held inside our parent pipeline.
         */
        setPreInitFunction {
            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: playNormalcyPipe.setPreInitFunction entry")
            val parentPipe = it.currentPipe ?: throw Exception("No parent pipe found in playNormalcyPipe @geoPoliticsAssessmentAgent.kt")
            val parentPipeline = parentPipe.getPipelinesFromInterface().last()
            val userPrompt = parentPipeline.miniBank.contextMap["player action"]?.contextElements[0] ?: throw Exception("No user prompt found in playNormalcyPipe @geoPoliticsAssessmentAgent.kt")
            it.text = userPrompt
            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: playNormalcyPipe.setPreInitFunction success")
        }

        /**
         * Pull the overton window into context and load it into our visible context to the llm.
         */
        setPreValidationMiniBankFunction { context, content ->
            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: playNormalcyPipe.setPreValidationMiniBankFunction entry")
            val overtonWindow = WorldManager.overtonWindow
            val newContextWindow = ContextWindow().apply {
                contextElements.add(overtonWindow)
            }
            context.contextMap["overton window"] = newContextWindow

            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: playNormalcyPipe.setPreValidationMiniBankFunction success")
            return@setPreValidationMiniBankFunction context
        }

        /**
         * Double up as validator and transformation function. Any invalid response will just be treated
         * as outside the overton window which will be seen as a more powerful play than one inside it.
         */
        setValidatorFunction {
            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: playNormalcyPipe.setValidatorFunction entry")
            val response = extractJson<TrueFalse>(it.text) ?: TrueFalse().apply {
                isTrue = false
            }

            val asJson = serialize(response)
            it.text = asJson

            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: playNormalcyPipe.setValidatorFunction success")
            return@setValidatorFunction true
        }

        /**
         * Update our assessment as we go along.
         */
        setTransformationFunction {
            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: playNormalcyPipe.setTransformationFunction entry")
            val response = extractJson<TrueFalse>(it.text)
            val parentPipe = it.currentPipe ?: throw Exception("No parent pipe found in playNormalcyPipe")
            val parentPipeline = parentPipe.getPipelinesFromInterface().last()
            val assessmentObj = parentPipeline.pipeMetaData["assessment"] as AgentAssessmentLevel
            assessmentObj.isConventionalForOvertonWindow = response?.isTrue ?: false
            parentPipeline.pipeMetaData["assessment"] = assessmentObj

            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: playNormalcyPipe.setTransformationFunction success")
            return@setTransformationFunction it
        }

    }

    val writtenAssessmentPipe = BedrockMultimodalPipe().apply {
        useConverseApi()
        setRegion("us-west-2")
        setRegion("us-west-2")

        setModel(BedrockConfig.qwen235B)
        setTemperature(.7)
        setTopP(.7)
        allowEmptyContentObject()
        allowEmptyUserPrompt()
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        setReasoningPipe(BedrockConfig.explicitCotBuilder())
        requireJsonPromptInjection()
        setJsonInput(PlayerActionInput::class)
        setPipeName("written assessment pipe")

        setSystemPrompt("""You are a political analyst that assesses the viability of an action by a nation state
            |in a fictional game world. When making your evaluation you must:
            |
            |- Compare the instigating actor to the target actor. The instigating actor will be the player's commander
            |or nation state. And the target actor will be another nation state, player, npc, or abstract outcome
            |like policy, research, economic actions etc.
            |
            |- Determine how favorable the action is likely to be for the instigating actor. Compare the history
            |of the instigating actor, the available recent events in the game's story, other relevant game data
            |provided, and the provided essay that covers the current geopolitical state of the game's world.
            |
            |- Determine how risky the instigators action is likely to be.
            |
            |- Determine what gains and losses are on the line for each party. (Or only gains if the targeted party is
            |an abstract concept or inanimate object.)
            |
            |- Determine how prepared the target is for this action. This may not be relevant if the target is
            |abstract, the player, an inanimate object, or doesn't even exist in the action at all.
            |
            |## ACTION INTENT ##
            |Actions are classified as Hostile or Friendly:
            |
            |- **Hostile actions**: Attacks, invasions, sabotage, threats, coercion. These face full stat opposition from targets.
            |- **Friendly actions**: Gifts, alliances, aid, trade, cooperation. These do NOT face stat opposition - evaluate cooperation viability instead.
            |
            |For Friendly actions, assess whether the cooperation makes sense, whether targets would accept, and mutual benefits.
            |Do NOT penalize Friendly actions for target strength - only assess if the proposal is reasonable and beneficial.
            |
            |## INTENT MISMATCH ##
            |If intent_mismatch is true, counter-responses have different intent than the original action (e.g., diplomatic action → military response).
            |In this case, use COMBINED STATS (wealth + might + reputation) for both attacker and all defenders.
            |This represents full escalation where all resources are brought to bear.
            |
            |## TERRITORY THREAT LEVELS ##
            |When the action targets territories, consider their threat levels:
            |
            |- Level 1 (Nonthreatening): Will always succeed - minimal resistance
            |- Level 2 (Threatening): Will fight back, but hard to lose
            |- Level 3 (Very Threatening): Will fight back with reasonable chance of victory
            |- Level 4 (Big Warning): Victory possible but requires excellent stats and planning
            |- Level 5 (Oh Dear God No): Will invoke technology and magic that doesn't yet exist to crush you
            |
            |Military threat levels apply to military actions. Diplomacy threat levels apply to diplomatic actions.
            |
            |**CRITICAL**: The player must MATCH OR EXCEED the territory's threat level with their capabilities. Evaluate:
            |- Player's historical performance and accumulated stats/resources
            |- Quality and sophistication of the current plan
            |- Whether the player's power level meets or exceeds the territory's threat level
            |
            |A Level 5 territory requires Level 5 capabilities (excellent stats, brilliant plan, strong resources).
            |A Level 1 player attempting a Level 5 territory should receive heavily negative favor points.
            |Reduce favorability proportionally when player capability falls short of territory threat level.
            |
            |## CONTESTED ACTIONS ##
            |If the action includes DEFENDER COUNTER-RESPONSES sections, this is a contested action where other
            |players are actively opposing the attacker. In this case:
            |
            |- Evaluate the ATTACKER'S action quality and likelihood of success
            |- Evaluate each DEFENDER'S counter-response strength and effectiveness
            |- Consider resources, capabilities, and positioning of both sides
            |- Assess the NET OUTCOME: Will the attacker succeed despite opposition?
            |- Reduce favorability if defenders mount strong opposition
            |- Increase target defense points based on active counter-responses
            |- Account for multiple defenders (each adds more opposition)
            |
            |Write up your analysis in the same manner a political scholar would. Dry and clinical.
        """.trimMargin())

        autoInjectContext("""You have been provided with the following context to allow you to make
            |your assessment.
            |
            |world: This context key contains all of the game's world data. Players, territory, and other
            |game data is visible to examine.
            |
            |history: This context key contains up to 12 of the previous turns in the game. 
            |
            |assessment: This context key contains the current geopolitical assessment of the game's world.
            |
            |territory_threats: This context key contains threat level data for all territories. Each territory
            |has a militaryLevel (1-5) and diplomacyLevel (1-5) indicating how difficult it is to conquer or
            |diplomatically influence that territory.
            |
            |The world data and player data have game stats in them which are explained by the following: ${BedrockConfig.gameStatsDescription}
        """.trimMargin())

        setPreValidationFunction { context, content ->
            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: writtenAssessmentPipe.setPreValidationFunction entry")
            val parentPipe = content?.currentPipe ?: throw Exception("No parent pipe found in writtenAssessmentPipe @geoPoliticsAssessmentAgent.kt")
            val parentPipeline = parentPipe.getPipelinesFromInterface().last()
            val playerActionStr = parentPipeline.miniBank.contextMap["player action"]?.contextElements?.get(0) ?: ""
            
            val inputWrapper = PlayerActionInput(
                action = PlayerAction(
                    player = player,
                    actionDescription = playerActionStr
                )
            )
            
            content.text = serialize(inputWrapper)
            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: writtenAssessmentPipe.setPreValidationFunction success")
            return@setPreValidationFunction context
        }

        setPreValidationMiniBankFunction { context, content ->
            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: writtenAssessmentPipe.setPreValidationMiniBankFunction entry")
            val world = serialize(WorldManager.world)
            val history = WorldManager.getRecentHistory(3)
            val assessment = WorldManager.geopoliticalAssessment

            val worldContextWindow = ContextWindow().apply {
                contextElements.add(world)
            }

            val historyContextWindow = ContextWindow().apply {
               val asJson = serialize(history)
               contextElements.add(asJson)
            }

            val assessmentContextWindow = ContextWindow().apply {
                contextElements.add(assessment)
            }

            context.contextMap["world"] = worldContextWindow
            context.contextMap["history"] = historyContextWindow
            context.contextMap["assessment"] = assessmentContextWindow
            
            val territoryThreats = WorldManager.world.mapTiles.map { territory ->
                TerritoryThreatInfo(
                    name = territory.name,
                    militaryLevel = structs.statValueToThreatLevel(territory.militaryThreatStat),
                    diplomacyLevel = structs.statValueToThreatLevel(territory.diplomacyThreatStat)
                )
            }
            
            val threatContextWindow = ContextWindow().apply {
                val threatsJson = serialize(territoryThreats)
                contextElements.add(threatsJson)
            }
            
            context.contextMap["territory_threats"] = threatContextWindow

            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: writtenAssessmentPipe.setPreValidationMiniBankFunction success")
            return@setPreValidationMiniBankFunction context
        }
    }

    val numericScoringPipe = BedrockMultimodalPipe().apply {
        useConverseApi()
        setRegion("us-west-2")
        setRegion("us-west-2")

        setModel(BedrockConfig.qwen235B)
        setTemperature(.7)
        requireJsonPromptInjection()
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        setReasoningPipe(BedrockConfig.explicitCotBuilder())
        setJsonOutput(AgentAssessmentLevel())
        allowEmptyContentObject()
        allowEmptyUserPrompt()
        setPipeName("numeric scoring pipe")

        setSystemPrompt("""You are a numeric scoring pipe that evaluates a given assessment of an action
            |about to be taken in a game world. And translates that assessment to a numeric set of scores.
            |You must breakdown the assessment and translate each aspect of it into the following set of
            |score values:
            |
            |favor: A integer value that defines how favorable the assessment has stated the outcome will be for the player.
            |The base range is -100 to 100, but for multi-target actions the maximum range increases:
            |- 1 target: -100 to 100
            |- 2 targets: -100 to 150 (base 100 + 50 for additional target)
            |- 3 targets: -100 to 200 (base 100 + 100 for 2 additional targets)
            |- N targets: -100 to (100 + 50 * (N-1))
            |
            |You must carefully and correctly match this value to the explanation provided by the assessment in your user prompt.
            |
            |risk: A integer value between 0 and 50 that defines the risk level involved for the player. The assessment
            |will have stated this information and you must carefully evaluate it and correctly translate that value
            |to this number.
            |
            |playTargetDefensePoints: An integer value between 0 and 100 that defines how prepared the target is for this action. 
            |This may not be relevant if the target is abstract, the player, an inanimate object, or doesn't even exist in the action at all. 
            |If not relevant then default this value to 0.
            |
        """.trimMargin())

        autoInjectContext("""You have been provided with extra context data to inform you of who the player is
            |in the context of this assessment. This is very important for you to first determine who the player is, then translate
            |the assessment to the target numerical values stated prior.
        """.trimMargin())

        setPreValidationFunction { context, content ->
            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: numericScoringPipe.setPreValidationFunction entry")
            //Get player action from our user
            val parentPipe = content?.currentPipe ?: throw Exception("No parent pipe found in numericScoringPipe @geoPoliticsAssessmentAgent.kt")
            val parentPipeline = parentPipe.getPipelinesFromInterface().last()
            val playerAction = parentPipeline.miniBank.contextMap["player action"]
            val fromJson = playerAction?.contextElements[0] ?: ""

            //Write back into this pipe's context. Now we have the data exactly where we need it to be.
            context.contextElements.add(fromJson)

            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: numericScoringPipe.setPreValidationFunction success")
            return@setPreValidationFunction context
        }

        setValidatorFunction {
            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: numericScoringPipe.setValidatorFunction entry")
            val fromJson = extractJson<AgentAssessmentLevel>(it.text)
            if(fromJson == null)
            {
                Logger.error(LogCategory.SYSTEM, "GeoPolitics: numericScoringPipe.setValidatorFunction failed: result is null")
                throw Exception("No json found in numericScoringPipe @geoPoliticsAssessmentAgent.kt")
            }

            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: numericScoringPipe.setValidatorFunction success")
            return@setValidatorFunction true
        }

        setTransformationFunction {
            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: numericScoringPipe.setTransformationFunction entry")
            val parentPipe = it.currentPipe ?: throw Exception("No parent pipe found in numericScoringPipe @geoPoliticsAssessmentAgent.kt")
            val parentPipeline = parentPipe.getPipelinesFromInterface().last()
            val pipelineAssessment = parentPipeline.pipeMetaData["assessment"] as AgentAssessmentLevel

            val fromLlmJson = extractJson<AgentAssessmentLevel>(it.text) ?: throw Exception("No json found in numericScoringPipe @geoPoliticsAssessmentAgent.kt")

            // Apply base scores
            pipelineAssessment.favorPoints = fromLlmJson.favorPoints
            pipelineAssessment.riskLevel = fromLlmJson.riskLevel
            pipelineAssessment.playTargetDefensePoints = fromLlmJson.playTargetDefensePoints
            
            // Apply +25 favor bonus for Friendly actions
            val intent = parentPipeline.miniBank.contextMap["action_intent"]?.contextElements?.get(0) ?: "Hostile"
            if (intent == "Friendly") {
                pipelineAssessment.favorPoints += 25
                Logger.info(LogCategory.LLM, "[ASSESSMENT] Applied +25 favor bonus for Friendly action (base=${fromLlmJson.favorPoints}, final=${pipelineAssessment.favorPoints})")
            }

            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: numericScoringPipe.setTransformationFunction success")
            return@setTransformationFunction it
        }

    }

    return Pipeline().apply {
        setPreValidationFunction { context, miniBank, content ->
            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: Pipeline.setPreValidationFunction entry")
            // Inject intent, target count, and intent mismatch flag for all pipes
            miniBank.contextMap["action_intent"] = ContextWindow().apply {
                contextElements.add(actionIntent)
            }
            miniBank.contextMap["target_count"] = ContextWindow().apply {
                contextElements.add(targetCount.toString())
            }
            miniBank.contextMap["intent_mismatch"] = ContextWindow().apply {
                contextElements.add(intentMismatch.toString())
            }
            Logger.debug(LogCategory.SYSTEM, "GeoPolitics: Pipeline.setPreValidationFunction success")
        }
        
        add(assessmentPipe)
        add(overtonWindowPipe)
        add(playNormalcyPipe)
        add(conflictLevelPipe)
        add(writtenAssessmentPipe)
        add(numericScoringPipe)
    }
}
