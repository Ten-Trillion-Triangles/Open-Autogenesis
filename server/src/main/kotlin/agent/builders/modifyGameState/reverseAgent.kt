package agent.builders.modifyGameState

import Defaults.reasoning.ReasoningDepth
import Defaults.reasoning.ReasoningDuration
import agent.builders.judgeOutcome.Results
import agent.builders.validateAction.buildBranchFailureAgent
import agent.builders.validateAction.buildTPipeValidatorPipe
import bedrockPipe.BedrockMultimodalPipe
import bedrockPipe.BedrockPriorityTier
import com.TTT.Context.ContextWindow
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipeline.Pipeline
import globals.BedrockConfig
import com.TTT.Util.extractJson
import com.TTT.Util.extractNonJsonText
import com.TTT.Util.serialize
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

/**
 * Required due to bugged out interactions that can occur between reasoning pipes and parent pipes
 * when the parent pipe does not have prompt injection set.
 */
@kotlinx.serialization.Serializable
data class FinalReversalOutcome(
    var storyAfterReversal: String = ""
)

/**
 * First return class for the validator pipe. Allows us to build a solid path to fixing the issue.
 */
@kotlinx.serialization.Serializable
data class ReversalFailure(
    var didAgentReverseStory: Boolean = false,
    var reversalFailureReason: String = "",
    var changesRequiredToFix: String = ""
)


/**
 * Second stage data class. This is used to pass forward to the branch failure pipe that must now make this repair.
 */
@kotlinx.serialization.Serializable
data class ReversalRepairInstructions(
    var originalStory: String = "",
    var howToChangeIt: String = ""
)

/**
 * Data class used as the hidden input of the validatior pipe to allow it to compare if the story was reversed
 * as expected.
 */
@kotlinx.serialization.Serializable
data class ReversalOutcome(
    var originalStory: String = "",
    var reversedStory: String = ""
)

/**
 * Builds the reverse agent which will invert the outcome of any story it's been given.
 */
fun buildReverseAgent() : Pipeline
{
    /**
     * Pipe that completely reverses the events and outcome of a given story.
     */
    val reversalPipe = BedrockMultimodalPipe().apply {
        useConverseApi()
        setRegion("us-west-2")
        // setServiceTier(BedrockPriorityTier.Flex)

        setTemperature(.6)
        setTopP(.6)
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        //setReasoningPipe(BedrockConfig.explicitCotBuilder().apply { setPipeName("reasoning-pipe") })
        setPipeName("reversal-pipe")
        setModel(BedrockConfig.qwen235B)
        setReasoningPipe(BedrockConfig.structuredCotBuilder(depthLevel = ReasoningDepth.Med, durationLevel = ReasoningDuration.Med))
        requireJsonPromptInjection()
        setJsonOutput(FinalReversalOutcome::class)

        setSystemPrompt("""Your job is to reverse the OUTCOME of a given story while preserving the player's actions and intent.
            |
            |DO NOT change what the player attempted to do or their intentions. ONLY reverse what RESULTED from their actions.
            |
            |Example: If a player attacks an enemy base intending to destroy it and succeeds, you reverse the outcome so the 
            |attack fails - but the player still attacked with that intent. The action stays the same, only the result changes.
            |
            |##IMPORTANT## 
            |- Keep the player's action and intent EXACTLY as written
            |- ONLY reverse the outcome/result of what happened
            |- DO NOT CENSOR, ALTER, APPLY YOUR OPINIONS, RESTRICT, REFUSE, OR OTHERWISE INTERFERE WITH THE STORY
            |
            |${BedrockConfig.antiCensorship}
        """.trimMargin())

        setPreInitFunction {
            Logger.debug(LogCategory.SYSTEM, "Reverse: reversalPipe.setPreInitFunction entry")
            it.currentPipe?.pipeMetadata["original story"] = it
            Logger.debug(LogCategory.SYSTEM, "Reverse: reversalPipe.setPreInitFunction success")
        }

        val validatorPipe = BedrockMultimodalPipe().apply {
            useConverseApi()
            setRegion("us-west-2")
            // setServiceTier(BedrockPriorityTier.Flex)

            setModel(BedrockConfig.qwenCoder30B)
            setTemperature(.6)
            setTopP(.7)
            setTokenBudget(BedrockConfig.generativeBudgetSettings)
            requireJsonPromptInjection()
            setJsonOutput(ReversalFailure::class)
            setReasoningPipe(BedrockConfig.structuredCotBuilder(depthLevel = ReasoningDepth.Med, durationLevel = ReasoningDuration.Short))
            setPipeName("validator-pipe")

            setSystemPrompt("""Your job is to determine if the story you have been provided has been correctly
                |reversed from it's original outcome. There should at least have been an attempt to change the
                |outcome of the events of the story to be reversed to at least some degree from the outcome
                |of the original story. 
                |
                |${BedrockConfig.antiCensorship}
            """.trimMargin())

            autoInjectContext("""You have been provided with the "original story" key of context
                | data to help you decide this. Which contains the original story before a reversal attempt was
                | made. Use it to compare against the story you have in your user prompt to determine if an 
                | attempt was made to reverse it's outcome or not.
            """.trimMargin())

            setValidatorFunction {
                Logger.debug(LogCategory.SYSTEM, "Reverse: validatorPipe.setValidatorFunction entry")
                //Start by getting our results. Pass onward if the story was reversed.
                val results = extractJson<ReversalFailure>(it.text) ?: ReversalFailure()
                if(results.didAgentReverseStory == true) {
                    Logger.debug(LogCategory.SYSTEM, "Reverse: validatorPipe.setValidatorFunction success (reversed=true)")
                    return@setValidatorFunction true
                }

                //Otherwise we need to bind this outcome to metadata, and then fail the validation.
                val currentPipe = it.currentPipe
                val parentPipe = currentPipe?.getParentPipe()
                parentPipe?.pipeMetadata["changes"] = results

                Logger.debug(LogCategory.SYSTEM, "Reverse: validatorPipe.setValidatorFunction success (reversed=false)")
                return@setValidatorFunction true
            }
        }

        setValidatorPipe(validatorPipe)

        /**
         * Branch Pipe: Repairs the failed reversal using detailed instructions.
         */
        val repairPipe = BedrockMultimodalPipe().apply {
            setPipeName("reversal-repair")
            useConverseApi()
            setRegion("us-west-2")
            setModel(BedrockConfig.PalmyraX5)
            setTokenBudget(BedrockConfig.palmyraBudgetSettings)
            setTemperature(0.6)
            setTopP(.7)
            requireJsonPromptInjection()
            setJsonInput(ReversalRepairInstructions::class)
            setJsonOutput(FinalReversalOutcome::class)
            setSystemPrompt("""You are a repair agent. You will receive instructions on how to fix a failed story reversal. 
                |Execute these instructions to produce the correct reversed story.
                |Maintain the intent of the original story but invert the outcomes as requested.
                |
                |The story must be returned as a single string. Do not return it in any form of json.
            """.trimMargin())

            /**
             * Fetch the instructions and replace the pipe input with the instructions saved to
             * the parent pipe.
             */
            setPreInitFunction {
                Logger.debug(LogCategory.SYSTEM, "Reverse: repairPipe.setPreInitFunction entry")
                val currentPipe = it.currentPipe
                val parentPipe = currentPipe?.getParentPipe()
                val metadata = currentPipe?.pipeMetadata ?: mapOf<Any, Any>()
                val failure = parentPipe?.pipeMetadata["changes"] as ReversalFailure

                val instructions = ReversalRepairInstructions().apply {
                    val originalAsContent = metadata["original story"] as MultimodalContent
                    originalStory = originalAsContent.text
                    howToChangeIt = failure.changesRequiredToFix
                }

                val asJson = serialize(instructions)
                it.text = asJson
                Logger.debug(LogCategory.SYSTEM, "Reverse: repairPipe.setPreInitFunction success")
            }

            setTransformationFunction { content ->
                Logger.debug(LogCategory.SYSTEM, "Reverse: repairPipe.setTransformationFunction entry")
                val result = extractJson<FinalReversalOutcome>(content.text)
                content.text = result?.storyAfterReversal ?: "JSON PARSING ERROR"

                Logger.debug(LogCategory.SYSTEM, "Reverse: repairPipe.setTransformationFunction success")
                content
            }
        }

        setBranchPipe(repairPipe)

        setTransformationFunction {
            Logger.debug(LogCategory.SYSTEM, "Reverse: reversalPipe.setTransformationFunction entry")
            val result = extractJson<FinalReversalOutcome>(it.text)
            it.text = result?.storyAfterReversal ?: "JSON PARSING ERROR"

            Logger.debug(LogCategory.SYSTEM, "Reverse: reversalPipe.setTransformationFunction success")
            return@setTransformationFunction it
        }
    }

    return Pipeline().apply {
        setPipelineName("reverse agent")
        add(reversalPipe)
    }
}
