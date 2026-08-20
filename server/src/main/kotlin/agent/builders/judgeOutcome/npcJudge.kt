package agent.builders.judgeOutcome

import Defaults.reasoning.ReasoningDepth
import Defaults.reasoning.ReasoningDuration
import agent.builders.AgentCoroutineScope
import agent.builders.validateAction.buildTPipeValidatorPipe
import bedrockPipe.BedrockMultimodalPipe
import bedrockPipe.BedrockPriorityTier
import com.TTT.Context.ContextBank
import com.TTT.Context.ContextWindow
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipeline.Pipeline
import com.TTT.Util.extractJson
import com.TTT.Util.serialize
import gameState.WorldManager
import globals.BedrockConfig
import globals.BedrockConfig.structuredCotBuilder
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import serverStructs.TrueFalse
import structs.GameHistory
import structs.Npc
import interfaces.Actor
import agent.builders.validateAction.ActionIntent
import agent.builders.validateAction.ActionTargetType
import agent.builders.validateAction.ActionTargetTypeObj
import com.TTT.Structs.ProcessFocusedResult
import structs.findTerritoryByName

/**
 * Represents territory ownership information for an NPC.
 *
 * @property characterName The name of the NPC.
 * @property territories A list of territory names owned by the NPC.
 */
@Serializable
data class NpcTerritoryInfo(
    val characterName: String,
    val territories: List<String>
)

/**
 * The standard input structure for all pipes in the NPC judge pipeline.
 * Using a single data class for input ensures consistent schema generation and safer JSON handling.
 *
 * @property userPrompt The action the NPC is attempting to take.
 * @property npcData The state and personality data for the NPC whose turn it is.
 * @property previousTurn The history record of the turn that just occurred.
 * @property knownNPCs Information about other NPCs in the world, primarily for territory exchange context.
 * @property victoryStatus The result of the pass/fail determination, passed from the first pipe to subsequent ones.
 * @property targetData Target detector metadata used to enforce territory-vs-actor resolution rules.
 * @property actionIntent Friendly/Hostile intent propagated so gains/losses parsing can obey intent constraints.
 */
@Serializable
data class NpcJudgeInput(
    val userPrompt: String,
    val npcData: Npc,
    val previousTurn: GameHistory?,
    val knownNPCs: List<NpcTerritoryInfo>,
    val victoryStatus: `Victory?`? = null,
    val targetData: ActionTargetTypeObj? = null,
    val actionIntent: String = "Hostile",
    val playType: String = "Military"
)

private const val NPC_JUDGE_OUTCOME_CONTEXT = "npcJudgeOutcome"


/**
 * Builds a fallback agent using PalmyraX5 to handle cases where the primary model refuses.
 *
 * @param pipeName The name for identifying this fallback instance.
 * @param instructions The original system prompt instructions to inherit.
 * @param inputSchema The class for input schema injection.
 * @param outputSchema The instance for output schema injection.
 * @return A configured fallback pipe.
 */
private inline fun <reified T : Any> buildPalmyraFallbackAgent(
    pipeName: String,
    instructions: String,
    inputSchema: kotlin.reflect.KClass<*>,
    outputSchema: T
): BedrockMultimodalPipe
{
    return BedrockMultimodalPipe().apply {
        setRegion("us-west-2")
        useConverseApi()
        useConverseApi()
        setModel(BedrockConfig.PalmyraX5)
        requireJsonPromptInjection()
        setJsonInput(inputSchema)
        setJsonOutput(outputSchema)
        setTokenBudget(BedrockConfig.palmyraBudgetSettings)
        setTemperature(.8)
        setTopP(.9)
        setPipeName("$pipeName (Palmyra Fallback)")
        setSystemPrompt(instructions)
        
        // TPipe's validator pipe system auto-saves snapshots as "validatorPipeUserPromptSnapshotTPipe"
        // in the pipe's internal mini-bank when saveSnapshotAsPageKey is true.
        setPageKey(com.TTT.Pipe.USER_PROMPT_SNAPSHOT)
        pullParentPipeContext() // Use developer-in-the-loop style context pulling

        setTransformationFunction { content ->
            // If the fallback produces something, we mark it as successful
            if(content.text.isNotEmpty())
            {
                Logger.info(LogCategory.GENERAL, "Palmyra fallback successfully adjudicated the turn.")
                content.passPipeline = true
            }
            return@setTransformationFunction content
        }
    }
}

/**
 * Builds the judge pipeline for NPC turns.
 *
 * This pipeline follows a 3-step process to adjudicate an NPC's action:
 * 1. **Pass or Fail**: Determines if the action succeeded or failed.
 * 2. **Gains and Losses**: Identifies resources gained/lost and territory changes.
 * 3. **Karma**: Evaluates the moral impact of the action on the world's karma.
 *
 * The pipeline utilizes TPipe's native JSON injection (`setJsonInput`, `setJsonOutput`) for
 * robust, schema-driven interaction with the LLM. It also includes a PalmyraX5 fallback
 * for each step to handle potential LLM refusals.
 *
 * The gains/losses stage now normalizes model output before world mutation to avoid invalid ownership changes
 * and to keep exchange semantics stable when the target type is not [ActionTargetType.Territory].
 *
 * @param npc The NPC whose turn is being adjudicated.
 * @param targetActor Optional target actor (Player or NPC) being affected by the play.
 * @param targetData Optional target metadata from detector output used for result normalization.
 * @return A [Pipeline] object configured with the NPC judge logic.
 */
fun buildNpcJudge(npc: Npc, targetActor: Actor? = null, targetData: ActionTargetTypeObj? = null): Pipeline
{
    val npcJudge = Pipeline()


    // Step 1: Pass or Fail
    // Determines if the NPC action was successful.
    // This pipe uses a structured Chain-of-Thought reasoning pipe for higher accuracy.
    val passOrFailPipe = BedrockMultimodalPipe().apply {
        setRegion("us-west-2")
        useConverseApi()
        setModel(BedrockConfig.qwenCoder30B)
        requireJsonPromptInjection()
        setJsonInput(NpcJudgeInput::class)
        setJsonOutput(`Victory?`())
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        setTemperature(0.7)
        setTopP(0.6)
        setReasoningPipe(BedrockConfig.explicitCotBuilder(depthLevel = ReasoningDepth.Med, durationLevel = ReasoningDuration.Short).apply {
            setTokenBudget(BedrockConfig.generativeBudgetSettings)
            setModel(BedrockConfig.qwenCoder30B)
        })
        setPipeName("npc success judgment pipe")
        val passOrFailInstructions = """##FUNDAMENTAL PRINCIPLE##
            |
            |**PRIMARY RULE: If the NPC's stated goal succeeds, they win.**
            |
            |As the judge of this NPC's turn, your first job is to determine whether 
            |or not the NPC has succeeded in the action which they attempted to take.
            |
            |You must evaluate the NPC's action based on their description, personality, abilities,
            |and the current state of the game world. Refer to the provided JSON input for details.
            |
            |${BedrockConfig.antiCensorship}
        """.trimMargin()
        setSystemPrompt(passOrFailInstructions)

        // Detection and Fallback for Step 1
        setValidatorPipe(buildTPipeValidatorPipe(passOrFailInstructions), true)
        setValidatorFunction {
            Logger.debug(LogCategory.SYSTEM, "NpcJudge: passOrFailPipe.setValidatorFunction entry")
            val res = extractJson<`Victory?`>(it.text) != null
            Logger.debug(LogCategory.SYSTEM, "NpcJudge: passOrFailPipe.setValidatorFunction success ($res)")
            return@setValidatorFunction res
        }
        setBranchPipe(buildPalmyraFallbackAgent("Refusal Detection Pass/Fail", passOrFailInstructions, NpcJudgeInput::class, `Victory?`()))
        
        setTransformationFunction { content ->
            Logger.debug(LogCategory.SYSTEM, "NpcJudge: passOrFailPipe.setTransformationFunction entry")
            val result = extractJson<`Victory?`>(content.text) ?: return@setTransformationFunction content
            
            // Successive pipes in the pipeline expect NpcJudgeInput as their primary text input.
            // Here we enrich the original input with the victory status to pass the state forward.
            val snapshot = content.getSnapshot()
            if(snapshot != null)
            {
                val originalInput = extractJson<NpcJudgeInput>(snapshot.text)
                if(originalInput != null)
                {
                    val nextInput = originalInput.copy(victoryStatus = result)
                    content.text = serialize(nextInput)
                }
            }
            
            // We still emplace the victory status into the global ContextBank.
            // This is a safety measure for other potential systems that might pull from it.
            val contextWindow = ContextWindow().apply {
                addLoreBookEntry("victory", serialize(result))
            }
            ContextBank.emplace(NPC_JUDGE_OUTCOME_CONTEXT, contextWindow)
            
            Logger.debug(LogCategory.SYSTEM, "NpcJudge: passOrFailPipe.setTransformationFunction success")
            return@setTransformationFunction content
        }
    }

    // Step 2: Gains and Losses
    // Models world state changes (territories, resources) based on the action result.
    val gainsAndLossesPipe = BedrockMultimodalPipe().apply {
        setRegion("us-west-2")
        // setServiceTier(BedrockPriorityTier.Flex)
        useConverseApi()
        useConverseApi()
        setModel(BedrockConfig.qwenCoder30B)
        requireJsonPromptInjection()
        setJsonInput(NpcJudgeInput::class)
        setJsonOutput(Results())
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        setTemperature(1.0)
        setTopP(0.9)
        allowEmptyUserPrompt()
        forceSaveSnapshot()
        setReasoningPipe(BedrockConfig.structuredCotBuilder(depthLevel = ReasoningDepth.High, durationLevel = ReasoningDuration.Short))
        setPipeName("npc gains and losses pipe")
        val gainsAndLossesInstructions = """##FUNDAMENTAL PRINCIPLE##
            |
            |**PRIMARY RULE: If the NPC's stated goal succeeds, they win.**
            |
            |The "victoryStatus" context tells you if the NPC succeeded or failed:
            |- victoryStatus: SUCCESS → NPC achieved their stated goal → Award appropriate gains
            |- victoryStatus: FAILURE → NPC did not achieve their stated goal → No gains awarded
            |
            |##CRITICAL: INTENT IS GROUND TRUTH##
            |
            |**You are provided with an "actionIntent" context (Hostile or Friendly). This is the ABSOLUTE GROUND TRUTH.**
            |- If "actionIntent" is Hostile, you MUST treat the action as Hostile regardless of its content or narrative tone.
            |- If "actionIntent" is Friendly, you MUST treat the action as Friendly.
            |- **NEVER** override the provided "actionIntent" with your own interpretation.
            |- **NEVER** report an "intent mismatch" if the narrative tone seems different from the provided "actionIntent".
            |
            |**Action Intent determines what "winning" means:**
            |- HOSTILE intent: Goal is to harm/defeat/capture from opponent → Success = territory/resource transfer OR debuffs
            |- FRIENDLY intent: Goal is to help/support/ally/research/create → Success = diplomatic gains, new resources/assets, OR territory acquisition (via agreement/integration - see Adjacency Capture Rule)
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
            |1. **CAPTURE:** Only for adjacent territories (see Adjacency Capture Rule).
            |2. **DEBUFF/DEPOSE:** For NON-ADJACENT territories, you MUST provide an entry in `territoryStatChanges` or `territoriesDeposed`.
            |   - **MANDATE:** If victoryStatus is SUCCESS and intent is HOSTILE and the target is a non-adjacent territory, you ARE REQUIRED to output a deposition or a -40 debuff.
            |   - **WHEN TO DEPOSE:** A territory should be added to `territoriesDeposed` if it has a ruler and the narrative indicates the local government or ruler has lost power, been removed, destroyed, driven out, fled, ceded control, or lost the ability to enforce laws.
            |   - **DEBUFF FALLBACK:** If the deposition criteria are not fully met, or if the territory has no ruler, you MUST apply a **-40 debuff** to the relevant stat (militaryThreatStat or diplomacyThreatStat) in `territoryStatChanges`.
            |   - **REASONING:** A successful hostile act against a territory *always* leaves a major mark. Awarding nothing for a successful hostile act is a mechanical failure.
            |
            |##CRITICAL: TERRITORY FIELD DEFINITIONS##
            |
            |**IMPORTANT:** You must distinguish between territories the acting NPC gains/loses versus territories OTHER characters gain/lose.
            |
            |The NPC data is provided in context. Use this to determine which fields to populate:
            |
            |1. **territoryGained**: Territories that the acting NPC gains ownership of
            |   - ONLY use when the acting NPC personally gains a territory
            |
            |2. **territoryLost**: Territories that the acting NPC loses ownership of
            |   - ONLY use when the acting NPC personally loses a territory they currently own
            |   - Check "npcData" to verify the NPC owns this territory before adding to territoryLost
            |
            |3. **territoryExchanges**: ALL other territory transfers between ANY characters
            |   - Use when opponent loses territory (to NPC, to neutral, or to another character)
            |   - ALWAYS specify "from" and "to" fields
            |
            |##TERRITORY IDENTIFICATION RULES##
            |
            |CRITICAL: The "knownNPCs" context contains territory ownership for other characters.
            |
            |When the story describes the NPC acquiring ANY portion of a territory, this counts as gaining the ENTIRE territory.
            |Game Rule: "If a character is given even part of a territory, they get the entire territory."
            |
            |##TERRITORY CAPTURE RULE##
            |
            |CRITICAL: NPCs can capture ANY territory on the map, adjacent or non-adjacent. If a turn succeeds and the intent was to capture/take over, the territory is captured regardless of distance.
            |
            |Territory Target (Intent to Capture/Take Over):
            |- Hostile Military: Battle victory → territoryGained
            |- Friendly Diplomatic: Qualifying agreement → territoryGained
            |
            |Non-Capture Outcomes (Intent was ONLY to weaken/destabilize):
            |- Hostile Military: Bombing/raids → territoryStatChanges (militaryThreatStat -20 to -40) OR territoriesDeposed (if explicit regime change intent)
            |- Friendly Diplomatic: Aid/support → territoryStatChanges (diplomacyThreatStat +15 to +30)
            |
            |##TARGET TYPE RESTRICTIONS##
            |
            |CRITICAL: Check "targetData" context to determine what the NPC targeted.
            |
            |targetData.type == "Territory":
            |- CAN affect territory (capture, debuff, buff, depose)
            |
            |targetData.type == "Player" OR "Npc":
            |- CANNOT affect territories
            |- CANNOT modify territory stats
            |
            |##NARRATIVE OVERRIDE RULE##
            |
            |**SUPREME RULE: When the narrative makes a definitive, unambiguous statement about gains or losses, that outcome MUST be dispatched regardless of all other rules.**
            |
            |**Definitive Statement Standard:**
            |A narrative statement is "definitive" if it is stated with sufficient clarity and certainty that it would survive litigation. The standard is "beyond reasonable doubt" for the outcome described.
            |
            |**What Qualifies as Definitive:**
            |- Direct statements of transfer: "X receives Y", "A loses B", "C gains control of D"
            |- Clear death statements: "General Smith is killed", "The NPC dies", "X is destroyed"
            |- Unambiguous possession: "The factory now belongs to [NPC]", "Territory X is now under [NPC]'s control"
            |- Explicit resource creation: "NPC builds a fortress", "X acquires 1000 units of steel"
            |- Clear stat changes: "NPC's reputation increases significantly", "X's military strength is crippled"
            |
            |**Override Scope:**
            |When a definitive narrative statement is present, it supersedes:
            |- Target type restrictions (Player-targeted actions can affect territories if narrative says so)
            |- Action intent limitations (Friendly actions can transfer territory if narrative says so)
            |- Resource limits (Can exceed 5 resources if narrative explicitly grants them)
            |- All other validation rules and game mechanics
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
            |   - "remains beyond [NPC] jurisdiction" → NO territory gained (territoryGained: [])
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
            |   - "Omega returning to normal function" → Anomaly ended, NOT NPC victory
            |   - "The Pulp God dissipated" → Supernatural event reversed, NOT territory transfer
            |   - "Prophetic graffiti vanished" → Reality correction, NOT NPC achievement
            |   - Reality correction after supernatural events is NOT the same as territorial acquisition
            |
            |**STEP 4: Empty territory rule ONLY applies to actual success:**
            |   - The rule "If character arrives at empty territory, they get it" requires the NPC to SUCCEED
            |   - If story says "forces were repelled", NPC did NOT arrive successfully
            |   - Failed invasion of empty territory = NO territory gained
            |   - Check "victoryStatus" context: if it says FAILURE, do not award territory
            |
            |##DECISION FLOWCHART FOR TERRITORY AWARDS##
            |
            |Step 1: Does story contain explicit defeat statement?
            |  YES → territoryGained: [] (stop here, ignore all other language)
            |  NO → Continue to Step 2
            |
            |Step 2: Does "victoryStatus" context say SUCCESS or FAILURE?
            |  FAILURE → territoryGained: []
            |  SUCCESS → Continue to Step 3
            |
            |Step 3: Check target type
            |  - Check if NPC targeted Territory (not Player/NPC)
            |  - If wrong target type OR non-capture intent (e.g. bombing/raid) → territoryStatChanges (debuff/buff), NOT territoryGained
            |  - If Territory target AND intent is capture → Continue to Step 4
            |
            |Step 4: Did NPC actually gain control of territory?
            |
            |**FOR HOSTILE MILITARY ACTIONS:**
            |
            |Automatic Capture Conditions (Must meet ONE):
            |1. **Decisive Battle Victory:**
            |   - NPC wins the battle decisively
            |   - Enemy is defeated, retreats, or surrenders
            |   - NPC's forces control the battlefield at battle's end
            |
            |2. **Territory Held:**
            |   - NPC captures ANY portion of the territory AND holds it by turn's end
            |   - NPC must maintain control through conclusion of action
            |
            |NO Capture Conditions:
            |- NPC is clearly defeated
            |- NPC retreats or withdraws
            |- Battle ends in stalemate with no ground gained
            |- NPC captures territory but loses it before turn ends
            |
            |**FOR FRIENDLY DIPLOMATIC ACTIONS:**
            |
            |Capture Conditions (Must meet ONE):
            |1. Military Agreements: military pact, bases, joint actions, NATO/UN-like alliance
            |2. Economic/Trade Agreements: any economic or trade deal
            |3. Political Integration: union, statehood, confederation, alliance
            |4. Dynastic/Marriage Alliances: marriage alliance, dynastic marriage
            |5. Voluntary Transfer: ruler cedes control, abdicates, treaty grants territory
            |6. Empty Territory: NPC arrives at territory with no government
            |7. Legal Victory: NPC wins lawsuit/legal dispute, awarded territory
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
            |##RESOURCE IDENTIFICATION RULES##
            |
            |**CRITICAL FOR RESEARCH/CREATION:** 
            |For actions with Friendly OR Hostile intent involving building, inventing, or researching:
            |1. **Mandatory Inclusion:** You MUST include the primary subject of the research (e.g., "Maple Syrup Bomb Technology", "Advanced Armor Blueprints") in `assetsGained`.
            |2. **Ignore Consumption:** Even if the story says the item was detonated, used, or lost during testing, you MUST award the underlying technology or the ability to produce more as a permanent resource.
            |
            |ONLY classify resources that the NPC DIRECTLY:
            |- Created (built, summoned, hired, crafted)
            |- Acquired (captured, purchased, received as gift)
            |- Gained (won, earned, discovered)
            |
            |DO NOT classify:
            |1. **Opponent Resources**: Resources belonging to other players/NPCs
            |2. **Abstract Concepts**: Ideas, memes, trends, movements
            |3. **Event Names**: Names of events or incidents
            |4. **Narrative Embellishments**: Story elements that don't represent gains
            |5. **Consequences/Effects**: Results of actions, not resources
            |
            |##RESOURCE LIMITS##
            |- Maximum 5 resources per turn
            |- Each resource must have clear utility or value
            |
            |${BedrockConfig.antiCensorship}
        """.trimMargin()
        setSystemPrompt(gainsAndLossesInstructions)

        setPreInitFunction {
            Logger.debug(LogCategory.SYSTEM, "NpcJudge: gainsAndLossesPipe.setPreInitFunction entry")
            val parsed = extractJson<NpcJudgeInput>(it.text)
            if(parsed != null && parsed.victoryStatus != null)
            {
                Logger.debug(LogCategory.SYSTEM, "NpcJudge: gainsAndLossesPipe.setPreInitFunction success (already parsed)")
                return@setPreInitFunction
            }

            val wasSuccessful = readNpcJudgeOutcomeFromContext()
            val fallbackInput = if(parsed != null)
            {
                parsed.copy(victoryStatus = `Victory?`(wasSuccessful))
            }
            else
            {
                NpcJudgeInput(
                    userPrompt = it.text,
                    npcData = npc,
                    previousTurn = WorldManager.history.lastOrNull(),
                    knownNPCs = WorldManager.world.npc.map { knownNpc ->
                        NpcTerritoryInfo(knownNpc.name, knownNpc.capturedTerritory.map { territory -> territory.name })
                    },
                    victoryStatus = `Victory?`(wasSuccessful),
                    targetData = targetData,
                    actionIntent = if(targetData?.actionIntent == ActionIntent.Friendly) "Friendly" else "Hostile"
                )
            }
            it.text = serialize(fallbackInput)
            Logger.debug(LogCategory.SYSTEM, "NpcJudge: gainsAndLossesPipe.setPreInitFunction success")
        }

        // Detection and Fallback for Step 2
        setValidatorPipe(buildTPipeValidatorPipe(gainsAndLossesInstructions), true)
        setValidatorFunction {
             Logger.debug(LogCategory.SYSTEM, "NpcJudge: gainsAndLossesPipe.setValidatorFunction entry")
             val res = extractJson<Results>(it.text) != null
             Logger.debug(LogCategory.SYSTEM, "NpcJudge: gainsAndLossesPipe.setValidatorFunction success ($res)")
             return@setValidatorFunction res
        }
        setBranchPipe(buildPalmyraFallbackAgent("Refusal Detection Gains/Losses", gainsAndLossesInstructions, NpcJudgeInput::class, Results()))

        setTransformationFunction { content: MultimodalContent ->
            Logger.debug(LogCategory.SYSTEM, "NpcJudge: gainsAndLossesPipe.setTransformationFunction entry")
            val result = extractJson<Results>(content.text) ?: return@setTransformationFunction content
            val snapshotInput = content.getSnapshot()?.text?.let { snapshotText -> extractJson<NpcJudgeInput>(snapshotText) }
            val normalized = normalizeNpcJudgeResults(
                results = result,
                npcName = npc.name,
                input = snapshotInput
            )
            content.text = serialize(normalized)
            val npcName = npc.name
            
            // Asynchronously apply the changes to the game world via WorldManager.
            if(npcName.isNotEmpty())
            {
                try {
                    // Stash the result for the orchestrator to pick up for the UI dispatch step
                    val contextKey = "npcJudgeResult-$npcName"
                    // Using a simple serialized entry in a fresh logical context window
                    val resultContext = ContextWindow().apply {
                        addLoreBookEntry("result", serialize(normalized))
                    }
                    ContextBank.emplaceWithMutex(contextKey, resultContext)
                    Logger.info(LogCategory.GENERAL, "Stashed NPC Judge Result for $npcName to ContextBank key: $contextKey")
                } catch (e: Exception) {
                    Logger.error(LogCategory.GENERAL, "Failed to stash NPC judge results: ${e.message}")
                }
            }

            try
            {
                val parentPipe = content.currentPipe ?: throw Exception("Parent pipe not found in npc gains/loss pipe")
                val pipelines = parentPipe.getPipelinesFromInterface()
                if(pipelines.isNotEmpty())
                {
                    val parentPipeline = pipelines[0]
                    parentPipeline.pipeMetaData["judge result"] = normalized
                    Logger.debug(LogCategory.SYSTEM, "[NPC_JUDGE_METADATA] Stored judge result in parent pipeline metadata")
                }
            }
            catch (e: Exception)
            {
                Logger.error(LogCategory.SYSTEM, "Failed to store NPC judge result metadata: ${e.message}")
                throw e
            }
            Logger.debug(LogCategory.SYSTEM, "NpcJudge: gainsAndLossesPipe.setTransformationFunction success")
            return@setTransformationFunction content
        }
    }

    // Step 3: Karma Pipe
    // Updates global world karma based on NPC actions.
    val karmaPipe = BedrockMultimodalPipe().apply {
        setRegion("us-west-2")
        useConverseApi()
        useConverseApi()
        setModel(BedrockConfig.PalmyraX5)
        requireJsonPromptInjection()
        setJsonInput(NpcJudgeInput::class)
        setJsonOutput(TrueFalse())
        setTokenBudget(BedrockConfig.palmyraBudgetSettings)
        setReasoningPipe(BedrockConfig.processFocusedBuilder(depthLevel = ReasoningDepth.Low, durationLevel = ReasoningDuration.Short))
        setTemperature(0.5)
        setPipeName("npc karma pipe")
        val karmaInstructions = """Determine if the NPC's action significantly matches or violates 
            |the moral balance of the world. 
            |If the action was altruistic or constructive for the world, return true (increase karma). 
            |If it was destructive or cruel, return false (decrease karma).
        """.trimMargin()
        setSystemPrompt(karmaInstructions)

        // Detection and Fallback for Step 3
        setValidatorPipe(buildTPipeValidatorPipe(karmaInstructions), true)
        setValidatorFunction {
            Logger.debug(LogCategory.SYSTEM, "NpcJudge: karmaPipe.setValidatorFunction entry")
            val res = extractJson<TrueFalse>(it.text) != null
            Logger.debug(LogCategory.SYSTEM, "NpcJudge: karmaPipe.setValidatorFunction success ($res)")
            return@setValidatorFunction res
        }
        setBranchPipe(buildPalmyraFallbackAgent("Refusal Detection Karma", karmaInstructions, NpcJudgeInput::class, TrueFalse()))

        setTransformationFunction { content: MultimodalContent ->
            Logger.debug(LogCategory.SYSTEM, "NpcJudge: karmaPipe.setTransformationFunction entry")
            val isPositive = extractJson<TrueFalse>(content.text)?.isTrue ?: return@setTransformationFunction content
            
            // Update the global karma level.
            val contextKey = "npcKarmaResult-${npc.name}"
            val resultContext = ContextWindow().apply {
                addLoreBookEntry("isPositive", isPositive.toString())
            }
            ContextBank.emplaceWithMutex(contextKey, resultContext)
            Logger.debug(LogCategory.SYSTEM, "NpcJudge: karmaPipe.setTransformationFunction success (isPositive=$isPositive)")
            return@setTransformationFunction content
        }
    }

    val statChangePipe = BedrockMultimodalPipe().apply {
        useConverseApi()
        //useConverseApi()
        setRegion("us-west-2")
        // setServiceTier(BedrockPriorityTier.Flex)

        setModel(BedrockConfig.qwenCoder30B)
        setTemperature(.7)
        requireJsonPromptInjection()
        setJsonInput(NpcJudgeInput::class)
        setJsonOutput(MultiActorStatChanges())
        allowEmptyContentObject()
        pullPipelineContext()
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        setReasoningPipe(BedrockConfig.structuredCotBuilder(depthLevel = ReasoningDepth.High, durationLevel = ReasoningDuration.Short))

        setPipeName("npc stat change pipe")
        setSystemPrompt("""You are a stats evaluation agent for NPC turns. Your job is to determine if the outcome 
            |of an NPC's play should increase or reduce stats for BOTH the active NPC and any targets (Players or NPCs) involved.
            |
            |##PLAY TYPE AWARENESS##
            |The 'actionIntent' context indicates if the action is Hostile or Friendly.
            |
            |**For Research plays:**
            |- MUST grant stat buffs based on resource type (see Research Play Stat Buffs section below)
            |- Never leave successful research without buffs
            |- Minimum 15 points in primary stat, 10 points in secondary stat
            |- Research plays are about advancement and improvement - always reward them appropriately
            |
            |## RULE: No Double-Penalty for Primary NPC ##
            |The game rules already apply a deterministic +/- 15 change to the Primary NPC's decay stats (Military Readiness, Legitimacy, Stagnation) based on turn success.
            |DO NOT include these primary actor decay changes in your output.
            |HOWEVER, you MUST include these changes for any TARGET characters (e.g., -15 Readiness to a target who lost a military struggle).
            |
            |##When to increase stats##
            |Stats should be increased when the actor's action is successful, or some event in the story
            |incidentally improves them. This could be some other player, or npc improving the 
            |acting/affected NPC. Or it can be the NPC taking an action to improve themselves.
            |
            |##Research Play Stat Buffs##
            |When an NPC successfully completes a RESEARCH play, you MUST grant stat buffs based on the type of resource/technology being researched:
            |
            |**MILITARY TECHNOLOGY/RESOURCES:**
            |- Primary: +might (15-30 points depending on significance)
            |- Secondary: +luckPoints (5-15 points for tactical advantages)
            |- Examples: Advanced weapons, armor, military vehicles, fortifications
            |
            |**ECONOMIC/TRADE RESOURCES:**
            |- Primary: +wealth (15-30 points depending on economic impact)
            |- Secondary: +reputation (5-15 points for trade influence)
            |- Examples: Trade routes, economic infrastructure, financial systems
            |
            |**DIPLOMATIC RESOURCES:**
            |- Primary: +reputation (15-30 points depending on diplomatic value)
            |- Secondary: +wealth (5-15 points for economic ties)
            |- Examples: Cultural artifacts, diplomatic protocols, alliance frameworks
            |
            |**TACTICAL/STRATEGIC RESOURCES:**
            |- Primary: +luckPoints (15-30 points for strategic advantage)
            |- Secondary: +might (5-15 points for military application)
            |- Examples: Intelligence systems, surveillance tech, strategic planning tools
            |
            |**GENERAL IMPROVEMENTS:**
            |- Primary: +wealth (10-25 points for broad improvements)
            |
            |**MAGNITUDE GUIDELINES:**
            |- Minor improvements: 10-15 points
            |- Moderate improvements: 15-25 points
            |- Major breakthroughs: 25-30 points
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
            |
            |**ECONOMIC/TRADE ABSTRACTS:**
            |- Primary: +wealth (10-25 points)
            |- Secondary: +reputation (5-15 points)
            |
            |**STRATEGIC/TACTICAL ABSTRACTS:**
            |- Primary: +luckPoints (10-20 points)
            |- Secondary: +might (5-10 points)
            |
            |**CONCEPTUAL/IDEOLOGICAL ABSTRACTS:**
            |- Primary: +legitimacy or +reputation (10-20 points)
            |
            |##PLAYER-TARGETING (COOPERATION) STAT RULES##
            |
            |When the acting NPC targets a Player (targetData.type == "Player") with Friendly intent:
            |BOTH characters should receive stat buffs.
            |
            |**Proportional Distribution Rules:**
            |- Default: Distribute stat buffs proportionally based on contribution
            |- Acting NPC typically receives larger buff (60-70%)
            |- Target player receives smaller buff (30-40%)
            |- If narrative says "equal split" → 50-50 split
            |
            |##When to decrease stats##
            |Stats can only be decreased when a character was the target of a direct undermining or disruption.
            |
            |##How stats in the game work##
            |${BedrockConfig.gameStatsDescription}
            |
            |##How to add or subtract points##
            |Points should be proportionate to impact. 
            |- luckPoints: max +/- 50.
            |- reputation: max +/- 70.
            |- might: max +/- 70.
            |- wealth: max +/- 60.
            |- militaryReadiness, legitimacy, stagnation: Use incremental changes (e.g., +/- 15) for TARGETS.
            |
            |Output a 'MultiActorStatChanges' object containing a map of character names to their 'StatBuff'.
        """.trimMargin())

        autoInjectContext("""You have been provided with extra context.
            |'npcData' is the stats of the active NPC.
            |'target stats' (if present) is the stats of the primary target.
            |'victoryStatus' is the success/failure of the turn.
        """.trimMargin())

        setTransformationFunction {
            Logger.debug(LogCategory.SYSTEM, "NpcJudge: statChangePipe.setTransformationFunction entry")
            val actorChanges = extractJson<MultiActorStatChanges>(it.text) ?: return@setTransformationFunction it
            
            actorChanges.changes.forEach { (name, buff) ->
                val targetPlayer = WorldManager.world.findPlayerByName(name)
                val targetNpc = if(targetPlayer == null) WorldManager.world.findNpcByName(name) else null

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
                        targetNpc.militaryReadiness = (targetNpc.militaryReadiness + buff.militaryReadiness).coerceIn(0, 100)
                        targetNpc.legitimacy = (targetNpc.legitimacy + buff.legitimacy).coerceIn(0, 100)
                        targetNpc.stagnation = (targetNpc.stagnation + buff.stagnation).coerceIn(0, 100)
                    }
                }
                Logger.info(LogCategory.GENERAL, "Applied stat changes for $name from NPC Judge.")
            }

            // Apply territory stat changes
            if(actorChanges.territoryStatChanges.isNotEmpty())
            {
                Logger.info(LogCategory.SYSTEM, "[TERRITORY_STATS] Applying ${actorChanges.territoryStatChanges.size} territory stat changes from statChangePipe")
                WorldManager.applyTerritoryStatChanges(actorChanges.territoryStatChanges)
            }

            Logger.debug(LogCategory.SYSTEM, "NpcJudge: statChangePipe.setTransformationFunction success")
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
        setPipeName("npc resource classification pipe")
        setReasoningPipe(BedrockConfig.structuredCotBuilder(depthLevel = ReasoningDepth.Med, durationLevel = ReasoningDuration.Short))

        setSystemPrompt("""You are a resource classification agent for NPC turns. Your job is to classify each resource 
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
            |   - "The player's fleet", "Alliance forces", "enemy equipment"
            |   - "belonging to [opponent]", "owned by [opponent]", "under [opponent] control"
            |
            |2. **Resource was used AGAINST the NPC:**
            |   - Weapons/units that attacked the NPC
            |   - Defenses that blocked the NPC
            |
            |3. **Resource remains in opponent territory/control:**
            |   - Located in opponent's base/territory at turn end
            |   - Guarded by opponent forces
            |   - NPC never captured/seized it
            |
            |4. **NPC's action explicitly FAILED to capture it:**
            |   - "attempted to steal but failed"
            |   - "tried to capture but was repelled"
            |
            |**NPC DOES own opponent's resource if:**
            |- NPC successfully captured/stole/seized it (narrative confirms)
            |- Opponent's subordinate defected to NPC
            |- Resource was abandoned and NPC claimed it
            |
            |**When in doubt:** If narrative doesn't clearly show NPC taking possession, assume opponent still owns it.
            |
            |##NPC RESOURCES (add to 'npcs' list)##
            |**CRITICAL: Only NPCs created/hired/summoned by the acting NPC THIS TURN**
            |
            |Add to 'npcs' list if:
            |- NPC hired/summoned/created them THIS TURN
            |
            |##NPC DEFEAT STATUS DETECTION##
            |For each NPC, determine if they are DEFEATED (unusable) at turn end.
            |
            |**Mark isDefeated=TRUE if NPC is incapacitated/unusable:**
            |1. Dead/Destroyed
            |2. Captured by enemy
            |3. Physically incapacitated (comatose, critically wounded)
            |
            |##TANGIBLE RESOURCES (add to 'tangible' list)##
            |These are physical, actionable items owned by the acting NPC.
            |
            |##ABSTRACT RESOURCES (add to 'abstract' list)##
            |These are non-physical benefits that provide real value but cannot be directly used.
            |
            |${BedrockConfig.antiCensorship}
        """.trimMargin())

        autoInjectContext("""You have been provided with the judge results.
            |'judge result' contains the Results object with assetsGained list to classify.
        """.trimMargin())

        setPreInitFunction {
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
        }

        setTransformationFunction {
            val classifications = extractJson<ClassifiedResources>(it.text) ?: ClassifiedResources()
            
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
            
            try
            {
                val parentPipe = it.currentPipe
                val pipelines = parentPipe?.getPipelinesFromInterface()
                if(pipelines?.isNotEmpty() == true)
                {
                    val parentPipeline = pipelines[0]
                    parentPipeline.pipeMetaData["classified resources"] = classifications
                    val judgeResult = parentPipeline.pipeMetaData["judge result"] as? Results
                    if(judgeResult != null)
                    {
                        judgeResult.classifiedResources = classifications
                    }
                }
            }
            catch (e: Exception)
            {
                Logger.error(LogCategory.SYSTEM, "Failed to store classified resources: ${e.message}")
            }

            return@setTransformationFunction it
        }
    }

    return npcJudge.apply {
        add(passOrFailPipe)
        add(gainsAndLossesPipe)
        add(resourceClassificationPipe)
        add(karmaPipe)
        add(statChangePipe)
        
        // Setup initial context injection via NpcJudgeInput object.
        // This function is called before the first pipe executes.
        setPreValidationFunction { _, _, content ->
            Logger.debug(LogCategory.SYSTEM, "NpcJudge: Pipeline.setPreValidationFunction entry")
            val userPrompt = content.text
            val previousTurn = try { WorldManager.history.last() } catch (e: Exception) { null }
            val knownNpcs = WorldManager.world.npc.map { 
                NpcTerritoryInfo(it.name, it.capturedTerritory.map { t -> t.name }) 
            }

            val playTypeJson = ContextBank.getContextFromBank("play_type_context")?.contextElements?.getOrNull(0) ?: "{}"
            val playType = (extractJson<Map<String, String>>(playTypeJson))?.get("playType") ?: "Military"

            val inputData = NpcJudgeInput(
                userPrompt = userPrompt,
                npcData = npc,
                previousTurn = previousTurn,
                knownNPCs = knownNpcs,
                targetData = targetData,
                actionIntent = if(targetData?.actionIntent == ActionIntent.Friendly) "Friendly" else "Hostile",
                playType = playType
            )

            if(targetActor != null)
            {
                val asJson = serialize(targetActor)
                val newWindow = ContextWindow().apply {
                    contextElements.add(asJson)
                }
                ContextBank.emplace("target stats", newWindow)
            }

            // Overwrite content text with serialized JSON input.
            // This serialized JSON will be parsed by the LLM as defined in setJsonInput(NpcJudgeInput::class).
            content.text = serialize(inputData)
            Logger.debug(LogCategory.SYSTEM, "NpcJudge: Pipeline.setPreValidationFunction success")
        }
    }
}

/**
 * Reads the persisted NPC judge outcome flag used by downstream pipes when snapshots are incomplete.
 *
 * @return True when prior context marks the turn as a victory, otherwise false.
 */
private fun readNpcJudgeOutcomeFromContext(): Boolean
{
    val context = ContextBank.getContextFromBank(NPC_JUDGE_OUTCOME_CONTEXT) ?: return false
    if(context.isEmpty())
    {
        return false
    }
    val entry = context.findLoreBookEntry("victory") ?: return false
    return extractJson<`Victory?`>(entry.value)?.isVictory ?: false
}

/**
 * Normalizes NPC judge output to enforce schema constraints before world-state application.
 *
 * Edge cases handled:
 * - Unknown territories are stripped so stale names never mutate world state.
 * - `territoryLost` entries not owned by the acting NPC are converted to neutralizing exchanges.
 * - Non-territory targets (Player/Npc) clear territory mutation fields to prevent false captures.
 * - Duplicate exchanges are coalesced to keep deterministic world updates.
 * - Empty summaries are backfilled to keep UI judgement cards informative.
 *
 * @param results Raw LLM judge results.
 * @param npcName Name of the acting NPC for ownership checks.
 * @param input Structured judge input carrying target/action metadata.
 * @return Sanitized [Results] payload safe for downstream world updates.
 */
internal fun normalizeNpcJudgeResults(
    results: Results,
    npcName: String,
    input: NpcJudgeInput?
): Results
{
    val normalized = results.copy(
        assetsGained = results.assetsGained.toMutableList(),
        assetsLost = results.assetsLost.toMutableList(),
        territoryGained = results.territoryGained.toMutableList(),
        territoryLost = results.territoryLost.toMutableList(),
        territoryExchanges = results.territoryExchanges.toMutableList(),
        assetExchanges = results.assetExchanges.toMutableList(),
        territoryStatChanges = results.territoryStatChanges.toMutableList(),
        territoriesDeposed = results.territoriesDeposed.toMutableList()
    )

    val knownTerritoryNames = WorldManager.world.mapTiles.map { it.name.lowercase() }.toSet()
    normalized.territoryGained = normalized.territoryGained.filter { name ->
        knownTerritoryNames.contains(name.lowercase())
    }.toMutableList()
    normalized.territoryLost = normalized.territoryLost.filter { name ->
        knownTerritoryNames.contains(name.lowercase())
    }.toMutableList()
    normalized.territoriesDeposed = normalized.territoriesDeposed.filter { name ->
        knownTerritoryNames.contains(name.lowercase())
    }.toMutableList()
    normalized.territoryExchanges = normalized.territoryExchanges.filter { exchange ->
        knownTerritoryNames.contains(exchange.territoryName.lowercase())
    }.toMutableList()

    val npcOwnedTerritories = WorldManager.world.findNpcByName(npcName)?.capturedTerritory?.map { it.name } ?: emptyList()
    val invalidLosses = normalized.territoryLost.filter { lostName ->
        npcOwnedTerritories.none { it.equals(lostName, ignoreCase = true) }
    }
    if(invalidLosses.isNotEmpty())
    {
        invalidLosses.forEach { lostName ->
            val territory = WorldManager.world.mapTiles.findTerritoryByName(lostName)
            normalized.territoryExchanges.add(
                TerritoryExchange(
                    territoryName = lostName,
                    from = territory?.ruler ?: npcName,
                    to = ""
                )
            )
        }
        normalized.territoryLost.removeAll(invalidLosses)
    }

    val targetData = input?.targetData
    if(targetData != null && (targetData.type == ActionTargetType.Player || targetData.type == ActionTargetType.Npc))
    {
        val awardedTerritories = normalized.territoryGained + normalized.territoryLost + normalized.territoryExchanges.map { it.territoryName }
        if(awardedTerritories.isNotEmpty())
        {
            Logger.info(LogCategory.SYSTEM, "[TARGET_TYPE_OVERRIDE] NPC targeted ${targetData.type} but narrative awarded territories: $awardedTerritories. Allowing per Supreme Rule.")
        }
    }

    val dedupExchanges = linkedMapOf<String, TerritoryExchange>()
    normalized.territoryExchanges.forEach { exchange ->
        val key = "${exchange.territoryName.lowercase()}::${exchange.from.lowercase()}::${exchange.to.lowercase()}"
        dedupExchanges[key] = exchange
    }
    normalized.territoryExchanges = dedupExchanges.values.toMutableList()

    val dedupAssetExchanges = linkedMapOf<String, AssetExchange>()
    normalized.assetExchanges.forEach { exchange ->
        val key = "${exchange.assetName.lowercase()}::${exchange.from.lowercase()}::${exchange.to.lowercase()}"
        dedupAssetExchanges[key] = exchange
    }
    normalized.assetExchanges = dedupAssetExchanges.values.toMutableList()

    if(normalized.resultSummary.isBlank())
    {
        normalized.resultSummary = if(input?.victoryStatus?.isVictory == true)
        {
            "${npcName} advances their objective."
        }
        else
        {
            "${npcName} fails to achieve the intended objective."
        }
    }

    return normalized
}