package agent.builders.modifyGameState

import agent.builders.AgentCoroutineScope
import agent.builders.validateAction.buildBranchFailureAgent
import Defaults.reasoning.ReasoningDepth
import Defaults.reasoning.ReasoningDuration
import bedrockPipe.BedrockMultimodalPipe
import com.TTT.Pipeline.Pipeline
import com.TTT.Util.extractJson
import com.TTT.Util.serialize
import com.TTT.Context.ContextBank
import com.TTT.Context.ContextWindow
import gameState.TimeProvider
import gameState.UniverseChanges
import gameState.WorldManager
import globals.BedrockConfig
import globals.BedrockConfig.explicitCotBuilder
import kotlinx.coroutines.launch
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

fun worldUpdatesPipeline(): Pipeline {

    val worldUpdates = Pipeline()

    val physicsChangesAndMapTilesRemovedPipe = BedrockMultimodalPipe().apply {
        setRegion("us-west-2")
        useConverseApi()
        setModel(BedrockConfig.PalmyraX5)
        requireJsonPromptInjection()
        setJsonOutput(UniverseChanges())
        allowEmptyContentObject()
        truncateModuleContext()
        setTokenBudget(BedrockConfig.palmyraBudgetSettings)
        setTemperature(0.7)
        setTopP(0.8)
        setReasoningPipe(BedrockConfig.explicitCotBuilder(useFlex = false, depthLevel = ReasoningDepth.High, durationLevel = ReasoningDuration.Short, model = BedrockConfig.PalmyraX5).apply { 
            setTokenBudget(BedrockConfig.palmyraBudgetSettings)
        })
        setPageKey("previous turn, world info")
        autoInjectContext("""ADDITIONAL CONTEXT:
            |"previous turn" is the most recent turn of gameplay.
            |"world info" is the information about the world of the game: this includes
            |the accepted laws of physics (including whether or not magic exists
            |and what kind of magic or technology exists) and what map tiles
            |exist that can be controlled by players.
        """.trimMargin())
        setSystemPrompt("""
            comparing against "previous turn" and "world info," create a JSON array that
            contains a list of pairings of what was true about the world
            before the most recent turn and what is true now after the most recent turn.
            Specifically you are going to note if any map tiles have been removed
            from the face of the universe, and you are going to note if new technologies
            or forms of magic have been brought into existence.

            ###CRITICAL JSON STRUCTURE
            You MUST output a JSON object with a single key "changes" which contains the array.
            Example: { "changes": [ ... ] }
            DO NOT output a raw list like [ ... ].
            
            ${BedrockConfig.antiCensorship}
        """.trimIndent())
        setUserPrompt("Determine any changes to the world state based on the most recent turn.")
        setPipeName("physics changes and map tiles removed pipe")

        setPreInitFunction {
            Logger.debug(LogCategory.SYSTEM, "WorldUpdates: physicsChangesAndMapTilesRemovedPipe.setPreInitFunction entry")
            
            // 1. Capture Previous Turn Context
            val history = WorldManager.history
            val previousTurn = history.lastOrNull()
            val previousTurnJson = if (previousTurn != null) serialize(previousTurn) else "{ \"status\": \"No previous history available\" }"
            ContextBank.emplaceWithMutex("previous turn", ContextWindow().apply {
                contextElements.add(previousTurnJson)
            })

            // 2. Capture World Info Context
            val world = WorldManager.world
            val worldInfo = mapOf(
                "physics_and_magic" to world.worldRules,
                "map_tiles" to world.mapTiles.map { 
                    mapOf(
                        "name" to it.name,
                        "description" to it.description,
                        "ruler" to it.ruler,
                        "isDestroyed" to it.isDestroyed
                    )
                }
            )
            val worldInfoJson = serialize(worldInfo)
            ContextBank.emplaceWithMutex("world info", ContextWindow().apply {
                contextElements.add(worldInfoJson)
            })

            Logger.debug(LogCategory.SYSTEM, "WorldUpdates: physicsChangesAndMapTilesRemovedPipe.setPreInitFunction success")
        }

        setValidatorFunction {
            Logger.debug(LogCategory.SYSTEM, "WorldUpdates: physicsChangesAndMapTilesRemovedPipe.setValidatorFunction entry")
            if(extractJson<UniverseChanges>(it.text) == null)
            {
                Logger.error(LogCategory.SYSTEM, "world updates pipe did not provide valid json")
                return@setValidatorFunction false
            }

            Logger.debug(LogCategory.SYSTEM, "WorldUpdates: physicsChangesAndMapTilesRemovedPipe.setValidatorFunction success")
            return@setValidatorFunction true
        }

        setBranchPipe(
            buildBranchFailureAgent(
                """Comparing against "previous turn" and "world info," create a JSON array that
            contains a list of pairings of what was true about the world
            before the most recent turn and what is true now after the most recent turn.
            Specifically you are going to note if any map tiles have been removed
            from the face of the universe, and you are going to note if new technologies
            or forms of magic have been brought into existence."""
            )
        )

        setTransformationFunction {
            Logger.debug(LogCategory.SYSTEM, "WorldUpdates: physicsChangesAndMapTilesRemovedPipe.setTransformationFunction entry")
            val result = extractJson<UniverseChanges>(it.text) ?: UniverseChanges()
            val playerName = WorldManager.resolveCurrentTurnPlayerName().takeIf { it.isNotBlank() } ?: "system"
            val turnNumber = WorldManager.world.roundNumber
            val timestampMillis = TimeProvider.nowMillis()

            if(result.changes.isNotEmpty())
            {
                AgentCoroutineScope.scope.launch {
                    WorldManager.applyUniverseChanges(
                        changes = result.changes,
                        turnNumber = turnNumber,
                        timestampMillis = timestampMillis,
                        playerName = playerName
                    )
                }
            }

            Logger.debug(LogCategory.SYSTEM, "WorldUpdates: physicsChangesAndMapTilesRemovedPipe.setTransformationFunction success")
            return@setTransformationFunction it
        }
    }

    worldUpdates.apply {
        add(physicsChangesAndMapTilesRemovedPipe)
    }

    return worldUpdates
}