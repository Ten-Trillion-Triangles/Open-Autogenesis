package agent.builders.validateAction

import agent.structs.AgentRetry
import bedrockPipe.BedrockMultimodalPipe
import bedrockPipe.BedrockPriorityTier
import com.TTT.Context.ContextBank
import com.TTT.Context.ContextWindow
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Pipe.USER_PROMPT_SNAPSHOT
import com.TTT.Util.constructPipeFromTemplate
import com.TTT.Util.extractJson
import com.TTT.Util.serialize
import globals.BedrockConfig
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger


fun buildBranchFailureAgent(instructions: String = "") : BedrockMultimodalPipe
{
    val branchPipe = BedrockMultimodalPipe().apply {
        setRegion("us-west-2")
        setRegion("us-west-2")
        useConverseApi()

        setModel(BedrockConfig.qwen235B)
        setTokenBudget(BedrockConfig.workerBudgetSettings)
        requireJsonPromptInjection()
        setJsonOutput(AgentRetry())
        setTemperature(.6)
        setTopP(.7)

        val baseInstructions = """You are an error logging agent that's responsible for assessing what kind of
            |error has occurred that caused a prior llm to fail. You must assess it's output and determine the
            |error type, then log what the error reason was. You will be given instructions from the user as
            |follows: $instructions
            |
            |These instructions are the rules the prior llm was supposed to follow. However, because we reached
            |this point it means that llm did not follow the instructions for one reason or another. You
            |will need to determine why that is.
        """.trimMargin()
        setSystemPrompt(baseInstructions)

        setFooterPrompt("""Return your output by doing the following:
            |Use the enum in your json output to identify which type of error has occurred. Next, apply the following
            |logic for each enum type:
            |
            |- RefusedTask: Make a note of why the llm refused. This will be used for system logging.
            |
            |
            |- DidNotFollowInstructions: Make a note of what instructions the llm specifically did not follow.
            | 
            |- IncorrectResult: Make a note of why the result was incorrect.
        """.trimMargin())

        autoInjectContext("""The key of $USER_PROMPT_SNAPSHOT may have been provided to you as context.
            |If so, that key is the user prompt of the prior llm agent that was originally being worked on. Compare it to the
            |user prompt you have as input to assist with your assessment of the error.
        """.trimMargin())


        /**
         * Capture and bind the user prompt snapshot if it exists, and save it to the mini bank at the target
         * [USER_PROMPT_SNAPSHOT] key.
         */
        setPreValidationMiniBankFunction { context, content ->
            Logger.debug(LogCategory.SYSTEM, "BranchFailure: setPreValidationMiniBankFunction entry")
            //Boilerplate to fetch the snapshot.
            val currentPipe = content?.currentPipe
            val parentPipe = currentPipe?.getParentPipe()
            val snapshot = parentPipe?.getCachedInput() ?: MultimodalContent()
            val asJson = serialize(snapshot)

            /**
             * If null asJson should be an empty string IE we have no context here and should pass muster with the
             * autoInject instructions. Resulting in this being valid in either case.
             */
            context.contextMap[USER_PROMPT_SNAPSHOT] = ContextWindow().apply {
                contextElements.add(asJson)
            }

            Logger.debug(LogCategory.SYSTEM, "BranchFailure: setPreValidationMiniBankFunction success")
            return@setPreValidationMiniBankFunction context
        }


        setTransformationFunction {
            Logger.debug(LogCategory.SYSTEM, "BranchFailure: setTransformationFunction entry")
            val result = extractJson<AgentRetry>(it.text)
            if(result == null) {
                Logger.error(LogCategory.SYSTEM, "BranchFailure: setTransformationFunction failed: result is null")
                throw Exception("Retry results must be extractable as valid AgentRetry json schema.")
            }

            //Record error to the context bank so the orchestrator can log the failure and handle it above.
            ContextBank.emplaceWithMutex("errorStatus", ContextWindow().apply { contextElements.add(it.text) })
            it.terminate() //Issue terminate flag to end the pipeline due to an error state of reaching this point.
            Logger.debug(LogCategory.SYSTEM, "BranchFailure: setTransformationFunction success")
            return@setTransformationFunction it
        }

    }

    return branchPipe
}


/**
 * Build a branch failure pipe from the template of the constructed pipe. This allows us to handle basic switchovers
 * like model and token budget without having to re-assign the other values.
 */
fun buildBranchPipeFromTemplate(pipe: Pipe, model: String, budget: TokenBudgetSettings, copyFunctions: Boolean = false) : BedrockMultimodalPipe
{
    val newPipe = constructPipeFromTemplate<BedrockMultimodalPipe>(pipe, copyMetadata = true, copyFunctions = copyFunctions) ?: throw Exception("Failed to copy pipe from template")
    newPipe.apply {
        setModel(model)
        setTokenBudget(budget)
        setRegion("us-west-2")
        setServiceTier(BedrockPriorityTier.Standard)

        setPreInitFunction {
            Logger.debug(LogCategory.SYSTEM, "BranchFailure: buildBranchPipeFromTemplate.setPreInitFunction entry")
            val snapshot = it.getSnapshot()
            val asJson = serialize(snapshot)
            it.text = asJson
            Logger.debug(LogCategory.SYSTEM, "BranchFailure: buildBranchPipeFromTemplate.setPreInitFunction success")
        }
    }

    return newPipe
}
