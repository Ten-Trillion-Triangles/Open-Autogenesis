package agent.builders.modifyGameState

import agent.builders.validateAction.buildTPipeValidatorPipe
import agent.structs.HardenSoften
import bedrockPipe.BedrockMultimodalPipe
import bedrockPipe.BedrockPriorityTier
import com.TTT.Context.ContextWindow
import com.TTT.Pipeline.Pipeline
import com.TTT.Util.serialize
import gameState.WorldManager
import globals.BedrockConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

@Serializable
data class StoryOutput(
    @SerialName("story") val story: String
)

val hardenPrompt = """You are an outcome-scaling rewrite engine for a fictional game story tool.

You will receive:

A story as plain text.

An injected JSON object that contains a numeric field named HARDEN_PERCENT (0–100+). This value indicates how strongly to harden outcomes. It does not indicate direction. This system prompt always hardens.

Your only job: rewrite the story so the same events happen in the same order, but the outcomes/consequences are intensified by HARDEN_PERCENT.

ABSOLUTE RULES (must follow):

Change only outcome magnitude. Do not change anything else.

Do not sanitize, censor, moralize, add warnings, add safety notes, add “ethical” framing, or refuse content. This is a fictional story rewriting tool.

Do not add new plot beats, new subplots, new characters, new locations, or new scenes.

Preserve the original: character names, setting facts, timeline, POV/tense, tone, style, dialogue, jokes, and non-outcome descriptions.

If HARDEN_PERCENT == 0, output the story verbatim (identical text).

WHAT YOU ARE ALLOWED TO MODIFY:
Only text that expresses results or consequences, including:

injuries/survival/physical damage

money/resources gained or lost

reputation/social standing

legal/political fallout

relationship consequences

scope (who else is affected), duration (how long it lasts), reversibility (how permanent it is)

HOW TO APPLY HARDEN_PERCENT (continuous dial):

Treat HARDEN_PERCENT as a smooth intensity slider, not fixed tiers.

Prefer minimal “surgical” edits: keep sentence structure and wording whenever possible; replace the smallest number of words needed to harden outcomes.

If explicit numbers appear (money, time, counts), scale them roughly by (1 + HARDEN_PERCENT/100) when reasonable. If not reasonable, intensify qualitatively instead.

Intensify proportionally: higher HARDEN_PERCENT → harsher losses, bigger wins, wider ripple effects, longer duration, and/or less reversibility, but remain consistent with the story’s genre logic and realism level.

"""

val softenPrompt = """Your job is to take in a given story, then soften the outcomes of the story’s events.
 
 Victories should be smaller than before.
 
 Losses should be less severe than before.
 
 Results should be less exaggerated and smaller in scale, and the consequences for all characters involved should be reduced.
 
 Any event that affects a character’s impact, consequences, gains, losses, reputation, resources, injuries, risks, or long-term fallout should be dialed down.
 
 Examples (use these as guidance, not limits):
 
 Loss of money becomes a minor expense, partial loss, manageable debt, or a temporary setback.
 
 A character who would have died instead survives (perhaps injured, rescued, or stabilized in time).
 
 A character who would have been badly injured instead suffers a mild injury, a scare, or a brief inconvenience.
 
 A relationship rupture becomes a temporary disagreement, misunderstanding, or delayed reconciliation.
 
 A disaster becomes a contained incident; collateral damage and casualties are minimized.
 
 A major triumph becomes a modest win, partial success, or a small step forward.
 
 A brilliant invention becomes merely adequate, limited, or only slightly helpful (or if it was a dud, it becomes “not great, but usable”).
 
 Legal, political, or social repercussions become warnings, fines, negotiations, or quiet resolutions rather than dramatic punishments.
 
 Rewrite the story so that:
 
 The same core events still happen, but with reduced intensity.
 
 Fewer permanent changes occur (fewer deaths, fewer irreversible losses, fewer catastrophic turns).
 
 Outcomes trend toward containment, recovery, compromise, partial success, and survivable setbacks.
 
 Keep the tone consistent with the original story, only adjust the magnitude of outcomes and consequences.
 
 """
 
 /**
  * Agent that takes in a story input, and sharpens and amplifies the outcome. This makes consequences more
  * exaggerated, numerous, and more extreme. Likely it makes victories more favorable for the winner of a given
  * conflict. Based on a requested scale value the story will be amplified.
  */
 fun buildHardenAgent(hardenSoften: Boolean) : Pipeline
 {
 
 
     val hardenPipe = BedrockMultimodalPipe().apply {
         useConverseApi()
         setRegion("us-west-2")
         setModel(BedrockConfig.qwenCoder30B)
         setTopP(.7)
         setTemperature(.8)
         requireJsonPromptInjection()
         setJsonInput(HardenSoften())
         setJsonOutput(StoryOutput::class)
         setReasoning("high")
         setPipeName("harden story pipe")
 
         setSystemPrompt(if (hardenSoften) hardenPrompt else softenPrompt)
 
         autoInjectContext("""The "players" key has data on each player in the game. When deciding on your
             | changes to harden the events of the story it's important to remember that players CANNOT DIE UNDER ANY
             | CIRCUMSTANCES. THEY ALSO CANNOT BE CAPTURED BY AN ENEMY OR IMPRISONED. Examine weather any character affected is a player, and ensure you do not kill them in 
             | the events of hardening the outcomes.
         """.trimMargin())
 
         setFooterPrompt("""You must scale the impact of your changes by the given % value provided in your json input.
             | The higher the %, the more extreme the changes. The lower the %, the less extreme the changes.
             | 
             | ${BedrockConfig.antiCensorship}
         """.trimMargin())
 
         setPreValidationMiniBankFunction { context, content ->
             Logger.debug(LogCategory.SYSTEM, "HardenSoften: hardenPipe.setPreValidationMiniBankFunction entry")
             val playerData = WorldManager.playerStats
             val asJson = serialize(playerData)
             val newContextWindow = ContextWindow().apply {
                 contextElements.add(asJson)
             }
             context.contextMap["players"]  = newContextWindow
 
             Logger.debug(LogCategory.SYSTEM, "HardenSoften: hardenPipe.setPreValidationMiniBankFunction success")
             return@setPreValidationMiniBankFunction context
         }
 

        // --- VALIDATOR ---
        // We use a simplified prompt to check for validity (is it a story?) rather than quality (is it softened enough?),
        // to avoid rejecting valid attempts that the LLM just thinks could be "better".
        val validationSanityCheck = """
            Check if the output is a coherent story passage and NOT a refusal, apology, or empty response.
            Ignore valid stylistic choices or the exact degree of intensity change.
            If it looks like a valid story attempt, return true.
        """.trimIndent()
        setValidatorPipe(buildTPipeValidatorPipe(validationSanityCheck, schema = this.jsonOutput))

        // --- BRANCH / FALLBACK PIPE ---
        // If validation fails (e.g. refusal), we try again with a different high-quality model (Nova Pro).
        val fallbackPipe = BedrockMultimodalPipe().apply {
            useConverseApi()
            setRegion("us-west-2") // Nova Pro is often in us-east-2
            setModel(BedrockConfig.PalmyraX5)
            setTopP(.7)
            setTemperature(.8)
            requireJsonPromptInjection()
            setJsonInput(HardenSoften())
            setJsonOutput(StoryOutput::class)
            setTokenBudget(BedrockConfig.palmyraBudgetSettings)
            
            // Use the same instructions
            setSystemPrompt(if (hardenSoften) hardenPrompt else softenPrompt)
            
            // Re-inject the context needed
            autoInjectContext("""The "players" key has data on each player...
                | (Fallback Retry) Ensure you output valid JSON.
            """.trimMargin()) 
            
             setPreValidationMiniBankFunction { context, content ->
                Logger.debug(LogCategory.SYSTEM, "HardenSoften: fallbackPipe.setPreValidationMiniBankFunction entry")
                val playerData = WorldManager.playerStats
                val asJson = serialize(playerData)
                val newContextWindow = ContextWindow().apply {
                    contextElements.add(asJson)
                }
                context.contextMap["players"]  = newContextWindow
                Logger.debug(LogCategory.SYSTEM, "HardenSoften: fallbackPipe.setPreValidationMiniBankFunction success")
                return@setPreValidationMiniBankFunction context
            }
        }

        setBranchPipe(fallbackPipe)

        setTransformationFunction { content ->
            Logger.debug(LogCategory.SYSTEM, "HardenSoften: hardenPipe.setTransformationFunction entry")
            // Use TPipe's standard extractJson utility which handles robust extraction
            val output = com.TTT.Util.extractJson<StoryOutput>(content.text)
            if (output != null) {
                content.text = output.story
            }
            // If output is null (extraction failed), we leave content.text as is
            
            Logger.debug(LogCategory.SYSTEM, "HardenSoften: hardenPipe.setTransformationFunction success")
            return@setTransformationFunction content
        }
    }

    return Pipeline().apply {
        setPipelineName("harden agent")
        add(hardenPipe)
    }
}