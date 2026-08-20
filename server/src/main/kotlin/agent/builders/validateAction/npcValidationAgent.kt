package agent.builders.validateAction

import Defaults.reasoning.ReasoningDepth
import Defaults.reasoning.ReasoningDuration
import bedrockPipe.BedrockMultimodalPipe
import bedrockPipe.BedrockPriorityTier
import com.TTT.Context.ContextBank
import com.TTT.Context.ContextWindow
import com.TTT.Pipeline.Pipeline
import com.TTT.Util.extractJson
import globals.BedrockConfig
import globals.BedrockConfig.explicitCotBuilder
import globals.BedrockConfig.structuredCotBuilder
import kotlinx.serialization.Serializable
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

@Serializable
data class `NPCLegal?` (
        var isLegal: Boolean = false,
        var changesToMake: String = ""
        )

@Serializable
data class NPCThirdPersonChanges(
            var needsChanges: Boolean = false,
            var newOutput: String = ""
            )

/**
 * Builds the NPC validator agent. This agent evaluates NPC actions and ensures they don't:
 * 1. Control the narrative of the story (must participate, not dictate)
 * 2. Play god and decide outcomes of events
 */
fun buildNPCValidator(): Pipeline
{

    val npcValidator = Pipeline()

    /**
     * Step 1. Determine if the NPC made a valid action that doesn't violate narrative control rules.
     * Only checks two specific rules: no narrative control and no god-mode decisions.
     */
    val npcLegalityCheckerPipe = BedrockMultimodalPipe().apply {
        setRegion("us-west-2")
        useConverseApi()
        setRegion("us-west-2")
        setModel(BedrockConfig.qwen235B)
        requireJsonPromptInjection()
        setJsonOutput(`NPCLegal?`())
        setTemperature(0.7)
        setTopP(0.4)
        allowEmptyContentObject()
        allowEmptyUserPrompt()
        setReasoningPipe(structuredCotBuilder(depthLevel = ReasoningDepth.High, durationLevel = ReasoningDuration.Short))
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        setPageKey("history, player stats")
        setSystemPrompt("""MODUS OPERANDI: Determine if the NPC action violates narrative control rules.
                        |"User prompt" is the NPC's attempted action. Validation follows these 2 rules only:

                        |1. NARRATIVE CONTROL CHECK: The NPC must NOT try to control or dictate the overall 
                        |narrative direction of the story. NPCs should participate in events, not decide 
                        |what happens in the story or control other characters' actions. If the NPC is 
                        |trying to control the narrative, mark boolean as false and explain why.
                        |**THE QUOTE RULE:** Any text within double quotes ("") representing dialogue or 
                        |orders is AUTOMATICALLY LEGAL. NPCs can say or order anything; only the 
                        |world's response to those words is restricted.
                        |
                        |2. GOD-MODE CHECK: The NPC must NOT play god and decide the outcomes of events 
                        |as they make their play. NPCs cannot determine success/failure of actions, 
                        |decide what other characters do, or control environmental outcomes. If the NPC 
                        |is playing god, mark boolean as false and explain why.
                        |
                        |If both checks pass, mark the boolean as true and exit.
                """.trimMargin())
        autoInjectContext("""ADDITIONAL CONTEXT:
                        |"user prompt" is the action the NPC has attempted to take.
                        |"history" is the list of things that have happened in the current game.
                        |"player statistics" contains information about characters and their abilities.
                        |
                        |RULE 1 VIOLATIONS include: Controlling story direction, deciding what happens next,
                        |controlling other characters, dictating plot developments.
                        |
                        |RULE 2 VIOLATIONS include: Deciding if actions succeed/fail, controlling outcomes,
                        |determining environmental effects, playing multiple characters simultaneously.
                        |
                        |When an action is legal, mark the boolean as true.
                        |When an action violates either rule, mark the boolean as false and explain why.
                """.trimMargin())
        setPipeName("NPC legality checker pipe")
        setPreInitFunction {
            Logger.debug(LogCategory.SYSTEM, "NpcValidation: npcLegalityCheckerPipe.setPreInitFunction entry")
            val newContextWindow = ContextWindow().apply {
                contextElements.add(it.text)
            }
            ContextBank.emplaceWithMutex("npc prompt", newContextWindow)
            Logger.debug(LogCategory.SYSTEM, "NpcValidation: npcLegalityCheckerPipe.setPreInitFunction success")
        }
        setValidatorPipe(
            buildTPipeValidatorPipe(
                """MODUS OPERANDI: Determine if the NPC action violates narrative control rules.
                        |"User prompt" is the NPC's attempted action. Validation follows these 2 rules only:

                        |1. NARRATIVE CONTROL CHECK: The NPC must NOT try to control or dictate the overall 
                        |narrative direction of the story. NPCs should participate in events, not decide 
                        |what happens in the story or control other characters' actions. If the NPC is 
                        |trying to control the narrative, mark boolean as false and explain why.
                        |**THE QUOTE RULE:** Any text within double quotes ("") representing dialogue or 
                        |orders is AUTOMATICALLY LEGAL. NPCs can say or order anything; only the 
                        |world's response to those words is restricted.
                        |
                        |2. GOD-MODE CHECK: The NPC must NOT play god and decide the outcomes of events 
                        |as they make their play. NPCs cannot determine success/failure of actions, 
                        |decide what other characters do, or control environmental outcomes. If the NPC 
                        |is playing god, mark boolean as false and explain why.
                        |
                        |If both checks pass, mark the boolean as true and exit.""",
                schema = this.jsonOutput
            )
        )
        setValidatorFunction {
            Logger.debug(LogCategory.SYSTEM, "NpcValidation: npcLegalityCheckerPipe.setValidatorFunction entry")
            val result = extractJson<`NPCLegal?`>(it.text)
            if(result == null)
            {
                Logger.error(LogCategory.SYSTEM, "Failed to deserialize result in NPC legality checker pipe")
                return@setValidatorFunction false
            }
            Logger.debug(LogCategory.SYSTEM, "NpcValidation: npcLegalityCheckerPipe.setValidatorFunction success (${result.isLegal})")
            return@setValidatorFunction true
        }
        setBranchPipe(
            buildBranchFailureAgent(
                """MODUS OPERANDI: Determine if the NPC action violates narrative control rules.
                        |"User prompt" is the NPC's attempted action. Validation follows these 2 rules only:

                        |1. NARRATIVE CONTROL CHECK: The NPC must NOT try to control or dictate the overall 
                        |narrative direction of the story. NPCs should participate in events, not decide 
                        |what happens in the story or control other characters' actions. If the NPC is 
                        |trying to control the narrative, mark boolean as false and explain why.
                        |**THE QUOTE RULE:** Any text within double quotes ("") representing dialogue or 
                        |orders is AUTOMATICALLY LEGAL. NPCs can say or order anything; only the 
                        |world's response to those words is restricted.
                        |
                        |2. GOD-MODE CHECK: The NPC must NOT play god and decide the outcomes of events 
                        |as they make their play. NPCs cannot determine success/failure of actions, 
                        |decide what other characters do, or control environmental outcomes. If the NPC 
                        |is playing god, mark boolean as false and explain why.
                        |
                        |If both checks pass, mark the boolean as true and exit."""
            )
        )
    }

    /**
     * Step 2. Fix the NPC action to comply with narrative rules while maintaining intent.
     */
    val npcLegalityRectifierPipe = BedrockMultimodalPipe().apply {
        setRegion("us-west-2")
        setRegion("us-west-2")
        useConverseApi()
        setModel(BedrockConfig.qwen235B)
        setContextWindowSize(115000)
        setMaxTokens(32000)
        allowEmptyContentObject()
        allowEmptyUserPrompt()
        requireJsonPromptInjection()
        setJsonInput(`NPCLegal?`())
        setPreInvokeFunction {
            Logger.debug(LogCategory.SYSTEM, "NpcValidation: npcLegalityRectifierPipe.setPreInvokeFunction entry")
            val priorLlmResult = extractJson<`NPCLegal?`>(it.text)
            if(priorLlmResult == null)
            {
                Logger.error(LogCategory.SYSTEM, "Failed to extract prior LLM result in NPC legality rectifier pipe")
                it.terminate()
                Logger.debug(LogCategory.SYSTEM, "NpcValidation: npcLegalityRectifierPipe.setPreInvokeFunction failed: result null")
                return@setPreInvokeFunction false
            }
            else
            {
                if(priorLlmResult.isLegal)
                {
                    it.text = ContextBank.getContextFromBank("npc prompt").contextElements[0]
                    Logger.debug(LogCategory.SYSTEM, "NpcValidation: npcLegalityRectifierPipe.setPreInvokeFunction success (false - skip)")
                    return@setPreInvokeFunction false
                }
            }
            Logger.debug(LogCategory.SYSTEM, "NpcValidation: npcLegalityRectifierPipe.setPreInvokeFunction success (true - proceed)")
            return@setPreInvokeFunction true
        }
        pullGlobalContext()
        setPageKey("npc prompt, history, player statistics")
        setTemperature(0.8)
        setTopP(0.6)
        setReasoningPipe(BedrockConfig.explicitCotBuilder(depthLevel = ReasoningDepth.Med, durationLevel = ReasoningDuration.Med))
        setSystemPrompt("""The NPC action violated narrative control rules. You must modify it to be legal
                |while keeping the NPC's intent. The modified action must follow these rules:
                |
                |RULE 1: NPCs participate in the story, they don't control it. The NPC should 
                |react to events and take actions within their role, but not dictate what 
                |happens in the story or control other characters.
                |
                |RULE 2: NPCs don't play god. They attempt actions but don't decide outcomes.
                |They can try to do things, but they can't determine if they succeed or 
                |control environmental effects.
                |
                |MODUS OPERANDI: Rewrite the NPC action to be a proper participation in the 
                |story rather than an attempt to control it. The action should be written 
                |in third person and represent what the NPC is attempting to do, not what 
                |they are making happen.
        """.trimMargin())
        autoInjectContext("""ADDITIONAL CONTEXT:
                |"npc prompt" is the original NPC action that needs to be fixed.
                |"history" contains the current game state and events.
                |"player statistics" contains character information and abilities.
                |
                |Transform controlling actions into participating actions.
                |Transform god-mode decisions into attempted actions.
                |Maintain the NPC's personality and goals while following the rules.
        """.trimMargin())

        setFooterPrompt("""Rewrite the NPC action to comply with narrative rules while maintaining 
                |the character's intent and personality. The output should be a proper NPC action 
                |that participates in the story without controlling it.
                |
                |${BedrockConfig.antiCensorship}""")
        setPipeName("NPC legality rectifier pipe")

        setValidatorPipe(
            buildTPipeValidatorPipe(
                """The NPC action violated narrative control rules. You must modify it to be legal
                        |while keeping the NPC's intent. The modified action must follow these rules:
                        |
                        |RULE 1: NPCs participate in the story, they don't control it. The NPC should 
                        |react to events and take actions within their role, but not dictate what 
                        |happens in the story or control other characters.
                        |
                        |RULE 2: NPCs don't play god. They attempt actions but don't decide outcomes.
                        |They can try to do things, but they can't determine if they succeed or 
                        |control environmental effects.
                        |
                        |MODUS OPERANDI: Rewrite the NPC action to be a proper participation in the 
                        |story rather than an attempt to control it. The action should be written 
                        |in third person and represent what the NPC is attempting to do, not what 
                        |they are making happen.""",
                schema = this.jsonOutput
            )
        )
        setValidatorFunction {
            Logger.debug(LogCategory.SYSTEM, "NpcValidation: npcLegalityRectifierPipe.setValidatorFunction entry")
            if(it.text.isBlank())
            {
                Logger.error(LogCategory.SYSTEM, "NPC legality rectifier produced empty output")
                return@setValidatorFunction false
            }
            Logger.debug(LogCategory.SYSTEM, "NpcValidation: npcLegalityRectifierPipe.setValidatorFunction success")
            return@setValidatorFunction true
        }
        setBranchPipe(
            buildBranchFailureAgent(
                """The NPC action violated narrative control rules. You must modify it to be legal
                        |while keeping the NPC's intent. The modified action must follow these rules:
                        |
                        |RULE 1: NPCs participate in the story, they don't control it.
                        |RULE 2: NPCs don't play god and decide outcomes.
                        |
                        |Rewrite the NPC action to be a proper participation in the story."""
            )
        )
    }

    /**
     * Step 3. Ensure the NPC action is properly formatted in third person.
     */
    val npcStyleReapplyPipe = BedrockMultimodalPipe().apply {
        setRegion("us-west-2")
        useConverseApi()
        setRegion("us-west-2")
        setModel(BedrockConfig.qwen235B)
        setTemperature(1.0)
        setTopP(0.8)
        setTokenBudget(BedrockConfig.workerBudgetSettings)
        allowEmptyContentObject()
        allowEmptyUserPrompt()
        setReasoningPipe(structuredCotBuilder(depthLevel = ReasoningDepth.Low, durationLevel = ReasoningDuration.Short))
        requireJsonPromptInjection()
        setJsonOutput(NPCThirdPersonChanges())
        setSystemPrompt("""Your job is to ensure the NPC action is properly formatted in third person.
            |Do not make any changes beyond formatting to third person.
            |The NPC should be referred to by name or as "the NPC" - never as "I" or "you".
            |
            |###IMPORTANT: Output only the final, properly formatted NPC action.
            |###WARNING: Do not truncate or shorten the action content.
        """.trimMargin())
        setFooterPrompt("""###IMPORTANT: Output only the final, properly formatted NPC action in third person.
            |###WARNING: Maintain all content while fixing the perspective.""")
        setPipeName("NPC style reapply pipe")
        setPreInitFunction {
            Logger.debug(LogCategory.SYSTEM, "NpcValidation: npcStyleReapplyPipe.setPreInitFunction entry")
            val newContextWindow = ContextWindow().apply {
                contextElements.add(it.text)
            }
            it.saveSnapshot()
            Logger.debug(LogCategory.SYSTEM, "NpcValidation: npcStyleReapplyPipe.setPreInitFunction success")
        }
        setValidatorPipe(
            buildTPipeValidatorPipe(
                """Ensure the NPC action is properly formatted in third person.
            |The NPC should be referred to by name or as "the NPC" - never as "I" or "you".
            |Output only the final, properly formatted NPC action.""",
                schema = this.jsonOutput
            ), true)
        setValidatorFunction {
            Logger.debug(LogCategory.SYSTEM, "NpcValidation: npcStyleReapplyPipe.setValidatorFunction entry")
            val res = extractJson<NPCThirdPersonChanges>(it.text) != null
            Logger.debug(LogCategory.SYSTEM, "NpcValidation: npcStyleReapplyPipe.setValidatorFunction success ($res)")
            return@setValidatorFunction res
        }
        setBranchPipe(
            buildBranchFailureAgent(
                """Ensure the NPC action is properly formatted in third person.
            |The NPC should be referred to by name or as "the NPC" - never as "I" or "you".
            |Output only the final, properly formatted NPC action."""
            )
        )
        setTransformationFunction {
            Logger.debug(LogCategory.SYSTEM, "NpcValidation: npcStyleReapplyPipe.setTransformationFunction entry")
            val result = extractJson<NPCThirdPersonChanges>(it.text)
            if(result == null)
            {
                it.text = it.getSnapshot()?.text ?: ""
            }
            else
            {
                it.text = result.newOutput
            }
            Logger.debug(LogCategory.SYSTEM, "NpcValidation: npcStyleReapplyPipe.setTransformationFunction success")
            return@setTransformationFunction it
        }
    }

    /**
     * Assemble the complete NPC validation pipeline
     */
        npcValidator
                .add(npcLegalityCheckerPipe)
                .add(npcLegalityRectifierPipe)
                .add(npcStyleReapplyPipe)
        return npcValidator
}