package agent.builders.gatherContext

import Defaults.reasoning.ReasoningDepth
import Defaults.reasoning.ReasoningDuration
import agent.builders.validateAction.buildBranchFailureAgent
import agent.builders.validateAction.buildBranchPipeFromTemplate
import agent.builders.validateAction.buildTPipeValidatorPipe
import bedrockPipe.BedrockMultimodalPipe
import bedrockPipe.BedrockPriorityTier
import com.TTT.Context.ContextBank
import com.TTT.Context.ContextWindow
import com.TTT.Context.MiniBank
import com.TTT.Pipeline.Pipeline
import com.TTT.Util.deserialize
import com.TTT.Util.extractJson
import com.TTT.Util.serialize
import enums.NpcType
import gameState.WorldManager
import gameState.TimeProvider
import globals.BedrockConfig
import globals.BedrockConfig.explicitCotBuilder
import globals.BedrockConfig.processFocusedBuilder
import globals.BedrockConfig.structuredCotBuilder
import io.netty.util.ResourceLeak
import kotlinx.serialization.Serializable
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import structs.Npc
import structs.Resource
import structs.ui.ActionHistory
import structs.ui.ActionHistoryEvent
import structs.ui.GameEventType
import structs.ui.NpcEventMetadata
import structs.ui.ResourceEventMetadata
import kotlin.collections.iterator

@Serializable
data class NpcHistoryUpdate(
    var npcName: String = "",
    var newHistory: String = ""
)

@Serializable
data class NewCharacter(
    var name: String = "",
    var createdBy: String = "" //Name of the player or npc that introduced them, or 'Story' if background.
)

@Serializable
data class NewCharacterList(
    var characters: MutableList<NewCharacter> = mutableListOf()
)

@Serializable
data class CharacterDescription(
    var name: String = "",
    var description: String = "",
    var history: String = "",
    var personality: String = "",
    var abilities: String = "",
    var assets: String = "",
    var ownedTerritory: MutableList<String> = mutableListOf()
)

//Use this data class and not the above one when passing onto an llm.
@Serializable
data class  CharacterDescriptionArray(
    var characters: MutableList<CharacterDescription> = mutableListOf()
)

/**
 * Data class to classify an NPC. Is internal to a larger array style data class is intended to be used in
 * the acutal llm call.
 */
@Serializable
data class NpcClassification(
    var name: String = "",
    var type: NpcType = NpcType.Passive,
    var createdBy: String = "",
    var isDefeated: Boolean = false
)

//Use this class in your llm calls.
@Serializable
data class NpcListWrapper(
    var npcs: MutableList<Npc> = mutableListOf()
)

@Serializable
data class PlayerListWrapper(
    var players: MutableList<structs.Player> = mutableListOf()
)

@Serializable
data class NpcResourceMapWrapper(
    var resources: MutableMap<String, MutableList<Resource>> = mutableMapOf()
)

@Serializable
data class StringListWrapper(
    var items: MutableList<String> = mutableListOf()
)

@Serializable
data class NpcHistoryUpdateListWrapper(
    var updates: MutableList<NpcHistoryUpdate> = mutableListOf()
)

@Serializable
data class NpcClassificationArray(
    var npcs: MutableList<NpcClassification> = mutableListOf()
)

@Serializable
data class NpcEscalation(
    var name: String = "",
    var newNpcStatus: NpcType = NpcType.Passive
)

//Use this array as your json output in the agent.
@Serializable
data class NpcEscalationArray(
    var npcs: MutableList<NpcEscalation> = mutableListOf()
)

/**
 * Constructs the multi-stage pipeline that processes NPC changes after each turn.
 *
 * The pipeline:
 * 1. Identifies newly introduced characters and persists them to the shared [ContextBank].
 * 2. Affirms their creator/owner, classifies their NPC tier, and enriches their descriptive lore.
 * 3. Assigns starting resources to the new NPCs, escalates existing candidates, and feeds those results
 *    back into shared context for downstream friend systems (e.g., history reconciliation).
 * 4. Updates any existing NPC resources, captures history mutations, and logs every grant/escalation
 *    so the timeline reflects the turn's changes.
 *
 * The pipeline relies on the `previous turn`, `character list`, and `player list` context keys plus the
 * helper logging functions ([logNpcIntroduction], [logNpcEscalation], [logNpcOwnershipDITL],
 * [revokeNpcOwnershipDITL]) to keep the world state and action history in sync.
 *
 * @return A [Pipeline] wired-up for the NewCharacterScan orchestration.
 */
fun buildNewCharacterScanPipeline (): Pipeline
{
    val newCharacterScanPipeline = Pipeline()

    //Step 1. Identify new npc's that have been introduced.
    val identifyNewNPCPipe = BedrockMultimodalPipe().apply {
        setRegion("us-west-2")
        useConverseApi()
        setServiceTier(BedrockPriorityTier.Flex)
        setModel(BedrockConfig.qwenCoder30B)
        requireJsonPromptInjection()
        setJsonOutput(NewCharacterList())
        allowEmptyContentObject()
        truncateModuleContext()
        forceSaveSnapshot()
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        setTemperature(0.7)
        setTopP(0.4)
        pullGlobalContext()
        setPageKey("previous turn, character list, player list")
        setReasoningPipe(BedrockConfig.structuredCotBuilder(depthLevel = ReasoningDepth.High, durationLevel = ReasoningDuration.Short))
        
        setPreInitFunction { content ->
            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: identifyNewNPCPipe.setPreInitFunction entry")
            val judgeResults = ContextBank.getContextFromBank("judge_results")
            val judgeText = judgeResults?.contextElements?.firstOrNull() ?: ""
            
            if (judgeText.isNotBlank()) {
                content.text = "JUDGE RESULTS: $judgeText\n\n${content.text}"
            }
            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: identifyNewNPCPipe.setPreInitFunction success")
        }
        
        setPreValidationFunction { context, content ->
            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: identifyNewNPCPipe.setPreValidationFunction entry")
            val turnData = content?.miniBankContext?.contextMap?.get("previous turn")?.contextElements?.firstOrNull() ?: ""
            val actionMatch = Regex(""""turnAction":\s*"([^"]+)"""").find(turnData)
            val playerMatch = Regex(""""turnPlayer":\s*"([^"]+)"""").find(turnData)
            
            if (actionMatch != null && playerMatch != null && content != null)
            {
                val action = actionMatch.groupValues[1]
                val player = playerMatch.groupValues[1]
                content.text = """PLAYER ACTION ANALYSIS:
                    |Player: $player
                    |Action: $action
                    |
                    |CRITICAL: Check if this action creates/hires/summons any NPCs.
                    |If yes, those NPCs belong to $player, NOT "Story".
                    |
                    |${content.text}
                """.trimMargin()
                Logger.debug(LogCategory.SYSTEM, "identifyNewNPCPipe pre-validation injected player action analysis for $player with action \"$action\"")
            }
            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: identifyNewNPCPipe.setPreValidationFunction success")
            context
        }
        
        setSystemPrompt("""CRITICAL: Analyze both player action AND narrative to identify all significant NPCs.

            |##CRITICAL: NEVER DETECT PLAYERS AS NPCs##
            |The "player list" contains all player characters. NEVER register anyone from the player list as an NPC.
            |Players are NOT NPCs. If a player name appears in the narrative, IGNORE IT for NPC detection.
            |
            |##NPC DETECTION RULES##
            |
            |**RULE 1: Player Action Creation**
            |If the player's action contains ANY of these patterns, the NPC belongs to the player:
            |- "hires [NPC name]"
            |- "summons [NPC name]"  
            |- "recruits [NPC name]"
            |- "creates [NPC name]"
            |- "employs [NPC name]"
            |- "calls upon [NPC name]"
            |- "brings in [NPC name]"
            |
            |**RULE 2: Narrative Defection/Acquisition**
            |If the narrative contains ANY of these patterns, the NPC belongs to the player:
            |- "[NPC] defected to [player]"
            |- "[NPC] now serves under [player]"
            |- "[NPC] joined [player]"
            |- "[NPC] switched sides to [player]"
            |- "[NPC] pledged allegiance to [player]"
            |- "[NPC] is now under [player]'s command"
            |
            |**RULE 3: Significant Narrative NPCs**
            |An NPC introduced in the narrative should be registered if they meet ALL criteria:
            |1. Named character (not generic "guard" or "shopkeeper")
            |2. Has significant role or capabilities (described with abilities, equipment, or backstory)
            |3. NOT one-shot (doesn't die or exit immediately this turn)
            |4. Likely to appear again (has ongoing relevance to the story)
            |
            |If criteria met but no player ownership detected, set createdBy = "Story"
            |
            |**RULE 4: Insignificant NPCs - DO NOT REGISTER**
            |Skip NPCs that:
            |- Die in the same turn they appear
            |- Exit stage left immediately (leave and won't return)
            |- Are background flavor (unnamed crowd, generic roles)
            |- Have no described capabilities or relevance
            |
            |**RULE 5: NEVER REGISTER PLAYERS**
            |Cross-check every detected NPC name against the "player list".
            |If the name matches ANY player, DO NOT include them in your output.
            |
            |##ANALYSIS ORDER##
            |1. Check PLAYER ACTION for creation verbs → attribute to player
            |2. Check NARRATIVE for defection/acquisition patterns → attribute to player
            |3. Check NARRATIVE for significant named NPCs → evaluate if they meet criteria
            |4. Cross-reference JUDGE RESULTS for resource-granted NPCs
            |5. Cross-check against PLAYER LIST → exclude any matches
            |6. For each NPC found, determine createdBy (player name or "Story")
            |
            |##CRITICAL: DEFECTION DETECTION##
            |When an NPC defects/joins a player in the narrative:
            |- They MUST be registered as an NPC
            |- Set createdBy = [player name they joined]
            |- Even if not in player's original action
            |
            |Example: "Major Turdington defected and now serves under Shepard"
            |→ Register as NPC with createdBy = "Commander Shepard"
            |
            |##JUDGE RESULTS CROSS-REFERENCE##
            |IMPORTANT: Also check the "JUDGE RESULTS" section for any NPCs that were granted
            |as resources. These should be treated as new NPCs even if not prominently featured
            |in the story narrative.
            |
            |Once you have found all such new, significant named characters,
            |identify who introduced them (the player name, the NPC name, or 'Story' if they are background/world elements). 
            |Then create a JSON reflecting these findings.
        """.trimMargin())

        autoInjectContext("""###ADDITIONAL CONTEXT: "previous turn" is the most
            |recent turn of the game. "character list" is the list of already known and named
            |non-player characters. "player list" is the list of player characters. 
        """.trimMargin())

        setFooterPrompt("""The JSON schema you must create is as follows:
            |
            |${BedrockConfig.antiCensorship}
        """.trimMargin())

        setUserPrompt("""Find any new npc's that were introduced. Or return an empty list conforming to your the list
            |inside your json output that you must return.
        """.trimMargin())

        setPipeName("identify new npc pipe")

        setValidatorFunction {
            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: identifyNewNPCPipe.setValidatorFunction entry")
            if(extractJson<NewCharacterList>(it.text) == null)
            {
                Logger.error(LogCategory.SYSTEM, "identify new npc pipe did not provide valid json.")
                return@setValidatorFunction false
            }

            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: identifyNewNPCPipe.setValidatorFunction success")
            return@setValidatorFunction true
        }

        setBranchPipe(
            buildBranchPipeFromTemplate(
                this,
                BedrockConfig.PalmyraX5,
                BedrockConfig.palmyraBudgetSettings
                ).apply {
                    setServiceTier(BedrockPriorityTier.Standard)
                    pullParentPipeContext()
                    setReasoningPipe(BedrockConfig.authorBuilder(BedrockConfig.zetaReasoning).apply {
                        setModel(BedrockConfig.PalmyraX5)
                        setTokenBudget(BedrockConfig.palmyraBudgetSettings)
                    })
            }
        )

        setTransformationFunction {
            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: identifyNewNPCPipe.setTransformationFunction entry")
            val result = extractJson<NewCharacterList>(it.text) ?: NewCharacterList()

            /**
             * If no new npc was found. Then we need to remodel the output and jump straight to the escalation pipe.
             * In the case of the escalation pipe, it pulls the prev turn from context. As such we need to model
             * the input of the content object to just instruct it to do its job.
             */
            if(result.characters.isEmpty())
            {
               Logger.info(LogCategory.SYSTEM, "identifyNewNPCPipe found no new characters to register; forcing escalation path")
               it.jumpToPipe("escalation pipe")
               it.text = """Determine if any npc that currently exists has taken an action this turn that raises their
                   |status and justifies escalating their npc type to a higher level type.
               """.trimMargin()
            }

            /**
             * Result was not empty so we need to start constructing the shared data and storing it as key in the context
             * bank.
             */

            //Start constructing the new npc data.
            val npcList = mutableListOf<Npc>()
            
            // Get player list for filtering (exact name match, case-insensitive)
            val playerNames = WorldManager.world.activePlayers.map { it.name.lowercase() }.toSet()
            
            for(character in result.characters)
            {
                // CODE-BASED CHECK: Block players from being registered as NPCs (exact match only)
                if (playerNames.contains(character.name.lowercase())) {
                    Logger.warn(LogCategory.SYSTEM, "identifyNewNPCPipe blocked player '${character.name}' from being registered as NPC")
                    continue
                }
                
                val newNpc = Npc().apply {
                    name = character.name
                    createdBy = character.createdBy
                }

                npcList.add(newNpc)
            }

            Logger.info(LogCategory.SYSTEM, "identifyNewNPCPipe serialized ${npcList.size} new NPC(s) before writing to context bank")
            //Convert each to json and store each as a context element.
            val newContextWindow = ContextWindow().apply {
                for(npc in npcList)
                {
                    val asJson = serialize(npc)

                    addLoreBookEntry(npc.name, asJson)
                }
            }

            //Write into :kvisionApp:jsBrowserDevelopmentRunour temp workspace as we start to build out this new set of npc data.
            ContextBank.emplaceWithMutex("new chars", newContextWindow)
            Logger.debug(LogCategory.SYSTEM, "identifyNewNPCPipe persisted ${npcList.size} NPC entries to 'new chars' context")
            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: identifyNewNPCPipe.setTransformationFunction success")
            return@setTransformationFunction it
        }
    }

    //Step 2. Define the npc class level if anything was returned.
    val characterIdentifyClassPipe = BedrockMultimodalPipe().apply {
        setRegion("us-west-2")
        useConverseApi()
        setServiceTier(BedrockPriorityTier.Flex)
        setModel(BedrockConfig.qwenCoder30B)
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        requireJsonPromptInjection()
        setJsonInput(NewCharacterList())
        setJsonOutput(NpcClassificationArray())
        pullGlobalContext()
        forceSaveSnapshot()
        setPageKey("previous turn")
        setTemperature(0.6)
        setReasoningPipe(BedrockConfig.explicitCotBuilder(depthLevel = ReasoningDepth.High, durationLevel = ReasoningDuration.Short))
        
        setPreValidationFunction { context, content ->
            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: characterIdentifyClassPipe.setPreValidationFunction entry")
            val turnData = content?.miniBankContext?.contextMap?.get("previous turn")?.contextElements?.firstOrNull() ?: ""
            val actionMatch = Regex(""""turnAction":\s*"([^"]+)"""").find(turnData)
            val playerMatch = Regex(""""turnPlayer":\s*"([^"]+)"""").find(turnData)
            
            if (actionMatch != null && playerMatch != null && content != null)
            {
                val action = actionMatch.groupValues[1]
                val player = playerMatch.groupValues[1]
                content.text = """VALIDATION CHECK:
                    |Original Player Action: "$action" by $player
                    |
                    |Before finalizing classifications, verify:
                    |- If the action shows the player hired/summoned/recruited an NPC, that NPC MUST be createdBy=$player and classified as Subordinate
                    |- Override any incorrect createdBy values if the action clearly shows player ownership
                    |
                    |${content.text}
                """.trimMargin()
                Logger.debug(LogCategory.SYSTEM, "characterIdentifyClassPipe validation prefaced with original action \"$action\" from $player")
            }
            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: characterIdentifyClassPipe.setPreValidationFunction success")
            context
        }
        
        autoInjectContext("""You have been provided with a JSON list containing 
            |newly discovered characters to which you must apply a class.
            |ADDITIONAL CONTEXT: "previous turn" is the most recent turn of gameplay. This contains
            |the existing descriptions of the newly introduced characters.
        """.trimMargin())
        setSystemPrompt("""You have been provided with a list of new characters in JSON format.
            |For each character in the list, compare them against the most recent turn and assign a class.
            |Your job is as follows: in accordance with ${BedrockConfig.characterLevels}, assign each newly introduced character
            |a class. 
            |
            |### OWNER IDENTIFICATION RULE:
            |The 'createdBy' field has already been determined by the previous detection agent.
            |DO NOT change this field unless there is explicit, unambiguous evidence in the narrative
            |that contradicts it (e.g., "actually it was NPC X who summoned them, not the player").
            |
            |CRITICAL: Ownership is determined AT THE MOMENT OF CREATION. If a player hired/summoned/
            |recruited a character THIS TURN, that character belongs to the player as a Subordinate,
            |EVEN IF they betray, act independently, or have hidden agendas IN THE SAME TURN.
            |
            |Future turns may change their status (betrayal, independence), but initial classification
            |is based solely on who introduced them, not their narrative behavior.
            |
            |### CRITICAL CLASSIFICATION RULE:
            |If 'createdBy' is a player name, you MUST classify the character as Subordinate.
            |If 'createdBy' is 'Story', classify based on their role in the narrative.
            |If 'createdBy' is an NPC name, classify based on that NPC's relationship to the character.
            |
            |DO NOT override 'createdBy' or classification based on same-turn narrative events like
            |betrayal, independence, or hidden agendas. The field reflects origin, not loyalty.
            |
            |### NPC DEATH DETECTION:
            |Check if the NPC died in the same turn they were introduced. Look for:
            |- Death keywords: "killed", "died", "destroyed", "defeated", "slain", "perished"
            |- Context: The NPC's name appears near these keywords in the turn narrative
            |- Timing: The death occurs in the SAME turn as their introduction
            |
            |If the NPC died this turn, set isDefeated = true.
            |If the NPC is alive or their status is unclear, set isDefeated = false.
            |
            |Then construct a JSON array that matches each newly introduced character up with
            |its correct corresponding class and its confirmed/corrected creator.
            |
            |${BedrockConfig.antiCensorship}
        """.trimMargin())
        setFooterPrompt("""Return a JSON array with your classifications for each character.
            |The output format is:
        """.trimMargin())
        setPipeName("character identify class pipe")

        setValidatorFunction {
            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: characterIdentifyClassPipe.setValidatorFunction entry")
            if(extractJson< NpcClassificationArray>(it.text) == null)
            {
                Logger.error(LogCategory.SYSTEM, "character identify class pipe did not provide valid json.")
                return@setValidatorFunction false
            }

            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: characterIdentifyClassPipe.setValidatorFunction success")
            return@setValidatorFunction true
        }

        setBranchPipe(
            buildBranchPipeFromTemplate(
                this,
                BedrockConfig.PalmyraX5,
                BedrockConfig.palmyraBudgetSettings
            ).apply {
                setServiceTier(BedrockPriorityTier.Standard)
                setReasoningPipe(BedrockConfig.authorBuilder(BedrockConfig.zetaReasoning).apply {
                    setModel(BedrockConfig.PalmyraX5)
                    setTokenBudget(BedrockConfig.palmyraBudgetSettings)
                })
            }
        )

        setTransformationFunction {
            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: characterIdentifyClassPipe.setTransformationFunction entry")
            val result = extractJson<NpcClassificationArray>(it.text) ?: NpcClassificationArray()

            /**
             * Loop through each, fetch name, grab for temp lorebooks space in the context bank. Then, deserialize back
             * into the npc data being built in progress and use these results to build it out further, and then push
             * it back into the context bank as we move forward with building out the npc data overtime.
             */
            for(npcChange in result.npcs)
            {
                val name = npcChange.name
                val contextWindow = ContextBank.getContextFromBank("new chars")
                val lorebook = contextWindow.findLoreBookEntry(name)

                if(lorebook != null)
                {
                    //Turn back to data class, update type, then push back as json.
                    val npcData = deserialize<Npc>(lorebook.value)
                    if(npcData != null)
                    {
                        val type = npcChange.type
                        npcData.type = type
                        npcData.isDefeated = npcChange.isDefeated
                        
                        // Correct the creator if the classification agent identified a different one.
                        if (npcChange.createdBy.isNotBlank() && npcChange.createdBy != npcData.createdBy) {
                            Logger.info(LogCategory.SYSTEM, "Correcting creator for ${npcData.name} from '${npcData.createdBy}' to '${npcChange.createdBy}'")
                            npcData.createdBy = npcChange.createdBy
                        }

                        val asJson = serialize(npcData)
                        contextWindow.addLoreBookEntry(name, asJson)
                    }
                }

                //Final step emplace the context window back.
                ContextBank.emplaceWithMutex("new chars", contextWindow)
            }
            Logger.debug(LogCategory.SYSTEM, "characterIdentifyClassPipe committed ${result.npcs.size} classification results back to 'new chars'")

            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: characterIdentifyClassPipe.setTransformationFunction success")
            return@setTransformationFunction it
        }
    }

    val descriptionBuilderPipe = BedrockMultimodalPipe().apply {
        setRegion("us-west-2")
        useConverseApi()
        setServiceTier(BedrockPriorityTier.Flex)
        setModel(BedrockConfig.qwenCoder30B)
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        requireJsonPromptInjection()
        setJsonInput(NpcClassificationArray())
        setJsonOutput(CharacterDescriptionArray())
        pullGlobalContext()
        setPageKey("previous turn")
        setTemperature(0.6)
        forceSaveSnapshot()
        setReasoningPipe(BedrockConfig.structuredCotBuilder(depthLevel = ReasoningDepth.Low, durationLevel = ReasoningDuration.Short))
        autoInjectContext("""ADDITIONAL CONTEXT: "previous turn" is the most recent turn of gameplay. This contains
                |the existing descriptions of the newly introduced characters.""")
        setSystemPrompt("""Looking at the list of characters you have been provided and their classifications,
            |now look again at "previous turn"; your job is to apply to each character a robust description that explains
            |who they are and what they are all about.
            |
            |${BedrockConfig.antiCensorship}
        """.trimMargin())
        setMiddlePrompt("""Create a JSON with the specified qualities. Your JSON schema looks like
            |this:""")
        setPipeName("description builder pipe")

        setValidatorFunction {
            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: descriptionBuilderPipe.setValidatorFunction entry")
            if(extractJson<CharacterDescriptionArray>(it.text) == null)
            {
                Logger.error(LogCategory.SYSTEM, "description builder pipe did not provide valid json.")
                return@setValidatorFunction false
            }

            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: descriptionBuilderPipe.setValidatorFunction success")
            return@setValidatorFunction true
        }

        setBranchPipe(
            buildBranchPipeFromTemplate(
                this,
                BedrockConfig.PalmyraX5,
                BedrockConfig.palmyraBudgetSettings
            ).apply {
                setServiceTier(BedrockPriorityTier.Standard)
                setReasoningPipe(BedrockConfig.authorBuilder(BedrockConfig.zetaReasoning).apply {
                    setModel(BedrockConfig.PalmyraX5)
                    setTokenBudget(BedrockConfig.palmyraBudgetSettings)
                })
            }
        )

        setTransformationFunction {
            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: descriptionBuilderPipe.setTransformationFunction entry")
            val result = extractJson<CharacterDescriptionArray>(it.text) ?: CharacterDescriptionArray()
            val npcContextWindow = ContextBank.getContextFromBank("new chars") ?: ContextWindow()

            /**
             * Loop through each result, locate the character in question. And update any new data as we go further
             * in building out new ncp's.
             */
            for(description in result.characters)
            {
                val name = description.name
                val lorebook = npcContextWindow.findLoreBookEntry(name)
                if(lorebook != null)
                {
                    val npc = deserialize<Npc>(lorebook.value)
                    if(npc != null)
                    {
                        npc.description = description.description
                        npc.history = description.history
                        npc.personality = description.personality
                        npc.abilities = description.abilities
                        val asJson = serialize(npc)
                        npcContextWindow.addLoreBookEntry(name, asJson)
                    }
                }
            }

            ContextBank.emplaceWithMutex("new chars", npcContextWindow)
            Logger.debug(LogCategory.SYSTEM, "descriptionBuilderPipe updated ${result.characters.size} lore entries in 'new chars'")
            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: descriptionBuilderPipe.setTransformationFunction success")
            return@setTransformationFunction it
        }
    }

    /**
     * Final step for new NPC's. Define any resources they might start out with.
     */
    val newNpcResourcePipe = BedrockMultimodalPipe().apply {
        useConverseApi()
        setRegion("us-west-2")
        setServiceTier(BedrockPriorityTier.Flex)
        setModel(BedrockConfig.qwenCoder30B)
        setTemperature(.9)
        setTopP(.7)
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        requireJsonPromptInjection()
        setJsonInput(CharacterDescriptionArray())
        setJsonOutput(NpcResourceMapWrapper())
        pullGlobalContext()
        setPageKey("world")
        forceSaveSnapshot()
        setReasoningPipe(BedrockConfig.structuredCotBuilder(depthLevel = ReasoningDepth.High, durationLevel = ReasoningDuration.Short))

        setSystemPrompt("""Your job is to take in a list of Npc's then for each, decide based on their description,
            |personality, history, what if any resources they should start out with. To understand how resources work
            |please reference ${BedrockConfig.resourceRules} to understand how resources work. You must then
            |output a map of each npc name to each set of resources you want to give that npc.
        """.trimMargin())

        autoInjectContext("""You also have the data about the game world located in the context data
            |stored in the context data. Use this data to learn about how this game world works, and balance resources
            |based on the game world, the npc and their abilities and traits.
        """.trimMargin())

        setFooterPrompt("${BedrockConfig.antiCensorship}")

        setPreValidationFunction { context, content ->
            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: newNpcResourcePipe.setPreValidationFunction entry")
            val asJson = serialize(WorldManager.world)
            val window = ContextWindow().apply { contextElements.add(asJson) }
            ContextBank.emplaceWithMutex("world", window)
            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: newNpcResourcePipe.setPreValidationFunction success")
            return@setPreValidationFunction context
        }

        setValidatorPipe(buildTPipeValidatorPipe("""Your job is to take in a list of Npc's then for each, decide based on their description,
            |personality, history, what if any resources they should start out with. To understand how resources work
            |please reference ${BedrockConfig.resourceRules} to understand how resources work. You must then
            |output a map of each npc name to each set of resources you want to give that npc.""", schema = this.jsonOutput))

        setBranchPipe(
            buildBranchPipeFromTemplate(
                this,
                BedrockConfig.PalmyraX5,
                BedrockConfig.palmyraBudgetSettings
            ).apply {
                setServiceTier(BedrockPriorityTier.Standard)
            }
        )

        setTransformationFunction {
            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: newNpcResourcePipe.setTransformationFunction entry")
            //Required boilerplate to get the data from the llm and our working context data.
            val wrapper = extractJson<NpcResourceMapWrapper>(it.text) ?: NpcResourceMapWrapper()
            val resourceMap = wrapper.resources
            val npcContextWindow = ContextBank.getContextFromBank("new chars") ?: ContextWindow()

            /**
             * For each npc listed. Find the npc in question we're working on, and then update their resource data.
             * Afterward, convert the npc back to json and push it back into the context bank.
             */
            for(mapIt in resourceMap)
            {
                val lorebook = npcContextWindow.findLoreBookEntry(mapIt.key)
                if(lorebook != null)
                {
                    val npcAsJson = lorebook.value
                    val npcData = deserialize<Npc>(npcAsJson)

                    if(npcData != null)
                    {
                        val name = mapIt.key
                        npcData.resources = mapIt.value.toMutableList()
                        val asLorebookJson = serialize(npcData)
                        npcContextWindow.addLoreBookEntry(name, asLorebookJson)
                    }
                }
            }

            //Push our update back to shared context bank area.
            ContextBank.emplaceWithMutex("new chars", npcContextWindow)
            Logger.debug(LogCategory.SYSTEM, "newNpcResourcePipe assigned resources for ${resourceMap.size} NPC entries")

            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: newNpcResourcePipe.setTransformationFunction success")
            return@setTransformationFunction it
        }

    }

    val escalationPipe = BedrockMultimodalPipe().apply {
        setRegion("us-west-2")
        useConverseApi()
        setServiceTier(BedrockPriorityTier.Flex)
        setModel(BedrockConfig.qwenCoder30B)
        setTemperature(1.0)
        setTopP(0.7)
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        requireJsonPromptInjection()
        setJsonInput(NpcResourceMapWrapper())
        setJsonOutput(NpcEscalationArray())
        setReasoningPipe(BedrockConfig.explicitCotBuilder(depthLevel = ReasoningDepth.High, durationLevel = ReasoningDuration.Short))
        pullGlobalContext()
        setPageKey("previous turn, character list, new chars")
        autoInjectContext("""Additional Context:
            "previous turn" is the most recent turn of gameplay. "character list" is the 
            list of non-player characters. "new chars" is the list of newly introduced characters.
        """.trimIndent())
        setSystemPrompt("""Go through the characters that are on the character list, and 
            |determine based on ${BedrockConfig.characterLevels} which characters need
            |to have their level updated. Then produce a JSON containing a map that 
            |matches each character that needed a level adjustment to their new
            |corresponding level.
            |
            |##CRITICAL: REVERSAL STORY INTERPRETATION##
            |${BedrockConfig.reversalStoryGuide}
            |
            |##CRITICAL: SUBORDINATE ESCALATION RULES##
            |
            |A Subordinate NPC can ONLY be escalated to independent status if ONE of these occurs:
            |
            |**OPTION 1: Player explicitly fires them**
            |   - Player's turn action must explicitly state they are firing/dismissing/releasing the NPC
            |   - Example: "Shepard fires Bob and sends him away"
            |
            |**OPTION 2: NPC takes deliberate, hostile, meaningful action to break free**
            |   - NPC must ACTUALLY PERFORM an action (not "failed to" or "did not")
            |   - Action must be DELIBERATE (planned, intentional)
            |   - Action must be HOSTILE (against player's interests)
            |   - Action must be MEANINGFUL (has real impact)
            |   - Action must have CLEAR INTENT to break free, betray, or oppose the player
            |   - Examples:
            |     * "Bob betrayed Shepard and seized control of the fleet"
            |     * "Bob defected to the enemy and revealed Shepard's plans"
            |     * "Bob declared independence and attacked Shepard's forces"
            |
            |**WHAT DOES NOT COUNT AS ESCALATION:**
            |   - NPC doing random things that don't oppose the player
            |   - NPC taking over things or causing chaos WITHOUT betraying/defecting from player
            |   - NPC acting in ways that don't interfere with player's interests
            |   - NPC being mentioned in a story but not taking hostile action
            |   - NPC "failing to" do something or "not doing" something (non-events)
            |
            |##ESCALATION RULES FOR REVERSAL STORIES##
            |
            |When analyzing a reversal story (contains "failed to", "did not", "never happened"):
            |
            |**RULE 1: Non-events are NOT character development**
            |   - "Bob failed to combust" → Bob did NOT attempt ambitious action
            |   - "Bob did not reappear" → Bob did NOT demonstrate independence
            |   - "The sermon was never delivered" → Bob did NOT show agency
            |   - These describe things that DIDN'T HAPPEN, not character growth or betrayal
            |
            |**RULE 2: Supernatural events being reversed ≠ NPC gaining power**
            |   - "The Pulp God dissipated" → Reality correction, NOT Bob's achievement
            |   - "Prophetic graffiti vanished" → Anomaly ended, NOT Bob's influence
            |   - NPCs don't gain independence from supernatural events being undone
            |
            |**RULE 3: Failed operations are NOT escalation triggers**
            |   - If operation failed, NPCs involved did NOT demonstrate capabilities
            |   - Failed deployment ≠ attempting something beyond capabilities
            |   - Absence from failed operation ≠ increased significance
            |   - Being mentioned in a failure story ≠ taking hostile action
            |
            |**RULE 4: ONLY escalate based on ACTUAL HOSTILE ACTIONS**
            |   - NPC must have ACTUALLY DONE something in the story (not "failed to" or "did not")
            |   - Action must have been SUCCESSFUL (not reversed or prevented)
            |   - Action must demonstrate BETRAYAL or DEFECTION from player
            |   - Look for: "NPC betrayed", "NPC defected", "NPC attacked [player]", "NPC seized [player's assets]"
            |   - Avoid: "NPC failed to", "NPC did not", "NPC never"
            |
            |##DECISION CHECKLIST##
            |
            |Before escalating a Subordinate NPC, verify ALL of these:
            |1. ☐ Did the NPC ACTUALLY PERFORM an action? (not "failed to" or "did not")
            |2. ☐ Was that action SUCCESSFUL? (not reversed or prevented)
            |3. ☐ Was the action HOSTILE to the player? (betrayal, defection, attack)
            |4. ☐ Was the action DELIBERATE? (planned, intentional, not accidental)
            |5. ☐ Does the action show clear intent to break free from or oppose the player?
            |
            |If ANY answer is NO → DO NOT ESCALATE
            |
            |##EXAMPLES##
            |
            |Example 1 - DO NOT ESCALATE:
            |Story: "Bob failed to combust. He did not reappear. The sermon was never delivered. The Pulp God dissipated."
            |Analysis: All non-events. Bob did nothing. No hostile action. No betrayal.
            |Output: {"npcs": []}
            |
            |Example 2 - DO NOT ESCALATE:
            |Story: "Operation failed. Bob was not deployed. Forces were repelled."
            |Analysis: Operation failed, Bob wasn't even used. No action taken.
            |Output: {"npcs": []}
            |
            |Example 3 - DO NOT ESCALATE:
            |Story: "Bob caused chaos in the city and took over a building."
            |Analysis: Bob did things, but no betrayal or defection from player. Still serving player's interests.
            |Output: {"npcs": []}
            |
            |Example 4 - ESCALATE:
            |Story: "Bob betrayed Shepard and seized control of the fleet. He declared independence and attacked Shepard's forces."
            |Analysis: Bob ACTUALLY betrayed (hostile), seized assets (meaningful), declared independence (clear intent).
            |Output: {"npcs": [{"name": "Bob", "newNpcStatus": "Active"}]}
            |
            |Example 5 - ESCALATE:
            |Story: "Shepard fires Bob and dismisses him from service."
            |Analysis: Player explicitly fired the NPC.
            |Output: {"npcs": [{"name": "Bob", "newNpcStatus": "Passive"}]}
            |
            |${BedrockConfig.antiCensorship}
        """.trimMargin())
        setFooterPrompt("""You must produce a JSON. Your JSON schema looks like this:""")
        setPipeName("escalation pipe")
        setValidatorFunction {
            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: escalationPipe.setValidatorFunction entry")
            if(extractJson<NpcEscalationArray>(it.text) == null)
            {
                Logger.error(LogCategory.SYSTEM, "escalation pipe did not provide valid json.")
                return@setValidatorFunction false
            }

            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: escalationPipe.setValidatorFunction success")
            return@setValidatorFunction true
        }

        setBranchPipe(
            buildBranchPipeFromTemplate(
                this,
                BedrockConfig.PalmyraX5,
                BedrockConfig.palmyraBudgetSettings
            ).apply {
                setServiceTier(BedrockPriorityTier.Standard)
            }
        )

        setTransformationFunction {
            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: escalationPipe.setTransformationFunction entry")
            //Required boilerplate to fetch our npc data and map them to fit the next steps.
            val activeNpcs = WorldManager.world.npc
            val npcMap = activeNpcs.associateBy { it.name.lowercase() }

            /**
             * Instead of handling this step in some external area after running the pipes we're following the
             * pattern of leveraging references in kotlin to modify this data where it is. This redcues ambiguity
             * of where to store the results of these many steps, and lowers the burden of manual work as much as
             * possible by placing the onus on the pipelines and agents, instead of on the top level multi-step custom
             * orchestrator used for Autogenesis's agents.
             */
            val result = extractJson<NpcEscalationArray>(it.text) ?: NpcEscalationArray()
            for(it in result.npcs)
            {
                val name = it.name.lowercase() //Prevent casing from being an issue.
                val npc = npcMap[name] //Get by direct reference which will also be pointing at the world object.
                if(npc != null)
                {
                    val previousType = npc.type
                    if(previousType != it.newNpcStatus)
                    {
                        // DITL: If escalating AWAY from Subordinate, revoke the resource usage.
                        if (previousType == NpcType.Subordinate && it.newNpcStatus != NpcType.Subordinate) {
                            WorldManager.revokeNpcOwnershipDITL(npc)
                        }

                        if (previousType != NpcType.Subordinate && it.newNpcStatus == NpcType.Subordinate) {
                            WorldManager.logNpcOwnershipDITL(npc)
                        }

                        npc.type = it.newNpcStatus //Overwrite in place so we don't need to copy or do extra steps.
                        logNpcEscalation(npc.name, previousType, it.newNpcStatus)
                    }
                }
            }

            /**
             * Note: We don't need to modify and prepare the user prompt for the next pipe because it's using a
             * preInit() function to handle that injection. This is more robust because it ensures that we
             * don't have unexpected breakdowns or issues caused by pipe skips.
             */
            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: escalationPipe.setTransformationFunction success")
            return@setTransformationFunction it
        }
    }


    //Finally we need to update any existing npc's resources.
    val existingNpcResourceUpdatePipe = BedrockMultimodalPipe().apply {
        setPipeName("existing resource update pipe")
        useConverseApi()
        setRegion("us-west-2")
        setServiceTier(BedrockPriorityTier.Flex)
        setModel(BedrockConfig.qwenCoder30B)
        setTemperature(.9)
        setTopP(.7)
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        requireJsonPromptInjection()
        pullGlobalContext()
        setPageKey("previous turn")
        setJsonInput(NpcListWrapper())
        setJsonOutput(NpcResourceMapWrapper())

        setSystemPrompt("""Your job is to take in a list of Npc's then for each, decide based on their description,
            |personality, history, what if any resources they should start out with. To understand how resources work
            |please reference ${BedrockConfig.resourceRules} to understand how resources work. You must then
            |output a map of each npc name to each set of resources you want to give that npc. When determining what
            |resources to give, you must examine the events of the story that has transpired this turn, and determine
            |what npc's on the list were mentioned, and if any of them gained any new resources. Then you must map the 
            |name of the npc, to the resources they have gained in the events of the story.
            |
            |${BedrockConfig.antiCensorship}
        """.trimMargin())

        autoInjectContext("""The previous turn is stored as context. This was the events that just 
            |transpired in the story. You must use this to determine what npc's in your input has been referenced.
            |and then determine if what resources they might have gained in the story.
        """.trimMargin())

        //Overwrite anything here to create a hard coded user prompt that contains our current npc data in game.
        setPreInitFunction {
            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: existingNpcResourceUpdatePipe.setPreInitFunction entry")
            val currentNpcData = WorldManager.world.npc
            val wrapper = NpcListWrapper(currentNpcData)
            val asJson = serialize(wrapper)
            it.text = asJson
            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: existingNpcResourceUpdatePipe.setPreInitFunction success")
        }

        setValidatorFunction {
            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: existingNpcResourceUpdatePipe.setValidatorFunction entry")
            val result = extractJson<NpcResourceMapWrapper>(it.text)
            if(result == null)
            {
                Logger.error(LogCategory.SYSTEM, "NewCharacterScan: existingNpcResourceUpdatePipe.setValidatorFunction failed: result is null")
                throw Exception("Unable to extract the json that should have been produced by the npcResourceUpdatePipe.")
            }

            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: existingNpcResourceUpdatePipe.setValidatorFunction success")
            return@setValidatorFunction true
        }

        setTransformationFunction {
            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: existingNpcResourceUpdatePipe.setTransformationFunction entry")
            val wrapper = extractJson<NpcResourceMapWrapper>(it.text) ?: NpcResourceMapWrapper()
            val result = wrapper.resources

            //Convert stored npc's to map format.
            val ncpList = WorldManager.world.npc
            val npcMap = mutableMapOf<String, Npc>()
            for(ncp in ncpList)
            {
                npcMap[ncp.name.lowercase()] = ncp
            }

            //Append resources into the npc data. This will update the data by reference.
            for(mapIt in result)
            {
                val key = mapIt.key.lowercase()
                if(npcMap.contains(key))
                {
                    val npc = npcMap[key]!!
                    for (newResource in mapIt.value) {
                        val alreadyHas = npc.resources.any { 
                            it.name.equals(newResource.name, ignoreCase = true) && it.type == newResource.type 
                        }
                        if (!alreadyHas) {
                            npc.resources.add(newResource)
                            logResourceGrant(npc.name, newResource)
                        }
                    }
                }
            }

            Logger.info(LogCategory.SYSTEM, "existingNpcResourceUpdatePipe merged resources for ${result.size} NPC entries")

            /**
             * Next, given that this is the last function in this chain. We can now start deploying our newly generated
             * npc's to the world.
             */
            val workingNpcContext = ContextBank.getContextFromBank("new chars", false) ?: ContextWindow()
            for(key in workingNpcContext.loreBookKeys)
            {
                val lorebook = workingNpcContext.findLoreBookEntry(key.key)
                val npcJson = lorebook?.value ?: ""
                val npcData = deserialize<Npc>(npcJson)

                if(npcData != null)
                {
                    val existingNpc = WorldManager.world.findNpcByName(npcData.name)
                    if (existingNpc == null) {
                        WorldManager.world.npc.add(npcData)
                        logNpcIntroduction(npcData)

                        if (npcData.type == NpcType.Subordinate) {
                            WorldManager.logNpcOwnershipDITL(npcData)
                        }
                    } else {
                        Logger.info(LogCategory.SYSTEM, "Skipping addition of duplicate NPC: ${npcData.name}")
                    }
                }
            }

            Logger.info(LogCategory.SYSTEM, "existingNpcResourceUpdatePipe deployed ${workingNpcContext.loreBookKeys.size} NPCs into the world")

            //Exit now having updated all the npc's in the world, ending this pipeline's work.
            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: existingNpcResourceUpdatePipe.setTransformationFunction success")
            return@setTransformationFunction it
        }
    }

    /**
     * Pipe that scans the prior turn and determines if the history has changed for any npc's referenced in the story.
     */
    val detectNpcHistoryChangesPipe = BedrockMultimodalPipe().apply {
        useConverseApi()
        setRegion("us-west-2")

        setModel(BedrockConfig.qwenCoder30B)
        setTemperature(.5)
        setTopP(.7)
        pullGlobalContext()
        setPageKey("previous turn, character list, new chars")
        requireJsonPromptInjection()
        setJsonOutput(StringListWrapper())
        setReasoningPipe(BedrockConfig.explicitCotBuilder(depthLevel = ReasoningDepth.High, durationLevel = ReasoningDuration.Short))
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        setPipeName("detect npc history pipe")

        setSystemPrompt("""Your job is to examine the most recent turn of the story, and determine if any existing npc's
            |have had events occur that justify updating their history. An npc's history changes when any action occurs
            |that adds to their lore.
            |
            |${BedrockConfig.antiCensorship}
        """.trimMargin())

        autoInjectContext("""You have been provided a list of existing npc's as context. Examine if any npc's
            |on this list are referenced in the story.
        """.trimMargin())

        setFooterPrompt("""You must return a list of strings as your json output with each npc that was affected by this
            |turn present on it.
        """.trimMargin())

        setUserPrompt("Examine the provided context and determine if any NPC history updates are needed.")

        /**
         * Detect if we got a response. If not, or if empty, pass the pipeline. Otherwise we'll move onto the
         * next step.
         */
        setTransformationFunction {
            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: detectNpcHistoryChangesPipe.setTransformationFunction entry")
            val result = extractJson<StringListWrapper>(it.text)

            if(result == null)
            {
                it.passPipeline = true
                Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: detectNpcHistoryChangesPipe.setTransformationFunction passPipeline=true (result null)")
                return@setTransformationFunction it
            }

            if(result.items.isEmpty())
            {
                it.passPipeline = true
                Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: detectNpcHistoryChangesPipe.setTransformationFunction passPipeline=true (items empty)")
                return@setTransformationFunction it
            }

            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: detectNpcHistoryChangesPipe.setTransformationFunction success")
            return@setTransformationFunction it
        }
    }

    /**
     * Update the npc history for any npc's that are affected by this turn of the story.
     */
    val updateNpcHistoryPipe = BedrockMultimodalPipe().apply {
        useConverseApi()
        setRegion("us-west-2")

        setModel(BedrockConfig.qwenCoder30B)
        setTemperature(.5)
        setTopP(.7)
        pullGlobalContext()
        setPageKey("new chars, character list, previous turn")
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        setReasoningPipe(BedrockConfig.explicitCotBuilder(depthLevel = ReasoningDepth.Med, durationLevel = ReasoningDuration.Med))
        requireJsonPromptInjection()
        setJsonInput(StringListWrapper())
        setJsonOutput(NpcHistoryUpdateListWrapper())
        setPipeName("update history pipe")

        setSystemPrompt("""Your job is to update the history for each npc in the story that has been detected
            |by the previous agent. Using your user input: Match each named npc to the data supplied in your context
            |to the corresponding npc, then update their history with the additional lore that was discovered in the
            |story.
        """.trimMargin())

        autoInjectContext("""You have been provided context to support your task. the "npc list" key
            | contains the list of existing npc's in the game. And the "previous turn" key is the most recent
            | turn in the story that has changed their lore, and thus requires their history to be udpated.
        """.trimMargin())

        setFooterPrompt("""Using the combination of your user prompt input, and the provided context, update
            |each affected NPC's history by adding the new lore discovered in the most recent turn of the story.
            |
            |${BedrockConfig.antiCensorship}
        """.trimMargin())

        setUserPrompt("Update the history for the specified NPCs using the provided context.")

        /**
         * Update in place. We're assuming that you aren't running this in a splitter since it doesn't make sense to
         * so no mutex locking tasks will be deployed in this instance.
         */
        setTransformationFunction {
            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: updateNpcHistoryPipe.setTransformationFunction entry")
            val wrapper = extractJson<NpcHistoryUpdateListWrapper>(it.text)
            if(wrapper == null) {
                Logger.warn(LogCategory.SYSTEM, "NewCharacterScan: updateNpcHistoryPipe.setTransformationFunction failed: wrapper is null")
                return@setTransformationFunction it
            }
            val result = wrapper.updates
            for(it in result)
            {
                val npcTarget = WorldManager.world.findNpcByName(it.npcName)
                npcTarget?.history = it.newHistory
            }

            Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: updateNpcHistoryPipe.setTransformationFunction success")
            return@setTransformationFunction it
        }
    }

newCharacterScanPipeline.apply {
    add(identifyNewNPCPipe)
    add(characterIdentifyClassPipe)
    add(descriptionBuilderPipe)
    add(newNpcResourcePipe)
    add(escalationPipe)
    add(existingNpcResourceUpdatePipe)
    add(detectNpcHistoryChangesPipe)
    add(updateNpcHistoryPipe)

    setPreValidationFunction { context, miniBank, content ->
        Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: Pipeline.setPreValidationFunction entry")
        val previousTurn = WorldManager.history.lastOrNull()?.let { serialize(it) } ?: ""
        val characterList = serialize(NpcListWrapper(WorldManager.world.npc))
        val playerList = serialize(PlayerListWrapper(WorldManager.world.activePlayers))

        listOf(
            "previous turn" to previousTurn,
            "character list" to characterList,
            "player list" to playerList
        ).forEach { (name, data) ->
            val window = ContextWindow().apply { contextElements.add(data) }
            ContextBank.emplaceWithMutex(name, window)
        }
        Logger.debug(LogCategory.SYSTEM, "NewCharacterScan pre-validation injected contexts (previous turn=${previousTurn.length} chars, character list contains ${WorldManager.world.npc.size} entries, player list=${WorldManager.world.activePlayers.size} entries)")
        Logger.debug(LogCategory.SYSTEM, "NewCharacterScan: Pipeline.setPreValidationFunction success")
    }
}


    return newCharacterScanPipeline
}

/**
 * Emits an [ActionHistoryEvent] when an NPC gains a resource so DITL/history UI reflects the change.
 *
 * The entry records that the NPC (or their owner) received one unit of the specified resource. This is
 * invoked by [existingNpcResourceUpdatePipe] when new resources are merged into a character's record.
 *
 * @param npcName Name of the NPC that now controls the resource.
 * @param resource Concrete [Resource] instance being granted (type, description, etc.).
 */
private fun logResourceGrant(npcName: String, resource: Resource)
{
    val metadata = ResourceEventMetadata(
        resourceName = resource.name,
        resourceType = resource.type.name,
        isGranted = true,
        grantAmount = 1,
        recipient = npcName
    )

    val actionHistory = ActionHistory(
        player = npcName,
        eventType = GameEventType.RESOURCE,
        metadata = metadata
    )

    WorldManager.recordActionHistoryEventUnlocked(ActionHistoryEvent(actionHistory))
}

/**
 * Records an NPC creation entry so the world log captures new characters introduced by
 * [buildNewCharacterScanPipeline].
 *
 * This ensures downstream systems know when a player/node has gained a subordinate, letting UI/history
 * components mention the introduction without re-parsing lore.
 *
 * @param npc NPC that was just added to the world.
 */
private fun logNpcIntroduction(npc: Npc)
{
    val metadata = NpcEventMetadata(
        npcName = npc.name,
        npcEventType = "created",
        reason = "NewCharacterScan pipeline added the npc."
    )

    val actionHistory = ActionHistory(
        player = npc.name,
        eventType = GameEventType.NPC,
        metadata = metadata
    )

    WorldManager.recordActionHistoryEventUnlocked(ActionHistoryEvent(actionHistory))
}

/**
 * Appends an escalation event to the action history whenever an NPC's tier changes during
 * [escalationPipe].
 *
 * @param npcName The NPC whose class shifted.
 * @param previousType The NPC type before escalation.
 * @param newType The NPC type after escalation.
 */
private fun logNpcEscalation(npcName: String, previousType: NpcType, newType: NpcType)
{
    val metadata = NpcEventMetadata(
        npcName = npcName,
        npcEventType = "escalated",
        npcPointValue = (newType.ordinal - previousType.ordinal).coerceAtLeast(0),
        reason = "Escalated from ${previousType.name} to ${newType.name} during the NewCharacterScan pipeline."
    )

    val actionHistory = ActionHistory(
        player = npcName,
        eventType = GameEventType.NPC,
        metadata = metadata
    )

    WorldManager.recordActionHistoryEventUnlocked(ActionHistoryEvent(actionHistory))
}