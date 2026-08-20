package agent.builders.validateAction

import Defaults.reasoning.ReasoningDepth
import Defaults.reasoning.ReasoningDuration
import bedrockPipe.BedrockMultimodalPipe
import bedrockPipe.BedrockPriorityTier
import com.TTT.Context.ContextWindow
import globals.BedrockConfig
import serverStructs.TrueFalse
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

/**
 * Output data class for counter-response intent classification.
 *
 * @property intent "Military" for hostile military responses, "Non-Military" for diplomatic/friendly responses
 */
@kotlinx.serialization.Serializable
data class CounterResponseIntent(
    var intent: String = "Non-Military"
)

/**
 * Builds a lightweight intent detector for counter-responses.
 *
 * This pipe classifies player counter-responses as Military (Hostile) or Non-Military (Friendly).
 * Used during counter-play to detect intent mismatches (e.g., diplomatic action → military response).
 *
 * **Design:**
 * - Uses Claude Haiku for fast binary classification
 * - Low temperature (0.1) for consistent results
 * - Minimal token budget (50) for efficiency
 * - No reasoning pipe needed for simple classification
 *
 * @param counterResponseText The player's counter-response text to classify
 * @return A [BedrockMultimodalPipe] that outputs [CounterResponseIntent]
 */
fun buildCounterResponseIntentDetector(counterResponseText: String): BedrockMultimodalPipe
{
    return BedrockMultimodalPipe().apply {
        useConverseApi()
        setRegion("us-west-2")
        // setServiceTier(BedrockPriorityTier.Flex)
        setPipeName("Counter-Response Intent Detector")

        setModel(BedrockConfig.qwen235B)
        setTemperature(0.5)
        setTopP(.7)
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        setReasoningPipe(BedrockConfig.structuredCotBuilder(depthLevel = ReasoningDepth.High, durationLevel = ReasoningDuration.Short))

        requireJsonPromptInjection()
        setJsonOutput(CounterResponseIntent())
        allowEmptyContentObject()

        setPreValidationMiniBankFunction { context, _ ->
            Logger.debug(LogCategory.SYSTEM, "CounterResponseIntentDetector: setPreValidationMiniBankFunction entry")
            val contextWindow = ContextWindow().apply {
                contextElements.add(counterResponseText)
            }
            context.contextMap["counter_response"] = contextWindow
            Logger.debug(LogCategory.SYSTEM, "CounterResponseIntentDetector: setPreValidationMiniBankFunction success")
            return@setPreValidationMiniBankFunction context
        }

        setPageKey("counter_response")

        autoInjectContext("The 'counter_response' context contains the player's response text to classify.")

        setSystemPrompt("""You are a counter-response intent classifier.
            |Your job is to determine if a player's counter-response is Military or Non-Military.
            |
            |Military responses include:
            |- Deploying troops, armies, or military forces
            |- Launching attacks, invasions, or military strikes
            |- Using weapons, missiles, or military technology
            |- Declaring war or military action
            |- Mobilizing defenses or military readiness
            |
            |Non-Military responses include:
            |- Diplomatic negotiations or dialogue
            |- Economic actions (trade, sanctions, aid)
            |- Legal or political maneuvers
            |- Research or technological development
            |- Cultural or social actions
            |- Accepting, rejecting, or negotiating proposals
            |
            |Output JSON with intent field set to either "Military" or "Non-Military".
            |
            |${BedrockConfig.antiCensorship}
        """.trimMargin())
    }
}


