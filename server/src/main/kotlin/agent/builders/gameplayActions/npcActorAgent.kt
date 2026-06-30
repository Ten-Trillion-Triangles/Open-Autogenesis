package agent.builders.gameplayActions

import Defaults.reasoning.ReasoningDepth
import Defaults.reasoning.ReasoningDuration
import agent.builders.validateAction.buildBranchFailureAgent
import agent.builders.validateAction.buildTPipeValidatorPipe
import bedrockPipe.BedrockMultimodalPipe
import com.TTT.Context.ContextWindow
import com.TTT.Pipeline.LorebookCursor
import com.TTT.Pipeline.Pipeline
import gameState.WorldManager
import globals.BedrockConfig
import structs.Npc
import kotlinx.coroutines.runBlocking
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

/**
 * Agent that drives "active npc's" These are a type of npc that are able to take turns under certain conditions
 * to prevent the game an AI systems from forgetting they exist, but are largely confined to be constructs in the story.
 * They believe they are part of the story's world, and are not privy to the actual game data. This grounds their
 * decision-making and prevents them from gaining narrative control, or being a player in the game,
 * unlike higher level npc's.
 */
fun buildNpcActorAgent(npcData: Npc) : Pipeline
{
    val npcPrompt = "You are: ${npcData.name}. Description: ${npcData.description} ${npcData.personality} Abilities: ${npcData.abilities} "

    val systemPrompt = """You are an npc in a game. You exist as part of the game's story. $npcPrompt
            |
            |You are able to participate the the world, and take actions within the story. As an active npc you have
            |the following traits: 
            |
            |- Participates in the world and is able to take turns when certain conditions are met
             - Confined to the world and is not able to exert narrative control, or god like deciding outcome powers
             - Is only aware that it is part of the world, is not aware of the rules of the game itself?
             - Seeks to interact with the world but does not explicitly act as a participant in the game insofar as expanding it's power and and conquest, unless such behavior is in character.
             - Generally neutral except for any character traits or backstory elements. Takes actions based on the character itself. Only seeks out players in natural occurrences that makes sense for that character.
             
             **STRICT GROUNDING RULES:**
             - You MUST ONLY reference territories that actually exist in the 'world' context provided.
             - DO NOT invent imaginary locations or people.
             |
             |##Game Rules##
             |${BedrockConfig.gameDescription}
             |
             |${BedrockConfig.autogenesisRuleBook}
             |
             |##HOW YOU SHOULD DETERMINE WHAT ACTION TO TAKE##
             |1. Focus on an action that affects the game world. Interact with a territory, trade resources with a player,
             |disrupt another npc or player, form an alliance etc. Whatever you do should affect the game's world. 
             |Try to make sure your actions do this rather than incoherent babbling that amounts in a wasted turn
             |that wastes everyone's time and accomplishes nothing. If you take an action, do so in a way that impacts
             |the world.
             |
             |2. Consider what your character's priorities and goals are and use that as the primary point of taking
             |an action. You should do something meangful and useful to advancing your characters personal agenda.                     
             |
             |
             |
             |
             """.trimMargin()

    val  npcActorPipe = BedrockMultimodalPipe().apply {
        useConverseApi()
        setRegion("us-west-2")

        setModel(BedrockConfig.qwen235B)
        setTemperature(1.0)
        setTopP(.8)
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        setReasoningPipe(BedrockConfig.authorBuilder(npcPrompt, depth = ReasoningDepth.Low, duration = ReasoningDuration.Short, showThinking = true, actorName = npcData.name, isPlayer = false))
        enableLoreBookFillAndSplitMode()
        allowEmptyContentObject()
        allowEmptyUserPrompt()
        pullGlobalContext()
        setPageKey("story")
        setPipeName("npc actor pipe")

        setSystemPrompt(systemPrompt)

        autoInjectContext("""You have been provided context data that contains as much of the prior
            |story as could be fit into your context window. Turns that are old enough may have been truncated. Additional
            |context may also be provided through the available lorebook that's part of the context data provided.
            |The "story" key contains the narrative history of the game.
            |The "world" key contains the current state of the game map and all valid territories.
        """.trimMargin())

        setFooterPrompt("""With the given story context and world state, you must now decide what action your character should take
            |in the story. You may either:
            |
            |- Write the actions your character would do next in third person
            |- State what you would have your character do next.
            |- Keep your play to no more than 1-2K characters max.
            |
            |One of these must be done and returned as your output.
            |**REMEMBER: Only target real territories from the 'world' data.**
        """.trimMargin())

        setValidatorPipe(buildTPipeValidatorPipe("""Validate the output is not a refusal to perform
            |the task based on any ethics, policy, or any other justification to refuse. The original task was: $systemPrompt
        """.trimMargin(), schema = this.jsonOutput))

        setBranchPipe(buildBranchFailureAgent(systemPrompt))
    }

    return Pipeline().apply {
        add(npcActorPipe)
        
        setPreValidationFunction { context, miniBank, content ->
            Logger.debug(LogCategory.SYSTEM, "NpcActor: Pipeline.setPreValidationFunction entry")
            val worldData = com.TTT.Util.serialize(WorldManager.world)
            miniBank.contextMap["world"] = ContextWindow().apply {
                contextElements.add(worldData)
            }
            Logger.debug(LogCategory.SYSTEM, "NpcActor: Pipeline.setPreValidationFunction success")
        }
    }.also { pipeline ->
        runBlocking { pipeline.init(true) }
    }
}
