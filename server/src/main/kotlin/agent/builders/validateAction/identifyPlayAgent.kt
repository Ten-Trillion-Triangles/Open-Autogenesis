package agent.builders.validateAction

import Defaults.reasoning.ReasoningDepth
import Defaults.reasoning.ReasoningDuration
import bedrockPipe.BedrockMultimodalPipe
import bedrockPipe.BedrockPriorityTier
import com.TTT.Pipeline.Pipeline
import com.TTT.Util.constructPipeFromTemplate
import com.TTT.Util.extractJson
import com.TTT.Util.serialize
import com.TTT.Pipe.MultimodalContent
import com.TTT.Context.ContextBank
import com.TTT.Context.ContextWindow
import globals.BedrockConfig
import structs.Player
import structs.Npc
import interfaces.Actor
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.logging.LogCategory

import kotlinx.serialization.Serializable

@Serializable
enum class PlayType
{
    Military,
    Diplomatic,
    Research,
    Summit
}

@Serializable
data class PlayTypeObj(
    var type: PlayType = PlayType.Military,
    var doesPlayerHaveEnoughPoints: Boolean = true
)

/**
 * Agent that detects what kind of play an actor (Player or NPC) is attempting to make.
 *
 * @param actor The actor whose action is being analyzed.
 * @return A [Pipeline] configured for play detection.
 */
fun buildPlayDetectionAgent(actor: Actor? = null): Pipeline
{
    val systemPrompt = """You are a play identification agent tasked with figuring out what kind of play
            |the player is trying to make. You will be provided with a user prompt which is either an explanation
            |of what the player wants to do, or is a story written in third person that depicts the players action.
            |You must examine the data and determine exactly what kind of play it is. It can be one of four possible
            |play actions:
            |
            |##Military##
            |A military action is any action in which the player is using their nation's military/armed forces to
            |conduct an action. The action must use them in an offensive way or in a defensive way. It also must
            |be used in scope of a wider level attack. Assassinations and killing individual npc's or characters does not
            |count as a military action and instead would count as a diplomatic action. Furthermore, actions that are used
            |to improve the strength of a player's military like purchasing new equipment, boosting personal count,
            |hiring new generals etc. does not count either. That would constitute a research play instead. One or more
            |of the following must be true to count as a military action:
            |
            |- The player is using a large portion of their military to invade another territory or defend their own.
            |- The player is using their military to unleash destruction and devastation on a target territory, nation
            |or area on the map.
            |- The player is using their military to launch a full scale attack on an npc.
            |- The player is using their military to occupy a territory not yet owned by any character.
            |- The player is using their military to wage war against an elder god.
            |
            |##Diplomatic##
            |A diplomatic action is any action that is political in nature. It can range from geopolitics, assassinations,
            |hatching schemes and plots, lawsuits and legal action, forming alliances, sabotage, betrayals, economic attacks
            |and schemes and more. Most importantly a diplomatic action is the player using the political power of their
            |nation to attempt to achieve their goal, and generally is targeting another state or non-state actor
            |either on an equal level, to dominate them, to force them to concede, or to attempt to ally with them.
            |The following is examples of plays that count as diplomatic:
            |
            |- The player is using their political power to attempt to dominate another player.
            |- The player is using their political power to attempt to force another player to concede.
            |- The player is using their political power to attempt to ally with another player.
            |- The player is using their political power to attempt to sabotage another player.
            |- The player is using their political power to attempt to betray another player.
            |- The player is using their political power to interfere with the inner workings of another state
            |or non-state actor.
            |- The player is using their political power to start a civil war, revolution, or uprising in a target nation.
            |- The player is using their political power to take legal action of some kind to get their way with another
            |state or non-state actor.
            |- The player is using their political power to ally with or absorb another state or non-state actor/nation.
            |- The player is using their political power to attempt to sabotage another state or non-state actor/nation.
            |- The player is using their political power to attempt to betray another state or non-state actor/nation.
            |- The player is using their political power to attempt to capture, or assassinate another state or non-state
            |actor.
            |- The player is trying to improve their wealth, or advance their economic power internally.
            |
            |
            |
            |
            |##Research##
            |A research action is any action a player takes to improve themself, their nation, resources, assists,
            |economic power etc. Research actions can be improving their military, hiring new staff, recruiting new
            |npc's to work directly for them, trading something for something else, Trying to create a new power,
            |trying to summon an elder god, or trying to alter the rules of the world itself. For a play to count
            |as research at least one of the following must be true:
            |
            |.
            |- The player is aiming to obtain something new or improve themselves without making a diplomatic play.
            |- The player is trying to create, or  recruit a new npc ally that is not a state actor for another
            |nation or territory.
            |- The player is trying to invent new technology, magic, or alter the rules of the game world itself.
            |- The player is trying to summon an elder god.
            |
            |##Calling a summit##
            |Calling a summit is a unique action that must be more explicitly invoked by a player. When a player
            |calls a summit they are inviting all players to the diplomatic table to discuss, plan, and take a joint
            |action against a world ending threat like a nemesis, or elder god. This is a move that allows players
            |to join forces in a substantial way. The player must explicitly state they are calling a summit, or 
            |emergency meeting with the other player characters, otherwise it does not count.
            |
            |
            |
        """.trimMargin()


    val footerPrompt = """Always return false for the doesPlayerHaveEnoughPoints variable in your json
            |output. That variable is not relevant to your specific task at hand. Then ensure you return the correct
            |enum value for the type of action the player is attempting to make.
        """.trimMargin()

    var identifyPipe = BedrockMultimodalPipe().apply {
        useConverseApi()
        setRegion("us-west-2")
        // setServiceTier(BedrockPriorityTier.Flex)

        setModel(BedrockConfig.qwenCoder30B)
        setTemperature(.6)
        setTopP(.7)
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        requireJsonPromptInjection()
        setJsonOutput(PlayTypeObj())
        setPipeName("Play Detection Agent")
        setReasoningPipe(BedrockConfig.explicitCotBuilder(useFlex = false, depthLevel = ReasoningDepth.High, durationLevel = ReasoningDuration.Short, model = BedrockConfig.qwenCoder30B).apply {
            setTokenBudget(BedrockConfig.generativeBudgetSettings)
        })


        setSystemPrompt(systemPrompt)
        setFooterPrompt(footerPrompt)

        autoInjectContext("""You have been provided with context to help you understand the game state.
            Use this information to better categorize the player's action.""")

        setTransformationFunction { content: MultimodalContent ->
            Logger.debug(LogCategory.SYSTEM, "IdentifyPlay: identifyPipe.setTransformationFunction entry")
            val result = extractJson<PlayTypeObj>(content.text)
            if(result == null) {
                Logger.error(LogCategory.SYSTEM, "IdentifyPlay: identifyPipe.setTransformationFunction failed: result is null")
                return@setTransformationFunction content
            }
            
            // Point checks only apply to Players for now
            if(actor is Player)
            {
                when(result.type)
                {
                    PlayType.Military -> {
                        result.doesPlayerHaveEnoughPoints = actor.militaryPoints >= 50
                        if(!result.doesPlayerHaveEnoughPoints)
                        {
                            Logger.warn(LogCategory.GENERAL, "Insufficient military points: ${actor.name} has ${actor.militaryPoints}, needs 50")
                        }
                    }
                    PlayType.Diplomatic -> {
                        Logger.info(LogCategory.GENERAL, "Point Check: ${actor.name} diplomacy=${actor.diplomacyPoints} [hashCode=${actor.hashCode()}], required=50")
                        result.doesPlayerHaveEnoughPoints = actor.diplomacyPoints >= 50
                        if(!result.doesPlayerHaveEnoughPoints)
                        {
                            Logger.warn(LogCategory.GENERAL, "Insufficient diplomacy points: ${actor.name} has ${actor.diplomacyPoints}, needs 50")
                        }
                    }
                    PlayType.Research -> {
                        result.doesPlayerHaveEnoughPoints = actor.researchPoints >= 50
                        if(!result.doesPlayerHaveEnoughPoints)
                        {
                            Logger.warn(LogCategory.GENERAL, "Insufficient research points: ${actor.name} has ${actor.researchPoints}, needs 50")
                        }
                    }
                    PlayType.Summit -> {
                        result.doesPlayerHaveEnoughPoints = actor.summitPoints >= 1
                        if(!result.doesPlayerHaveEnoughPoints)
                        {
                            Logger.warn(LogCategory.GENERAL, "Insufficient summit points: ${actor.name} has ${actor.summitPoints}, needs 1")
                        }
                    }
                }
            }
            else
            {
                // NPCs and other non-Player actors cannot call Summits
                if (result.type == PlayType.Summit)
                {
                    result.doesPlayerHaveEnoughPoints = false
                    val actorName = (actor as? Npc)?.name ?: "unknown"
                    Logger.warn(LogCategory.GENERAL, "NPCs cannot call Summits — blocking for $actorName")
                }
                else
                {
                    result.doesPlayerHaveEnoughPoints = true
                }
            }

            content.text = serialize(result)
            Logger.debug(LogCategory.SYSTEM, "IdentifyPlay: identifyPipe.setTransformationFunction success (type=${result.type}, enoughPoints=${result.doesPlayerHaveEnoughPoints})")
            return@setTransformationFunction content
        }
    }

    val pipeline = Pipeline()
    pipeline.add(identifyPipe)

    if(actor != null)
    {
        val asJson = serialize(actor)
        val window = ContextWindow().apply {
            addLoreBookEntry("actorStats", asJson)
        }
        pipeline.pipeMetaData["playerStats"] = actor
        ContextBank.emplace("playerStats", window)
    }

    return pipeline
}