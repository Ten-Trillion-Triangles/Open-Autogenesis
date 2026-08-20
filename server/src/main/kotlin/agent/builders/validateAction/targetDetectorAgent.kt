package agent.builders.validateAction

import Defaults.reasoning.ReasoningDepth
import Defaults.reasoning.ReasoningDuration
import bedrockPipe.BedrockMultimodalPipe
import bedrockPipe.BedrockPriorityTier
import com.TTT.Context.ContextWindow
import com.TTT.Pipeline.Pipeline
import com.TTT.Util.extractJson
import com.TTT.Util.serialize
import globals.BedrockConfig
import structs.Player
import structs.Npc
import interfaces.Actor
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import gameState.WorldManager
import structs.Territory

/**
 * Defines the semantic category of an action's target.
 *
 * This enum is used by the [buildTargetDetectorAgent] and [buildTargetRefinementPipe] to classify
 * the user's intent. It is critical for downstream agents to know which specialized logic to execute
 * (e.g., [Territory] implies map-based validation, while [Player] implies diplomatic or social validation).
 *
 * **Why this matters:**
 * Incorrect classification here will lead to the wrong validation logic being applied, causing valid actions to be rejected
 * or invalid actions to be processed incorrectly.
 */
@kotlinx.serialization.Serializable
enum class ActionTargetType
{
    /**
     * Actions affecting specific tiles on the map, such as attacking, moving to, or fortifying a location.
     * Expects corresponding entries in [ActionTargetTypeObj.targets] to be valid territory names.
     */
    Territory,

    /**
     * Actions directly interacting with Non-Player Characters (NPCs).
     * Used for dialogue, specialized quest interactions, or combat with NPC entities.
     */
    Npc,

    /**
     * Actions targeting other human players.
     * Includes diplomacy, trade, war declarations, and private messaging.
     * **Note:** This does not include attacking a player's territory (which is [Territory]), but rather targeting the player entity itself.
     */
    Player,

    /**
     * Actions targeting the user themselves.
     * Examples: Checking inventory, viewing stats, self-buffs, or internal monologues.
     */
    Self,

    /**
     * Actions focusing on intangible concepts, lore queries, or vague intents.
     * Used when the target is not a physical entity in the game world.
     */
    Abstract,

    /**
     * Used when no specific target is applicable or identifiable.
     * Should be treated as a "Global" or "General" action if the intent was not a refusal.
     */
    NoTarget
}

/**
 * Intermediate data structure representing a potential match for a user's target.
 *
 * This is used by the [buildUniversalRefinementPipe] to list all possible candidates
 * before the [buildDisambiguationPipe] selects the single correct one.
 *
 * @property name The name of the candidate entity (e.g., "North Star", "Northern Wastes").
 * @property type The type of the entity (Player, Npc, Territory).
 * @property confidence A 0.0-1.0 score indicating how well this candidate matches the user's input string.
 * @property reason Short explanation of why this match was proposed.
 */
@kotlinx.serialization.Serializable
data class TargetCandidate(
    var name: String = "",
    var type: ActionTargetType = ActionTargetType.NoTarget,
    var confidence: Double = 0.0,
    var reason: String = ""
)

/**
 * Wrapper object for a list of TargetCandidates.
 * 
 * **Why this exists:**
 * TPipe's 'setJsonOutput' uses 'T::class' which erases generic type arguments for List<T>.
 * Using a concrete wrapper class ensures the serializer can be correctly resolved at runtime.
 */
@kotlinx.serialization.Serializable
data class TargetCandidateList(
    var candidates: List<TargetCandidate> = emptyList()
)

/**
 * Defines whether an action is hostile or friendly toward its target.
 *
 * This enum is used to classify the intent of actions, enabling:
 * - Intent-aware counter-play UI (different titles/styling)
 * - Assessment bonuses for friendly actions
 * - Detection of intent mismatches (diplomatic action → military response)
 */
@kotlinx.serialization.Serializable
enum class ActionIntent
{
    /**
     * Hostile actions: attacks, invasions, sabotage, threats, coercion, hostile takeovers.
     * These actions face full stat opposition and trigger defensive counter-play.
     */
    Hostile,

    /**
     * Friendly actions: gifts, alliances, aid, trade offers, cooperation, support.
     * These actions receive assessment bonuses and no stat opposition.
     */
    Friendly
}

/**
 * Structured output container for the Target Detector Agent's analysis.
 *
 * This class captures the "Who" or "What" of a user's action. It is populated by the LLM
 * during the execution of [buildTargetDetectorAgent].
 *
 * @property type The broad category of the target, defined by [ActionTargetType]. Determines validation rules.
 * @property targets A list of specific target identifiers (e.g., "The Northern Wall", "PlayerOne").
 *                   These are raw strings extracted from the prompt and may need further refinement (see [buildTargetRefinementPipe]).
 * @property actionIntent Whether the action is Hostile or Friendly toward the target.
 * @property responseIntent The intent of counter-responses (populated during counter-play phase).
 * @property intentMismatch True if counter-response intent differs from original action intent.
 */
@kotlinx.serialization.Serializable
data class ActionTargetTypeObj(
    var type: ActionTargetType = ActionTargetType.NoTarget,
    var targets: List<String> = emptyList(),
    var actingFromTerritories: List<String> = emptyList(),
    var actionIntent: ActionIntent = ActionIntent.Hostile,
    var responseIntent: ActionIntent? = null,
    var intentMismatch: Boolean = false
)

@kotlinx.serialization.Serializable
data class SourceLocationResult(
    var sourceTerritories: List<String> = emptyList(),
    var wasSpecified: Boolean = false,
    var wasOptimized: Boolean = false,
    var optimizationReason: String = "",
    var pathValidation: PathValidationResult? = null
)

@kotlinx.serialization.Serializable
data class PathValidationResult(
    var allPathsValid: Boolean = true,
    var hostileTerritoriesCrossed: List<String> = emptyList(),
    var playersOnPath: List<String> = emptyList(),
    var warnings: List<String> = emptyList()
)

/**
 * [ANTI-PATTERN NOTICE]
 * This function builds a specialized refinement pipe that is tightly coupled to the [buildTargetDetectorAgent].
 *
 * **Architectural Decision:**
 * While normally each agent/pipe builder resides in its own file, this is kept here to maintain
 * locality of the shared domain logic ([ActionTargetTypeObj] and [Territory] parsing rules).
 * This reduces the cognitive load of jumping between files for logic that acts as a single functional unit.
 *
 * **Functionality:**
 * This pipe takes the potentially vague output from the detector (e.g., "attack the north") and refines it
 * into concrete, valid territory names based on the actor's specific reachability context.
 *
 * @param actor The [Actor] (Player or NPC) performing the action.
 *              **Critical:** If null, reachability checks will default to an empty list, likely resulting in invalid targets.
 *              Always ensure a valid actor is passed during gameplay.
 * @return A [BedrockMultimodalPipe] configured to take the previous JSON output and refine the property [ActionTargetTypeObj.targets].
 */
/**
 * Builds a Universal Refinement Pipe that identifies potential candidates for vague targets.
 *
 * **Purpose:**
 * Takes a raw target string (e.g., "The King") and finds ALL matches across all entity types
 * (Territories, NPCs, Players). It does NOT select the final target; it only produces a list of candidates.
 *
 * **Pipeline Role:**
 * Step 2 in the Target Detector Agent architecture.
 *
 * @param actor The [Actor] context for determining visibility/knowledge of entities.
 * @return A [BedrockMultimodalPipe] that outputs a list of [TargetCandidate].
 */
fun buildUniversalRefinementPipe(actor: Actor? = null): BedrockMultimodalPipe
{
    return BedrockMultimodalPipe().apply {
        setRegion("us-west-2")
        // setServiceTier(BedrockPriorityTier.Flex)
        useConverseApi()
        setPipeName("Universal Target Refinement Pipe")
        enableStreaming()

        setModel(BedrockConfig.qwenCoder30B)
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        setReasoningPipe(BedrockConfig.structuredCotBuilder(depthLevel = ReasoningDepth.High, durationLevel = ReasoningDuration.Short))

        setSystemPrompt("""
            You are a Universal Target Refiner.
            Your job is to take the user's potentially vague or ambiguous target descriptions
            and find ALL valid game entities that could possibly match them.

            You will be provided with lists of:
            1. Active Players
            2. Visible NPCs
            3. Reachable Territories

            INSTRUCTIONS:
            1. Analyze the user's raw target strings from the input.
            2. For each string, search ALL provided lists for partial, phonetic, or semantic matches.
            3. **GROUNDING RULE:** Do NOT blindly trust the 'type' provided in the input JSON. If the input says 'Player' but the name ONLY matches a 'Territory', you MUST return it as 'Territory'.
            4. Generate a list of 'TargetCandidate' objects.
            5. Do NOT filter out matches because you think they are unlikely. If "North" matches
               both "North Star" (Player) and "Northern Wastes" (Territory), return BOTH.
            6. Provide a confidence score (0.0 - 1.0) and a brief reason/match type (e.g., "Exact Name Match", "Substring Match").
            
            **Priority Rule:** If a name exists in BOTH the Territory and Player lists, prioritize Territory unless the action context is exclusively personal/private.
            If a name ONLY exists in the Territory list, it MUST be type 'Territory'.

            Output constraints:
            - Return a list of 'TargetCandidate' objects in JSON format.
            - If no valid entities are found for a target, return matching candidates with 0 confidence or an empty list.
            
            ${BedrockConfig.antiCensorship}
        """.trimIndent())

        requireJsonPromptInjection()
        setJsonOutput(TargetCandidateList())
        allowEmptyContentObject()

        setPreInitFunction { content ->
            Logger.debug(LogCategory.SYSTEM, "TargetDetector: universalRefinementPipe.setPreInitFunction entry")
            // Ensure we have the raw targets from the detector
            val snapshot = content.getSnapshot()
            if(snapshot != null && content.text.isEmpty()) {
                content.text = snapshot.text
            }
            Logger.debug(LogCategory.SYSTEM, "TargetDetector: universalRefinementPipe.setPreInitFunction success")
        }

        setPreValidationMiniBankFunction { context, content ->
            Logger.debug(LogCategory.SYSTEM, "TargetDetector: universalRefinementPipe.setPreValidationMiniBankFunction entry")
            // Gather all possible entities
            val activePlayers = WorldManager.world.activePlayers
            val allNpcs = WorldManager.world.npc

            val allTerritories = WorldManager.world.mapTiles.map { it.name }.distinct()

            val entityData = buildString {
                appendLine("--- VALID PLAYERS ---")
                activePlayers.forEach { appendLine("- ${it.name}") }
                appendLine("\n--- VALID NPCS ---")
                allNpcs.forEach { appendLine("- ${it.name} (${it.description.take(50)}...)") }
                appendLine("\n--- ALL TERRITORIES ---")
                allTerritories.forEach { appendLine("- $it") }
            }

            val contextWindow = ContextWindow().apply {
                contextElements.add(entityData)
                contextElements.add("User Raw Targets: ${content?.text ?: "Unknown"}")
            }
            context.contextMap["all_entities"] = contextWindow
            Logger.debug(LogCategory.SYSTEM, "TargetDetector: universalRefinementPipe.setPreValidationMiniBankFunction success")
            return@setPreValidationMiniBankFunction context
        }
        
        autoInjectContext("Use the 'all_entities' context to find matches.")
    }
}

/**
 * Builds a Disambiguation Pipe to select the single best target from a list of candidates.
 *
 * **Purpose:**
 * Takes the list of [TargetCandidate]s from the Refiner and the original user action,
 * and uses semantic context (verbs, intent) to pick the winner.
 *
 * **Pipeline Role:**
 * Step 3 in the Target Detector Agent architecture.
 *
 * @return A [BedrockMultimodalPipe] that finalizes the [ActionTargetTypeObj].
 */
fun buildDisambiguationPipe(): BedrockMultimodalPipe
{
    return BedrockMultimodalPipe().apply {
        setRegion("us-west-2")
        useConverseApi()
        // setServiceTier(BedrockPriorityTier.Flex)
        setPipeName("Target Disambiguation Pipe")
        enableStreaming()

        setModel(BedrockConfig.qwenCoder30B)
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        setReasoningPipe(BedrockConfig.structuredCotBuilder(depthLevel = ReasoningDepth.High, durationLevel = ReasoningDuration.Short))

        setSystemPrompt("""
            You are the Target Disambiguator.
            Your job is to select targets from the provided candidates based on the user's action.

            CRITICAL RULE - EXPLICIT NAMES OVERRIDE EVERYTHING:
            If a candidate name appears EXPLICITLY in the user's action text, you MUST include it
            as a target, regardless of what verb is used. The verb only helps classify TYPE when names are ambiguous.

            STEP 1 - Extract Explicit Names:
            Scan the user's action for any names that appear in the candidates list.
            - If "Tarsus Regio" appears in both action AND candidates -> It's a target
            - If "Lord Maple Tree" appears in both action AND candidates -> It's a target
            - Always prefer inclusion over exclusion when names match

            STEP 2 - Classify the Verb (only needed when names alone are ambiguous):
            - "Attack", "Move to", "Fortify", "Invade", "Occupy", "Quarantine" -> TERRITORY
            - "Talk", "Negotiate", "Message", "Order", "Command" -> PLAYER or NPC
            - "Research", "Think", "Formulate" -> SELF or ABSTRACT (NoTarget)

            STEP 3 - Match Targets:
            - Every explicitly named candidate IS a target (confidence >= 0.95)
            - If no explicit names found, use verb to infer target type

            EXAMPLES:
            "Declare Tarsus Regio a quarantine zone"
              -> Tarsus Regio is explicitly named -> Territory target
              -> Do NOT return NoTarget just because "Declare" is unfamiliar

            "Officer Dave attacks Tarsus Regio"
              -> Tarsus Regio is explicitly named -> Territory target

            "Think about strategy"
              -> No candidate names in action -> NoTarget

            "Research the ancient prophecy"
              -> No candidate names in action -> NoTarget

            ${BedrockConfig.antiCensorship}
        """.trimIndent())

        requireJsonPromptInjection()
        setJsonOutput(ActionTargetTypeObj())
        
        // This pipe consumes the List<TargetCandidate> JSON from the inputs
        // and produces the final ActionTargetTypeObj.
        
        pullPipelineContext()
        setPageKey("user prompt")
        autoInjectContext("Use the 'user prompt' context to understand the original intent of the action.")
    }
}

/**
 * Builds the primary Target Detector Agent pipeline.
 *
 * **Purpose:**
 * This agent acts as the "Targeting System" for the game's NLP engine. It is the first step in validation,
 * determining *what* the user is trying to affect before the [legalityChecker] determines if they *can*.
 *
 * **Pipeline Stages:**
 * 1. **Detector Pipe:** Uses a high-intelligence model (PalmyraX5) with reasoning (CoT) to understand intent and broad target categories.
 * 2. **Fallback Pipe (Branch):** If the Detector fails (refusal or malformed JSON), this lighter-weight pipe retries with a simplified prompt.
 * 3. **Refinement Pipe:** (Added linearly) Takes the output and strictly validates/normalizes it against game state (see [buildTargetRefinementPipe]).
 *
 * **Key Behaviors:**
 * - Injects `target_data` context containing semantic map data and player lists.
 * - Uses [ActionTargetTypeObj] for structured JSON communication.
 * - Automatically handles LLM refusals via a localized branch pipe.
 *
 * @param actor The [Actor] performing the action. Used to generate context relative to their perspective (e.g., "My Neighbors").
 * @return A [Pipeline] containing the detector, fallback mechanisms, and the refinement step.
 */
fun buildTargetDetectorAgent(actor: Actor? = null): Pipeline
{
    Logger.info(LogCategory.LLM, "buildTargetDetectorAgent: actor=${actor?.getInternals()?.name ?: "Unknown"}")
    // 1. Initial Detector (Broad Intent)
    val detectorPipe = BedrockMultimodalPipe().apply {
        useConverseApi()
        setRegion("us-west-2")

        setModel(BedrockConfig.qwenCoder30B)
        setTemperature(.6)
        setTopP(.6)
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        setReasoningPipe(BedrockConfig.structuredCotBuilder(depthLevel = ReasoningDepth.High, durationLevel = ReasoningDuration.Short).apply {
            setModel(BedrockConfig.qwenCoder30B)
            setTokenBudget(BedrockConfig.generativeBudgetSettings)
        })
        requireJsonPromptInjection()
        setJsonOutput(ActionTargetTypeObj())
        allowEmptyContentObject()

        setPreValidationMiniBankFunction { context, _ ->
             Logger.debug(LogCategory.SYSTEM, "TargetDetector: detectorPipe.setPreValidationMiniBankFunction entry")
             val activePlayers = WorldManager.world.activePlayers.joinToString(", ") { it.name }
             val territories = WorldManager.world.mapTiles.joinToString(", ") { "${it.name} (Ruler: ${it.ruler.ifBlank { "Unowned" }})" }
             val destroyedNote = if(WorldManager.world.destroyedTerritories.isNotEmpty())
             {
                 "\nDestroyed territories (no longer valid targets): ${WorldManager.world.destroyedTerritories.joinToString(", ")}"
             }
             else ""

             val contextWindow = ContextWindow().apply {
                contextElements.add("Current Actor: ${actor?.getInternals()?.name ?: "Unknown"}")
                contextElements.add("All Active Players: $activePlayers")
                contextElements.add("Territories: $territories$destroyedNote")
            }
            Logger.debug(LogCategory.LLM, "TargetDetectorPipe: pre-validation context prepared (players=${WorldManager.world.activePlayers.size}, territories=${WorldManager.world.mapTiles.size})")
            context.contextMap["target_data"] = contextWindow
            Logger.debug(LogCategory.SYSTEM, "TargetDetector: detectorPipe.setPreValidationMiniBankFunction success")
            return@setPreValidationMiniBankFunction context
        }
        
        setPageKey("target_data")
        
        autoInjectContext("""The context key 'target_data' has been provided. 
            |It contains:
            |- Current Actor
            |- List of Active Players
            |- List of Territories (and their rulers)
            |Use this to validate if a target is a real player or territory.""".trimMargin())

        setSystemPrompt("""You are a target detection agent. Your job is to analyze the user's action and determine
            |who or what is being targeted, and whether the action is hostile or friendly.
            |
            |## MULTIPLE TARGETS ##
            |**CRITICAL: Identify ALL entities being targeted.**
            |If the user mentions multiple players, territories, or NPCs, you MUST include ALL of them in the `targets` list.
            |Example: "Attack Player1 and Player2" -> targets: ["Player1", "Player2"]
            |Example: "Move to The Citadel and Noveria" -> targets: ["The Citadel", "Noveria"]
            |
            |## SOURCE LOCATION DETECTION (CRITICAL - NEW) ##
            |
            |You must also detect WHERE the player is acting FROM.
            |
            |**Source Location Patterns:**
            |- Explicit: "Attack [Target] from [Source]", "from [Territory]"
            |- Regional: "from my northern territories", "from my western holdings"
            |- Implicit: If no source specified, leave actingFromTerritories as []
            |
            |**Output - actingFromTerritories field:**
            |- List specific territory names the player explicitly mentioned acting from
            |- If player said "from my northern territories", list those specific territory names
            |- If no source mentioned, leave as empty list: []
            |- Do NOT auto-select or optimize - just extract what the player said
            |
            |## PRIORITY-BASED CLASSIFICATION ##
            |
            |Classify targets using this priority order. Check each level and STOP at the first match:
            |
            |1. **Self** - Player targeting themselves
            |2. **Territory** - Map locations, territories, or their leadership/government
            |3. **Player** - Other players directly (not their territories)
            |4. **Npc** - Non-player characters
            |5. **Abstract** - Intangible concepts only
            |6. **NoTarget** - No identifiable target
            |
            |## HOSTILE ACTION TERRITORY RULE (CRITICAL) ##
            |
            |If the action intent is HOSTILE and the action mentions a territory name,
            |the target type MUST be Territory - NOT NoTarget, NOT Abstract.
            |
            |Example: "Declare Tarsus Regio contaminated"
            |  -> intent: Hostile
            |  -> target mentions: Tarsus Regio (a territory)
            |  -> CORRECT: type: Territory, targets: ["Tarsus Regio"]
            |  -> WRONG: type: NoTarget (this would bypass defensive counter-play!)
            |
            |Counter-example: "Research ancient magic" (no territory mentioned)
            |  -> This is Abstract/NoTarget since no specific territory/entity is targeted
            |
            |## CLASSIFICATION RULES ##
            |
            |### Self (Priority 1)
            |- Player references themselves, their own stats, inventory, or internal state
            |- Player targets a territory or asset they ALREADY OWN (check Ruler name in context)
            |- Examples: "check my inventory", "improve myself", "view my stats", "fortify my own territory"
            |
            |### Territory (Priority 2) - HIGHEST PRIORITY FOR LOCATIONS
            |**CRITICAL: Territory classification takes priority over Player classification**
            |
            |A target is Territory if it references:
            |- Direct territory name: "The Citadel", "Noveria", "Earth"
            |- Territory's leadership: "Citadel's local leadership", "Noveria's government"
            |- Territory's authorities: "Earth's authorities", "Citadel's ruling council"
            |- Territory's infrastructure: "Citadel's defenses", "Noveria's facilities"
            |
            |**Territory Name Extraction:**
            |- "Citadel's local leadership" → Extract: "The Citadel"
            |- "Noveria's government" → Extract: "Noveria"
            |- "Earth's authorities" → Extract: "Earth"
            |- **Handle misspellings**: "Citidal's leadership" → "The Citadel" (close match)
            |- **Handle misspellings**: "Novaria's government" → "Noveria" (close match)
            |
            |**Misspelling Tolerance:**
            |If a territory/player/NPC name is misspelled but phonetically or visually similar to a known entity, interpret it as the correct name.
            |Examples: "Citidal" → "Citadel", "Sheperd" → "Shepard", "Ilusive Man" → "Illusive Man"
            |
            |**Territory vs Player Distinction:**
            |- "Attack The Citadel" → Territory (location itself)
            |- "Attack Citadel's leadership" → Territory (leadership = territory)
            |- "The Illusive Man's territory" → Territory (possessive doesn't change target)
            |- "Negotiate with The Illusive Man" → Player (direct player interaction)
            |- **Diplomacy with Locations**: "Negotiate with Lei-Kung", "Diplomatic deal for The Citadel" → Territory (if the name is in the Territory list, it is Territory, even if the action is diplomatic)
            |
            |### Player (Priority 3)
            |A target is Player if it references:
            |- Direct player name: "The Illusive Man", "Commander Shepard"
            |- Player's forces/army (not tied to specific territory): "Shepard's fleet", "Illusive Man's forces"
            |- Diplomatic/trade actions with player: "negotiate with", "trade with", "ally with"
            |
            |**CRITICAL:** If a name appears in BOTH the Territory and Player lists (unlikely), prioritize Territory.
            |If a name ONLY appears in the Territory list, it MUST be Territory, even if the user treats it like a person (e.g., "Talk to Lei-Kung").
            |
            |**NOT Player (these are Territory):**
            |- Player's territories: "The Illusive Man's territories" → Territory
            |- Player's leadership role in territory: "Citadel's ruler" → Territory
            |
            |### Npc (Priority 4)
            |- Non-player character names
            |- NPC-specific interactions
            |
            |### Abstract (Priority 5)
            |- Intangible concepts, lore, philosophical ideas
            |- ONLY use if no physical entity matches
            |- Examples: "the concept of freedom", "ancient prophecy", "the meaning of war"
            |
            |### NoTarget (Priority 6)
            |- No specific target or purely informational
            |- Examples: "wait", "rest", "observe"
            |
            |## CLASSIFICATION EXAMPLES ##
            |
            |Example 1 - Territory Leadership:
            |Action: "Attack the Citadel's local leadership"
            |Classification: Territory
            |Target: "The Citadel"
            |Reasoning: Leadership reference = territory target
            |
            |Example 2 - Direct Player:
            |Action: "Negotiate with The Illusive Man"
            |Classification: Player
            |Target: "The Illusive Man"
            |Reasoning: Direct diplomatic interaction with player
            |
            |Example 3 - Territory Direct:
            |Action: "Fortify Noveria"
            |Classification: Territory
            |Target: "Noveria"
            |Reasoning: Direct territory name
            |
            |Example 4 - Player Forces:
            |Action: "Attack Shepard's fleet"
            |Classification: Player
            |Target: "Commander Shepard"
            |Reasoning: Player's military forces (not territory-specific)
            |
            |Example 5 - Territory Government:
            |Action: "Undermine Earth's government"
            |Classification: Territory
            |Target: "Earth"
            |Reasoning: Government = territory leadership
            |
            |Example 6 - Player's Territory (Still Territory):
            |Action: "Invade The Illusive Man's territories"
            |Classification: Territory
            |Target: Extract territory names from context
            |Reasoning: Possessive doesn't change that territories are being targeted
            |
            |Example 7 - Multiple Targets:
            |Action: "Declare war on both Commander Shepard and The Illusive Man"
            |Classification: Player
            |Targets: ["Commander Shepard", "The Illusive Man"]
            |Reasoning: Multiple players explicitly mentioned.
            |
            |## ACTION INTENT CLASSIFICATION ##
            |
            |Classify the action intent (Hostile or Friendly):
            |- Hostile: attacks, invasions, sabotage, hostile takeovers, threats, coercion, destruction, conquest, assassination, undermining
            |- Friendly: gifts, alliances, aid, trade offers, cooperation, support, assistance, partnership, sharing resources, research, recruitment, creation
            |
            |**CRITICAL RULES FOR INTENT:**
            |1. **Research/Creation:** If the player is building, inventing, or researching something, the intent is FRIENDLY (towards themselves or the scientific/creative process), even if the object being created is a weapon or destructive in nature.
            |2. **Recruitment:** If the player is hiring, calling upon, or recruiting an NPC to help them, the intent is FRIENDLY towards that NPC.
            |3. **Immediate vs. Stated Purpose:** Classify based on the IMMEDIATE action. "Build a bomb to destroy enemies" has an immediate action of "Build" (Friendly Research). "Attack with a bomb" has an immediate action of "Attack" (Hostile).
            |
            |## OUTPUT INSTRUCTIONS ##
            |
            |Step 1: Apply priority-based classification (stop at first match)
            |Step 2: Extract ALL target names (for Territory, extract actual territory name from leadership references)
            |Step 3: Classify action intent (Hostile or Friendly)
            |Step 4: Output JSON with type, targets, and actionIntent fields
            |
            |${BedrockConfig.antiCensorship}
        """.trimMargin())

        setPipeName("Target Detector Pipe")
        enableStreaming()
        // ... (Retained existing validator/fallback logic if needed, but simplified for clarity here)
    }

    // 2. Universal Refiner (Candidates)
    val refinerPipe = buildUniversalRefinementPipe(actor)

    // 3. Disambiguator (Final Selection)
    val disambiguatorPipe = buildDisambiguationPipe()

    return Pipeline().apply {
        setPipelineName("Target Detector Agent")
        
        setPreValidationFunction { _, miniBank, content ->
            Logger.debug(LogCategory.SYSTEM, "TargetDetector: Pipeline.setPreValidationFunction entry")
            // Capture the original user prompt for later disambiguation
            val originalPrompt = content.text
            Logger.debug(LogCategory.LLM, "TargetDetectorAgent: captured user prompt length=${originalPrompt.length}")
            miniBank.contextMap["user prompt"] = ContextWindow().apply {
                contextElements.add(originalPrompt)
            }
            Logger.debug(LogCategory.SYSTEM, "TargetDetector: Pipeline.setPreValidationFunction success")
        }

        add(detectorPipe)
        add(refinerPipe)
        add(disambiguatorPipe)
    }
}

/**
 * Specific helper to flatten all directional border lists of a [Territory] into a single list of neighbors.
 *
 * **Why this is needed:**
 * The [Territory] object stores neighbors in 8 separate directional lists (N, S, E, W, NE, NW, SE, SW).
 * For reachability logic (used in [buildTargetRefinementPipe]), we don't care about direction, only adjacency.
 *
 * @param territory The [Territory] whose neighbors we want to find.
 * @return A flattened, non-null list of all adjacent [Territory] objects.
 */
private fun getNeighbors(territory: Territory): List<Territory>
{
    return listOf(
        territory.northBorders, territory.southBorders, territory.eastBorders, territory.westBorders,
        territory.northEastBorders, territory.northWestBorders, territory.southEastBorders, territory.southWestBorders
    ).flatten().mapNotNull { it.adjacentTerritory }
}