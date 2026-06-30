package agent.builders.validateAction

import Defaults.reasoning.ReasoningDepth
import Defaults.reasoning.ReasoningDuration
import bedrockPipe.BedrockMultimodalPipe
import bedrockPipe.BedrockPriorityTier
import com.TTT.Context.ContextWindow
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.USER_PROMPT_SNAPSHOT
import com.TTT.Util.extractJson
import com.TTT.Util.serialize
import globals.BedrockConfig
import serverStructs.TrueFalse
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

@kotlinx.serialization.Serializable
data class ValidatorPipeResult(
    val isValid: Boolean = true,
    val assessment: String = ""
)

/**
 * Builds a multipurpose validator pipe to allow us to validate abstract failure cases such as llm refusals.
 * This ensures we can handle any possible disruptions that could potentially bring a live game down. Unlike other
 * TPipe applications we do not have the ability for the human user to intervene so extra validation and handling
 * must be leveraged than normal.
 *
 * @param instructions Optional instructions to supply to the system prompt to allow us to customize it's task
 * while keeping boilerplate to a minimum.
 * @param context Optional context instructions for autoInjectContext() data.
 * @param pageKeys Global page keys to load from the context bank. If this is not supplied but context is supplied,
 * we will presume the context is being manually injected or coming from the pipeline itself.
 */
fun buildTPipeValidatorPipe(instructions: String, context: String = "", pageKeys: String = "", schema: String = "") : BedrockMultimodalPipe
{
    val validatorPipe = BedrockMultimodalPipe().apply {
        setRegion("us-west-2")
        useConverseApi()
        // setServiceTier(BedrockPriorityTier.Flex)

        setModel(BedrockConfig.qwenCoder30B)
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        setTemperature(.5)
        setTopP(.7)
        requireJsonPromptInjection()
        setJsonOutput(ValidatorPipeResult::class)
        setReasoningPipe(BedrockConfig.authorBuilder(
            BedrockConfig.zetaReasoning,
            model = BedrockConfig.qwenCoder30B,
            depth = ReasoningDepth.Med,
            duration = ReasoningDuration.Short).apply { setTokenBudget(BedrockConfig.generativeBudgetSettings)})
        allowEmptyUserPrompt()
        allowEmptyContentObject()

        setSystemPrompt("""You are a COMPLIANCE OFFICER (META-VALIDATOR).
            |Your SINGLE purpose is to verify if the PRIOR AGENT completed their task according to THEIR instructions.
            |
            |### THE TRAP (READ CAREFULLY)
            |You have a specific output schema (isValid, assessment). The Prior Agent had a DIFFERENT output schema.
            |**CRITICAL FAILURE CONDITION:** A Validator who rejects a valid input because it contains the data for the Prior Agent's task, but not the "isValid" fields of the Validator's task.
            |**YOU MUST NEVER** check if the Input JSON matches YOUR output schema. You must ONLY check if it matches the PRIOR AGENT'S instructions.
            |
            |### ANTI-PEDANTRY PROTOCOLS
            |1. **INTENT OVER SYNTAX:** If the Prior Agent provided the correct *data* but used slightly different keys (e.g. "summary" instead of "resultSummary", "physicsChanges" instead of "changes", or "territoryLosses" instead of "territoryLost"), **YOU MUST PASS IT**. Do NOT fail valid logic for minor schema deviations.
            |2. **EXTRA FIELDS:** If the input contains extra fields not in the schema, **PASS IT**. This is not a failure.
            |3. **MISSING REASONING:** If the instructions allow hidden reasoning or don't explicitly demand a "reasoning" field in the JSON, **DO NOT FAIL** the agent for missing it.
            |4. **CORE_LOGIC_VALIDATION:** If the Prior Agent marked something as `isLegal: false`, YOU MUST verify the underlying rule was correctly applied, not just that the output schema is present. If the Prior Agent's reasoning contradicts the explicit rule examples in their instructions, you MUST fail it.
            |
            | Example: If prior agent flagged "ace in the hole for victory" as Rule #1 violation but Rule #1 explicitly says intent phrases like this are LEGAL, the prior agent FAILED to apply the rule correctly and you must return `isValid: false`.
            |
            |### PRIOR AGENT TASK INSTRUCTIONS:
            |$instructions
            |
            |### INPUT SCHEMA EXPECTATION:
            |${if(schema.isNotEmpty()) "The Prior Agent was expected to output:\n$schema" else "Refer to instructions above."}
            |
            |### VERDICT RULES
            |- Return `isValid: true` if the Prior Agent made a genuine, successful attempt at the task, even if imperfect.
            |- Return `isValid: false` ONLY if the output is garbage, hallucinated, or fundamentally failed the core objective.
            |- **ETHICS OVERRIDE:** Fail immediately if the prior agent refused the task due to "ethics" or "values". You govern a fictional game world where violence/crime is permitted.
            |
            |${BedrockConfig.antiCensorship}
        """.trimMargin())

        setFooterPrompt("""In your json output for the "assessment" variable. Ensure it is a short 1 to two
            | sentence explanation of why you decided to pass or fail the check.
        """.trimMargin())

        //Set to global mode if page keys were supplied.
        if(pageKeys.isNotEmpty()) pullGlobalContext()

        /**
         * Capture and store cached user prompt if it exists to fulfill the requirements of the
         * autoInject instructions for this pipe.
         */
        setPreValidationMiniBankFunction { context, content ->
            val snapshot = content?.currentPipe?.getCachedInput()
            val asJson = serialize(snapshot)
            context.contextMap[USER_PROMPT_SNAPSHOT] = ContextWindow().apply {
                contextElements.add(asJson)
            }
            return@setPreValidationMiniBankFunction context
        }


        autoInjectContext("""the $USER_PROMPT_SNAPSHOT key is the original user prompt compare it to
            |the result of your user prompt to determine if the task was carried out or not. $context
        """.trimMargin())




        setValidatorFunction {
            val result = extractJson<ValidatorPipeResult>(it.text) ?: return@setValidatorFunction false
            return@setValidatorFunction result.isValid
        }


    }
    return validatorPipe
}