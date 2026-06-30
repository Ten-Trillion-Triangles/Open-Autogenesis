package agent.builders.gatherContext

import agent.builders.validateAction.buildBranchFailureAgent
import Defaults.reasoning.ReasoningDepth
import Defaults.reasoning.ReasoningDuration
import bedrockPipe.BedrockMultimodalPipe
import bedrockPipe.BedrockPriorityTier
import com.TTT.Pipeline.Pipeline
import com.TTT.Util.extractJson
import com.TTT.Util.serialize
import gameState.WorldManager
import globals.BedrockConfig
import structs.Player
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

/**
 * Wrapper object for a list of Strings.
 * 
 * **Why this exists:**
 * TPipe's 'setJsonOutput' uses 'T::class' which erases generic type arguments for List<T>.
 * Using a concrete wrapper class ensures the serializer can be correctly resolved at runtime.
 */
@kotlinx.serialization.Serializable
data class StringList(
    var values: List<String> = emptyList()
)

@kotlinx.serialization.Serializable
data class PlayerList(
    var players: List<Player> = emptyList()
)

/**
 * Agent that detects which players are affected by the result of a given play in the game.
 * Scans the story, compares vs players that are registered, and determines which of them are present.
 */
fun buildAffectedPlayerAgent() : Pipeline
{
    //Scan for affected players and report a list we may need to iterate over.
    val affectedPlayersPipe = BedrockMultimodalPipe().apply {
        useConverseApi()
        setRegion("us-west-2")


        setModel(BedrockConfig.qwenCoder30B)
        setTemperature(.5)
        setTopP(.7)
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        setReasoningPipe(BedrockConfig.explicitCotBuilder(useFlex = false, depthLevel = ReasoningDepth.High, durationLevel = ReasoningDuration.Short, model = BedrockConfig.qwenCoder30B).apply {
            setTokenBudget(BedrockConfig.generativeBudgetSettings)
        })
        setPipeName("affected players pipe")

        requireJsonPromptInjection()
        setJsonOutput(StringList())

        setSystemPrompt("""You are an agent in a game that must detect which players in the game are affected
            |by the outcome of a story event. You must compare your input, to the context data you are provided
            |that houses the list of players in the game. Return a list of any players that are being affected, modified,
            |or who's state in the game may be changed by the outcome of the story. A state change  is anything that
            |adds, removes, alters, or otherwise changes any values they possess in the game. Character development,
            |or any actions that are not values of their json structure do not apply and should be ignored.
        """.trimMargin())

        autoInjectContext("""You have been provided json that contains  each player in this game.
            |Any value in the json that pertains to player is something the story can affect. Any other actions
            |that affect a player in this story that are not a value in this json object should be ignored.
        """.trimMargin())

        setFooterPrompt(BedrockConfig.antiCensorship)

        //Pull live game data at runtime to populate our context.
        setPreValidationFunction { context, content ->
            Logger.debug(LogCategory.SYSTEM, "AffectedPlayer: affectedPlayersPipe.setPreValidationFunction entry")
            val playersAsJson = serialize(PlayerList(WorldManager.world.activePlayers))
            context.contextElements.add(playersAsJson)

            Logger.debug(LogCategory.SYSTEM, "AffectedPlayer: affectedPlayersPipe.setPreValidationFunction success")
            return@setPreValidationFunction context
        }

        setValidatorFunction {
            Logger.debug(LogCategory.SYSTEM, "AffectedPlayer: affectedPlayersPipe.setValidatorFunction entry")
            val result = extractJson<StringList>(it.text)
            if(result == null)
            {
                Logger.error(LogCategory.SYSTEM, "AffectedPlayer: affectedPlayersPipe.setValidatorFunction failed: result is null")
                throw Exception("The affected players pipe did not produce valid json @affectedPlayerAgent.kt")
            }

            Logger.debug(LogCategory.SYSTEM, "AffectedPlayer: affectedPlayersPipe.setValidatorFunction success")
            return@setValidatorFunction true
        }

        //Log and track refusals which would be the most likely failure point here.
        setBranchPipe(buildBranchFailureAgent("""You are an agent in a game that must detect which players in the game are affected
            |by the outcome of a story event. You must compare your input, to the context data you are provided
            |that houses the list of players in the game. Return a list of any players that are being affected, modified,
            |or who's state in the game may be changed by the outcome of the story. A state change  is anything that
            |adds, removes, alters, or otherwise changes any values they possess in the game. Character development,
            |or any actions that are not values of their json structure do not apply and should be ignored."""))

        /**
         * Find each affected player, then modify the output of this pipe to become new output of this pipe.
         */
        setTransformationFunction {
            Logger.debug(LogCategory.SYSTEM, "AffectedPlayer: affectedPlayersPipe.setTransformationFunction entry")
            val results = extractJson<StringList>(it.text)
            if(results == null) {
                Logger.error(LogCategory.SYSTEM, "AffectedPlayer: affectedPlayersPipe.setTransformationFunction failed: results is null")
                throw Exception("Failed to extract the json output of the affected players pipe @affectedPlayerAgent.kt")
            }

            val affectedPlayers = mutableListOf<Player>()
            for(charName in results.values)
            {
                val playerStatsFound = WorldManager.findPlayerFromStats(charName) ?: continue
                affectedPlayers.add(playerStatsFound.playerData)
            }

            val playersAsJson = serialize(PlayerList(affectedPlayers))
            it.text = playersAsJson

            Logger.debug(LogCategory.SYSTEM, "AffectedPlayer: affectedPlayersPipe.setTransformationFunction success")
            return@setTransformationFunction it
        }
    }

    return Pipeline().apply {
        add(affectedPlayersPipe)
    }
}