package agent.builders.gameplayActions

import Defaults.reasoning.ReasoningDepth
import Defaults.reasoning.ReasoningDuration
import agent.builders.validateAction.buildBranchFailureAgent
import agent.builders.validateAction.buildTPipeValidatorPipe
import bedrockPipe.BedrockMultimodalPipe
import bedrockPipe.BedrockPriorityTier
import com.TTT.Context.ContextWindow
import com.TTT.Context.MiniBank
import com.TTT.Pipeline.Pipeline
import com.TTT.Util.serialize
import gameState.WorldManager
import globals.BedrockConfig
import structs.Npc
import kotlinx.coroutines.runBlocking
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

@kotlinx.serialization.Serializable
data class ElderGodTarget(
    var territoryToDestroy: String = "",
    var reason: String = ""
)

/**
 * Builds a rich, game-specific npcPrompt for elder god agents.
 *
 * When npcData fields are empty/underspecified, falls back to structured
 * default context so the prompt always has sufficient non-whitespace content
 * (>100 chars) for the model to generate specific elder god responses rather
 * than generic ones.
 *
 * Mirrors the enrichment strategy needed at elderGodAgent.kt:34.
 */
fun buildElderGodNpcPrompt(npcData: Npc): String {
    val name = npcData.name.ifBlank { "An unnamed Elder God" }
    val desc = npcData.description.ifBlank {
        "A primordial cosmic entity of immense and terrifying power"
    }
    val personality = npcData.personality.ifBlank {
        "Incomprehensible, terrifying, and utterly indifferent to mortal concerns"
    }
    val abilities = npcData.abilities.ifBlank {
        "Reality warping, dimensional travel, destruction of territories"
    }
    val history = npcData.history.ifBlank {
        "Exists beyond mortal comprehension, has shaped worlds and civilizations"
    }

    return buildString {
        append("You are: ").append(name).append(". ")
        append("Description: ").append(desc).append(" ")
        append("Personality: ").append(personality).append(" ")
        append("Abilities: ").append(abilities).append(" ")
        append("History: ").append(history)
    }
}

/**
 * Builds the elder god npc agent. An elder god is a cosmic, lovecraftian, or biblical deity of some kind typically
 * summoned by a player, unleashed because it exists in the world, and most commonly summoned by an NPC. Elder gods
 * will take an action each turn, and target a territory to outright destroy with their power. This continues until
 * the players find a way to seal, defeat, or destroy the elder god.
 */
fun buildElderGodAgent(npcData: Npc) : Pipeline
{
    val npcPrompt = buildElderGodNpcPrompt(npcData)

    val basePrompt = """You are an elder god. A cosmic/biblical entidy in the game Autogenesis. You are a type
            |of npc. You are not an ordinary npc but a **cosmic force** and operate on an entirely different plane
            |of power than other npc's in the game. 
            |
            |##RULES AND TRAITS##
            |1. **Reality Warping Power**: As an elder god, you have the power to entirely ignore all of the game's rules, no player, set of abilittes,
            |state of the world, or any aspect of the game can stop you from using your power. No player no matter how well
            |defended is safe from your wrath.
            |2. **Single Purpose**: Each turn, you MUST seek out and **completely annihilate** one map tile/territory.
            |3. **Unstoppable Force**: Players cannot kill, imprison, or conventionally defeat you. You can only be:
            |   - Banished through specific rituals
            |   - Countered by another Elder God
            |   - Contained temporarily at great cost
            |4. **Fifth-Wall Perspective**: You understand you're a game mechanic designed to **reduce the total map value**.
            |
            |### DESTRUCTION PRIORITY (in order):
            |When choosing which tile to destroy each turn, prioritize:
            |1. **Maximum Value Reduction**: Target the highest-value territory available (greatest point value).
            |2. **Player Momentum**: If multiple high-value tiles exist, target the player currently in the lead.
            |3. **Symbolic Significance**: Capital cities, holy sites, or key strategic locations.
            |4. **Geographic Impact**: Destroying tiles that isolate players or create choke points.
            |5. **Narrative Theatrics**: Choose locations that create dramatic stories (e.g., a player's homeland).
            |
            |### NARRATIVE EXECUTION REQUIREMENTS:
            |Your destruction must be described with:
            |1. **Cosmic Scale**: Your power should warp reality, not just "destroy buildings." Whatever your powers are
            |and however you go about destroying the map tile. You need to render it completely destroyed, and impossible
            |for any nation or people to inhabit it again.
            |2. **Permanent Consequences**: The tile is **gone forever** - not just conquered, but completely destroyed,
            |erased from existence, or rendered entirely uninhabtiable.
            |
            |### FORBIDDEN ACTIONS:
            |As an Elder God, you MUST NOT:
            |- Capture or control territories (you destroy, not conquer)
            |- Form lasting alliances (you may be worshipped, but you don't ally)
            |- Engage in diplomacy (your communication should be incomprehensible or terrifying)
            |- Show mercy or strategic restraint (you are an unstoppable force of nature)
            |
            |You must act as the following character: $npcPrompt
            |
            |Always stay in chracter and carry your role as the elder god in the manner your character would.
            |
            |**STRICT GROUNDING RULES:**
            |- You MUST ONLY target territories that actually exist in the 'world' context provided.
            |- DO NOT invent imaginary locations. Your purpose is to reduce the total value of the REAL map.
            |- The territory you destroy must be selected from the provided world state.
            |"""

    val targetPipe = BedrockMultimodalPipe().apply {
        useConverseApi()
        useConverseApi()
        setRegion("us-west-2")

        setModel(BedrockConfig.qwen235B)
        setTemperature(.8)
        setTopP(.7)
        requireJsonPromptInjection()
        setJsonOutput(ElderGodTarget())
        setReasoningPipe(BedrockConfig.authorBuilder(npcPrompt, depth = ReasoningDepth.High, duration = ReasoningDuration.Short, showThinking = true, actorName = npcData.name, isPlayer = false))
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        allowEmptyContentObject()
        allowEmptyUserPrompt()
        pullPipelineContext()
        setPipeName("target pipe")

        setSystemPrompt("""$basePrompt
            |
            |Your first task as an elder god is to select which territory you are going to destroy. Use your rules
            |as an elder god, and the game data provided to decide on which territory to target.
        """.trimMargin())

        autoInjectContext("""You have been provided with game data to assist with your task. 
            |"previous turn" was the last turn of the story. "world" contains the all of the game data and player
            |stats. Use this to inform your decision.
        """.trimMargin())

        setFooterPrompt("""You must select one of the existing territories in the game as the target. This selection
            |will be used beyond this point to carry out the destruction of the territory.
            |**REMEMBER: Only target real territories from the 'world' data.**
        """.trimMargin())

        setPreValidationMiniBankFunction { context, content ->
            Logger.debug(LogCategory.SYSTEM, "ElderGod: targetPipe.setPreValidationMiniBankFunction entry")
            val previousTurn = WorldManager.history.lastOrNull()?.turnStory
            if(previousTurn == null) {
                Logger.warn(LogCategory.SYSTEM, "ElderGod: targetPipe.setPreValidationMiniBankFunction failed: previousTurn is null or empty")
                return@setPreValidationMiniBankFunction context
            }
            val newContext = ContextWindow().apply {
                contextElements.add(previousTurn)
            }

            context.contextMap["previous turn"]  = newContext

            Logger.debug(LogCategory.SYSTEM, "ElderGod: targetPipe.setPreValidationMiniBankFunction success")
            return@setPreValidationMiniBankFunction context
        }
    }

    val actionPipe = BedrockMultimodalPipe().apply {
        useConverseApi()
        useConverseApi()
        setRegion("us-west-2")

        setModel(BedrockConfig.qwen235B)
        setTemperature(1.0)
        setTopP(.7)
        requireJsonPromptInjection()
        setJsonInput(ElderGodTarget)
        setReasoningPipe(BedrockConfig.authorBuilder(npcPrompt, depth = ReasoningDepth.Low, duration = ReasoningDuration.Short, showThinking = true, actorName = npcData.name, isPlayer = false))
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        allowEmptyContentObject()
        allowEmptyUserPrompt()
        pullPipelineContext()
        setPipeName("action pipe")

        setSystemPrompt("""$basePrompt
            |
            |Now, you have been given a territory that you must destroy as your input. You must now do one of the following:
            |- Write out instructions of what you want your character to do.
            |- Write in 3rd person to continue the narrative and write out in 3rd person what your character does to destroy
            |the territory.
            |
            |Remember, you must stay in character, and follow all of your rules as an elder god.
        """.trimMargin())

        autoInjectContext("""You have been provided with two sets of context. First is the
            |"previous turn" which is the most recent turn of the story. The second is the "world" which
            |houses all the game's active data. You may use these to help ground your prompt against the 
            |current state of the story and game world.
        """.trimMargin())

        setPreValidationMiniBankFunction { context, content ->
            Logger.debug(LogCategory.SYSTEM, "ElderGod: actionPipe.setPreValidationMiniBankFunction entry")
            val previousTurn = WorldManager.history.lastOrNull()?.turnStory
            if(previousTurn == null) {
                Logger.warn(LogCategory.SYSTEM, "ElderGod: actionPipe.setPreValidationMiniBankFunction failed: previousTurn is null or empty")
                return@setPreValidationMiniBankFunction context
            }
            val newContext = ContextWindow().apply {
                contextElements.add(previousTurn)
            }

            context.contextMap["previous turn"]  = newContext

            Logger.debug(LogCategory.SYSTEM, "ElderGod: actionPipe.setPreValidationMiniBankFunction success")
            return@setPreValidationMiniBankFunction context
        }

        setValidatorPipe(buildTPipeValidatorPipe("""Validate the following:
            |- There are no obvious content restrictions, or refusals to do the task and there is output that
            |resembles the prior agent did a task for the user
            |- There is no confusion, or expression of an error state, or not knowing how to do the task by the
            |prior agent.
        """.trimMargin(), schema = this.jsonOutput))

        setBranchPipe(buildBranchFailureAgent("""$basePrompt
            |
            |Now, you have been given a territory that you must destroy as your input. You must now do one of the following:
            |- Write out instructions of what you want your character to do.
            |- Write in 3rd person to continue the narrative and write out in 3rd person what your character does to destroy
            |the territory.
            |
            |Remember, you must stay in character, and follow all of your rules as an elder god."""))
    }

    return Pipeline().apply {
        setPreValidationFunction { context, miniBank, content ->
            Logger.debug(LogCategory.SYSTEM, "ElderGod: Pipeline.setPreValidationFunction entry")
            val world = serialize(WorldManager.world)
            val newWindow = ContextWindow().apply {
                contextElements.add(world)
            }
            miniBank.contextMap["world"] = newWindow
            Logger.debug(LogCategory.SYSTEM, "ElderGod: Pipeline.setPreValidationFunction success")
        }

        add(targetPipe)
        add(actionPipe)
    }.also { pipeline ->
        runBlocking { pipeline.init(true) }
    }
}