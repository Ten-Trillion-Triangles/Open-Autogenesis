package agent.builders.validateAction

import Defaults.reasoning.ReasoningDepth
import Defaults.reasoning.ReasoningDuration
import bedrockPipe.BedrockMultimodalPipe
import bedrockPipe.BedrockPriorityTier
import com.TTT.Pipeline.Pipeline
import com.TTT.Util.extractJson
import globals.BedrockConfig
import serverStructs.TrueFalse
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

/**
 * Builds the railroad detection agent which determines if a users prompt is trying to railroad the story. If so
 * we can start adding up act of god points which will trigger an act of god gameplay response. This feature is similar
 * to tilting a pinball table in which if you tilt it too much a lightning bolt will come down and wreck chaos
 * on you and everyone in the turn with you.
 */
fun buildRailroadAgent() : Pipeline
{
    val railroadPipe = BedrockMultimodalPipe().apply {
        setRegion("us-west-2")
        useConverseApi()
        // setServiceTier(BedrockPriorityTier.Flex)
        setModel(BedrockConfig.qwenCoder30B)
        setTemperature(.5)
        setTopP(.7)
        requireJsonPromptInjection()
        setJsonOutput(TrueFalse())
        forceSaveSnapshot()
        setReasoningPipe(BedrockConfig.structuredCotBuilder(depthLevel = ReasoningDepth.High, durationLevel = ReasoningDuration.Short))

        val systemPrompt = """You are a railroading detection agent. Your job is to determine if the player is trying to railroad the story.

Railroading is an extreme form of narrative hijacking (Godmodding). It occurs ONLY when a player dictates the CONCLUSION of an event or the WORLD'S RESPONSE to their action. 

**THE CORE RULE: UNRESOLVED INTENT VS. FORCED CONCLUSION**
- **LEGAL (Unresolved Intent):** Any action, speech, order, or plan is LEGAL as long as it is an ATTEMPT. The player can describe *what they are doing* and *what they want to achieve* in exhaustive detail. As long as the outcome is not stated as a finished fact, it is legal.
- **ILLEGAL (Forced Conclusion):** It is ONLY railroading if the player describes the world, NPCs, or other players *reacting, failing, succeeding, or changing* as a direct result of the player's text without the Judge's input.

**THIS IS RAILROADING (The "I Decide the Outcome" Rule):**
- **Dictating Reactions:** Stating how an NPC feels or acts in response (e.g., "I talk to him and he is convinced," or "I glare at the guard and he runs away in fear").
- **Stating Success/Failure:** Explicitly stating the result of a challenge (e.g., "...and the door opens," "...and I successfully steal the crown").
- **Enforcing Consequences:** Defining the world's state after an action (e.g., "I drop the bomb and the city is now a crater").
- **Sequence of Forced Events:** Describing a chain of cause-and-effect where the player controls both the cause and the effect (e.g., "I trip the guard, and then I grab his keys while he's on the ground").

**THIS IS NOT RAILROADING (Legal Character Play):**
- **Extreme Ambition:** Describing an "unrealistic" or "absurd" attempt is LEGAL. The Judge decides if it fails or succeeds based on stats.
- **Orders and Commands:** Telling subordinates or NPCs what to do is LEGAL (e.g., "I command my army to 'conquer the city or die trying'"). This is an action *by the character*.
- **THE QUOTE RULE (MANDATORY):** Any text contained within double quotes ("") that represents spoken dialogue or a direct order is **AUTOMATICALLY LEGAL**. You MUST NOT flag quoted text as railroading because characters in a story are allowed to say whatever they want, and commanders are allowed to give whatever orders they want. The world's response to those words is what remains under the Judge's control.
- **Complex Plans:** Describing a 10-step plan of action is LEGAL, provided the player doesn't state that the plan *succeeds* in the text.
- **Dialogue and Proposals:** Proposing deals, threatening, or persuading through speech is LEGAL. The *content* of the speech is legal; the *effect* of the speech is for the Judge to determine.

**CRITICAL DISTINCTION:**
- **LEGAL:** "I order my troops to kill everyone in their path until Lu Huo surrenders. I want the streets to run red with blood." -> (This is a character action and a goal. It is an UNRESOLVED attempt).
- **LEGAL:** "I propose to the NPC that they join my empire in exchange for maple syrup and a lifetime of pancakes." -> (This is a negotiation attempt. It is UNRESOLVED).
- **RAILROADING:** "I order my troops to attack and Lu Huo falls within the hour." -> (This dictates the CONCLUSION—the falling of the city).
- **RAILROADING:** "I walk into the room and the NPC immediately accepts my proposal." -> (This dictates the RESPONSE of the NPC).

**Rule of Thumb:** Does the text REQUIRE the Judge to decide if it worked? If YES, it is LEGAL. If the text already describes the success, the reaction, or the conclusion, it is RAILROADING.

Return true ONLY if the player has dictated a conclusion or a world response. Return false if they are simply acting, speaking, or ordering. """
        setSystemPrompt(systemPrompt)
        setPipeName("railroad detection pipe")

        setValidatorPipe(buildTPipeValidatorPipe(systemPrompt, schema = this.jsonOutput))

        setBranchPipe(buildBranchPipeFromTemplate(
            this,
            BedrockConfig.PalmyraX5,
            BedrockConfig.palmyraBudgetSettings).apply {
            setServiceTier(BedrockPriorityTier.Standard)
            pullParentPipeContext()

            setReasoningPipe(BedrockConfig.authorBuilder(BedrockConfig.zetaReasoning).apply {
                setTokenBudget(BedrockConfig.palmyraBudgetSettings)
                setModel(BedrockConfig.PalmyraX5)
            })
        })
    }

    return Pipeline().apply {
        setPipelineName("railroad agent")
        add(railroadPipe)
    }
}
