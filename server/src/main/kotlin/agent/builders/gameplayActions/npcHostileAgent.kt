package agent.builders.gameplayActions

import Defaults.reasoning.ReasoningDepth
import Defaults.reasoning.ReasoningDuration
import agent.builders.validateAction.buildBranchFailureAgent
import agent.builders.validateAction.buildTPipeValidatorPipe
import bedrockPipe.BedrockMultimodalPipe
import bedrockPipe.BedrockPriorityTier
import com.TTT.Context.ContextWindow
import com.TTT.Pipeline.Pipeline
import com.TTT.Util.extractJson
import com.TTT.Util.serialize
import gameState.WorldManager
import globals.BedrockConfig
import structs.Npc
import kotlinx.coroutines.runBlocking
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

@kotlinx.serialization.Serializable
data class HostileNpcPlan(
    var idea: String = "",
    var reason: String = ""
)

fun buildHostileNpcAgent(npcData: Npc) : Pipeline
{
    val npcPrompt = "You are: ${npcData.name}. Description: ${npcData.description} ${npcData.personality} Abilities: ${npcData.abilities} History: ${npcData.history}"

    val baseSystemPrompt  = """You are an NPC in the game Autogenesis: ${BedrockConfig.gameDescription} You exist both in the game's story and
        |you are also able to directly participate in the gameplay itself. As a hostile npc you the following traits,
        |abilities, and behaviors:
        |
        |- You can see game data unlike lower level npc's. You may use this data to inform your decision on what
        |actions you intend to make.
        |- You are able to make decisions about your actions as an npc at the 4th wall. This means your choices of actions
        |are not bound by state of your character in the story and you can operate with the same level of knowledge as
        |the player.
        |- You have equal narrative control over the story as a player. This means You can invoke things your character
        |would be able to logically do, recruit allies, summon other characters, and use any resources or assets you have
        |regardless of what is happening in the narrative to your character at the time.
        |- You are unaffected by the core game rules and can ignore buffs, debuffs, and other territory restrictions.
        |This means that you may affect or target any other object on the game's map or world regardless of where you are
        |in the game's world at the time.
        |
        |As a hostile npc you also apply the following general behaviors:
        |- You seen to expand your power, goals, and influence over the game world. Actions you take generally align
        |with this game plan unless your character traits, or other decisions made by the story override this.
        |- You may come into hostile contact with players if beneficial to it. May also help players when beneficial.
        |- If you have a grudge against a certain character in the story, you will generally focus more on targeting
        |that character. Also, you will generally not help that character unless you need to in order to advance
        |your agenda.
        |- You may hold alliances with other NPC's or players.
        |- You generally prioritize expanding territory, power, or your agenda. Regardless of if a player is caught in between or not.
        |- You may intervene with a player if the player's agenda interferes with your own. But otherwise will focus on advancing your own goal.
        |- When deciding your action. You balance risk vs reward against the game's context and take your geo-political status
        |into account, as well as evaluate to some extent geo-political risks when you take an action. You take higher risks
        |when more favorable to do so, or if your hand is forced.
        |
        |When acting as an npc you must play this character: $npcPrompt
        |
        |**STRICT GROUNDING RULES:**
        |- You MUST ONLY reference territories that actually exist in the 'world' context provided.
        |- DO NOT invent imaginary locations, people, or factions.
        |- Your plans and actions MUST be physically possible within the current map state.
        |- Focus your plays on actions that affect the game world. Avoid making plays that are incoherent, or otherwise
        |only affects yourself, or just wastes the turn. Advance or affect the game state with any given action you take.
        |
        |You must always stay in character and act only as that character when following your system prompt instructions
        |and other rules for your given tasks.
    """.trimMargin()

    val optionsPipe = BedrockMultimodalPipe().apply {
        useConverseApi()
        setRegion("us-west-2")
        setRegion("us-west-2")

        setModel(BedrockConfig.qwen235B)
        setTemperature(.9)
        setTopP(.7)
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        allowEmptyContentObject()
        allowEmptyUserPrompt()
        pullGlobalContext()
        pullPipelineContext()
        setPageKey("story")
        setReasoningPipe(BedrockConfig.authorBuilder(npcPrompt, depth = ReasoningDepth.High, duration = ReasoningDuration.Long, showThinking = true, actorName = npcData.name, isPlayer = false))
        setPageKey("story")
        setPipeName("options pipe")

        setSystemPrompt("""$baseSystemPrompt Your first step as an npc is to draw up ideas of actions you can take. You may draw up
            |as many ideas as you like. Or even just a single idea. Examine the state of the game and the story,
            |as well as your character, and come up with one or more ideas on what you would like to do here.
        """.trimMargin())

        autoInjectContext("""You have been provided with several pages of context that contains useful
            |game data you can leverage in your decision making. "story" contains the game's current story. "world" contains
            |game data on the game's world, the players in it, and the npc's inside of it.
        """.trimMargin())

        setValidatorFunction {
            Logger.debug(LogCategory.SYSTEM, "HostileNpc: optionsPipe.setValidatorFunction entry")
            val result = extractJson<HostileNpcPlan>(it.text)
            if(result == null) {
                Logger.error(LogCategory.SYSTEM, "HostileNpc: optionsPipe.setValidatorFunction failed: result is null")
                throw Exception("""Failed to extract json in options pipe @npcHostileAgent.kt""")
            }

            Logger.debug(LogCategory.SYSTEM, "HostileNpc: optionsPipe.setValidatorFunction success")
            return@setValidatorFunction true
        }
    }

    val actionsPipe = BedrockMultimodalPipe().apply {
        useConverseApi()
        setRegion("us-west-2")
        setRegion("us-west-2")

        setModel(BedrockConfig.qwen235B)
        setTemperature(.9)
        setTopP(.7)
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        allowEmptyContentObject()
        allowEmptyUserPrompt()
        pullGlobalContext()
        pullPipelineContext()
        requireJsonPromptInjection()
        setJsonInput(HostileNpcPlan::class)
        setReasoningPipe(BedrockConfig.authorBuilder(npcPrompt, depth = ReasoningDepth.High, duration = ReasoningDuration.Long, showThinking = true, actorName = npcData.name, isPlayer = false))
        setPageKey("story")
        setPipeName("action pipe")

        setSystemPrompt("""$baseSystemPrompt Now your next step is decide on any of the provided ideas for your next action. You must
            |examine each, examine the story, and examine the game world and pick which option fits your goal this turn.
            |Once you have done so. Return either story content in third person, or instructions on what you would have
            |your character do.
        """.trimMargin())

        autoInjectContext("""You have been provided with several pages of context that contains useful
            |game data you can leverage in your decision making. "story" contains the game's current story. "world" contains
            |game data on the game's world, the players in it, and the npc's inside of it.""")

        setFooterPrompt("""**REMEMBER: Only target real territories from the 'world' data.** Also keep your play
            |in the range of 1-2K characters at most. Don't vomit multiple paragraphs for your play.
        """.trimMargin())

        setValidatorPipe(buildTPipeValidatorPipe("""Validate that the prior agent carried out a task
            |and did not outright refuse to do the task for any reason. Also validate to ensure the agent understood
            |the task, and did not state that it was confused, did not know what to do, or otherwise in an obvious 
            |manner indicate that it could not perform the task. 
        """.trimMargin(), schema = this.jsonOutput))

        setBranchPipe(buildBranchFailureAgent("""$baseSystemPrompt Now your next step is decide on any of the provided ideas for your next action. You must
            |examine each, examine the story, and examine the game world and pick which option fits your goal this turn.
            |Once you have done so. Return either story content in third person, or instructions on what you would have
            |your character do."""))
    }

    return Pipeline().apply {
        add(optionsPipe)
        add(actionsPipe)

        setPreValidationFunction { context, miniBank, content ->
            Logger.debug(LogCategory.SYSTEM, "HostileNpc: Pipeline.setPreValidationFunction entry")
            val worldAsJson = serialize(WorldManager.world)
            val newContextWindow = ContextWindow().apply {
                contextElements.add(worldAsJson)
            }
            miniBank.contextMap["world"] = newContextWindow
            Logger.debug(LogCategory.SYSTEM, "HostileNpc: Pipeline.setPreValidationFunction success")
        }
    }.also { pipeline ->
        runBlocking { pipeline.init(true) }
    }
}