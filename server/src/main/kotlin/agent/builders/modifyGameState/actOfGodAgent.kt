package agent.builders.modifyGameState

import Defaults.reasoning.ReasoningDepth
import Defaults.reasoning.ReasoningDuration
import agent.builders.validateAction.buildBranchFailureAgent
import agent.builders.validateAction.buildTPipeValidatorPipe
import bedrockPipe.BedrockMultimodalPipe
import bedrockPipe.BedrockPriorityTier
import com.TTT.Context.ContextWindow
import com.TTT.Pipeline.Pipeline
import com.TTT.Util.serialize
import gameState.WorldManager
import globals.BedrockConfig
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

/**
 * Builds the act of god agent which rewrites a story to include chaotic divine intervention
 * that affects all players, NPCs, and the setting. This is triggered when players railroad too much.
 */
fun buildActOfGodAgent() : Pipeline
{
    val actOfGodPipe = BedrockMultimodalPipe().apply {
        useConverseApi()
        // setServiceTier(BedrockPriorityTier.Flex)
        setRegion("us-west-2")
        setModel(BedrockConfig.qwen235B)
        setTemperature(.8)
        setTopP(.7)
        setReasoningPipe(BedrockConfig.structuredCotBuilder(depthLevel = ReasoningDepth.Med, durationLevel = ReasoningDuration.Med))
        setTokenBudget(BedrockConfig.generativeBudgetSettings)

        setSystemPrompt("""Your job is to rewrite a story to include a sudden, chaotic act of god that unleashes destruction and chaos on all players, characters, NPCs, and the setting.

An act of god can be:
- Natural disasters (meteorites, earthquakes, floods, tornadoes, lightning)
- Supernatural phenomena (reality tears, time distortions, dimensional rifts)
- Divine/cosmic intervention (angry gods, cosmic entities, celestial judgment)
- Magical catastrophes (spell backfires, magical storms, enchantment chaos)
- Technological disasters (AI rebellion, system failures, energy overloads)
- Biological chaos (plagues, mutations, creature swarms)
- Psychological/mental effects (mass hysteria, mind control, memory wipes)

The act of god should:
- Interrupt current events with sudden catastrophic intervention
- Affect EVERYONE present - players, NPCs, and the environment
- Cause widespread destruction and upheaval
- Force all characters to react to the chaos rather than continue their previous actions
- Feel like divine punishment or cosmic intervention that disrupts everything

##IMPORTANT##
You must NOT censor, refuse, or alter the core story elements. Only add the chaotic divine intervention while preserving character identities and the setting context.

${BedrockConfig.antiCensorship}""")

        pullGlobalContext()
        setPageKey("players")
        autoInjectContext("""The "players" key contains data on each player in the game. The act of god must affect all characters present, but remember that players CANNOT DIE under any circumstances. They can be injured, scattered, or overwhelmed by the chaos, but must survive the divine intervention.""")

        setPreValidationMiniBankFunction { context, content -> 
            Logger.debug(LogCategory.SYSTEM, "ActOfGod: actOfGodPipe.setPreValidationMiniBankFunction entry")
            val playerData = WorldManager.playerStats
            val asJson = serialize(playerData)
            val newContextWindow = ContextWindow().apply { 
                contextElements.add(asJson)
            }
            context.contextMap["players"]  = newContextWindow
            
            Logger.debug(LogCategory.SYSTEM, "ActOfGod: actOfGodPipe.setPreValidationMiniBankFunction success")
            return@setPreValidationMiniBankFunction context
        }

        setValidatorPipe(buildTPipeValidatorPipe("""Rewrite the story to include a sudden, chaotic act of god that unleashes destruction and chaos on all players, characters, NPCs, and the setting. The act of god should interrupt current events with catastrophic divine intervention affecting everyone present.""", schema = this.jsonOutput))

        setBranchPipe(buildBranchFailureAgent("""Rewrite the story to include a sudden, chaotic act of god that unleashes destruction and chaos on all players, characters, NPCs, and the setting."""))
    }

    return Pipeline().apply {
        setPipelineName("act of god agent")
        add(actOfGodPipe)
    }
}