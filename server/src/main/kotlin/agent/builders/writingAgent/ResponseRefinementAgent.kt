package agent.builders.writingAgent

import Defaults.reasoning.ReasoningDepth
import Defaults.reasoning.ReasoningDuration
import bedrockPipe.BedrockMultimodalPipe
import bedrockPipe.BedrockPriorityTier
import com.TTT.Pipeline.Pipeline
import com.TTT.Util.extractJson
import com.TTT.Util.serialize
import com.TTT.Pipe.MultimodalContent
import com.TTT.Context.ContextWindow
import gameState.WorldManager
import globals.BedrockConfig
import kotlinx.serialization.Serializable
import serverStructs.TrueFalse
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

@Serializable
data class RefinedResponse(
    val refinedProse: String = ""
)

/**
 * Agent that converts raw player response input into 3rd-person narrative prose.
 * Follows TPipe standards for high-quality narrative integration.
 */
fun buildResponseRefinementAgent(): Pipeline {
    val systemPrompt = """You are a narrative refinement agent for a competitive narrative game. 
        |Your task is to take a raw, 1st-person player response (e.g., "I block with my shield") 
        |and transform it into visceral, 3rd-person narrative prose (e.g., "Raising their tactical 
        |shield with practiced ease, the player prepares to absorb the incoming impact").
        |
        |### Guidelines:
        |- POV: 3rd-person, past tense.
        |Ensure you convert the response to third person. If it's very bare, flesh it out in accordance to the 
        |general styling of the story's writing.
        |
    """.trimMargin()

    val detectPipe = BedrockMultimodalPipe().apply {
        useConverseApi()

        setRegion("us-west-2")

        setModel(BedrockConfig.qwen235B)
        setTemperature(.6)
        setTopP(.7)
        allowEmptyContentObject()
        allowEmptyUserPrompt()
        requireJsonPromptInjection()
        setReasoningPipe(BedrockConfig.explicitCotBuilder(depthLevel = ReasoningDepth.High, durationLevel = ReasoningDuration.Short))
        setJsonOutput(TrueFalse::class)
        setPipeName("Response Detection Pipe")
        enableStreaming()
        setSystemPrompt("""Your job is to examine your user prompt and determine if it has been written as third person
            |or not.
        """.trimMargin())
        setFooterPrompt("""In your json output you must return true if it's in third person. Otherwise you must return
            |false. ${BedrockConfig.antiCensorship}
        """.trimMargin())

        setPreInitFunction {
            Logger.debug(LogCategory.SYSTEM, "ResponseRefinement: detectPipe.setPreInitFunction entry")
            it.saveSnapshot()
            Logger.debug(LogCategory.SYSTEM, "ResponseRefinement: detectPipe.setPreInitFunction success")
        }

        setTransformationFunction {
            Logger.debug(LogCategory.SYSTEM, "ResponseRefinement: detectPipe.setTransformationFunction entry")
            //Attempt to get the json if able. Default to pass if we can't since that's the safest option.
            val result = extractJson<TrueFalse>(it.text) ?: TrueFalse().apply { isTrue = true }
            val parentPipe = it.currentPipe ?: throw Exception("Failed to extract parent pipe during detection pipe's" +
                    " transformation function @ResponseRefinementAgent.kt")

            //Fetch the original user prompt so we can save it as context.
            val parentPipeline = parentPipe.getPipelinesFromInterface()[0]
            val snapshot = it.getSnapshot()
            val originalInputPrompt = snapshot?.text ?: ""

            //Create new context window and assign the original prompt to it.
            val newContextWindow = ContextWindow().apply { contextElements.add(originalInputPrompt) }
            parentPipeline.setContextWindow(newContextWindow) //Update the context window.

            Logger.debug(LogCategory.SYSTEM, "ResponseRefinement: detectPipe.setTransformationFunction success")
            return@setTransformationFunction it
        }
    }

    val refinePipe = BedrockMultimodalPipe().apply {
        useConverseApi()
        setRegion("us-west-2")
        // Using the Qwen model specifically requested by the user
        setModel(BedrockConfig.qwen235B) 
        setTemperature(0.6)
        setTopP(0.7)
        allowEmptyContentObject()
        allowEmptyUserPrompt()
        requireJsonPromptInjection()
        setReasoningPipe(BedrockConfig.structuredCotBuilder(depthLevel = ReasoningDepth.Low, durationLevel = ReasoningDuration.Short))
        setJsonOutput(RefinedResponse())
        setPipeName("Response Refinement Pipe")
        enableStreaming()
        setSystemPrompt(systemPrompt)
        autoInjectContext("""You have been provided with the previous chapter of the story as context.
            |Use this to style how you convert from the input to 3rd person writing.
        """.trimMargin())
        setFooterPrompt("""Only change the input from it's current state to third person. Do not alter the intent
            |of the input, or it's content in any other way.
        """.trimMargin())

        setPreInvokeFunction {
            Logger.debug(LogCategory.SYSTEM, "ResponseRefinement: refinePipe.setPreInvokeFunction entry")
            val result = extractJson<TrueFalse>(it.text) ?: return@setPreInvokeFunction true

            // Seek the context saved from the parent pipeline and use it to restore our user prompt.
            val parentPipe = it.currentPipe ?: throw Exception("Failed to extract parent pipe during refinement pipe's pre-init function @ResponseRefinementAgent.kt")
            val parentPipeline = parentPipe.getPipelinesFromInterface()[0]
            val context = parentPipeline.context
            val originalUserPrompt = context.contextElements[0]
            
            // ALWAYS restore the original prompt so if we skip, we return the clean text, not the detection JSON.
            it.text = originalUserPrompt

            Logger.debug(LogCategory.SYSTEM, "ResponseRefinement: refinePipe.setPreInvokeFunction success (${result.isTrue})")
            
            // Return true to SKIP if it's already third person.
            return@setPreInvokeFunction result.isTrue
        }

        setPreValidationFunction { context, content ->
            Logger.debug(LogCategory.SYSTEM, "ResponseRefinement: refinePipe.setPreValidationFunction entry")
            val lastChapter = WorldManager.history.lastOrNull()?.turnStory ?: ""
            context.contextElements.add(lastChapter)

            Logger.debug(LogCategory.SYSTEM, "ResponseRefinement: refinePipe.setPreValidationFunction success")
            return@setPreValidationFunction context
        }

        setTransformationFunction {
            Logger.debug(LogCategory.SYSTEM, "ResponseRefinement: refinePipe.setTransformationFunction entry")
            val response = extractJson<RefinedResponse>(it.text)
            if(response != null)
            {
                it.text = response.refinedProse
            }
            else
            {
                val snapshot = it.getSnapshot()
                it.text = snapshot?.text ?: ""
            }

            Logger.debug(LogCategory.SYSTEM, "ResponseRefinement: refinePipe.setTransformationFunction success")
            return@setTransformationFunction it
        }
    }

    val pipeline = Pipeline()
    pipeline.add(detectPipe)
    pipeline.add(refinePipe)
    return pipeline
}