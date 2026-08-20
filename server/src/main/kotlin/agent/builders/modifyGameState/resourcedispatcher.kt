package agent.builders.modifyGameState

import Defaults.reasoning.ReasoningDepth
import Defaults.reasoning.ReasoningDuration
import agent.builders.AgentCoroutineScope
import bedrockPipe.BedrockMultimodalPipe
import com.TTT.Pipeline.Pipeline
import com.TTT.Util.extractJson
import gameState.ResourceAction
import gameState.ResourceAdjustment
import gameState.WorldManager
import gameState.TimeProvider
import globals.BedrockConfig
import globals.BedrockConfig.processFocusedBuilder
import agent.builders.validateAction.buildBranchFailureAgent
import bedrockPipe.BedrockPriorityTier
import com.TTT.Context.ContextBank
import com.TTT.Context.ContextWindow
import com.TTT.Util.serialize
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import structs.Player
import structs.World

@Serializable
data class ResourceArray(
    var adjustments: MutableList<ResourceAdjustment> = mutableListOf()
)

@Serializable
data class StringList(
    var values: List<String> = emptyList()
)

/**
 * Resource dispatcher agent which handles updating resources for the player. Is able to add, modify, or
 * destroy them based on the game's ruleset and criteria.
 */
fun buildResourceDispatcher(playerStats: Player? = null): Pipeline
{

    val resourceDispatcher = Pipeline()

    val dispatchPipe = BedrockMultimodalPipe()
        .setRegion("us-west-2")
        .useConverseApi()
        .setRegion("us-west-2")
        .setModel(BedrockConfig.qwen235B)
        .requireJsonPromptInjection()
        .setJsonOutput(ResourceArray())
        .truncateModuleContext()
        .setTokenBudget(BedrockConfig.generativeBudgetSettings)
        .setTemperature(0.7)
        .setTopP(0.3)
        .setReasoningPipe(BedrockConfig.processFocusedBuilder(depthLevel = ReasoningDepth.Med, durationLevel = ReasoningDuration.Med))
        .pullPipelineContext()
        .setPageKey("previous turn, player stats")
        .autoInjectContext("""ADDITIONAL CONTEXT:
            |"previous turn" is the most recent turn of gameplay.
            |"player stats" is the information about the player whose turn it currently
            |is.
        """.trimMargin())
        .setSystemPrompt("""Your job is to create a JSON array of ResourceAdjustment objects that will 
            |update the player's resources based on the most recent turn of gameplay.
            |
            |Cross-reference "previous turn" and "player stats" against the judge pipeline output to identify:
            |1. Resources that were GRANTED to the player this turn
            |2. Resources that were DESTROYED/lost by the player this turn
            |
            |CRITICAL REQUIREMENTS FOR GRANTED RESOURCES:
            |
            |For each resource being GRANTED, you MUST provide:
            |
            |1. **description** (REQUIRED, 2-4 sentences):
            |   - Explain what the resource IS (physical object, person, ability, concept, etc.)
            |   - Explain what the resource DOES or enables
            |   - Include any inherent LIMITS or CONDITIONS for its use
            |   - Base this on the narrative context from the turn story
            |
            |2. **abilities** (REQUIRED, 2-4 sentences):
            |   - List SPECIFIC capabilities this resource provides
            |   - Explain HOW it can be used in gameplay (combat, diplomacy, research, etc.)
            |   - Note any HARD LIMITS (cannot do X, requires Y, depletes after Z uses, etc.)
            |   - Distinguish between what it CAN do vs what it CANNOT do
            |
            |3. **depletable** (boolean):
            |   - true: Resource has finite uses/quantity (ammunition, fuel, consumables)
            |   - false: Resource is renewable or permanent (people, buildings, abilities)
            |
            |4. **destructible** (boolean):
            |   - true: Resource can be permanently destroyed/killed
            |   - false: Resource cannot be destroyed (abstract concepts, inherent traits)
            |
            |EXAMPLES OF GOOD RESOURCE DESCRIPTIONS:
            |
            |Example 1 - Military Asset:
            |{
            |  "resourceName": "Cerberus Phantom Squad",
            |  "action": "GRANT",
            |  "description": "An elite stealth infiltration unit consisting of 12 biotic-enhanced operatives trained in assassination and sabotage. The squad specializes in covert operations behind enemy lines. They require 48 hours of preparation before deployment and cannot operate in zero-gravity environments without specialized equipment.",
            |  "abilities": "Can infiltrate heavily guarded facilities, assassinate high-value targets, plant explosives, gather intelligence, and extract hostages. Cannot engage in prolonged firefights or hold territory. Each mission risks casualties that require time to replace. Effective against isolated targets but vulnerable to area-of-effect weapons.",
            |  "depletable": true,
            |  "destructible": true
            |}
            |
            |Example 2 - Subordinate Character:
            |{
            |  "resourceName": "Dr. Liara T'Soni",
            |  "action": "GRANT",
            |  "description": "An asari archaeologist and information broker with extensive knowledge of Prothean technology and galactic intelligence networks. She provides strategic analysis and can decrypt ancient artifacts. Her expertise is limited to information gathering and analysis; she is not a combat specialist.",
            |  "abilities": "Can research Prothean technology, decrypt alien data, provide intelligence reports on enemy movements, analyze strategic situations, and negotiate with information brokers. Cannot lead military operations or engage in direct combat effectively. Requires access to data networks and archaeological sites to be most effective.",
            |  "depletable": false,
            |  "destructible": true
            |}
            |
            |Example 3 - Abstract Concept:
            |{
            |  "resourceName": "Galactic Council Recognition",
            |  "action": "GRANT",
            |  "description": "Official diplomatic recognition from the Citadel Council granting legitimacy to the player's faction. This status provides access to Council resources and diplomatic channels but comes with expectations of adhering to Council laws and responding to Council requests.",
            |  "abilities": "Enables diplomatic negotiations with Council races, grants access to Citadel space stations and trade routes, provides legal protection under Council law, and allows petitioning the Council for military aid. Cannot be used to override Council decisions or force Council members to act. Can be revoked if the player violates Council law.",
            |  "depletable": false,
            |  "destructible": false
            |}
            |
            |EXAMPLES OF BAD DESCRIPTIONS (DO NOT DO THIS):
            |
            |❌ BAD - Too vague:
            |{
            |  "description": "A useful military asset.",
            |  "abilities": "Helps in combat."
            |}
            |
            |❌ BAD - No limits specified:
            |{
            |  "description": "A powerful weapon that can destroy anything.",
            |  "abilities": "Can defeat any enemy and solve any problem."
            |}
            |
            |❌ BAD - Empty or placeholder text:
            |{
            |  "description": "",
            |  "abilities": "TBD"
            |}
            |
            |VALIDATION REQUIREMENTS:
            |- description must be at least 50 characters
            |- abilities must be at least 50 characters
            |- Both must reference specific capabilities and limits
            |- Both must be based on the narrative context from the turn
            |
            |For resources being DESTROYED, you only need resourceName and action="DESTROY".
            |Description and abilities are ignored for destroyed resources.
        """.trimMargin())
        .setPipeName("dispatch pipe")
        .setValidatorFunction {
            Logger.debug(LogCategory.SYSTEM, "ResourceDispatcher: dispatchPipe.setValidatorFunction entry")
            val resourceArray = extractJson<ResourceArray>(it.text)
            if(resourceArray == null)
            {
                Logger.error(LogCategory.SYSTEM, "dispatch pipe did not provide valid json")
                return@setValidatorFunction false
            }

            // Validate each adjustment
            resourceArray.adjustments.forEachIndexed { index, adjustment ->
                if(adjustment.action == ResourceAction.GRANT)
                {
                    if(adjustment.description.isBlank() || adjustment.description.length < 50)
                    {
                        Logger.error(LogCategory.SYSTEM, 
                            "Resource '${adjustment.resourceName}' at index $index has invalid description: " +
                            "'${adjustment.description.take(30)}...' (length: ${adjustment.description.length}, required: 50+)")
                        return@setValidatorFunction false
                    }

                    if(adjustment.abilities.isBlank() || adjustment.abilities.length < 50)
                    {
                        Logger.error(LogCategory.SYSTEM, 
                            "Resource '${adjustment.resourceName}' at index $index has invalid abilities: " +
                            "'${adjustment.abilities.take(30)}...' (length: ${adjustment.abilities.length}, required: 50+)")
                        return@setValidatorFunction false
                    }

                    // Check for placeholder text
                    val descLower = adjustment.description.lowercase()
                    val abilitiesLower = adjustment.abilities.lowercase()
                    if(descLower.contains("tbd") || descLower.contains("todo") || 
                       abilitiesLower.contains("tbd") || abilitiesLower.contains("todo"))
                    {
                        Logger.error(LogCategory.SYSTEM, 
                            "Resource '${adjustment.resourceName}' contains placeholder text (TBD/TODO)")
                        return@setValidatorFunction false
                    }
                }
            }

            Logger.debug(LogCategory.SYSTEM, "ResourceDispatcher: dispatchPipe.setValidatorFunction success")
            return@setValidatorFunction true
        }
        .setBranchPipe(
            buildBranchFailureAgent(
                """Your previous attempt to generate ResourceAdjustment objects was rejected.
            |
            |COMMON FAILURES:
            |1. Empty or missing "description" field (REQUIRED for GRANT actions)
            |2. Empty or missing "abilities" field (REQUIRED for GRANT actions)
            |3. Descriptions too vague (must be 50+ characters with specific details)
            |4. Abilities don't specify limits (must explain what resource CAN'T do)
            |5. Invalid JSON structure
            |
            |REQUIREMENTS:
            |- Every GRANTED resource needs a detailed description (2-4 sentences)
            |- Every GRANTED resource needs specific abilities with limits (2-4 sentences)
            |- Base descriptions on the narrative context from the turn story
            |- Specify what the resource CAN do and what it CANNOT do
            |
            |Review the turn narrative and judge output, then generate complete ResourceAdjustment 
            |objects with all required fields populated."""
            )
        )
        .setTransformationFunction {
            Logger.debug(LogCategory.SYSTEM, "ResourceDispatcher: dispatchPipe.setTransformationFunction entry")
            val result = extractJson<ResourceArray>(it.text) ?: ResourceArray()
            val playerName = WorldManager.resolveCurrentTurnPlayerName()
            val turnNumber = WorldManager.world.roundNumber
            val timestampMillis = TimeProvider.nowMillis()

            if(playerName.isNotBlank())
            {
                WorldManager.applyResourceAdjustments(
                    playerName = playerName,
                    adjustments = result.adjustments,
                    turnNumber = turnNumber,
                    timestampMillis = timestampMillis
                )
            }
            else
            {
                Logger.warn(LogCategory.SYSTEM, "Resource dispatcher could not resolve a player for the current turn.")
            }

            Logger.debug(LogCategory.SYSTEM, "ResourceDispatcher: dispatchPipe.setTransformationFunction success")
            return@setTransformationFunction it
        }

    resourceDispatcher.apply {
        add (dispatchPipe)

        setPreValidationFunction { context, miniBank, content ->
            Logger.debug(LogCategory.SYSTEM, "ResourceDispatcher: Pipeline.setPreValidationFunction entry")
            val previousTurn = WorldManager.history.lastOrNull()?.let { serialize(it) } ?: ""
            val playerStats = serialize(playerStats).takeIf { playerStats != null } ?: serialize(StringList(ContextBank.getContextFromBank("player stats").contextElements))
            Logger.debug(LogCategory.SYSTEM, "ResourceDispatcher: Pipeline.setPreValidationFunction success")
        }
    }

    return resourceDispatcher
}