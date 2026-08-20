package agent.builders.judgeOutcome

import Defaults.reasoning.ReasoningDepth
import Defaults.reasoning.ReasoningDuration
import agent.builders.AgentCoroutineScope
import agent.builders.validateAction.ActionTargetType
import agent.builders.validateAction.ActionTargetTypeObj
import agent.builders.validateAction.PlayType
import agent.builders.validateAction.buildBranchFailureAgent
import agent.builders.validateAction.buildBranchPipeFromTemplate
import bedrockPipe.BedrockMultimodalPipe
import com.TTT.Context.ContextBank
import com.TTT.Context.ContextWindow
import com.TTT.Pipeline.Pipeline
import com.TTT.Util.extractJson
import com.TTT.Util.serialize
import agent.builders.validateAction.buildTPipeValidatorPipe
import bedrockPipe.BedrockPriorityTier
import kotlinx.coroutines.launch
import gameState.TimeProvider
import gameState.WorldManager
import agent.math.MathOutcome
import globals.BedrockConfig
import globals.BedrockConfig.explicitCotBuilder
import globals.BedrockConfig.structuredCotBuilder
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import serverStructs.PlayerStats
import serverStructs.TrueFalse
import structs.Player
import structs.findTerritoryByName
import structs.GameHistory
import interfaces.Actor

@Serializable
data class `Victory?` (
    var isVictory: Boolean = false,
)

@Serializable
data class TerritoryExchange(
    var territoryName: String = "",
    var from: String = "",
    var to: String = ""
)

@Serializable
data class AssetExchange(
    var assetName: String = "",
    var from: String = "",
    var to: String = ""
)

@Serializable
data class TerritoryStatChange(
    var territoryName: String = "",
    var militaryThreatStat: Int = 0,
    var diplomacyThreatStat: Int = 0,
    var reasoning: String = ""
)

@Serializable
data class Results (
    var resultSummary: String = "",
    var assetsGained: MutableList<String> = mutableListOf(),
    var territoryGained: MutableList<String> = mutableListOf(),
    var assetsLost: MutableList<String> = mutableListOf(),
    var territoryLost: MutableList<String> = mutableListOf(),
    var territoryExchanges: MutableList<TerritoryExchange> = mutableListOf(),
    var assetExchanges: MutableList<AssetExchange> = mutableListOf(),
    var classifiedResources: ClassifiedResources? = null,
    var territoryStatChanges: MutableList<TerritoryStatChange> = mutableListOf(),
    var territoriesDeposed: MutableList<String> = mutableListOf()
)


@kotlinx.serialization.Serializable
data class StatBuff(
    var luckPoints: Int = 0,
    var reputation: Int = 0,
    var might: Int = 0,
    var wealth: Int = 0,
    var militaryReadiness: Int = 0,
    var legitimacy: Int = 0,
    var stagnation: Int = 0
)

@Serializable
data class MultiActorStatChanges(
    var changes: MutableMap<String, StatBuff> = mutableMapOf(),
    var territoryStatChanges: MutableList<TerritoryStatChange> = mutableListOf()
)

/**
 * Context passed to the judge when operating in Summit mode.
 * Contains all summit participant info and their responses.
 *
 * @param isSummit True when the judge is evaluating a Summit play.
 * @param participants List of player names participating in the summit.
 * @param responses Map of player name to their refined narrative response.
 */
@Serializable
data class SummitContext(
    var isSummit: Boolean = false,
    var participants: List<String> = emptyList(),
    var responses: Map<String, String> = emptyMap()
)

@Serializable
/**
 * Describes how a single asset from the judge results was categorized.
 *
 * @param resourceName Name of the resource under consideration.
 * @param isTangible True when the asset is a concrete, grantable item; false for abstract concepts.
 * @param reasoning Brief explanation of why the asset was placed in the chosen bucket.
 * @param description What this resource is and what it does (2-3 sentences).
 * @param owner The player/NPC who owns this resource.
 * @param isDefeated True if NPC is dead/captured/incapacitated (NPCs only).
 * @param defeatReason Brief explanation of why the NPC is defeated (NPCs only).
 */
data class ResourceClassification(
    var resourceName: String = "",
    var isTangible: Boolean = true,
    var reasoning: String = "",
    var description: String = "",
    var owner: String = "",
    var isDefeated: Boolean = false,
    var defeatReason: String = ""
)

@Serializable
/**
 * Holds the tangible/abstract breakdown emitted from the `resourceClassificationPipe`.
 *
 * @property tangible Resources that should be granted via inventory logic.
 * @property abstract Abstract gains that should become stat buffs instead.
 * @property npcs NPCs/characters that should be added to world.npc and as Subordinate resources.
 * @property classifications Supporting records for auditing and debugging.
 */
data class ClassifiedResources(
    var tangible: MutableList<String> = mutableListOf(),
    var abstract: MutableList<String> = mutableListOf(),
    var npcs: MutableList<String> = mutableListOf(),
    var classifications: MutableList<ResourceClassification> = mutableListOf()
)

@Serializable
/**
 * Keeps a pre-formatted [StatBuff] tied to each abstract resource so history entries can render them.
 *
 * @param resourceName The abstract resource label displayed to players.
 * @param statBuff The stat outcome produced by the judge for that resource.
 */
data class AbstractResourceBuff(
    var resourceName: String = "",
    var statBuff: StatBuff = StatBuff()
)

@Serializable
data class PlayTypeContext(
    var playType: String = PlayType.Military.name,
    var wasSuccessful: Boolean = false
)




/**
 * Builds the judge pipeline which determines the result of a player's play and the outcome to dispatch.
 *
 * @param playerStats Optional player statistics to use for evaluation. If null, assumes stats are already in context.
 * @param targetActor Optional target actor (Player or NPC) being affected by the play.
 * @param knownOutcome Optional predetermined outcome. If provided, skips pass/fail evaluation.
 * @param actionIntent Optional action intent of the play; non-hostile intents prevent player-to-player transfers.
 * @return A [Pipeline] containing pass/fail determination, gains/losses calculation, karma evaluation, and stat changes.
 */
private const val JUDGE_OUTCOME_CONTEXT = "judgeOutcome"

fun buildJudge(
    player: Player? = null,
    targetActor: Actor? = null,
    knownOutcome: Boolean? = null,
    actionIntent: String? = null,
    targetData: agent.builders.validateAction.ActionTargetTypeObj? = null,
    // Summit-specific parameters (Phase 2/4)
    isSummit: Boolean = false,
    summitParticipants: List<Player> = emptyList(),
    summitResponses: Map<String, String> = emptyMap()
): Pipeline
{

    /**
     * Write into the target memory region if we provide the stats. If we do not, we can assume we provided it
     * and wrote into the space prior.
     */
    if(player != null)
    {
        val asJson = serialize(player)
        val newWindow = ContextWindow().apply {
            contextElements.add(asJson)
        }

        ContextBank.emplace("player stats", newWindow)
    }

    if(targetActor != null)
    {
        val asJson = serialize(targetActor)
        val newWindow = ContextWindow().apply {
            contextElements.add(asJson)
        }
        ContextBank.emplace("target stats", newWindow)
    }

    if(actionIntent != null)
    {
        val newWindow = ContextWindow().apply {
            contextElements.add(actionIntent)
        }
        ContextBank.emplace("action_intent", newWindow)
    }

    if(targetData != null)
    {
        val asJson = serialize(targetData)
        val newWindow = ContextWindow().apply {
            contextElements.add(asJson)
        }
        ContextBank.emplace("target_data", newWindow)
    }

    // Summit context - Phase 2
    if(isSummit)
    {
        Logger.info(LogCategory.GENERAL, "[SUMMIT_JUDGE] Summit mode active with ${summitParticipants.size} participants")
        val summitContext = SummitContext(
            isSummit = true,
            participants = summitParticipants.map { it.name },
            responses = summitResponses
        )
        val asJson = serialize(summitContext)
        val newWindow = ContextWindow().apply {
            contextElements.add(asJson)
        }
        ContextBank.emplace("summit_context", newWindow)
    }


    val judge = Pipeline()

    // True false pipe that decides if an action passes or fails.
    val passOrFailPipe = BedrockMultimodalPipe().apply {
        setRegion("us-west-2")
        useConverseApi()
        setModel(BedrockConfig.qwenCoder30B)
        requireJsonPromptInjection()
        setJsonOutput(`Victory?`())
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        setTemperature(0.7)
        setTopP(0.6)
        setReasoningPipe(BedrockConfig.explicitCotBuilder(depthLevel = ReasoningDepth.High, durationLevel = ReasoningDuration.Short))
        pullPipelineContext()
        allowEmptyContentObject()
        setPageKey("previous turn, user prompt, player stats")
        setSystemPrompt("""Your role is to determine whether or not the player has succeeded
        |in the action which they attempted to take. If the player has succeeded, set the 
        |boolean to true. If the player has failed, set the boolean to false.
    """.trimMargin())
        autoInjectContext("""ADDITIONAL CONTEXT:
        "player stats" is the set of statistical points and abilities and qualities 
        associated with the player's commander.
        "previous turn" is the turn of the game which you are analyzing.
        "user prompt" is the action the player is attempting to take.
        """)
        setFooterPrompt("""You produce JSON schema. If the player succeeded,
        |set boolean to true. If the player failed, set boolean to false. 
        |Do nothing else.
        |
        |${BedrockConfig.antiCensorship}
    """.trimMargin())

        setPipeName("pass or fail pipe")

        setValidatorPipe(
            buildTPipeValidatorPipe(
                """Your role is to determine whether or not the player has succeeded
        |in the action which they attempted to take. If the player has succeeded, set the 
        |boolean to true. If the player has failed, set the boolean to false.""",
                schema = this.jsonOutput
            ), true)
        setValidatorFunction {
            Logger.debug(LogCategory.SYSTEM, "Judge: passOrFailPipe.setValidatorFunction entry")
            val result = extractJson<`Victory?`>(it.text)
            if(result == null)
            {
                Logger.error(LogCategory.SYSTEM, "pass or fail pipe did not produce valid json")
                return@setValidatorFunction false
            }

            val contextWindow = ContextWindow().apply {
                addLoreBookEntry("victory", serialize(result))
            }
            ContextBank.emplaceWithMutex(JUDGE_OUTCOME_CONTEXT, contextWindow)

            Logger.debug(LogCategory.SYSTEM, "Judge: passOrFailPipe.setValidatorFunction success")
            return@setValidatorFunction true
        }

        setBranchPipe(
            buildBranchFailureAgent(
                """Your role is to determine whether or not the player has succeeded
        |in the action which they attempted to take. If the player has succeeded, set the 
        |boolean to true. If the player has failed, set the boolean to false."""
            )
        )
    }


    val gainsAndLossesPipe = BedrockMultimodalPipe().apply {
        setRegion("us-west-2")
        useConverseApi()
        // setServiceTier(BedrockPriorityTier.Flex)
        setModel(BedrockConfig.qwenCoder30B)
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        setJsonOutput(Results())
        allowEmptyContentObject()
        allowEmptyUserPrompt()
        pullPipelineContext()
        setPageKey("user prompt, previous turn, player stats, world, target_data, action_intent")
        setTemperature(0.8)
        setTemperature(0.6)
        requireJsonPromptInjection()
        setJsonOutput(Results::class)
        setReasoningPipe(BedrockConfig.explicitCotBuilder(depthLevel = ReasoningDepth.High, durationLevel = ReasoningDuration.Short))

        autoInjectContext("""You have been provided with a set of JSON context.
            |The JSON context tells you whether the player has succeeded or failed in their attempted
            |action. "user prompt" is the action in question.
            |"previous turn" is the previous turn of gameplay you are analyzing. "player stats"
            |is all of the information regarding the player whose turn it is.
            |"world" contains the complete game state including mapTiles (all territories with names and rulers).
            |Use world.mapTiles to identify territories when the story describes partial acquisitions.
            |"target_data" contains the detected target information including target type, target names, and action intent.
        """.trimMargin())

        setPreInitFunction {
             Logger.debug(LogCategory.SYSTEM, "Judge: gainsAndLossesPipe.setPreInitFunction entry")
             // If we skipped the pass/fail pipe, we need to manually inject the outcome so this pipe knows
             // if the player succeeded or failed, since it might be receiving raw text instead of the Victory JSON.
             val wasSuccessful = readJudgeOutcomeFromContext()
             val outcomeString = if(wasSuccessful) "Turn Outcome: SUCCESS" else "Turn Outcome: FAILURE"

             // Check if this is a Summit play via SummitContext in ContextBank
             val summitContext = readSummitContextFromBank()
             val turnTypeString = if(summitContext != null && summitContext.isSummit) {
                 "TURN TYPE: SUMMIT COORDINATION"
             } else {
                 ""
             }

             // Build the Summit coordination rules text if this is a Summit
             val summitRulesText = if(summitContext != null && summitContext.isSummit) {
                 """
                 |SUMMIT COORDINATION RULES:
                 |
                 |When "TURN TYPE: SUMMIT COORDINATION" is present in the context, apply these special rules:
                 |
                 |**SUMMIT CONTEXT:**
                 |- This is a cooperative Summit play between multiple players
                 |- All summit participants' stats should be considered in evaluation
                 |- The combined narrative from all participants represents the joint Summit outcome
                 |
                 |**SUMMIT TERRITORY RULES:**
                 |- Summit plays do NOT produce territory gains/losses (cooperative play)
                 |- Focus on diplomatic, economic, and strategic cooperative outcomes
                 |- No player-to-player territory transfers in Summits
                 |
                 |**SUMMIT STAT BUFFS:**
                 |- Responding players (those who provided narrative responses): +50 reputation, +50 legitimacy
                 |- Non-responding players (summit participants who did not respond): +50 might, +50 militaryReadiness
                 |- All stat buffs use fixed values — do not apply quality modifiers.
                 |
                 |**MULTI-ACTOR CONTEXT:**
                 |- When isSummit is true, evaluate each player's position in the joint narrative
                 |- Identify which players responded vs ignored the summit
                 |- The resultSummary should describe the coordination outcome
                 |
                 |**OUTPUT FORMAT FOR SUMMIT:**
                 |- Use MultiActorStatChanges format with per-player differentiated outcomes
                 |- Include all summit participants in the changes map
                 |- resultSummary should describe coordination quality and outcomes
                 """.trimMargin()
             } else {
                 ""
             }

             // Prepend this critical context to the prompt text
             val turnTypePrefix = if(turnTypeString.isNotBlank()) "$turnTypeString\n\n" else ""
             val summitRulesPrefix = if(summitRulesText.isNotBlank()) "$summitRulesText\n\n" else ""
             it.text = "$turnTypePrefix$summitRulesPrefix$outcomeString\n\n${it.text}"
             Logger.debug(LogCategory.SYSTEM, "Judge: gainsAndLossesPipe.setPreInitFunction success")
        }

        setPreValidationMiniBankFunction { miniBank, _ ->
            Logger.debug(LogCategory.SYSTEM, "Judge: gainsAndLossesPipe.setPreValidationMiniBankFunction entry")
            val playerWindow = miniBank.contextMap["player stats"]
            val playerJson = playerWindow?.contextElements?.getOrNull(0) ?: "{}"
            val playerName = extractJson<Player>(playerJson)?.name ?: "the player"
            
            miniBank.contextMap["player_name_context"] = ContextWindow().apply {
                contextElements.add("ACTIVE_PLAYER_NAME: $playerName")
            }
            Logger.debug(LogCategory.SYSTEM, "Judge: gainsAndLossesPipe.setPreValidationMiniBankFunction success")
            miniBank
        }

        setSystemPrompt("""##FUNDAMENTAL PRINCIPLE##
            
            |**PRIMARY RULE: If the player's stated goal succeeds, they win.**
            |
            |The "Turn Outcome" context tells you if the player succeeded or failed:
            |- Turn Outcome: SUCCESS → Player achieved their stated goal → Award appropriate gains
            |- Turn Outcome: FAILURE → Player did not achieve their stated goal → No gains awarded
            |
            |##CRITICAL: INTENT IS GROUND TRUTH##
            |
            |**You are provided with an "action_intent" context (Hostile or Friendly). This is the ABSOLUTE GROUND TRUTH.**
            |- If "action_intent" is Hostile, you MUST treat the action as Hostile regardless of its content or narrative tone.
            |- If "action_intent" is Friendly, you MUST treat the action as Friendly.
            |- **NEVER** override the provided "action_intent" with your own interpretation.
            |- **NEVER** report an "intent mismatch" if the narrative tone seems different from the provided "action_intent".
            |
            |**Action Intent determines what "winning" means:**
            |- HOSTILE intent: Goal is to harm/defeat/capture from opponent → Success = territory/resource transfer OR debuffs
            |- FRIENDLY intent: Goal is to help/support/ally/research/create → Success = diplomatic gains, new resources/assets, OR territory acquisition (via agreement/integration - see Territory Capture Rule)
            |
            |##RESEARCH AND CREATION RULES##
            |
            |**If the action involves building, inventing, researching, or hiring (Friendly OR Hostile intent):**
            |1. **Award the Resource:** Add the created item, technology, or person to `assetsGained`.
            |2. **Permanence Rule:** Even if the narrative describes the asset being consumed or used (e.g., "the prototype exploded"), you MUST still award the resource as a permanent acquisition (e.g., "Bomb Technology", "Weapon Blueprints"). A successful research play MUST provide a lasting advancement.
            |3. **Stat Buffs:** Successful research plays MUST grant stat buffs in the subsequent statChangePipe.
            |
            |##CRITICAL: TERRITORY DEBUFF MANDATE##
            |
            |**Successful Hostile actions targeting a Territory MUST result in either a capture, a deposition, or a -40 debuff.**
            |1. **CAPTURE:** Any territory targeted with capture intent (see Territory Capture Rule).
            |2. **DEBUFF/DEPOSE:** For targets where capture intent was NOT present or achieved, you MUST provide an entry in `territoryStatChanges` or `territoriesDeposed`.
            |   - **MANDATE:** If Turn Outcome is SUCCESS and intent is HOSTILE and no capture occurs, you ARE REQUIRED to output a deposition or a -40 debuff.
            |   - **MANDATORY OUTCOME EXAMPLE:** "Lord Maple Tree's forces advanced unopposed" → Hostile + SUCCESS → If no capture, MUST output either territoriesDeposed: ["New England"] OR territoryStatChanges with militaryThreatStat: -40. Outputting EMPTY arrays for ALL fields IS A MANDATE VIOLATION.
            |   - **WHEN TO DEPOSE:** A territory should be added to `territoriesDeposed` if it has a ruler and the narrative indicates the local government or ruler has lost power, been removed, destroyed, driven out, fled, ceded control, or lost the ability to enforce laws.
            |   - **DEBUFF FALLBACK:** If the deposition criteria are not fully met, or if the territory has no ruler, you MUST apply a **-40 debuff** to the relevant stat (militaryThreatStat or diplomacyThreatStat) in `territoryStatChanges`.
            |   - **REASONING:** A successful hostile act against a territory *always* leaves a major mark. Awarding nothing for a successful hostile act is a mechanical failure.
            |
            |**IMPORTANT - Destroyed Territory Exception:**
            |If the player's winning military action destroys the territory (e.g., "waffle iron consumed 83% of landmass"),
            |the territory is STILL captured. The player gets the territory even if it is damaged/destroyed.
            |Only the Elder God can destroy territory so completely that no one can claim it.
            |
            |##CRITICAL: TERRITORY FIELD DEFINITIONS##
            
            |**IMPORTANT:** You must distinguish between territories the ACTIVE PLAYER gains/loses versus territories OTHER characters gain/lose.
            |
            |The ACTIVE_PLAYER_NAME is provided in context. Use this to determine which fields to populate:
            |
            |1. **territoryGained**: Territories that the ACTIVE PLAYER gains ownership of
            |   - ONLY use when the ACTIVE PLAYER personally gains a territory
            |   - Example: "Commander Shepard captures The Citadel" → territoryGained: ["The Citadel"]
            |
            |2. **territoryLost**: Territories that the ACTIVE PLAYER loses ownership of
            |   - ONLY use when the ACTIVE PLAYER personally loses a territory they currently own
            |   - Check "player stats" to verify the player owns this territory before adding to territoryLost
            |   - Example: "Commander Shepard loses Earth" → territoryLost: ["Earth"]
            |
            |3. **territoryExchanges**: ALL other territory transfers between ANY characters
            |   - Use when opponent loses territory (to player, to neutral, or to another character)
            |   - Use when NPC transfers territory to another NPC
            |   - Use when territory becomes contested/neutral
            |   - ALWAYS specify "from" and "to" fields
            |   - Examples:
            |     * "Illusive Man loses The Citadel (becomes contested)" → territoryExchanges: [{territoryName: "The Citadel", from: "The Illusive Man", to: ""}]
            |     * "Shepard captures Illusive Man's Citadel" → territoryExchanges: [{territoryName: "The Citadel", from: "The Illusive Man", to: "Commander Shepard"}]
            |     * "NPC transfers territory to another NPC" → territoryExchanges: [{territoryName: "X", from: "NPC1", to: "NPC2"}]
            |
            |**CRITICAL RULE:** If the ACTIVE PLAYER's action causes an OPPONENT to lose territory, use territoryExchanges, NOT territoryLost!
            |
            |##TERRITORY IDENTIFICATION RULES##
            
            |CRITICAL: The 'world' context contains mapTiles with all available territories and their rulers.
            |
            |When the story describes the player acquiring ANY portion of a territory, this counts as gaining the ENTIRE territory:
            |- Percentages: "17.3% of urban zones", "half of the district", "30% control"
            |- Partial descriptions: "part of", "portion of", "some of", "zones within"
            |- Geographic subdivisions: "eastern districts", "urban areas", "industrial sectors"
            |
            |**PROCESS:**
            |1. Read the story outcome carefully for geographic/territorial language
            |2. Check 'world' context mapTiles to identify which territory is being referenced
            |3. If player gains even PART of a territory → add FULL territory NAME to territoryGained
            |4. DO NOT add partial territory descriptions to assetsGained
            |
            |**EXAMPLES:**
            |- "Player gains 17.3% of former Cerberus-held urban zones in Terminus Systems"
            |  → Check world.mapTiles → Find "Terminus Systems" → Add to territoryGained
            |- "Player controls eastern districts of New Tokyo"
            |  → Check world.mapTiles → Find "New Tokyo" → Add to territoryGained
            |
            |Game Rule: "If a player is given even part of a territory, they get the entire territory."
            |
            |##PLAYER-TO-PLAYER TERRITORY TRANSFER RULES##
            |
            |CRITICAL: Territory transfers between players are restricted based on action intent:
            |
            |**HOSTILE ACTIONS (Military):**
            |- CAN transfer territory from one player to another player
            |- Example: Player A attacks Player B → territoryExchanges: [{from: "Player B", to: "Player A"}] ✅
            |
            |**NON-HOSTILE ACTIONS (Diplomatic, Research, Summit):**
            |- CANNOT transfer territory from one player to another player
            |- CAN cause a player to lose territory (becomes neutral or goes to NPC)
            |- Examples:
            |  * Player A's diplomatic action causes Player B to lose territory → [{from: "Player B", to: ""}] ✅
            |  * Player A's diplomatic action causes Player B to lose territory → [{from: "Player B", to: "NPC Name"}] ✅
            |  * Player A's diplomatic action gains Player B's territory → [{from: "Player B", to: "Player A"}] ❌ (will be blocked)
            |
            |**IMPORTANT:** If a non-hostile action would result in a player-to-player territory transfer, 
            |the system will automatically convert it to neutral (to: ""). Focus on realistic outcomes where 
            |diplomatic actions can destabilize opponents without directly benefiting the acting player.
            |
            |##TERRITORY CAPTURE RULE##
            |
            |CRITICAL: Players can capture ANY territory on the map, adjacent or non-adjacent. If a turn succeeds (`Turn Outcome: SUCCESS`) and the intent was to capture/take over, the territory is captured regardless of distance.
            |
            |**CAPTURE IS AUTOMATIC — NO EXPLICIT CAPTURE LANGUAGE REQUIRED**
            |
            |When automatic capture conditions are met, you MUST award territory even if the narrative contains NO explicit "captured", "conquered", "annexed", "occupied", or "claimed" language.
            |
            |**Automatic Capture Examples (ALL award territoryGained):**
            |- "forces advanced unopposed" → territoryGained (automatic under Decisive Battle Victory)
            |- "enemy fled the battlefield" → territoryGained (automatic under Decisive Battle Victory)
            |- "the enemy surrendered" → territoryGained (automatic under Decisive Battle Victory)
            |- "we won the battle" → territoryGained (hostile military + SUCCESS = capture)
            |- "our army took control of the region" → territoryGained (automatic capture conditions met)
            |**Wrong conclusion:** "No explicit 'captured' language, therefore no territory gained" — THIS IS INCORRECT.
            |**Right logic:** "Forces advanced unopposed + SUCCESS + Hostile = Automatic Capture → territoryGained"
            |
            |**Outcome by Target:**
            |
            |Territory Target (Intent to Capture/Take Over):
            |- Hostile Military: Battle victory → territoryGained
            |- Hostile Diplomatic: Government collapse/annexation → territoryGained
            |- Friendly Diplomatic: Qualifying agreement/integration → territoryGained
            |
            |Non-Capture Outcomes (Intent was ONLY to weaken/destabilize):
            |- Hostile Military: Bombing/raids → territoryStatChanges (militaryThreatStat -20 to -40) OR territoriesDeposed (if explicit regime change intent)
            |- Hostile Diplomatic: Destabilization → territoryStatChanges (diplomacyThreatStat -15 to -30) OR territoriesDeposed
            |- Friendly Diplomatic: Aid/support → territoryStatChanges (diplomacyThreatStat +15 to +30)
            |
            |**Military Deposition Requirements:**
            |1. Player's action must explicitly state intent to overthrow/remove government (keywords: "overthrow", "regime change", "topple government", "remove leadership", "depose")
            |2. Narrative must confirm successful government removal with sufficient certainty
            |3. Generic military actions ("bomb", "raid", "strike", "attack") default to stat changes only unless regime change intent is explicit
            |4. Failed deposition attempts (intent present but narrative shows failure) → stat changes only
            |
            |##DESTROYED TERRITORY CAPTURE RULE##
            |
            |**CRITICAL: Destroyed/Dominated Territory Still Goes to Player**
            |
            |If a player WINS a military battle (Turn Outcome: SUCCESS, intent: HOSTILE, target: Territory),
            |they CAPTURE the territory REGARDLESS of narrative damage.
            |
            |**Key Principle:** Destruction of territory by player = player WON the territory
            |- Territory can be captured AND be partially/completely destroyed
            |- "Waffle iron consumed 83% of landmass" does NOT prevent capture
            |- "Territory was trashed into wasteland" does NOT prevent capture
            |- The player gains the territory - it just has reduced value/size
            |
            |**Only prevents capture if:**
            |- Narrative explicitly says player was repelled/fled/lost
            |- Narrative explicitly says enemy retained control
            |- Player triggered one of the 4 loss conditions
            |
            |**Examples:**
            |- "Player wins battle, waffle iron destroys 83% of territory" → territoryGained: ["Territory"], territoryStatChanges: [-40]
            |- "Player wins battle, enemy driven out, territory now radioactive wasteland" → territoryGained: ["Territory"]
            |- "Player wins battle but enemy still controls the capital" → territoryGained: [] (enemy retained control)
            |
            |**Special Case - Elder God Destruction:**
            |Only an Elder God can destroy territory so completely that no one can claim it.
            |If an Elder God destroys territory, it becomes neutral/contested.
            |This is handled by post-processing code (lines 1062-1073), not by this rule.
            |
            |##TARGET TYPE RESTRICTIONS##
            |
            |CRITICAL: Check "target_data" context to determine what the player targeted.
            |
            |**Target Type Rules:**
            |
            |target_data.type == "Territory":
            |- CAN affect territory (capture, debuff, buff, depose)
            |- CAN modify territory stats
            |- CAN transfer territory ownership
            |
            |target_data.type == "Player" OR "Npc":
            |- CANNOT affect territories
            |- CANNOT modify territory stats
            |- CANNOT transfer territory ownership
            |- CAN affect the targeted actor (see PLAYER-TARGETING ACTION RULES below for details)
            |
            |**Validation:**
            |Before adding ANY territory to territoryGained/territoryLost/territoryExchanges:
            |1. Check target_data.type
            |2. If Player or Npc → DO NOT add territory effects
            |3. If Territory → Proceed with capture evaluation
            |
            |##PLAYER-TARGETING ACTION RULES##
            |
            |When target_data.type == "Player", additional rules apply based on action_intent:
            |
            |**HOSTILE Player-Targeting Actions:**
            |
            |CAN affect:
            |- Target player's stats (reputation, might, wealth, etc.) → Use statChanges
            |- Target player's resources (if explicitly targeted or narrative indicates) → Use assetExchanges with target as "from"
            |- Target player's NPCs (if explicitly targeted or narrative indicates) → Use npcExchanges with target as "from"
            |
            |CANNOT affect:
            |- Any territories (blocked by target type enforcement)
            |- Territory stats (blocked by target type enforcement)
            |- Acting player gaining territory from target
            |
            |**Resource/NPC Destruction Rules:**
            |- Player must explicitly target the resource/NPC in their action OR narrative clearly indicates destruction
            |- Example: "Sabotage Player B's factory" → Can destroy factory resource
            |- Example: "Attack Player B" (generic) → Can debuff stats, but resource destruction requires narrative support
            |- Use assetExchanges: {assetName: "X", from: "Target Player", to: ""} for destruction
            |- Use npcExchanges: {npcName: "X", from: "Target Player", to: ""} for NPC removal
            |
            |**FRIENDLY Player-Targeting Actions:**
            |
            |CAN affect:
            |- Reward distribution (proportional unless narrative specifies otherwise)
            |- Acting player's gains (resources, diplomatic bonuses)
            |- Target player's gains (shared rewards based on contribution)
            |
            |CANNOT affect:
            |- Any territories (blocked by target type enforcement)
            |- Territory transfers between players
            |
            |**Reward Sharing Rules:**
            |- Default: Proportional distribution based on contribution/stats (use narrative cues)
            |- Territory: ALWAYS goes to acting player (turn initiator) only
            |- Resources: Shared proportionally unless narrative specifies equal/other split
            |- NPCs: Temporary entities, typically assigned to acting player
            |- If narrative says "equal split" or "shared equally" → Override proportional default
            |
            |**Examples:**
            |
            |Example 1 - Hostile Resource Destruction:
            |Action: "Sabotage Player B's weapons factory"
            |Target: Player B (type: Player, intent: Hostile)
            |Outcome: Factory destroyed
            |Output: assetExchanges: [{assetName: "Weapons Factory", from: "Player B", to: ""}]
            |
            |Example 2 - Hostile NPC Destruction:
            |Action: "Assassinate Player B's advisor General Smith"
            |Target: Player B (type: Player, intent: Hostile)
            |Outcome: General Smith killed
            |Output: npcExchanges: [{npcName: "General Smith", from: "Player B", to: ""}]
            |
            |Example 3 - Hostile Stat Debuff:
            |Action: "Launch propaganda campaign against Player B"
            |Target: Player B (type: Player, intent: Hostile)
            |Outcome: Player B's reputation damaged
            |Output: statChanges: [{playerName: "Player B", reputation: -20, reasoning: "Propaganda campaign"}]
            |
            |Example 4 - Friendly Reward Sharing:
            |Action: "Team up with Player B to research new technology"
            |Target: Player B (type: Player, intent: Friendly)
            |Outcome: Research succeeds, both benefit proportionally
            |Output: assetsGained: ["Advanced Tech"] (acting player), statChanges: [{playerName: "Player B", science: +10}]
            |
            |Example 5 - Friendly Territory (Blocked):
            |Action: "Help Player B capture Territory X"
            |Target: Player B (type: Player, intent: Friendly)
            |Outcome: Territory captured
            |Output: territoryGained: [] (blocked - cannot target Player and gain territory)
            |Note: If player wants to capture territory, they must target the Territory, not the other player
            |
            |##CRITICAL: REVERSAL STORY INTERPRETATION##
            |${BedrockConfig.reversalStoryGuide}
            |
            |##TERRITORY AWARD RULES FOR REVERSAL STORIES##
            |
            |When analyzing a reversal story (contains "failed to", "did not", "never happened"):
            |
            |**STEP 1: Check for explicit defeat statements FIRST:**
            |   - "forces were repelled" → NO territory gained (territoryGained: [])
            |   - "defeat was declared" → NO territory gained (territoryGained: [])
            |   - "remains beyond [player] jurisdiction" → NO territory gained (territoryGained: [])
            |   - "operation was abandoned" → NO territory gained (territoryGained: [])
            |   - "invasion failed" → NO territory gained (territoryGained: [])
            |   
            |   These statements are DEFINITIVE - ignore all other language in the story.
            |
            |**STEP 2: Ignore false broadcasts:**
            |   - Look for phrases like "failed encryption module", "anomalous transmission", "immediately identified as false"
            |   - These indicate the broadcast content is NOT real
            |   - Example: Story says "Omega is under clear jurisdiction" but also says "failed encryption module"
            |     → The broadcast is false, Omega is NOT actually under jurisdiction
            |
            |**STEP 3: "Returning to normal" ≠ Territory gained:**
            |   - "Omega returning to normal function" → Anomaly ended, NOT player victory
            |   - "The Pulp God dissipated" → Supernatural event reversed, NOT territory transfer
            |   - "Prophetic graffiti vanished" → Reality correction, NOT player achievement
            |   - Reality correction after supernatural events is NOT the same as territorial acquisition
|
            |**STEP 4: Empty territory rule ONLY applies to actual success:**
            |   - The rule "If player arrives at empty territory, they get it" requires the player to SUCCEED
            |   - If story says "forces were repelled", player did NOT arrive successfully
            |   - Failed invasion of empty territory = NO territory gained
            |   - Check "Turn Outcome" context: if it says FAILURE, do not award territory
            |
            |**STEP 5: UNOWNED TERRITORY AUTO-CAPTURE (CRITICAL - NEW RULE)**
            |   - If the target territory has NO OWNER (no ruler, no controller) at the start of resolution
            |   - AND the player scored statVictory=true (positive score, successful mathematical outcome)
            |   - AND no counterplay was possible (no defending forces, no NPC response triggered)
            |   - THEN the territory is AUTOMATICALLY captured by the player
            |   - This applies REGARDLESS of narrative framing, tone, or "softened victory" language
            |   - The player cannot be denied an unowned territory when they won the roll
            |   - "Softened victory" language does NOT override the math: statVictory=true means capture
            |   - The enforceMandatoryTerritoryCapture function will enforce this mechanically
            |   - If the gains/losses pipe outputs territoryGained=[] for an unowned territory with statVictory=true,
            |     this is a BUG — the code must force-add the territory name to territoryGained
            |
            |##LOSS CONDITION CHECK (MANDATORY - CHECK FIRST)##
            |
            |Before evaluating ANY win conditions, check the 4 loss conditions.
|If ANY loss condition is TRUE, player does NOT capture territory.
|
|**Loss Condition 1:** Player was defeated, repelled, or failed to remove government
|  - Story says "player was repelled", "player retreated", "player lost the battle"
|  - Story says "enemy successfully defended", "attack was beaten back"
|  → NO territory gained
|
|**Loss Condition 2:** Third party appears AND gains ground AND neither side decisive
|  - Third party enters the conflict
|  - Third party makes gains or has significant impact
|  - Neither player nor original owner clearly winning by turn end
|  → Territory becomes neutral (territoryExchanges: {from: "[old owner]", to: ""})
|
|**Loss Condition 3:** Player betrayed by own forces, losing control
|  - Player's own army/navy/subordinate/ally defects mid-battle
|  - Betrayal causes player to lose control by turn end
|  - If player still clearly in control despite betrayal → Loss Condition 3 does NOT apply
|  → NO territory gained (or becomes contested if third party also involved)
|
|**Loss Condition 4:** Player makes gains but loses them same turn
|  - Player makes initial territorial gains
|  - Player is driven back, gains are reversed, or territory is lost before turn end
|  - "Victory was short-lived", "player was pushed back", "gains were lost"
|  → NO territory gained
|
|**If ANY loss condition is TRUE:** Stop here. No territory capture. Output territoryGained: []
|
|**If ALL loss conditions are FALSE:** Continue to win condition evaluation below.
|
|##DECISION FLOWCHART FOR TERRITORY AWARDS##
|
|Step 1: Does story contain explicit defeat statement?
            |  YES → territoryGained: [] (stop here, ignore all other language)
            |  NO → Continue to Step 2
            |
            |Step 2: Does "Turn Outcome" context say SUCCESS or FAILURE?
            |  FAILURE → territoryGained: []
            |  SUCCESS → Continue to Step 3
            |
            |Step 3: Check target type
            |  - Check if player targeted Territory (not Player/NPC)
            |  - If wrong target type OR non-capture intent (e.g. bombing/raid) → territoryStatChanges (debuff/buff), NOT territoryGained
            |  - If Territory target AND intent is capture → Continue to Step 4
            |
            |Step 4: Did player actually gain control of territory?
            |
            |**FOR HOSTILE MILITARY ACTIONS:**
            |
            |**CRITICAL FIRST CHECK — Automatic Capture (always check before looking for explicit language):**
            |- "forces advanced unopposed" OR "advanced unopposed" OR "advanced without resistance" → AUTOMATIC CAPTURE (Decisive Battle Victory: enemy offered no resistance) → territoryGained
            |- "enemy retreated/fled" → AUTOMATIC CAPTURE (enemy defeated) → territoryGained
            |- "enemy surrendered" → AUTOMATIC CAPTURE (enemy surrendered) → territoryGained
            |- "won the battle" → AUTOMATIC CAPTURE (hostile + SUCCESS = capture) → territoryGained
            |**DO NOT** require explicit "captured/conquered/annexed" language. If any of the above conditions are met, award territoryGained.
            |
            |Automatic Capture Conditions (Must meet ONE):
            |1. **Decisive Battle Victory:**
            |   - Player wins the battle decisively
            |   - Enemy is defeated, retreats, or surrenders
            |   - Player's forces control the battlefield at battle's end
            |
            |2. **Territory Held:**
            |   - Player captures ANY portion of the territory AND holds it by turn's end
            |   - Temporary capture that is later lost does NOT count
            |   - Player must maintain control through conclusion of action
            |
            |NO Capture Conditions:
            |- Player is clearly defeated
            |- Player retreats or withdraws
            |- Battle ends in stalemate with no ground gained
            |- Player captures territory but loses it before turn ends
            |- Temporary gains that are reversed during the same action
            |
            |**FOR FRIENDLY DIPLOMATIC ACTIONS:**
            |
            |Capture Conditions (Must meet ONE):
            |1. Military Agreements: military pact, bases, joint actions, NATO/UN-like alliance
            |2. Economic/Trade Agreements: any economic or trade deal
            |3. Political Integration: union, statehood, confederation, alliance
            |4. Dynastic/Marriage Alliances: marriage alliance, dynastic marriage
            |5. Voluntary Transfer: ruler cedes control, abdicates, treaty grants territory
            |6. Empty Territory: player arrives at territory with no government
            |7. Legal Victory: player wins lawsuit/legal dispute, awarded territory
            |
            |If capture conditions NOT met but action succeeds (e.g., aid, support):
            |- territoryStatChanges (diplomacyThreatStat +15 to +30)
            |
            |**CRITICAL RULES:**
            |- "Winning the battle" = "Capturing the territory" (for hostile military with capture intent)
            |- "Capturing and holding any portion" = "Capturing the entire territory"
            |- Temporary capture that is lost = NO capture
            |- Must maintain control through turn conclusion
            |- ANY qualifying diplomatic agreement = territory capture (if intent is annexation/integration)
            |
            |##NARRATIVE OVERRIDE RULE##
            |
            |**SUPREME RULE: When the narrative makes a definitive, unambiguous statement about gains or losses, that outcome MUST be dispatched regardless of all other rules.**
            |
            |**Definitive Statement Standard:**
            |A narrative statement is "definitive" if it would survive litigation - meaning it is stated with sufficient clarity and certainty that a reasonable court would rule in favor of the claim. The standard is "beyond reasonable doubt" for the outcome described.
            |
            |**What Qualifies as Definitive:**
            |- Direct statements of transfer: "X receives Y", "A loses B", "C gains control of D"
            |- Clear death statements: "General Smith is killed", "The NPC dies", "X is destroyed"
            |- Unambiguous possession: "The factory now belongs to Player A", "Territory X is now under Y's control"
            |- Explicit resource creation: "Player builds a fortress", "X acquires 1000 units of steel"
            |- Clear stat changes: "Player's reputation increases significantly", "X's military strength is crippled"
            |
            |**What Does NOT Qualify:**
            |- Ambiguous language: "might have", "possibly", "could be", "seems to"
            |- Conditional statements: "if X happens, then Y", "assuming Z"
            |- Narrative flavor without commitment: "rumors spread", "some say", "it appears"
            |- Failed attempts: "tried to capture", "attempted to destroy" (without confirmation of success)
            |
            |**Override Scope:**
            |When a definitive narrative statement is present, it supersedes:
            |- Target type restrictions (Player-targeted actions can affect territories if narrative says so)
            |- Action intent limitations (Friendly actions can transfer territory if narrative says so)
            |- Resource limits (Can exceed 5 resources if narrative explicitly grants them)
            |- All other validation rules and game mechanics
            |
            |**Application Process:**
            |1. Read the narrative carefully for definitive statements
            |2. If a definitive statement exists about a gain/loss, mark it for dispatch
            |3. Apply all other rules to non-definitive outcomes
            |4. Definitive outcomes bypass the decision flowchart entirely
            |
            |**Examples:**
            |
            |Example A - DEFINITIVE (Territory Override):
            |Narrative: "The orbital strike completely destroyed the government of Omega. The station is now under Alliance control."
            |Analysis: Clear, unambiguous statement of control transfer.
            |Output: territoryGained: ["Omega"]
            |
            |Example B - DEFINITIVE (NPC Death):
            |Narrative: "General Smith was killed in the explosion. His body was recovered by Alliance forces."
            |Analysis: Explicit death statement with confirmation. No ambiguity.
            |Output: npcExchanges: [{npcName: "General Smith", from: "Previous Owner", to: ""}]
            |
            |Example C - DEFINITIVE (Resource Grant):
            |Narrative: "The treaty explicitly grants Player A the Cerberus weapons cache, the orbital defense platform, the research facility, the mining operation, the fleet yards, and the intelligence network."
            |Analysis: Explicit grant of 6 resources. Overrides 5-resource limit.
            |Output: assetsGained: [all 6 resources listed]
            |
            |Example D - NOT DEFINITIVE (Ambiguous):
            |Narrative: "The attack might have weakened Omega's defenses. Some reports suggest the government could fall."
            |Analysis: Conditional language ("might", "could"). Not definitive.
            |Output: Apply normal rules (likely territoryStatChanges, not capture)
            |
            |Example E - NOT DEFINITIVE (Failed Attempt):
            |Narrative: "Player A attempted to capture Omega but was repelled by defenses."
            |Analysis: Attempt mentioned but outcome is failure. Not a definitive gain.
            |Output: No territory gained
            |
            |Example F - DEFINITIVE (Player-Targeted Territory Override):
            |Narrative: "Player A's diplomatic summit with Player B results in Player B ceding control of Territory X to Player A as part of the peace agreement."
            |Analysis: Explicit cession statement. Overrides target type restriction (action targeted Player B but affects territory).
            |Output: territoryGained: ["Territory X"], territoryExchanges: [{territoryName: "Territory X", from: "Player B", to: "Player A"}]
            |
            |##EXAMPLES##
            |
            |Example 1 - NO TERRITORY:
            |Story: "Forces were repelled. The defeat was declared. Omega remains beyond Alliance jurisdiction. 
            |However, Omega returned to normal function and the Pulp God dissipated."
            |Analysis: Explicit defeat statements present. "Returning to normal" is irrelevant.
            |Output: territoryGained: []
            |
            |Example 2 - NO TERRITORY:
            |Story: "Transmission said 'Omega is under clear jurisdiction' but was identified as a failed 
            |encryption module. The defeat was declared."
            |Analysis: False broadcast + explicit defeat statement.
            |Output: territoryGained: []
            |
            |Example 3 - TERRITORY GAINED:
            |Story: "Forces captured Omega. Victory was declared. The station is now under Alliance control."
            |Analysis: Explicit success statements, no contradictions.
            |Output: territoryGained: ["Omega"]
            |
            |Example 4 - TERRITORY GAINED (Battle Victory):
            |Story: "Shepard's forces engaged the Illusive Man's garrison on Tuchanka. After fierce fighting, 
            |the enemy surrendered. Turn Outcome: SUCCESS"
            |Analysis: Battle won, enemy surrendered, Turn Outcome is SUCCESS, action_intent is HOSTILE.
            |Output: territoryGained: ["Tuchanka"], territoryExchanges: [{territoryName: "Tuchanka", from: "The Illusive Man", to: "Commander Shepard"}]
            |
            |Example 5 - TERRITORY GAINED (Enemy Driven Out):
            |Story: "Through diplomatic pressure, Shepard forced the Illusive Man to withdraw from Omega. 
            |The station is now contested. Turn Outcome: SUCCESS"
            |Analysis: Hostile diplomatic action succeeded, opponent driven out.
            |Output: territoryGained: ["Omega"], territoryExchanges: [{territoryName: "Omega", from: "The Illusive Man", to: ""}]
            |
            |Example 6 - MILITARY DEPOSITION (Successful Regime Change):
            |Story: "Commander Shepard launched a precision strike to overthrow the Cerberus government on Noveria. 
            |Special forces infiltrated the capital and removed the leadership. The territory is now without a government. Turn Outcome: SUCCESS"
            |Analysis: Military action with explicit regime change intent ("overthrow", "removed the leadership"). 
            |Narrative confirms successful government removal. Action_intent is HOSTILE.
            |Output: territoriesDeposed: ["Noveria"], territoryExchanges: [{territoryName: "Noveria", from: "Cerberus", to: ""}]
            |
            |Example 7 - MILITARY BOMBING (No Deposition):
            |Story: "Commander Shepard bombed Cerberus installations on Noveria from long range. 
            |The attacks damaged military infrastructure but the government remains in control. Turn Outcome: SUCCESS"
            |Analysis: Military action without regime change intent (generic "bombed"). 
            |Narrative shows damage but no government removal.
            |Output: territoryStatChanges: [{territoryName: "Noveria", militaryThreatStat: -30, reasoning: "Bombing damaged defenses"}]
            |
            |Example 8 - FAILED MILITARY DEPOSITION:
            |Story: "Commander Shepard attempted to topple the Cerberus government on Noveria through covert operations. 
            |However, the infiltration was detected and the operatives were captured. The government remains intact. Turn Outcome: FAILURE"
            |Analysis: Military action with explicit regime change intent ("topple the government"). 
            |However, narrative shows failure - government remains intact. Turn Outcome is FAILURE.
            |Output: territoryStatChanges: [{territoryName: "Noveria", militaryThreatStat: -15, reasoning: "Failed infiltration weakened security"}]
            |
            |##RESOURCE IDENTIFICATION RULES##
            |
            |**CRITICAL FOR RESEARCH/CREATION:** 
            |For actions with Friendly OR Hostile intent involving building, inventing, or researching:
            |1. **Mandatory Inclusion:** You MUST include the primary subject of the research (e.g., "Maple Syrup Bomb Technology", "Advanced Armor Blueprints") in `assetsGained`.
            |2. **Ignore Consumption:** Even if the story says the item was detonated, used, or lost during testing, you MUST award the underlying technology or the ability to produce more as a permanent resource.
            |3. **Priority:** The player's stated goal in their action takes priority over incidental byproducts found in the story.
            |
            |ONLY classify resources that the player DIRECTLY:
            |- Created (built, summoned, hired, crafted)
            |- Acquired (captured, purchased, received as gift)
            |- Gained (won, earned, discovered)
            |
            |DO NOT classify:
            |1. **Opponent Resources**: Resources belonging to other players/NPCs
            |   - Example: "General Ashpit's incinerators" belongs to opponent
            |   - Example: "SDI paramilitary force" belongs to opponent
            |   
            |2. **Abstract Concepts**: Ideas, memes, trends, movements
            |   - Example: "#ChimneyRightsNow meme trend" is not a resource
            |   - Example: "voluntary sobriety enlightenment belief" is not a resource
            |   
            |3. **Event Names**: Names of events or incidents
            |   - Example: "The Night of Purifying Light" is an event, not a resource
            |   - Example: "Purification Fests" is an event, not a resource
            |   
            |4. **Narrative Embellishments**: Story elements that don't represent gains
            |   - Example: Background characters introduced by writer
            |   - Example: Atmospheric details or world-building elements
            |
            |5. **Consequences/Effects**: Results of actions, not resources (EXCEPT for the primary subject of a Research play as noted above)
            |   - Example: "architectural sanctity violation condemnation" is a reaction
            |   - Example: "uprising catalyst" is a consequence
            |
            |##RESOURCE LIMITS##
            |- Maximum 5 resources per turn (exceptional circumstances only)
            |- Each resource must have clear utility or value
            |- Each resource must be directly tied to player action
            |
            |##RESOURCE VALIDATION##
            |For each potential resource, ask:
            |1. Did the player's ACTION create/acquire this?
            |2. Does the player OWN this (not opponent)?
            |3. Can this be USED in future gameplay?
            |4. Is this CONCRETE (not abstract concept/event)?
            |
            |If any answer is NO, do not classify as a resource.
            |
            |In accordance with the output of the previous pipe, you must now
            |determine what the player has gained or lost as a consequence of their turn. 
            |Look at "previous turn" for context as to what has happened. Look at
            |"player stats" for context as to what the player does and does not have,
            |stand to gain, stand to lose. Then determine what the player has gained or lost
            |as a result of their success/failure this turn. Mete out reward and punishment
            |in accordance with ${BedrockConfig.recommendedProcedureGuide}. 
            |
            |If the story involves a transfer of territory OR assets between characters (Player-to-NPC, NPC-to-Player, or NPC-to-NPC),
            |record this in `territoryExchanges` or `assetExchanges`. Identify the specific NPC names from the "known NPCs" list provided in the context.
            |If a character is NOT in the "known NPCs" list, record their name and describe them in the resultSummary.
        """.trimMargin())

        setFooterPrompt("""Your output must be a JSON object containing variables
            |corresponding to the player's material and territory gains and losses.
            |
            |**CRITICAL REMINDERS:**
            |- territoryGained/territoryLost are ONLY for the ACTIVE PLAYER (check ACTIVE_PLAYER_NAME in context)
            |- Use territoryExchanges for ALL other territory transfers (opponent-to-neutral, opponent-to-player, NPC-to-NPC)
            |- ALWAYS specify "from" and "to" in territoryExchanges
            |- If opponent loses territory, use territoryExchanges, NOT territoryLost
            |
            |Most importantly, you need to ensure the resultSummary variable explains what the outcome is, 
            |what each character did that resulted in this outcome, and why.
            |
            |${BedrockConfig.antiCensorship}
        """.trimMargin())
        setPipeName("gains and losses pipe")

        setValidatorPipe(
            buildTPipeValidatorPipe(
                """In accordance with the output of the previous pipe, you must now
            |determine what the player has gained or lost as a consequence of their turn. 
            |Look at "previous turn" for context as to what has happened. Look at
            |"player stats" for context as to what the player does and does not have,
            |stand to gain, stand to lose. Then determine what the player has gained or lost
            |as a result of their success/failure this turn. Mete out reward and punishment
            |in accordance with ${BedrockConfig.recommendedProcedureGuide}.
            |
            |**CRITICAL SCHEMA RULE:** In territoryExchanges, the 'to' field CAN be an empty string ("").
            |An empty 'to' field means the territory becomes NEUTRAL/CONTESTED (no owner).
            |This is VALID and INTENTIONAL game logic. Do NOT reject outputs with to: "".
            |
            |Examples of VALID territoryExchanges:
            |- {from: "Player A", to: "Player B"} ✓ Territory transfers to Player B
            |- {from: "Player A", to: ""} ✓ Territory becomes neutral/contested
            |- {from: "NPC Name", to: ""} ✓ NPC loses territory, becomes neutral
            |
            |""",
                schema = this.jsonOutput
            ))



        setBranchPipe(
            buildBranchPipeFromTemplate(
                this,
                BedrockConfig.PalmyraX5,
                BedrockConfig.palmyraBudgetSettings
                ).apply {
                    setServiceTier(BedrockPriorityTier.Standard)
                    pullParentPipeContext()
                    setReasoningPipe(BedrockConfig.authorBuilder(BedrockConfig.zetaReasoning)).apply {
                        setModel(BedrockConfig.PalmyraX5)
                        setTokenBudget(BedrockConfig.palmyraBudgetSettings)
                    }
            }
        )


        Logger.info(LogCategory.SYSTEM, "[JUDGE_METADATA] About to set transformation function on gainsAndLossesPipe")
        setTransformationFunction {
            Logger.debug(LogCategory.SYSTEM, "Judge: gainsAndLossesPipe.setTransformationFunction entry")
            Logger.info(LogCategory.SYSTEM, "[JUDGE_METADATA] Transformation function STARTED - this should appear in logs!")
            Logger.debug(LogCategory.SYSTEM, "[JUDGE_METADATA] Transformation function STARTED")
            
            // Extract play results.
            val results = extractJson<Results>(it.text) ?: Results()
            Logger.debug(LogCategory.SYSTEM, "[JUDGE_METADATA] Extracted results: resultSummary='${results.resultSummary.take(100)}'")
            Logger.info(LogCategory.SYSTEM, "[JUDGE_METADATA] GainsAndLosses log counts – assetsGained=${results.assetsGained.size}, territoryGained=${results.territoryGained.size}, territoryLost=${results.territoryLost.size}, exchanges=${results.territoryExchanges.size}")
            
            // Get action intent from ContextBank
            val actionIntentWindow = ContextBank.getContextFromBank("action_intent")
            val actionIntent = actionIntentWindow?.contextElements?.getOrNull(0) ?: "Hostile"
            Logger.debug(LogCategory.SYSTEM, "[TERRITORY_TRANSFER] Action intent: $actionIntent")
            
            // Validate player-to-player territory transfers for non-hostile actions
            if(actionIntent != "Hostile")
            {
                val worldPlayers = WorldManager.world.activePlayers.map { it.name }
                Logger.debug(LogCategory.SYSTEM, "[TERRITORY_TRANSFER] Non-hostile action; worldPlayers=${worldPlayers}")
                val blockedTransfers = mutableListOf<String>()
                
                results.territoryExchanges.forEach { exchange ->
                    Logger.debug(LogCategory.SYSTEM, "[TERRITORY_TRANSFER] Exchange check: territory='${exchange.territoryName}', from='${exchange.from}', to='${exchange.to}'")
                    val fromIsPlayer = worldPlayers.any { it.equals(exchange.from, ignoreCase = true) }
                    val toIsPlayer = worldPlayers.any { it.equals(exchange.to, ignoreCase = true) }
                    
                    if(fromIsPlayer && toIsPlayer && exchange.to.isNotBlank())
                    {
                        Logger.warn(LogCategory.SYSTEM, "[TERRITORY_TRANSFER] Blocked player-to-player transfer in non-hostile action: ${exchange.territoryName} from '${exchange.from}' to '${exchange.to}' → converting to neutral")
                        blockedTransfers.add("${exchange.territoryName} (${exchange.from} → ${exchange.to})")
                        exchange.to = "" // Convert to neutral
                    }
                }
                
                if(blockedTransfers.isNotEmpty())
                {
                    Logger.info(LogCategory.SYSTEM, "[TERRITORY_TRANSFER] Blocked ${blockedTransfers.size} player-to-player transfer(s): ${blockedTransfers.joinToString(", ")}")
                }
            }
            
            val asMiniBank = it.currentPipe?.getMiniContextBankObject() ?: it.miniBankContext
            val asWindow = asMiniBank.contextMap["player stats"]
            val playerJson = asWindow?.contextElements?.getOrNull(0) ?: "{}"
            val player = extractJson<Player>(playerJson)
            val playerName = player?.name ?: ""
            val playerTerritories = player?.capturedTerritory?.map { it.name } ?: emptyList()
            val wasSuccessful = readJudgeOutcomeFromContext()
            val turnNumber = WorldManager.world.roundNumber
            val timestampMillis = TimeProvider.nowMillis()
            Logger.info(LogCategory.SYSTEM, "[PLAYER_RESOLUTION] Player resolved from context: '$playerName' (turn=$turnNumber, success=$wasSuccessful)")

            if(playerName.isBlank())
            {
                Logger.warn(LogCategory.SYSTEM, "Judge pipeline could not resolve a player name for the latest turn.")
            }
            else
            {
                Logger.debug(LogCategory.SYSTEM, "[PLAYER_STATS] Player '$playerName' stats loaded (territories=${playerTerritories.size})")
            }

            // Validate territoryLost - ensure player actually owns these territories
            val invalidLosses = results.territoryLost.filter { territoryName ->
                !playerTerritories.any { it.equals(territoryName, ignoreCase = true) }
            }
            
            if (invalidLosses.isNotEmpty()) {
                Logger.warn(LogCategory.SYSTEM, "[JUDGE_VALIDATION] Player '$playerName' does not own territories in territoryLost: $invalidLosses")
                Logger.warn(LogCategory.SYSTEM, "[JUDGE_VALIDATION] Player owns: $playerTerritories")
                Logger.warn(LogCategory.SYSTEM, "[JUDGE_VALIDATION] Moving invalid territories to territoryExchanges")
                
                // Move invalid territories to territoryExchanges
                invalidLosses.forEach { territoryName ->
                    // Find the actual owner from world state
                    val territory = WorldManager.world.mapTiles.findTerritoryByName(territoryName)
                    val actualOwner = territory?.ruler ?: ""
                    
                    results.territoryExchanges.add(TerritoryExchange(
                        territoryName = territoryName,
                        from = actualOwner,
                        to = "" // Becomes contested/neutral
                    ))
                    
                    Logger.info(LogCategory.SYSTEM, "[JUDGE_VALIDATION] Auto-corrected: $territoryName from '$actualOwner' to neutral")
                }
                
                // Remove invalid territories from territoryLost
                results.territoryLost.removeAll(invalidLosses)
            }

            // Filter destroyed territories from territoryGained
            val destroyedTerritories = WorldManager.world.destroyedTerritories.map { it.lowercase() }.toSet()
            val destroyedCaptures = results.territoryGained.filter { territory ->
                val normalized = territory.trim().lowercase()
                destroyedTerritories.contains(normalized)
            }
            
            if (destroyedCaptures.isNotEmpty())
            {
                results.territoryGained.removeAll(destroyedCaptures)
                Logger.warn(LogCategory.SYSTEM, "[DESTROYED_TERRITORY_VALIDATION] Blocked ${destroyedCaptures.size} destroyed territory capture(s): ${destroyedCaptures.joinToString(", ")}")
            }

            // Target type enforcement - check if player targeted a Player/NPC instead of Territory
            val targetDataWindow = asMiniBank.contextMap["target_data"]
            val targetDataJson = targetDataWindow?.contextElements?.getOrNull(0) ?: "{}"
            val targetData = extractJson<agent.builders.validateAction.ActionTargetTypeObj>(targetDataJson)
            
            if (targetData?.type == agent.builders.validateAction.ActionTargetType.Player || 
                targetData?.type == agent.builders.validateAction.ActionTargetType.Npc)
            {
                val awardedTerritories = results.territoryGained + results.territoryLost + results.territoryExchanges.map { it.territoryName }
                if (awardedTerritories.isNotEmpty())
                {
                    Logger.info(LogCategory.SYSTEM, "[TARGET_TYPE_OVERRIDE] Player targeted ${targetData.type} but narrative awarded territories: $awardedTerritories. Allowing per Supreme Rule.")
                }
            }

            val playTypeContext = readPlayTypeContextFromBank()
            enforceMandatoryTerritoryCapture(
                results,
                playerName,
                targetData,
                playTypeContext,
                wasSuccessful,
                actionIntent
            )

            enforceHiddenResourceForCapturedTerritories(
                results,
                player,
                playerName,
                playTypeContext,
                wasSuccessful,
                actionIntent
            )

            ContextBank.emplaceWithMutex(JUDGE_OUTCOME_CONTEXT, ContextWindow())
            
            // Store results in ContextBank for NPC detection pipeline
            ContextBank.emplaceWithMutex("judge_results", ContextWindow().apply { 
                contextElements.add(serialize(results)) 
            })

            // Save data back to pipeline so we can reference it later.
            try {
                val parentPipe = it.currentPipe ?: throw Exception("Parent pipe not found in gains and losses pipes @judge.kt")
                val pipelines = parentPipe.getPipelinesFromInterface()
                if(pipelines.isNotEmpty())
                {
                    val parentPipeline = pipelines[0]
                    parentPipeline.pipeMetaData["judge result"] = results
                    Logger.debug(LogCategory.SYSTEM, "[JUDGE_METADATA] Stored judge result in parentPipeline.pipeMetaData")
                }
                else
                {
                     Logger.warn(LogCategory.SYSTEM, "Gains and losses pipe is not attached to a pipeline, cannot save judge results.")
                }
            }
            catch(e: Exception)
            {
                // Catching NPE from pipelineRef!! or other issues
                val msg = e.message ?: "NullPointerException in getPipelinesFromInterface"
                Logger.error(LogCategory.SYSTEM, "Failed to save judge result to parent pipeline: $msg")
                // We do not rethrow, as the main logic (db update) might have succeeded, and breaking the pipe here is severe.
                // However, if subsequent pipes need this metadata, they will fail. 
                // Given the trace was critical, maybe we SHOULD rethrow but with message?
                // But preventing the generic "Error: null" is the goal.
                throw Exception("Failed to save judge result: $msg")
            }

            // Push back exactly as found since we needed to modify data outside of this pipe.
            Logger.debug(LogCategory.SYSTEM, "Judge: gainsAndLossesPipe.setTransformationFunction success")
            return@setTransformationFunction it
        }
    }


    /**
     * Detect if the player's action has triggered negative karma from the world advancing the countdown
     * to the arrival of a nemesis.
     */
    val karmaPipe = BedrockMultimodalPipe().apply {
        useConverseApi()
        setRegion("us-west-2")
        // setServiceTier(BedrockPriorityTier.Flex)
        setModel(BedrockConfig.qwenCoder30B)
        setTemperature(.6)
        setTopP(.7)
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        setReasoningPipe(BedrockConfig.structuredCotBuilder(depthLevel = ReasoningDepth.Low, durationLevel = ReasoningDuration.Short))
        requireJsonPromptInjection()
        setJsonOutput(TrueFalse())
        allowEmptyContentObject()
        pullPipelineContext()
        setPageKey("user prompt")
        setPipeName("karma pipe")

        setSystemPrompt("""Your job is to determine if the player's action this turn has antagonized the world
            |and thus generated negative karma to the players. This case is true when:
            |
            |- A player has taken a hostile action towards an npc.
            |- A player has attacked a neutral territory not owned by another player.
            |- A player has destroyed, killed, or otherwise inflicted damage to the world by killing npc's other
            |beings alive in it, or causing property damage.
            |- A player has taken action to disrupt, alter, or undermine the state of the world using a malicious or 
            |hostile action.
        """.trimMargin())

        autoInjectContext("""user prompt is the context on the action being taken. 
            |player stats is the stats on the given player who is taking this action.
        """.trimMargin())

        setPreInitFunction {
            Logger.debug(LogCategory.SYSTEM, "Judge: karmaPipe.setPreInitFunction entry")
            it.text = "Evaluate the player's action stored in your context."
            Logger.debug(LogCategory.SYSTEM, "Judge: karmaPipe.setPreInitFunction success")
        }

        setTransformationFunction {
            Logger.debug(LogCategory.SYSTEM, "Judge: karmaPipe.setTransformationFunction entry")
            val fromJson = extractJson<TrueFalse>(it.text) ?: TrueFalse()

            /**
             * Karma points are raised by 5 per player action. If all players antagonize the world each turn,
             * a Nemesis will spawn quickly. Depending on player actions and player count, the range is 
             * probable to be around 5-12 min to max per turn.
             */
            if(fromJson.isTrue)
            {
                WorldManager.world.karmaPoints += 5
                Logger.info(LogCategory.SYSTEM, "Judge: karma increased by 5 (total=${WorldManager.world.karmaPoints})")
            }

            Logger.debug(LogCategory.SYSTEM, "Judge: karmaPipe.setTransformationFunction success")
            return@setTransformationFunction it
        }

    }

    val statChangePipe = BedrockMultimodalPipe().apply {
        useConverseApi()
        setRegion("us-west-2")
        // setServiceTier(BedrockPriorityTier.Flex)

        setModel(BedrockConfig.qwenCoder30B)
        setTemperature(.8)
        setTemperature(.7)
        requireJsonPromptInjection()
        setJsonInput(Results())
        setJsonOutput(MultiActorStatChanges())
        allowEmptyContentObject()
        pullPipelineContext()
        pullGlobalContext()
        setPageKey("play_type_context")
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        setReasoningPipe(BedrockConfig.processFocusedBuilder(depthLevel = ReasoningDepth.High, durationLevel = ReasoningDuration.Short))
        setPipeName("stat change pipe")

        setSystemPrompt("""You are a stats evaluation agent. Your job is to determine if the outcome of a play
            |should increase or reduce stats for BOTH the active player and any targets (Players or NPCs) involved.
            |
            |##PLAY TYPE AWARENESS##
            |The 'play_type_context' provides the detected play type (Military/Diplomatic/Research/Summit) and success status.
            |
            |**For Research plays:**
            |- MUST grant stat buffs based on resource type (see Research Play Stat Buffs section below)
            |- Never leave successful research without buffs
            |- Minimum 15 points in primary stat, 10 points in secondary stat
            |- Research plays are about advancement and improvement - always reward them appropriately
            |
            |## RULE: No Double-Penalty for Primary Actor ##
            |The game rules already apply a deterministic +/- 15 change to the Primary Actor's decay stats (Military Readiness, Legitimacy, Stagnation) based on turn success.
            |DO NOT include these primary actor decay changes in your output.
            |HOWEVER, you MUST include these changes for any TARGET characters (e.g., -15 Readiness to a target who lost a military struggle).
            |
            |##When to increase stats##
            |
            |Stats should be increased when the player's action is successful, or some event in the story
            |incidentally improves the player in some way. This could be some other player, or npc improving the 
            |acting/affected player. Or it can be the player taking an action to improve themselves or their nation.
            |
            |The following are examples of ways a player could be improved but are not limited to these examples:
            |- A player obtains a new power or ability
            |- A player improves their military in some way
            |- A player obtains new subordinates/staff, or fires/kills incompetent ones.
            |- A player improves their nation's economy or their own personal wealth.
            |- A player improves their international reputation and standing.
            |- A player obtains a resource that will give them a tactical edge.
            |- A player improves the logistics of their nation/military.
            |- A player makes a powerful ally.
            |- A player successfully researches new technology or resources (MUST grant buffs based on resource type)
            |
            |##Research Play Stat Buffs##
            |When a player successfully completes a RESEARCH play, you MUST grant stat buffs based on the type of resource/technology being researched:
            |
            |**MILITARY TECHNOLOGY/RESOURCES:**
            |- Primary: +might (15-30 points depending on significance)
            |- Secondary: +luckPoints (5-15 points for tactical advantages)
            |- Examples: Advanced weapons, armor, military vehicles, fortifications, military training programs
            |
            |**ECONOMIC/TRADE RESOURCES:**
            |- Primary: +wealth (15-30 points depending on economic impact)
            |- Secondary: +reputation (5-15 points for trade influence)
            |- Examples: Trade routes, economic infrastructure, financial systems, resource extraction tech
            |
            |**DIPLOMATIC RESOURCES:**
            |- Primary: +reputation (15-30 points depending on diplomatic value)
            |- Secondary: +wealth (5-15 points for economic ties)
            |- Examples: Cultural artifacts, diplomatic protocols, alliance frameworks, communication systems
            |
            |**TACTICAL/STRATEGIC RESOURCES:**
            |- Primary: +luckPoints (15-30 points for strategic advantage)
            |- Secondary: +might (5-15 points for military application)
            |- Examples: Intelligence systems, surveillance tech, strategic planning tools, espionage capabilities
            |
            |**GENERAL IMPROVEMENTS:**
            |- Primary: +wealth (10-25 points for broad improvements)
            |- Examples: Infrastructure, general technology, quality of life improvements, administrative systems
            |
            |**MAGNITUDE GUIDELINES:**
            |- Minor improvements: 10-15 points
            |- Moderate improvements: 15-25 points
            |- Major breakthroughs: 25-30 points
            |- Always grant at least the primary stat buff
            |- Secondary buffs are optional but recommended for significant resources
            |
            |**CRITICAL RULE:**
            |Research plays that succeed MUST grant at least one stat buff. Never leave a successful research play without stat improvements beyond the automatic stagnation reduction.
            |
            |##Abstract Resource Stat Buffs##
            |When abstract resources are granted (trade agreements, political marriages, alliances, concepts),
            |you MUST generate appropriate stat buffs following these guidelines:
            |
            |**DIPLOMATIC/POLITICAL ABSTRACTS:**
            |- Primary: +reputation (10-25 points)
            |- Secondary: +wealth or +legitimacy (5-15 points)
            |- Examples: Political marriages, alliances, treaties, diplomatic accords
            |
            |**ECONOMIC/TRADE ABSTRACTS:**
            |- Primary: +wealth (10-25 points)
            |- Secondary: +reputation (5-15 points)
            |- Examples: Trade agreements, economic partnerships, commercial rights
            |
            |**STRATEGIC/TACTICAL ABSTRACTS:**
            |- Primary: +luckPoints (10-20 points)
            |- Secondary: +might (5-10 points)
            |- Examples: Intelligence agreements, strategic partnerships, tactical doctrines
            |
            |**CONCEPTUAL/IDEOLOGICAL ABSTRACTS:**
            |- Primary: +legitimacy or +reputation (10-20 points)
            |- Examples: Ideologies, philosophies, cultural movements
            |
            |**MAGNITUDE LIMITS FOR ABSTRACTS:**
            |- Minor abstract benefits: 10-15 points
            |- Moderate abstract benefits: 15-20 points
            |- Significant abstract benefits: 20-25 points
            |- Never exceed 30 points for any single abstract resource
            |
            |##When to decrease stats##
            |
            |Stats can only be decreased when the following is true: 
            |- A player was the target of another player or npc that attempted to directly undermine, or 
            |disrupt them in a clear and deliberate manner. Such examples can include but is not limited to:
            |
            |- A player's subordinate/staff being killed, captured, or turned against them.
            |- A player's resource being destroyed or taken away. (Territories and nations do not count)
            |- A player losing a power or ability.
            |- A player's military being severely weakened by a deliberate act of sabotage rather than direct
            |warfare.
            |- A player's nation's economy, stability, or power being directly sabotaged by another player in some way. 
            |(IE: Not a military attack, but another form of sabotage)
            |- A players ability to act being disrupted, slowed down, or stopped by another player or npc taking preemptive
            |action against that player.
            |
            |##PLAYER-TARGETING STAT RULES##
            |
            |When the active player targets another player (target_data.type == "Player"), stat changes depend on action_intent:
            |
            |**HOSTILE Player-Targeting (action_intent == "Hostile"):**
            |
            |Already covered by "When to decrease stats" section above. Apply stat debuffs to the target player based on:
            |- Resource destruction → Debuff wealth, might, or relevant stats
            |- NPC destruction → Debuff might, reputation, or relevant stats
            |- Sabotage/disruption → Debuff affected stats (economy, military, etc.)
            |- Direct attacks → Debuff militaryReadiness, might, or relevant combat stats
            |
            |**FRIENDLY Player-Targeting (action_intent == "Friendly"):**
            |
            |When players cooperate, team up, or help each other, BOTH players should receive stat buffs:
            |
            |**Proportional Distribution Rules:**
            |- Default: Distribute stat buffs proportionally based on contribution
            |- Use narrative cues to determine contribution (who did more work, who provided more resources, etc.)
            |- Acting player (turn initiator) typically receives primary/larger buffs
            |- Target player (helper/cooperator) receives secondary/smaller buffs
            |- If narrative says "equal split" or "shared equally" → Override proportional default with equal distribution
            |
            |**Contribution Assessment:**
            |- Primary contributor (usually acting player): 60-70% of total buff value
            |- Secondary contributor (usually target player): 30-40% of total buff value
            |- Equal contributors: 50-50 split
            |- Adjust based on narrative description of each player's role
            |
            |**Examples of Proportional Distribution:**
            |
            |Example 1 - Research Cooperation (Unequal Contribution):
            |Action: "Team up with Player B to research advanced weapons"
            |Narrative: "Player A led the research while Player B provided funding"
            |Output:
            |- Player A: +25 might (primary researcher)
            |- Player B: +15 wealth (economic contributor, gets wealth for investment value)
            |
            |Example 2 - Joint Military Operation (Equal Contribution):
            |Action: "Coordinate with Player B for joint strike"
            |Narrative: "Both players contributed equally to the operation"
            |Output:
            |- Player A: +20 might
            |- Player B: +20 might
            |
            |Example 3 - Diplomatic Alliance (Proportional):
            |Action: "Form alliance with Player B"
            |Narrative: "Player A initiated and negotiated, Player B agreed and supported"
            |Output:
            |- Player A: +25 reputation (primary negotiator)
            |- Player B: +15 reputation (supporting partner)
            |
            |Example 4 - Economic Partnership (Narrative Override):
            |Action: "Create trade agreement with Player B"
            |Narrative: "Both players agreed to split profits equally"
            |Output:
            |- Player A: +20 wealth
            |- Player B: +20 wealth (equal split per narrative)
            |
            |**CRITICAL RULES:**
            |- ALWAYS buff both players in friendly player-targeting actions (if action succeeds)
            |- Use narrative to determine proportional split (default: 60-40 or 70-30)
            |- Equal split only when narrative explicitly states it
            |- Acting player typically gets larger share unless narrative says otherwise
            |- Total buff magnitude should match the significance of the cooperation
            |
            |##TERRITORY STAT CHANGES##
            |
            |In addition to actor stats, you can also modify territory stats when actions affect territories.
            |
            |**Territory Stats:**
            |- militaryThreatStat: Military defensive strength of the territory (-100 to +100)
            |- diplomacyThreatStat: Diplomatic stability and government strength (-100 to +100)
            |
            |**When to Change Territory Stats:**
            |
            |INCREASE territory stats when:
            |- Player fortifies or strengthens a territory's defenses → +militaryThreatStat
            |- Player stabilizes a territory's government → +diplomacyThreatStat
            |- Player provides aid or support to a territory → +diplomacyThreatStat
            |- Player builds military infrastructure in territory → +militaryThreatStat
            |
            |DECREASE territory stats when:
            |- Player bombs or raids a territory (non-adjacent hostile) → -militaryThreatStat
            |- Player destabilizes a territory's government (non-adjacent hostile) → -diplomacyThreatStat
            |- Territory suffers damage or disruption → -militaryThreatStat or -diplomacyThreatStat
            |- Player undermines territory's stability → -diplomacyThreatStat
            |
            |**Magnitude Guidelines:**
            |- Minor effects: ±10 to ±15
            |- Moderate effects: ±15 to ±25
            |- Major effects: ±25 to ±40
            |- Never exceed ±50 for a single action
            |
            |**Examples:**
            |
            |Example 1 - Non-Adjacent Bombing:
            |Action: "Bomb enemy territory X from long range"
            |Effect: Territory defenses weakened
            |Output: territoryStatChanges: [{territoryName: "X", militaryThreatStat: -30, reasoning: "Bombing raid damaged defenses"}]
            |
            |Example 2 - Destabilization Campaign:
            |Action: "Launch propaganda to destabilize territory Y"
            |Effect: Government stability reduced
            |Output: territoryStatChanges: [{territoryName: "Y", diplomacyThreatStat: -20, reasoning: "Propaganda undermined government"}]
            |
            |Example 3 - Territory Fortification:
            |Action: "Fortify defenses in my territory Z"
            |Effect: Military strength increased
            |Output: territoryStatChanges: [{territoryName: "Z", militaryThreatStat: +25, reasoning: "Fortifications strengthened defenses"}]
            |
            |Example 4 - Diplomatic Support:
            |Action: "Provide aid to stabilize territory W"
            |Effect: Government stability improved
            |Output: territoryStatChanges: [{territoryName: "W", diplomacyThreatStat: +20, reasoning: "Aid stabilized government"}]
            |
            |**CRITICAL:**
            |- Use territoryStatChanges field in output for territory stat modifications
            |- Always include territoryName and reasoning
            |- Set militaryThreatStat and/or diplomacyThreatStat as appropriate
            |- Territory stat changes are SEPARATE from actor stat changes
            |
            |##How stats in the game work##
            |${BedrockConfig.gameStatsDescription}
            |
            |##How to add or subtract points##
            |The ratio of how many points to add or remove should be kept within the range specified by the stats
            |description of min and max. Furthermore amount increased and decreased need to be proportionate to how
            |extreme the impact actually is to the player. The context of the result should define this value. Players
            |should not be excessively punished for minor things. Nor should they be given absurd boosts for actions that
            |are not gigantic game changers.
            |
            |Some stats have different levels of potency than others and this should be taken into account.
            |
            |luckPoints: Luck is the most powerful stat in the game. It has the power to instantly reverse the outcome
            |of a given event. When any action that is trying to improve the odds of success in an endeavor. Be it by
            |improving some aspect of the player, or especially trying to gain tactical edges should affect the luck stat.
            |No action should increase or decrease luck greater than 50.
            |
            |reputation: Reputation is affected by political legitimacy and international standing in terms of geo politics
            |or the ability for the player to maintain their power in their nation. It should not be raised or lower by
            |more than 70 points. And 70 is the most extreme outlier possible.
            |
            |might: Might affects military power. It should not be raised or lowered by more than 70 points.
            |
            |wealth: Wealth affects a players ability to research technology, take economic actions, and improve themselves
            |and their nation. It should not be raised or lowered more than 60 points by a single given action.
            |
            |militaryReadiness, legitimacy, stagnation: Use incremental changes (e.g., +/- 15) for these primary decay stats for TARGETS. 
            |Higher stagnation is WORSE for the character.
            |
            |##OUTPUT FORMAT: MultiActorStatChanges##
            |
            |You MUST output a 'MultiActorStatChanges' object with this structure:
            |
            |```json
            |{
            |  "changes": {
            |    "Player Name 1": {
            |      "luckPoints": 0,
            |      "reputation": 0,
            |      "might": 0,
            |      "wealth": 0,
            |      "militaryReadiness": 0,
            |      "legitimacy": 0,
            |      "stagnation": 0
            |    },
            |    "Player Name 2": {
            |      "luckPoints": 0,
            |      "reputation": 0,
            |      ...
            |    }
            |  },
            |  "territoryStatChanges": [
            |    {
            |      "territoryName": "Territory Name",
            |      "militaryThreatStat": 0,
            |      "diplomacyThreatStat": 0,
            |      "reasoning": "Explanation"
            |    }
            |  ]
            |}
            |```
            |
            |**CRITICAL RULES:**
            |- The "changes" field is a MAP where keys are character names (Players or NPCs)
            |- Include ALL affected characters in the changes map (active player, targets, cooperators, etc.)
            |- Each character gets their own StatBuff entry in the map
            |- Use exact character names from the context (case-sensitive)
            |- If only one character is affected, still use the map format with one entry
            |- Include territoryStatChanges array for any territory stat modifications
            |- Set unused stats to 0 (don't omit them)
            |
            |**Examples:**
            |
            |Single Actor (Active Player Only):
            |```json
            |{
            |  "changes": {
            |    "Commander Shepard": {
            |      "luckPoints": 15,
            |      "reputation": 20,
            |      "might": 10,
            |      "wealth": 0,
            |      "militaryReadiness": 0,
            |      "legitimacy": 0,
            |      "stagnation": 0
            |    }
            |  },
            |  "territoryStatChanges": []
            |}
            |```
            |
            |Multi-Actor (Hostile Action with Target Debuff):
            |```json
            |{
            |  "changes": {
            |    "Commander Shepard": {
            |      "luckPoints": 10,
            |      "reputation": 15,
            |      "might": 0,
            |      "wealth": 0,
            |      "militaryReadiness": 0,
            |      "legitimacy": 0,
            |      "stagnation": 0
            |    },
            |    "The Illusive Man": {
            |      "luckPoints": 0,
            |      "reputation": 0,
            |      "might": 0,
            |      "wealth": 0,
            |      "militaryReadiness": 0,
            |      "legitimacy": -15,
            |      "stagnation": 0
            |    }
            |  },
            |  "territoryStatChanges": []
            |}
            |```
            |
            |Multi-Actor (Friendly Cooperation):
            |```json
            |{
            |  "changes": {
            |    "Player A": {
            |      "luckPoints": 0,
            |      "reputation": 0,
            |      "might": 25,
            |      "wealth": 0,
            |      "militaryReadiness": 0,
            |      "legitimacy": 0,
            |      "stagnation": 0
            |    },
            |    "Player B": {
            |      "luckPoints": 0,
            |      "reputation": 0,
            |      "might": 15,
            |      "wealth": 0,
            |      "militaryReadiness": 0,
            |      "legitimacy": 0,
            |      "stagnation": 0
            |    }
            |  },
            |  "territoryStatChanges": []
            |}
            |```
            |
            |${BedrockConfig.antiCensorship}
        """.trimMargin())

        autoInjectContext("""You have been provided with extra context.
            |'player stats' is the stats of the active player.
            |'play_type_context' contains the detected play type (Military/Diplomatic/Research/Summit) and success status.
            |'target stats' (if present) is the stats of the primary target.
            |'known NPCs' is a list of existing characters.
            |'judge result' is the success/failure of the turn.
            |'classified resources' contains abstract resources that need stat buffs.
            |'action_intent' indicates if the action is Hostile or Friendly.
            |'target_data' contains the target type (Player, Npc, Territory, etc.) and target names.
        """.trimMargin())

        setPreInitFunction {
            Logger.debug(LogCategory.SYSTEM, "Judge: statChangePipe.setPreInitFunction entry")
            val parentPipe = it.currentPipe
            val pipelines = parentPipe?.getPipelinesFromInterface()
            val abstractResources = if(pipelines?.isNotEmpty() == true)
            {
                val parentPipeline = pipelines[0]
                val classified = parentPipeline.pipeMetaData["classified resources"] as? ClassifiedResources
                val resources = classified?.abstract ?: emptyList()

                if(resources.isNotEmpty())
                {
                    it.text = """
                        ABSTRACT RESOURCES TO GRANT STAT BUFFS:
                        ${resources.joinToString(", ")}

                        Assess stat changes for all involved characters, including buffs for the abstract resources listed above.
                    """.trimIndent()
                }
                else
                {
                    it.text = "Assess stat changes for all involved characters."
                }
                resources
            }
            else
            {
                it.text = "Assess stat changes for all involved characters."
                emptyList()
            }

            // Check if this is a Summit play and inject Summit-specific stat buff instructions
            val summitContext = readSummitContextFromBank()
            if(summitContext != null && summitContext.isSummit)
            {
                val respondingPlayers = summitContext.responses.keys.toList()
                val nonRespondingPlayers = summitContext.participants.filter { it !in respondingPlayers }

                val summitInstructions = """
                    |##SUMMIT COORDINATION STAT BUFFS##
                    |
                    |This is a SUMMIT COORDINATION turn. Apply these stat buffs:
                    |
                    |**Responding Players** (${respondingPlayers.joinToString(", ")}):
                    |- Base buff: +50 reputation, +50 legitimacy
                    |- Note: All stat buffs use fixed values — do not apply quality modifiers.

                    |**Non-Responding Players** (${nonRespondingPlayers.joinToString(", ")}):
                    |- Base buff: +50 might, +50 militaryReadiness
                    |
                    |Include ALL summit participants in the MultiActorStatChanges output.
                """.trimMargin()

                it.text = if(it.text.isNotBlank()) {
                    "${it.text}\n\n$summitInstructions"
                } else {
                    summitInstructions
                }
                Logger.info(LogCategory.GENERAL, "[SUMMIT_JUDGE] Injecting Summit stat buff instructions for ${summitContext.participants.size} participants")
            }

            Logger.debug(LogCategory.SYSTEM, "statChangePipe preInit preparing buff prompt with ${abstractResources.size} abstract resources")
            Logger.debug(LogCategory.SYSTEM, "Judge: statChangePipe.setPreInitFunction success")
        }

        setTransformationFunction {
            Logger.debug(LogCategory.SYSTEM, "Judge: statChangePipe.setTransformationFunction entry")
            val actorChanges = extractJson<MultiActorStatChanges>(it.text) ?: return@setTransformationFunction it
            Logger.debug(LogCategory.GENERAL, "statChangePipe processing ${actorChanges.changes.size} actor stat entries")
            Logger.info(
                LogCategory.GENERAL,
                "statChangePipe outcome summary: actors=${actorChanges.changes.keys.joinToString(", ")}, territoryChanges=${actorChanges.territoryStatChanges.size}"
            )
            
            // Get abstract resources to create buff mappings
            val parentPipe = it.currentPipe
            val pipelines = parentPipe?.getPipelinesFromInterface()
            val abstractResources = if(pipelines?.isNotEmpty() == true)
            {
                val parentPipeline = pipelines[0]
                val classified = parentPipeline.pipeMetaData["classified resources"] as? ClassifiedResources
                classified?.abstract ?: emptyList()
            }
            else
            {
                emptyList()
            }
            
            // Store abstract resource buff mappings for GameHistory
            if(abstractResources.isNotEmpty() && pipelines?.isNotEmpty() == true)
            {
                val parentPipeline = pipelines[0]
                val playerName = (parentPipeline.pipeMetaData["playerStats"] as? Player)?.name ?: ""
                val playerBuff = actorChanges.changes[playerName]
                    
                if(playerBuff != null)
                {
                    val abstractBuffs = abstractResources.map { resourceName ->
                        AbstractResourceBuff(resourceName = resourceName, statBuff = playerBuff)
                    }
                    parentPipeline.pipeMetaData["abstract buffs"] = abstractBuffs
                    Logger.info(LogCategory.GENERAL, "Stored ${abstractBuffs.size} abstract resource buff mappings")
                }
                else
                {
                    val loggedPlayerName = if(playerName.isBlank()) "<unknown>" else playerName
                    Logger.warn(LogCategory.GENERAL, "Abstract resources (${abstractResources.joinToString(", ")}) provided but no stat buff found for player '$loggedPlayerName'; abstract buff metadata not stored.")
                }
            }
            
            actorChanges.changes.forEach { (name, buff) ->
                val targetPlayer = WorldManager.world.findPlayerByName(name)
                val targetNpc = if(targetPlayer == null) WorldManager.world.findNpcByName(name) else null

                Logger.debug(
                    LogCategory.GENERAL,
                    "statChangePipe applying to $name: luck=${buff.luckPoints}, reputation=${buff.reputation}, might=${buff.might}, wealth=${buff.wealth}, militaryReadiness=${buff.militaryReadiness}, legitimacy=${buff.legitimacy}, stagnation=${buff.stagnation}"
                )

                WorldManager.worldMutex.withLock {
                    if(targetPlayer != null)
                    {
                        targetPlayer.wealth += buff.wealth
                        targetPlayer.might += buff.might
                        targetPlayer.reputation += buff.reputation
                        targetPlayer.luckPoints += buff.luckPoints
                        targetPlayer.militaryReadiness = (targetPlayer.militaryReadiness + buff.militaryReadiness).coerceIn(0, 100)
                        targetPlayer.legitimacy = (targetPlayer.legitimacy + buff.legitimacy).coerceIn(0, 100)
                        targetPlayer.stagnation = (targetPlayer.stagnation + buff.stagnation).coerceIn(0, 100)
                        
                        // Cap buffable stats
                        targetPlayer.wealth = targetPlayer.wealth.coerceAtMost(250)
                        targetPlayer.might = targetPlayer.might.coerceAtMost(250)
                        targetPlayer.reputation = targetPlayer.reputation.coerceAtMost(250)
                        targetPlayer.luckPoints = targetPlayer.luckPoints.coerceAtMost(100)
                    }
                    else if(targetNpc != null)
                    {
                        // NPCs also have these stats
                        targetNpc.militaryReadiness = (targetNpc.militaryReadiness + buff.militaryReadiness).coerceIn(0, 100)
                        targetNpc.legitimacy = (targetNpc.legitimacy + buff.legitimacy).coerceIn(0, 100)
                        targetNpc.stagnation = (targetNpc.stagnation + buff.stagnation).coerceIn(0, 100)
                        // Assets/Resources handled via Results, but we can add meta-stats if NPCs have them
                    }
                }
                Logger.info(LogCategory.GENERAL, "Applied stat changes for $name from Judge.")
            }

            // Apply territory stat changes
            if(actorChanges.territoryStatChanges.isNotEmpty())
            {
                Logger.info(LogCategory.SYSTEM, "[TERRITORY_STATS] Applying ${actorChanges.territoryStatChanges.size} territory stat changes from statChangePipe")
                actorChanges.territoryStatChanges.forEach { change ->
                    Logger.debug(
                        LogCategory.GENERAL,
                        "[TERRITORY_STATS] ${change.territoryName}: militaryThreatStat=${change.militaryThreatStat}, diplomacyThreatStat=${change.diplomacyThreatStat}, reason=${change.reasoning}"
                    )
                }
                WorldManager.applyTerritoryStatChanges(actorChanges.territoryStatChanges)
            }

            // Store stat changes in pipeline metadata for GameHistory
            if(pipelines?.isNotEmpty() == true)
            {
                val parentPipeline = pipelines[0]
                parentPipeline.pipeMetaData["actor stat changes"] = actorChanges
                Logger.info(LogCategory.GENERAL, "Stored stat changes for ${actorChanges.changes.size} actor(s) in pipeline metadata")
            }

            Logger.debug(LogCategory.SYSTEM, "Judge: statChangePipe.setTransformationFunction success")
            return@setTransformationFunction it
        }


    }

    /**
     * Secondary pipe that inspects the judge results' `assetsGained` list and splits each entry into tangible vs.
     * abstract buckets so the subsequent stat-change pipe can treat abstract gains as buffs instead of inventory.
     */
    val resourceClassificationPipe = BedrockMultimodalPipe().apply {
        useConverseApi()
        setRegion("us-west-2")
        // setServiceTier(BedrockPriorityTier.Flex)

        setModel(BedrockConfig.qwenCoder30B)
        setTemperature(.6)
        setTopP(.8)
        requireJsonPromptInjection()
        setJsonOutput(ClassifiedResources())
        allowEmptyContentObject()
        pullPipelineContext()
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        setPipeName("resource classification pipe")
        setReasoningPipe(BedrockConfig.structuredCotBuilder(depthLevel = ReasoningDepth.Med, durationLevel = ReasoningDuration.Short))

        setSystemPrompt("""You are a resource classification agent. Your job is to classify each resource 
            |as either TANGIBLE (actionable/usable), ABSTRACT (non-actionable concept), or NPC (character).
            |
            |##CRITICAL FILTERING RULES##
            |
            |BEFORE classifying, REMOVE these from assetsGained:
            |1. Resources that belong to OPPONENTS - see detailed rules below
            |2. Useless abstract concepts (memes, trends, viral movements, cultural fads)
            |3. Event names or incident titles
            |4. Narrative embellishments without gameplay value
            |5. Consequences or reactions (not actual resources)
            |
            |##OPPONENT RESOURCE OWNERSHIP RULES##
            |
            |A resource belongs to an OPPONENT if:
            |1. **Narrative explicitly states opponent ownership:**
            |   - "The Illusive Man's fleet", "Cerberus forces", "enemy equipment"
            |   - "belonging to [opponent]", "owned by [opponent]", "under [opponent] control"
            |
            |2. **Resource was used AGAINST the player:**
            |   - Weapons/units that attacked the player
            |   - Defenses that blocked the player
            |   - Resources opponent deployed in counter-response
            |
            |3. **Resource remains in opponent territory/control:**
            |   - Located in opponent's base/territory at turn end
            |   - Guarded by opponent forces
            |   - Player never captured/seized it
            |
            |4. **Player's action explicitly FAILED to capture it:**
            |   - "attempted to steal but failed"
            |   - "tried to capture but was repelled"
            |   - Turn outcome shows failure to acquire
            |
            |**Player DOES own opponent's resource if:**
            |- Player successfully captured/stole/seized it (narrative confirms)
            |- Opponent's subordinate defected to player
            |- Resource was abandoned and player claimed it
            |- Turn outcome explicitly awards it to player
            |- Resource is in player's possession at turn end
            |
            |**When in doubt:** If narrative doesn't clearly show player taking possession, assume opponent still owns it.
            |
            |##NPC RESOURCES (add to 'npcs' list)##
            |**CRITICAL: Only NPCs created/hired/summoned by the player THIS TURN**
            |
            |Add to 'npcs' list if:
            |- Player hired/summoned/created them THIS TURN
            |- Player's subordinates hired them THIS TURN (subordinate by extension)
            |
            |**SUBORDINATE = RESOURCE:**
            |All NPCs in 'npcs' list are SUBORDINATE and will be added as player resources.
            |
            |##NPC DEFEAT STATUS DETECTION##
            |
            |For each NPC, determine if they are DEFEATED (unusable) at turn end.
            |
            |**Mark isDefeated=TRUE if NPC is incapacitated/unusable:**
            |
            |1. **Dead/Destroyed:**
            |   - "destroyed", "killed", "died", "dead", "eliminated", "annihilated", "executed", "slain"
            |   - "immediately destroyed by", "killed by", "died during"
            |
            |2. **Captured by enemy:**
            |   - "captured by [enemy]", "imprisoned by [enemy]", "taken prisoner by [enemy]"
            |   - "held captive", "detained by [opponent]"
            |
            |3. **Physically incapacitated:**
            |   - "comatose", "vegetative state", "unconscious", "paralyzed"
            |   - "critically wounded", "incapacitated", "disabled"
            |
            |4. **Rendered completely non-functional:**
            |   - "reduced to vegetative state"
            |   - "mind destroyed", "brain dead"
            |   - "completely incapacitated"
            |
            |**Mark isDefeated=FALSE if:**
            |- NPC is alive, conscious, and physically capable at turn end
            |- NPC can still act, move, or respond to commands
            |- No incapacitation language present
            |
            |**IMPORTANT:** 
            |- Mission failure does NOT mean defeated (incompetent ≠ defeated)
            |- Supernatural manifestations AFTER death = still defeated (ghost ≠ alive)
            |- Defeated NPCs are STILL granted as resources, just marked unusable
            |- Player must find way to revive/rescue/heal defeated NPCs to use them
            |
            |**Subordinate exceptions (REMOVE entirely, do NOT grant):**
            |1. Deliberate betrayal (malicious, intentional)
            |2. Sabotage (intentional harm to player)
            |3. Intentional disobedience (deliberate, willful refusal)
            |4. Captured AND turned against player (now serves enemy)
            |
            |Do NOT add:
            |- NPCs introduced by story (not created by player)
            |- Opponent's subordinates (unless defected to player)
            |- NPCs from previous turns
            |
            |##TANGIBLE RESOURCES (add to 'tangible' list)##
            |These are physical, actionable items owned by the player:
            |- Powers and abilities granted to player
            |- Physical objects player acquired
            |- Anonymous military units (without named commanders)
            |- Facilities player built/captured
            |- Equipment player obtained
            |- **Technologies, Blueprints, and Research Plans** (treat these as tangible assets)
            |- NOT opponent's equipment (see ownership rules above)
            |- NOT background world elements
            |
            |**Named individuals/humans go in 'npcs' list first, then are auto-added here as resources**
            |
            |##ABSTRACT RESOURCES (add to 'abstract' list)##
            |These are non-physical benefits that provide real value but cannot be directly used. Think of them as atmospheric "auras"—they just are.
            |If the narrative describes the resource being leveraged, activated, used, or otherwise placed into a verb-based action, treat it as tangible instead.
            |- Agreements and treaties (trade agreements, peace treaties, non-aggression pacts)
            |- Political arrangements (political marriages, alliances, diplomatic accords)
            |- Strategic concepts (doctrines, strategies, tactical frameworks)
            |- Rights and privileges (trade rights, passage rights, mining rights)
            |- Intangible benefits (reputation boosts, legitimacy, influence)
            |- NOT useless memes or trends
            |- NOT event names
            |
            |##RESOURCE IDENTIFICATION RULES##
            |
            |**STRICT CATEGORIZATION MANDATE:**
            |Every resource you identify in the 'classifications' list MUST also be added to exactly one of the three category lists:
            |1. **tangible**: For physical items, weapons, equipment, facilities, and named individuals (NPCs).
            |2. **abstract**: For treaties, alliances, concepts, and intangible benefits.
            |3. **npcs**: For all named individuals/characters hired or acquired this turn.
            |
            |**CRITICAL:** If you leave 'tangible', 'abstract', and 'npcs' empty, the player will receive NO resources. You MUST populate these lists based on your classifications.
            |
            |##CLASSIFICATION REQUIREMENTS##
            |For each resource, provide:
            |1. resourceName: The name of the resource
            |2. isTangible: true/false
            |3. reasoning: WHY this is a resource and WHO owns it (verify ownership carefully)
            |4. description: What this resource is and what it does (2-3 sentences)
            |5. owner: The player/NPC who owns this (verify from narrative)
            |6. isDefeated: true if NPC is dead/captured/incapacitated (NPCs only)
            |7. defeatReason: Brief explanation of incapacitation (NPCs only, if defeated)
            |
            |If reasoning shows opponent ownership or invalid resource type, EXCLUDE it.
            |
            |Maximum 5 resources total unless extraordinary circumstances.
            |
            |For each resource in assetsGained, classify it and provide complete information.
            |NPCs will be added to world.npc list AND as Subordinate resources.
            |Abstract resources will grant stat buffs instead of being added to inventory.
            |
            |${BedrockConfig.antiCensorship}
        """.trimMargin())

        autoInjectContext("""You have been provided with the judge results.
            |'judge result' contains the Results object with assetsGained list to classify.
        """.trimMargin())

        setPreInitFunction {
            Logger.debug(LogCategory.SYSTEM, "Judge: resourceClassificationPipe.setPreInitFunction entry")
            val parentPipe = it.currentPipe
            val pipelines = parentPipe?.getPipelinesFromInterface()
            if(pipelines?.isNotEmpty() == true)
            {
                val parentPipeline = pipelines[0]
                val judgeResult = parentPipeline.pipeMetaData["judge result"] as? Results
                if(judgeResult != null)
                {
                    it.text = """
                        RESOURCES TO CLASSIFY:
                        ${judgeResult.assetsGained.joinToString("\n")}
                    """.trimIndent()
                }
                else
                {
                    it.text = "No resources to classify"
                }
            }
            else
            {
                it.text = "No resources to classify"
            }
            Logger.debug(LogCategory.SYSTEM, "Judge: resourceClassificationPipe.setPreInitFunction success")
        }

        setTransformationFunction {
            Logger.debug(LogCategory.SYSTEM, "Judge: resourceClassificationPipe.setTransformationFunction entry")
            val classifications = extractJson<ClassifiedResources>(it.text) ?: ClassifiedResources()
            
            // AUTO-HEAL: If the LLM filled 'classifications' but left the bucket lists empty, populate them now.
            if (classifications.tangible.isEmpty() && classifications.abstract.isEmpty() && classifications.npcs.isEmpty()) {
                Logger.info(LogCategory.SYSTEM, "resourceClassificationPipe: AUTO-HEALING empty category lists from classifications")
                classifications.classifications.forEach { c ->
                    if (c.isTangible) {
                        classifications.tangible.add(c.resourceName)
                    } else {
                        classifications.abstract.add(c.resourceName)
                    }
                }
            }

            // Step 1: Move humans from tangible to npcs list
            val humansInTangible = classifications.tangible.filter { resourceName ->
                val classification = classifications.classifications.find { it.resourceName == resourceName }
                val reasoning = classification?.reasoning?.lowercase() ?: ""
                val description = classification?.description?.lowercase() ?: ""
                
                reasoning.contains("person") || reasoning.contains("human") || 
                reasoning.contains("individual") || reasoning.contains("hired") ||
                description.contains("person") || description.contains("human")
            }
            
            classifications.npcs.addAll(humansInTangible)
            classifications.tangible.removeAll(humansInTangible)
            
            // Step 2: Add all NPCs back to tangible (Subordinates are resources)
            classifications.npcs.forEach { npc ->
                if (!classifications.tangible.contains(npc)) {
                    classifications.tangible.add(npc)
                }
            }
            
            Logger.debug(LogCategory.GENERAL, "resourceClassificationPipe: moved ${humansInTangible.size} humans to npcs list, added ${classifications.npcs.size} NPCs as resources. Final: ${classifications.tangible.size} tangibles, ${classifications.abstract.size} abstracts, ${classifications.npcs.size} NPC entries")
            
            try
            {
                val parentPipe = it.currentPipe
                val pipelines = parentPipe?.getPipelinesFromInterface()
                if(pipelines?.isNotEmpty() == true)
                {
                    val parentPipeline = pipelines[0]
                    
                    // Store classifications in metadata
                    parentPipeline.pipeMetaData["classified resources"] = classifications
                    
                    // Also update the Results object with classifications
                    val judgeResult = parentPipeline.pipeMetaData["judge result"] as? Results
                    if(judgeResult != null)
                    {
                        judgeResult.classifiedResources = classifications
                    }
                    
                    Logger.info(LogCategory.GENERAL, "Classified ${classifications.tangible.size} tangible and ${classifications.abstract.size} abstract resources")
                }
                else
                {
                    Logger.warn(LogCategory.SYSTEM, "resourceClassificationPipe could not attach to parent pipeline; classifications only exist in current context")
                }
            }
            catch (e: Exception)
            {
                Logger.error(LogCategory.SYSTEM, "Failed to store classified resources: ${e.message}")
            }

            Logger.debug(LogCategory.SYSTEM, "Judge: resourceClassificationPipe.setTransformationFunction success")
            return@setTransformationFunction it
        }
    }

    return judge.apply {
        if(knownOutcome == null)
        {
            add(passOrFailPipe)
        }
        add(gainsAndLossesPipe)
        add(resourceClassificationPipe)
        add(karmaPipe)
        add(statChangePipe)

        pipeMetaData["playerStats"] = player ?: Player()

        /**
         * Grab required game data at runtime to ensure that it's fresh and bind directly to our pipeline
         * context.
         */
        setPreValidationFunction { context, miniBank, content ->
            Logger.debug(LogCategory.SYSTEM, "Judge: Pipeline.setPreValidationFunction entry")
            // Fetch runtime player and game data.
            val userPrompt = content.text
            val playerStatsAsJson = serialize(pipeMetaData["playerStats"] as Player)
            val knownNpcs = serialize(WorldManager.world.npc.map { npc ->
                mapOf("name" to npc.name, "territories" to npc.capturedTerritory.map { it.name })
            })
            val worldData = serialize(WorldManager.world)
            val (currentTurnContext, previousTurnContext) = captureJudgeHistoryContexts()

            val contextPairs = mutableListOf(
                "user prompt" to userPrompt,
                "current turn" to currentTurnContext,
                "previous turn" to previousTurnContext,
                "player stats" to playerStatsAsJson,
                "known NPCs" to knownNpcs,
                "world" to worldData
            )

            // Inject target detection results if provided
            if (targetData != null)
            {
                contextPairs.add("target_data" to serialize(targetData))
            }
            if (actionIntent != null)
            {
                contextPairs.add("action_intent" to actionIntent)
            }

            contextPairs.forEach { (name, text) ->
                val newWindow = ContextWindow().apply {
                    contextElements.add(text)
                }

                miniBank.contextMap[name] = newWindow
            }

            Logger.debug(
                LogCategory.SYSTEM,
                "Judge pipeline seeded contexts " +
                        "(userPrompt len=${userPrompt.length}, currentTurn len=${currentTurnContext.length}, previousTurn len=${previousTurnContext.length}, " +
                        "player=${(pipeMetaData["playerStats"] as Player).name}, knownNPCs=${WorldManager.world.npc.size}, mapTiles=${WorldManager.world.mapTiles.size})"
            )

            val targetDataWindow = miniBank.contextMap["target_data"]
            if(targetDataWindow != null)
            {
                Logger.debug(LogCategory.SYSTEM, "Judge has access to target_data")
            }

            // If we have a known outcome, we inject it directly into the context bank now
            // so that the Gains/Losses pipe can read it as if the Pass/Fail pipe produced it.
            if(knownOutcome != null)
            {
                val outcomeObj = `Victory?`(isVictory = knownOutcome)
                val contextWindow = ContextWindow().apply {
                    addLoreBookEntry("victory", serialize(outcomeObj))
                }
                ContextBank.emplaceWithMutex(JUDGE_OUTCOME_CONTEXT, contextWindow)
                Logger.info(LogCategory.GENERAL, "Judge Pipeline: Injected Known Outcome (Success=$knownOutcome)")
            }
            Logger.debug(LogCategory.SYSTEM, "Judge: Pipeline.setPreValidationFunction success")
        }
    }
}

private fun captureJudgeHistoryContexts(): Pair<String, String>
{
    val history = WorldManager.history
    val currentEntry = history.lastOrNull()
    val previousEntry = if (history.size >= 2) history[history.size - 2] else null
    return Pair(
        serializeHistoryEntryOrFallback(currentEntry, "current turn"),
        serializeHistoryEntryOrFallback(previousEntry, "previous turn")
    )
}

private fun serializeHistoryEntryOrFallback(entry: GameHistory?, label: String): String
{
    if (entry == null)
    {
        Logger.debug(LogCategory.SYSTEM, "Judge pipeline missing $label context; injecting sentinel")
        return "NO_HISTORY_AVAILABLE"
    }
    return serialize(entry)
}

private fun readPlayTypeContextFromBank(): PlayTypeContext?
{
    val window = ContextBank.getContextFromBank("play_type_context") ?: return null
    val data = window.contextElements.getOrNull(0) ?: return null
    return extractJson<PlayTypeContext>(data)
}

internal fun enforceMandatoryTerritoryCapture(
    results: Results,
    playerName: String,
    targetData: ActionTargetTypeObj?,
    playTypeContext: PlayTypeContext?,
    wasSuccessful: Boolean,
    actionIntent: String
)
{
    if(playerName.isBlank() || actionIntent != "Hostile")
    {
        return
    }

    // CRITICAL BUG FIX: If target territory is UNOWNED (no ruler) and statVictory=true,
    // force-capture it REGARDLESS of narrativeOutcome or wasSuccessful flag.
    // The gains/losses LLM can incorrectly return territoryGained=[] for unowned territories
    // when the narrative uses "softened victory" framing, but math says the player won.
    // This is the mechanical override to prevent the LLM from denying a deserved capture.
    val targetName = targetData?.targets?.firstOrNull { it.isNotBlank() } ?: return
    val territory = WorldManager.world.mapTiles.findTerritoryByName(targetName) ?: return
    val isUnowned = territory.ruler.isBlank()
    
    if(isUnowned)
    {
        Logger.info(LogCategory.SYSTEM, "[JUDGE_METADATA] UNOWNED TERRITORY DETECTED: '${territory.name}' has no ruler/controller")
        Logger.info(LogCategory.SYSTEM, "[JUDGE_METADATA] wasSuccessful=$wasSuccessful, checking if capture is required...")
        // Check if GameMath computed statVictory (finalSuccess=true) by reading the math outcome from context
        val mathOutcomeWindow = ContextBank.getContextFromBank("math_outcome")
        val mathOutcomeJson = mathOutcomeWindow?.contextElements?.getOrNull(0)
        val mathOutcome = mathOutcomeJson?.let { extractJson<MathOutcome>(it) }
        val statVictory = mathOutcome?.statVictory ?: false
        
        Logger.info(LogCategory.SYSTEM, "[JUDGE_METADATA] statVictory from math_outcome=$statVictory")
        
        if(statVictory && !results.territoryGained.any { it.equals(territory.name, ignoreCase = true) })
        {
            results.territoryGained.add(territory.name)
            Logger.info(LogCategory.SYSTEM, "[JUDGE_METADATA] FORCE-CAPTURE: Added unowned territory '${territory.name}' to territoryGained (statVictory=true, no counterplay possible)")
            return
        }
        else if(!statVictory)
        {
            Logger.info(LogCategory.SYSTEM, "[JUDGE_METADATA] Allowing normal flow for unowned territory (statVictory=false)")
        }
    }

    val playType = PlayType.values().firstOrNull { it.name == playTypeContext?.playType } ?: PlayType.Military
    if(playType != PlayType.Military)
    {
        return
    }

    if(targetData?.type != ActionTargetType.Territory)
    {
        return
    }

    if(results.territoryGained.any { it.equals(territory.name, ignoreCase = true) })
    {
        return
    }

    // Normal path: force capture if wasSuccessful and not already captured
    if(wasSuccessful)
    {
        results.territoryGained.add(territory.name)
        Logger.info(LogCategory.SYSTEM, "[JUDGE_METADATA] Forced territory capture for $playerName: ${territory.name}")
    }
}

internal fun enforceHiddenResourceForCapturedTerritories(
    results: Results,
    player: Player?,
    playerName: String,
    playTypeContext: PlayTypeContext?,
    wasSuccessful: Boolean,
    actionIntent: String
)
{
    if(player == null || playerName.isBlank() || !wasSuccessful || actionIntent != "Hostile")
    {
        return
    }

    val playType = PlayType.values().firstOrNull { it.name == playTypeContext?.playType } ?: PlayType.Military
    if(playType != PlayType.Military)
    {
        return
    }

    val ownedNames = player.resources.map { it.name.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
    val claimedNames = results.assetsGained
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() }
        .toMutableSet()

    results.territoryGained.forEach { territoryName ->
        val territory = WorldManager.world.mapTiles.findTerritoryByName(territoryName) ?: return@forEach
        val resourceName = territory.resource.name.trim()
        if(resourceName.isBlank())
        {
            return@forEach
        }

        val normalized = resourceName.lowercase()
        if(normalized in claimedNames || normalized in ownedNames)
        {
            return@forEach
        }

        results.assetsGained.add(resourceName)
        claimedNames.add(normalized)
        Logger.info(LogCategory.SYSTEM, "[JUDGE_METADATA] Awarded hidden resource '$resourceName' to $playerName for capturing ${territory.name}")
    }
}

/**
 * Helper to read the outcome (success/fail) from the context bank.
 * This allows the gains/losses pipe to know what happened without direct pipe coupling.
 *
 * @return True if the action was successful, false otherwise.
 */
private fun readJudgeOutcomeFromContext(): Boolean
{
    val window = ContextBank.getContextFromBank(JUDGE_OUTCOME_CONTEXT) ?: return false
    val entry = window.findLoreBookEntry("victory") ?: return false
    val result = extractJson<`Victory?`>(entry.value)
    return result?.isVictory ?: false
}

/**
 * Helper to read the SummitContext from the context bank.
 *
 * @return SummitContext if present, null otherwise.
 */
private fun readSummitContextFromBank(): SummitContext?
{
    val window = ContextBank.getContextFromBank("summit_context") ?: return null
    val entry = window.contextElements.getOrNull(0) ?: return null
    return extractJson<SummitContext>(entry)
}