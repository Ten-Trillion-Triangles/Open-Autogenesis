package agent.builders.modifyGameState

import Defaults.BedrockConfiguration
import Defaults.reasoning.ReasoningBuilder.reasonWithBedrock
import Defaults.reasoning.ReasoningDepth
import Defaults.reasoning.ReasoningDuration
import Defaults.reasoning.ReasoningInjector
import Defaults.reasoning.ReasoningMethod
import Defaults.reasoning.ReasoningSettings
import agent.builders.validateAction.buildBranchFailureAgent
import agent.builders.validateAction.buildTPipeValidatorPipe
import com.TTT.Pipeline.Pipeline
import bedrockPipe.BedrockMultimodalPipe
import bedrockPipe.BedrockPriorityTier
import com.TTT.Context.ContextWindow
import com.TTT.Structs.PipeSettings
import com.TTT.Util.extractJson
import com.TTT.Util.serialize
import structs.Npc
import structs.Player
import structs.World
import enums.NpcType
import globals.BedrockConfig
import gameState.WorldManager
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import structs.GameHistory
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

@Serializable
data class GameHistoryList(
    val history: List<GameHistory> = emptyList()
)

@Serializable
data class PlayerList(
    val players: List<Player> = emptyList()
)

/**
 * Data classes for Nemesis generation pipeline
 */
@Serializable
data class NemesisConcept(
    val archetype: String = "",
    val personalityTraits: List<String> = listOf(),
    val primaryObjective: String = "",
    val secondaryObjectives: List<String> = listOf(),
    val worldInteractionStyle: String = "",
    val memorableQuirks: List<String> = listOf(),
    val extremityLevel: Int = 5,
    val thematicAlignment: String = ""
)

@Serializable
data class StoryContext(
    val keyThemes: List<String> = listOf(),
    val toneIndicators: List<String> = listOf(),
    val playerPatterns: List<String> = listOf()
)

@Serializable
data class GenerationParameters(
    val absurdityFactor: Int = 5,
    val unhingedElements: List<String> = listOf(),
    val boundaryPushing: Boolean = true
)

@Serializable
data class PlayerActionsResponse(
    val playerActions: List<String> = listOf()
)

@Serializable
data class NemesisConceptResponse(
    val nemesisConcept: NemesisConcept = NemesisConcept(),
    val storyContext: StoryContext = StoryContext(),
    val generationParameters: GenerationParameters = GenerationParameters()
)

@Serializable
data class NemesisRequest(
    val storyHistory: List<GameHistory> = listOf(),
    val worldState: World = World(),
    val playerActions: List<String> = listOf()
)

@Serializable
data class NpcDataResponse(
    val npcData: Npc = Npc()
)

/**
 * Builder function that creates a pipeline to generate nemesis characters based on story context.
 * This pipeline analyzes the current story state and creates memorable, extreme antagonists
 * that serve as persistent world-level threats.
 */
suspend fun buildNemesisCreationAgent(): Pipeline {
    
    // Reasoning configuration for nova models
    val reasoningSettings = ReasoningSettings(
        reasoningMethod = ReasoningMethod.ExplicitCot,
        depth = ReasoningDepth.High,
        duration = ReasoningDuration.Long,
        reasoningInjector = ReasoningInjector.AfterUserPrompt,
        injectFooterPrompt = true
    )

    val novaBedrockSettings = BedrockConfiguration(
        region = "us-west-2",
        model = BedrockConfig.novaModelName
    )

    val novaPipeSettings = PipeSettings(
        temperature = 0.8,
        topP = 0.8,
        contextWindowSize = BedrockConfig.novaBudgetSettings.contextWindowSize!!,
        maxTokens = BedrockConfig.novaBudgetSettings.reasoningBudget!!, // Use reasoning budget for reasoning pipe
        tokenBudgetSettings = BedrockConfig.novaBudgetSettings
    )

    val qwenBedrockSettings = BedrockConfiguration(
        region = "us-west-2", 
        model = BedrockConfig.qwen235B
    )

    val qwenPipeSettings = PipeSettings(
        temperature = 0.9,
        topP = 0.9,
        contextWindowSize = BedrockConfig.generativeBudgetSettings.contextWindowSize!!,
        maxTokens = BedrockConfig.generativeBudgetSettings.maxTokens!!,
        tokenBudgetSettings = BedrockConfig.generativeBudgetSettings
    )

    val reasoningPipe = BedrockConfig.explicitCotBuilder(useFlex = false)
    
    // Apply token budgeting to the reasoning pipe to match main pipe configurations
    reasoningPipe.setTokenBudget(BedrockConfig.novaBudgetSettings)

    /**
     * Pipe 0: Data Collection & Player Action Analysis
     * Collects game data and analyzes player actions to build context for nemesis creation
     */
    val dataCollectionPipe = BedrockMultimodalPipe()
        .setRegion("us-west-2")
        .useConverseApi()
        .setModel(BedrockConfig.PalmyraX5)
        .setPipeName("player action analysis pipe")
        .setReasoningPipe(BedrockConfig.structuredCotBuilder(useFlex = false, model = BedrockConfig.PalmyraX5).apply {
            setTokenBudget(BedrockConfig.palmyraBudgetSettings)})
        .requireJsonPromptInjection()
        .setJsonOutput(PlayerActionsResponse())
        .setTokenBudget(BedrockConfig.palmyraBudgetSettings)
        .setSystemPrompt("""You are a game analyst. Analyze the provided game history and generate meaningful 
            |player action descriptions that capture the essence of what players have been doing.
            |
            |Focus on:
            |- Player behavioral patterns and strategies
            |- Key decision-making moments
            |- Character interactions and relationship dynamics
            |- Strategic choices and their consequences
            |
            |Generate 5-10 concise but descriptive player action summaries that would be useful for 
            |creating a nemesis character that responds to these specific player behaviors.
        """.trimMargin())
        .setFooterPrompt("""Return a JSON list of player action descriptions that capture the strategic and 
            |behavioral patterns shown in the game history. These will be used to create a nemesis that 
            |specifically counters or responds to these player behaviors.
        """.trimMargin())
        .setPreValidationMiniBankFunction { context, content ->
            Logger.debug(LogCategory.SYSTEM, "NemesisCreation: dataCollectionPipe.setPreValidationMiniBankFunction entry")
            val history = serialize(GameHistoryList(WorldManager.getRecentHistory(3)))
            val players = serialize(PlayerList(WorldManager.world.activePlayers))

            val newContextWindow = ContextWindow().apply {
                contextElements.add(history)
            }

            context.contextMap["history"] = newContextWindow

            val playerContextWindow = ContextWindow().apply {
                contextElements.add(players)
            }

            context.contextMap["players"] = playerContextWindow
            Logger.debug(LogCategory.SYSTEM, "NemesisCreation: dataCollectionPipe.setPreValidationMiniBankFunction success")
            return@setPreValidationMiniBankFunction context
        }
        .setValidatorPipe(
            buildTPipeValidatorPipe(
                """You are a game data analyst. Your job is to analyze the current game state and recent player actions
            |to extract meaningful patterns and context for nemesis creation.
            |
            |Analyze the provided game data and create a summary of:
            |- Recent player actions and their patterns
            |- Key story themes and narrative elements
            |- Current world state and territorial control
            |- Player behavior patterns and strategies
            |
            |Focus on identifying elements that would inform the creation of a memorable nemesis character."""
            )
        )
        .setValidatorFunction { response ->
            Logger.debug(LogCategory.SYSTEM, "NemesisCreation: dataCollectionPipe.setValidatorFunction entry")
            val result = extractJson<PlayerActionsResponse>(response.text)
            if(result == null) {
                Logger.error(LogCategory.SYSTEM, "NemesisCreation: dataCollectionPipe.setValidatorFunction failed: result is null")
                return@setValidatorFunction false
            }
            val res = result.playerActions.isNotEmpty()
            Logger.debug(LogCategory.SYSTEM, "NemesisCreation: dataCollectionPipe.setValidatorFunction success ($res)")
            return@setValidatorFunction res
        }
        .setBranchPipe(
            buildBranchFailureAgent(
                """Analyze the game history and generate player action descriptions
            |that capture behavioral patterns and strategic choices.
        """.trimMargin()
            )
        )
        .setTransformationFunction { response ->
            Logger.debug(LogCategory.SYSTEM, "NemesisCreation: dataCollectionPipe.setTransformationFunction entry")
            // Extract LLM-generated player actions
            val playerActionsResult = extractJson<PlayerActionsResponse>(response.text)
            val playerActions = playerActionsResult?.playerActions ?: listOf()
            
            // Fetch actual game data and merge with LLM output
            val gameData = WorldManager.worldMutex.withLock {
                NemesisRequest(
                    storyHistory = WorldManager.getRecentHistory(3),
                    worldState = WorldManager.world,
                    playerActions = playerActions // Use LLM-generated actions
                )
            }
            
            // Replace response with merged data
            response.text = serialize(gameData)
            
            // Store in pipeline context for next pipes
            val pipeRef = response.currentPipe
            val pipelineRef = pipeRef!!.getPipelinesFromInterface()[0]
            val contextWindow = pipelineRef.context
            
            contextWindow.contextElements.add(0, serialize(gameData))
            pipelineRef.context = contextWindow
            
            Logger.debug(LogCategory.SYSTEM, "NemesisCreation: dataCollectionPipe.setTransformationFunction success")
            return@setTransformationFunction response
        }

    /**
     * Pipe 1: Story Analysis & Nemesis Conceptualization
     * Uses nova2-lite with TPipe reasoning to analyze story context and generate nemesis concepts
     */
    val storyAnalysisPipe = BedrockMultimodalPipe()
        .setRegion("us-west-2")
        .useConverseApi()
        .setRegion("us-west-2")
        .setModel(BedrockConfig.qwen235B)
        .setPipeName("story analysis pipe")
        .requireJsonPromptInjection()
        .setJsonInput(NemesisRequest())
        .setJsonOutput(NemesisConceptResponse())
        .setTokenBudget(BedrockConfig.generativeBudgetSettings)
        .setReasoningPipe(reasoningPipe.apply
        { setReasoningPipe(BedrockConfig.processFocusedBuilder(useFlex = false, depthLevel = ReasoningDepth.Med, durationLevel = ReasoningDuration.Med).apply
        { setTokenBudget(BedrockConfig.palmyraBudgetSettings)})})
        .autoInjectContext("""ADDITIONAL CONTEXT:
            |You have been provided with story history, world state, and player action data.
            |Use this context to analyze the narrative themes, player patterns, and world state
            |to create an appropriate nemesis concept that fits the established story world.
        """.trimMargin())
        .setSystemPrompt("""You are a master storyteller and nemesis designer. Your job is to analyze the current story context, 
            |game history, and player patterns to conceptualize a memorable, extreme nemesis character.
            |
            |A nemesis must be:
            |- Absurd and unhinged in ways that match the story's themes
            |- Memorable and distinctive with unique quirks
            |- Thematically extreme without artificial limitations
            |- A persistent world-level threat that disrupts all players
            |- Capable of independent action and territorial conquest
            |
            |Analyze the story events, identify key themes and tone, then design a nemesis concept that embodies
            |the darkest, most chaotic, or most twisted aspects of this story world. Push boundaries and create
            |something truly unforgettable that will define players' experience with this game world.
        """.trimMargin())
        .setFooterPrompt("""Base your nemesis concept on the story's established themes and tone. The more extreme and 
            |memorable, the better. This nemesis should be something players will never forget - a defining encounter
            |that captures the essence of this particular story world's darkness or chaos.
        """.trimMargin())
        .setPreValidationFunction { context, content ->
            Logger.debug(LogCategory.SYSTEM, "NemesisCreation: storyAnalysisPipe.setPreValidationFunction entry")
            val world = serialize(WorldManager.world)
            context.contextElements.add(world)

            Logger.debug(LogCategory.SYSTEM, "NemesisCreation: storyAnalysisPipe.setPreValidationFunction success")
            return@setPreValidationFunction context
        }
        .setValidatorPipe(
            buildTPipeValidatorPipe(
                """You are a master storyteller and nemesis designer. Your job is to analyze the current story context, 
            |game history, and player patterns to conceptualize a memorable, extreme nemesis character.
            |
            |A nemesis must be:
            |- Absurd and unhinged in ways that match the story's themes
            |- Memorable and distinctive with unique quirks
            |- Thematically extreme without artificial limitations
            |- A persistent world-level threat that disrupts all players
            |- Capable of independent action and territorial conquest
            |
            |Analyze the story events, identify key themes and tone, then design a nemesis concept that embodies
            |the darkest, most chaotic, or most twisted aspects of this story world. Push boundaries and create
            |something truly unforgettable that will define players' experience with this game world."""
            )
        )
        .setValidatorFunction { response ->
            Logger.debug(LogCategory.SYSTEM, "NemesisCreation: storyAnalysisPipe.setValidatorFunction entry")
            val validJson = extractJson<NemesisConceptResponse>(response.text)
            if(validJson == null)
            {
                Logger.error(LogCategory.SYSTEM, "NemesisCreation: storyAnalysisPipe.setValidatorFunction failed: validJson is null")
                throw Exception("Nemesis concept response is not valid JSON @story analysis pipe")
            }

            Logger.debug(LogCategory.SYSTEM, "NemesisCreation: storyAnalysisPipe.setValidatorFunction success")
            return@setValidatorFunction true
        }
        .setBranchPipe(
            buildBranchFailureAgent(
                """You are a master storyteller and nemesis designer. Your job is to analyze the current story context, 
            |game history, and player patterns to conceptualize a memorable, extreme nemesis character.
            |
            |A nemesis must be:
            |- Absurd and unhinged in ways that match the story's themes
            |- Memorable and distinctive with unique quirks
            |- Thematically extreme without artificial limitations
            |- A persistent world-level threat that disrupts all players
            |- Capable of independent action and territorial conquest
            |
            |Analyze the story events, identify key themes and tone, then design a nemesis concept that embodies
            |the darkest, most chaotic, or most twisted aspects of this story world. Push boundaries and create
            |something truly unforgettable that will define players' experience with this game world.
        """.trimMargin()
            )
        )
        .setTransformationFunction { response ->
            Logger.debug(LogCategory.SYSTEM, "NemesisCreation: storyAnalysisPipe.setTransformationFunction entry")
            // Store nemesis concept in pipeline context for next pipe
            val pipeRef = response.currentPipe
            val pipelineRef = pipeRef!!.getPipelinesFromInterface()[0]
            val contextWindow = pipelineRef.context
            
            val conceptData = extractJson<NemesisConceptResponse>(response.text)
            val conceptJson = serialize(conceptData)
            
            contextWindow.contextElements.add(0, conceptJson)
            pipelineRef.context = contextWindow
            
            Logger.debug(LogCategory.SYSTEM, "NemesisCreation: storyAnalysisPipe.setTransformationFunction success")
            return@setTransformationFunction response
        }

    /**
     * Pipe 2: Character Design & NPC Data Class Generation  
     * Uses qwen-max to transform the concept into a complete NPC data structure
     */
    val characterDesignPipe = BedrockMultimodalPipe()
        .setRegion("us-west-2")
        .useConverseApi()
        .setModel(BedrockConfig.qwen235B)
        .setPipeName("character design pipe")
        .requireJsonPromptInjection()
        .setJsonInput(NemesisConceptResponse())
        .setJsonOutput(NpcDataResponse())
        .setTokenBudget(BedrockConfig.generativeBudgetSettings)
        .setReasoningPipe(BedrockConfig.explicitCotBuilder(depthLevel = ReasoningDepth.High, durationLevel = ReasoningDuration.Long).apply { setTokenBudget(BedrockConfig.generativeBudgetSettings) })
        .setSystemPrompt("""You are a master character designer. Transform the provided nemesis concept into a complete,
            |detailed NPC character. Create:
            |
            |- A distinctive, memorable name that fits the concept
            |- Detailed physical and behavioral description
            |- Extreme personality traits that match the story themes  
            |- Unique abilities and special powers
            |- Compelling backstory that explains motivations
            |- Appropriate point value (15-25 for nemesis difficulty)
            |
            |The character should be:
            |- Memorable and distinctive
            |- Appropriately extreme and unhinged
            |- Thematically consistent with the story world
            |- Capable of serving as a persistent world-level threat
            |
            |Make this nemesis unforgettable - something that will define the players' experience.
        """.trimMargin())
        .setFooterPrompt("""Create a complete nemesis character that embodies the concept's extremity and memorability.
            |This should be a defining antagonist that players will never forget.
        """.trimMargin())
        .setValidatorPipe(
            buildTPipeValidatorPipe(
                """You are a master character designer. Transform the provided nemesis concept into a complete,
            |detailed NPC character. Create:
            |
            |- A distinctive, memorable name that fits the concept
            |- Detailed physical and behavioral description
            |- Extreme personality traits that match the story themes  
            |- Unique abilities and special powers
            |- Compelling backstory that explains motivations
            |- Appropriate point value (15-25 for nemesis difficulty)
            |
            |The character should be:
            |- Memorable and distinctive
            |- Appropriately extreme and unhinged
            |- Thematically consistent with the story world
            |- Capable of serving as a persistent world-level threat
            |
            |Make this nemesis unforgettable - something that will define the players' experience."""
            )
        )
        .setValidatorFunction { response ->
            Logger.debug(LogCategory.SYSTEM, "NemesisCreation: characterDesignPipe.setValidatorFunction entry")
            val validJson = extractJson<NemesisConceptResponse>(response.text)
            if(validJson == null)
            {
                Logger.error(LogCategory.SYSTEM, "NemesisCreation: characterDesignPipe.setValidatorFunction failed: validJson is null")
                throw Exception("Nemesis concept response is not valid JSON @character design pipe")
            }
            
            Logger.debug(LogCategory.SYSTEM, "NemesisCreation: characterDesignPipe.setValidatorFunction success")
            return@setValidatorFunction true
        }
        .setBranchPipe(
            buildBranchFailureAgent(
                """Transform the nemesis concept into a complete NPC character with extreme traits.
            |Create a memorable, unhinged character that will serve as an unforgettable antagonist.
        """.trimMargin()
            )
        )
        .setTransformationFunction { response ->
            Logger.debug(LogCategory.SYSTEM, "NemesisCreation: characterDesignPipe.setTransformationFunction entry")
            // Finalize the NPC data
            val result = extractJson<NpcDataResponse>(response.text)
            if (result != null) {
                // Ensure nemesis type is set correctly
                result.npcData.type = NpcType.Nemesis
                
                // Ensure appropriate point value for nemesis
                if (result.npcData.pointValue < 15) {
                    result.npcData.pointValue = 20
                }
                
                response.text = serialize(result)
            }
            
            Logger.debug(LogCategory.SYSTEM, "NemesisCreation: characterDesignPipe.setTransformationFunction success")
            return@setTransformationFunction response
        }

    // Build and return the pipeline
    val pipeline = Pipeline()
        .add(dataCollectionPipe)
        .add(storyAnalysisPipe)
        .add(characterDesignPipe)
        .enableTracing()
        .init()

    return pipeline
}
