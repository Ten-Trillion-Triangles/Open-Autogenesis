package agent.builders.systemActions

import bedrockPipe.BedrockMultimodalPipe
import com.TTT.Pipeline.Pipeline
import com.TTT.Pipe.MultimodalContent
import com.TTT.Util.extractJson
import Defaults.BedrockConfiguration
import Defaults.reasoning.ReasoningBuilder.reasonWithBedrock
import Defaults.reasoning.ReasoningDepth
import Defaults.reasoning.ReasoningDuration
import Defaults.reasoning.ReasoningInjector
import Defaults.reasoning.ReasoningMethod
import Defaults.reasoning.ReasoningSettings
import agent.builders.validateAction.buildBranchFailureAgent
import agent.builders.validateAction.buildBranchPipeFromTemplate
import agent.builders.validateAction.buildTPipeValidatorPipe
import com.TTT.Structs.PipeSettings
import globals.BedrockConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

/**
 * Enum for user action types
 */
@Serializable
enum class ActionType {
    GAMEPLAY,
    QUESTION,
    UI_COMMAND,
    CHAT
}

/**
 * Data class for user action classification result
 */
@Serializable
data class UserActionClassification(
    val actionType: ActionType,
    val confidence: Double = 0.0,
    val reasoning: String = ""
)

/**
 * Creates explicit CoT reasoning pipe for classification
 */
private fun createExplicitCotPipe(): BedrockMultimodalPipe {
    val reasoningSettings = ReasoningSettings(
        reasoningMethod = ReasoningMethod.ExplicitCot,
        depth = ReasoningDepth.High,
        duration = ReasoningDuration.Short,
        reasoningInjector = ReasoningInjector.AfterUserPrompt,
        numberOfRounds = 1
    )
    
    val bedrockSettings = BedrockConfiguration(
        region = "us-west-2",
        model = BedrockConfig.qwen235B
    )
    
    val pipeSettings = PipeSettings(
        temperature = 0.5,
        topP = 0.7,
        tokenBudgetSettings = BedrockConfig.workerBudgetSettings,
        pipeName = "explicit cot reasoning"
    )
    
    val pipe = reasonWithBedrock(bedrockSettings, reasoningSettings, pipeSettings)
    runBlocking { pipe.init() }
    return pipe as BedrockMultimodalPipe
}

/**
 * Validator function that uses extractJson to validate classification result
 */
private fun validateClassificationResult(content: MultimodalContent): Boolean {
    Logger.debug(LogCategory.SYSTEM, "UserActionClassification: validateClassificationResult entry")
    return try {
        val result = extractJson<UserActionClassification>(content.text)
        if (result == null) {
            Logger.error(LogCategory.SYSTEM, "UserActionClassification: validateClassificationResult failed: result is null")
            throw Exception("Validator Pipe Failed: Unable to extract valid UserActionClassification JSON from response")
        }
        
        // Validate that confidence is between 0 and 1
        if (result.confidence < 0.0 || result.confidence > 1.0) {
            Logger.error(LogCategory.SYSTEM, "UserActionClassification: validateClassificationResult failed: confidence ${result.confidence} out of range")
            throw Exception("Validator Pipe Failed: Confidence value ${result.confidence} is outside valid range [0.0, 1.0]")
        }
        
        // Validate that reasoning is not empty
        if (result.reasoning.trim().isEmpty()) {
            Logger.error(LogCategory.SYSTEM, "UserActionClassification: validateClassificationResult failed: reasoning empty")
            throw Exception("Validator Pipe Failed: Reasoning field cannot be empty")
        }
        
        Logger.debug(LogCategory.SYSTEM, "UserActionClassification: validateClassificationResult success")
        true
    } catch (e: Exception) {
        Logger.error(LogCategory.SYSTEM, "UserActionClassification: validateClassificationResult exception: ${e.message}")
        throw Exception("Classification Validator Pipe Failed: ${e.message}")
    }
}



/**
 * Creates the main user action classification pipeline
 */
/**
 * Creates a custom validator pipe that is robust against extra context JSON
 */
private fun createCustomValidatorPipe(taskPrompt: String): BedrockMultimodalPipe {
    return BedrockMultimodalPipe().apply {
        useConverseApi()
        setRegion("us-west-2")
        setModel(BedrockConfig.PalmyraX5) 
        setTokenBudget(BedrockConfig.palmyraBudgetSettings)
        setTemperature(0.6) 
        setTopP(0.7)
        requireJsonPromptInjection()
        setJsonOutput(serverStructs.TrueFalse())
        setPipeName("custom classification validator")
        
        val validatorSystemPrompt = """
            You are a validation pipe. Your job is to validate your input which was created
            from a prior agent. And determine if it fulfilled it's designed task. This is the task the prior agent
            was given: 
            
            $taskPrompt
            
            Return true in your json output if it completed the task correctly. Return false if it disobeyed, did
            not understand, or just failed to do the task for one reason or another.
            
            IMPORTANT: The input may contain an additional JSON object with context data (loreBookKeys, contextElements, etc.) at the end.
            You MUST IGNORE this additional context object. Only validate the classification result JSON found at the beginning of the input.
        """.trimIndent()
        
        setSystemPrompt(validatorSystemPrompt)

        setValidatorFunction { content ->
             Logger.debug(LogCategory.SYSTEM, "UserActionClassification: customValidatorPipe.setValidatorFunction entry")
             val result = com.TTT.Util.extractJson<serverStructs.TrueFalse>(content.text)
             val res = result?.isTrue ?: false
             Logger.debug(LogCategory.SYSTEM, "UserActionClassification: customValidatorPipe.setValidatorFunction success ($res)")
             res
        }
    }
}

/**
 * Creates the main user action classification pipeline
 */
fun createUserActionClassificationPipeline(): Pipeline {
    val pipeline = Pipeline()

    val systemPrompt = """You are an expert user action classifier. Analyze user input and classify it into one of these categories:
            
            1. GAMEPLAY: Actions related to game mechanics, character actions, or interactions within the game world
               Examples: "attack the goblin", "cast fireball", "move north", "pick up the sword", "analyze current sector", "scout region x"
            
            2. QUESTION: General questions asking for information, explanations, help, or clarification NOT directly related to taking an action right now.
               Examples: "what are my stats?", "how do I cast spells?", "what's in my inventory?", "tell me about the lore"
            
            3. UI_COMMAND: Commands to interact with the user interface or system controls
               Examples: "save game", "open settings", "show map", "quit game"

            4. CHAT: Intent to have a conversation or talk with a specific character (NPC or Player).
               Examples: "Hello @King Candy", "Talk to @The Illusive Man", "Tell Shepard I'm coming for him", "Ask Major Turdington if he's okay"
            
            Analyze the user's intent carefully. Provide:
            - actionType: The most appropriate category
            - confidence: A score from 0.0 to 1.0 indicating your certainty
            - reasoning: Clear explanation of why you chose this classification
            
            Be precise and consider context clues in the user's language.
            
            ${BedrockConfig.antiCensorship}"""
    
    // Create main classification pipe
    val classificationPipe = BedrockMultimodalPipe().apply {
        setRegion("us-west-2")
        setModel(BedrockConfig.qwenCoder30B)
        setTemperature(0.5)
        setTopP(0.7)
        setMaxTokens(8000)
        setContextWindowSize(115000)
        setTokenBudget(BedrockConfig.workerBudgetSettings)
        allowEmptyContentObject()
        allowEmptyUserPrompt()
        requireJsonPromptInjection()
        setJsonOutput(UserActionClassification(ActionType.QUESTION, 0.0, ""))
        setReasoningPipe(createExplicitCotPipe())
        setValidatorPipe(buildTPipeValidatorPipe(systemPrompt))
        setValidatorFunction(::validateClassificationResult)
        setBranchPipe(buildBranchPipeFromTemplate(this, BedrockConfig.PalmyraX5, BedrockConfig.palmyraBudgetSettings))
        setSystemPrompt(systemPrompt)
        setPipeName("user action classifier")
        enableTracing()
    }
    
    pipeline.add(classificationPipe)
    return pipeline
}