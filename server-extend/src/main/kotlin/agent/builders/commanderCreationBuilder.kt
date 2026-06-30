package agent.builders

import Defaults.BedrockConfiguration
import Defaults.reasoning.ReasoningBuilder.reasonWithBedrock
import Defaults.reasoning.ReasoningDepth
import Defaults.reasoning.ReasoningDuration
import Defaults.reasoning.ReasoningInjector
import Defaults.reasoning.ReasoningMethod
import Defaults.reasoning.ReasoningSettings
import com.TTT.Pipeline.Pipeline
import bedrockPipe.BedrockMultimodalPipe
import com.TTT.Context.ContextWindow
import com.TTT.Pipe.MultimodalContent
import com.TTT.Structs.PipeSettings
import com.TTT.Util.deserialize
import com.TTT.Util.extractJson
import com.TTT.Util.serialize
import enums.CommanderType
import globals.ExtendModelDefaults
import structs.Commander
import structs.agent.AgentCommanderTraitResponse
import structs.agent.AgentCommanderTypeResponse
import structs.requests.CommanderCreateRequest

/**
 * Builder function that builds a pipeline to handle determine what the commander's type and trait is.
 * This is something that needs to be decided by the system rather than the player for fairness reasons.
 *
 * Both the type and the traits are internal values not disclosed to the player, but impacts the results of
 * the game.
 */
suspend fun buildCommanderCreationAgent() : Pipeline
{
    //Declare the reasoning pipe we'll use for the nova models.
    val reasoningSettings = ReasoningSettings(
        reasoningMethod = ReasoningMethod.ExplicitCot,
        depth = ReasoningDepth.High,
        duration = ReasoningDuration.Long,
        reasoningInjector = ReasoningInjector.AfterUserPrompt,
        injectFooterPrompt = true
    )

    val bedrockSettings = BedrockConfiguration(
        region = "us-east-2",
        model = ExtendModelDefaults.novaModelName
    )

    val pipeSettings = PipeSettings(
        temperature = .7,
        topP = .7,
        contextWindowSize = 320000,
        maxTokens = 4000
    )

    val reasoningPipe = reasonWithBedrock(bedrockSettings, reasoningSettings, pipeSettings)

    /**
     * Identifies based on the description and name of the commander what it's type is. Flying, Land, or Aquatic.
     */
    val typeIdentifyPipe = BedrockMultimodalPipe()
        .setRegion("us-east-2")
        .useConverseApi()
        .setModel(ExtendModelDefaults.novaModelName)
        .setPipeName("type identify pipe")
        .setTemperature(.6)
        .requireJsonPromptInjection()
        .setMaxTokens(4000)
        .setTopP(.7)
        .setContextWindowSize(32000)
        .setJsonInput(CommanderCreateRequest())
        .setJsonInput(AgentCommanderTypeResponse())
        .setReasoningPipe(reasoningPipe)
        .setSystemPrompt("""Your job is to evaluate the name and description of this player's commander and determine
            |what type of commander it is. There are three possible types a commander can be:
            |
            |Flying: These are any commanders that rule a nation that resides above ground, and in the sky.
            |
            |Aquatic: These are commanders that rule over a nation that exists entirely underwater.
            |
            |Land: Every other commander that is not flying, or aquatic falls into this category. Land based commanders
            |have their nations stationed on the ground, on a continent, landlocked country, island, or shoreline based
            |landmass.
        """.trimMargin())
        .setFooterPrompt("""When deciding what type the commander is, you need to base it on the obvious traits in their
            |description. Commanders cannot be multiple types at once. So the most obvious thing that they are is there
            |type.
        """.trimMargin())
        .setValidatorFunction {
            //Validate it produced the correct json output. Kill pipeline if it did not.
            val result = extractJson<AgentCommanderTypeResponse>(it.text) ?: return@setValidatorFunction false
            return@setValidatorFunction true
        }
        .setTransformationFunction {
            //Gather the references we need to write back into the pipeline's context.
            val pipeRef = it.currentPipe //Get the pipe so we can get our pipeline.
            val pipelineRef = pipeRef!!.getPipelinesFromInterface()[0] //Catch 22. Either it has to throw or we have to throw.
            val newContextWindow = pipelineRef.context //Should not be empty but the same catch 22 for throws exists here too.

            //Restore the original reference, then start building out the actual commander data.
            val originalRequest = deserialize<CommanderCreateRequest>(newContextWindow.contextElements[0])
            val commanderStruct = Commander()
            commanderStruct.description = originalRequest!!.description
            commanderStruct.name = originalRequest.name
            commanderStruct.empire = originalRequest.empireDescription

            //Push back our commander data that's being built as the pipeline's actual context. Replaces the old request.
            val commanderBeingBuiltAsJson = serialize(commanderStruct)
            newContextWindow.contextElements.add(0, commanderBeingBuiltAsJson) //Emplace back into index 0.
            pipelineRef.context = newContextWindow //Emplace back into our pipeline overwriting the request.

            //Replace llm's output with the original request so we can pass it forward for the next single step predication.
            it.text = serialize(originalRequest)

            return@setTransformationFunction it //Push back the now modified content object forward into the next pipe.
        }


    val traitIdentifyPipe = BedrockMultimodalPipe()
        .setRegion("us-east-2")
        .useConverseApi()
        .setModel(ExtendModelDefaults.novaModelName)
        .setPipeName("trait identify pipe")
        .setTemperature(.6)
        .setTopP(.7)
        .requireJsonPromptInjection()
        .setContextWindowSize(32000)
        .setMaxTokens(4000)
        .setJsonInput(CommanderCreateRequest())
        .setJsonOutput(AgentCommanderTraitResponse())
        .setReasoningPipe(reasoningPipe)
        .setSystemPrompt("""Your job is to examine the input for player's request to create a new commander.
            |You must determine what the trait of the commander is. A commander can be one of the following
            |traits:
            |
            |Warlord: A warlord is a dictator of some kind that solves all his nations problems through force, oppression,
            |and hostile actions against enemy nations. The warlord excels as creating destructive resources, and taking
            |destructive actions, but is less likely to do well in diplomatic actions that aren't hostile, destructive,
            |or colluding to work together to do something hostile or destructive. Warlords tend to make themselves
            |general annoyances and menaces to the players, and npc's around them are unlikely to hold alliances unless
            |those alliances are beneficial to them.
            |
            |Diplomatic: A diplomatic commandeer tends to try to gain power, strength, and advance their agenda through
            |diplomatic negotiations with other nations. They excel at politics but are weak at military and hostile actions.
            |They are more likely to be successful with political actions than underhanded schemes, and are more prone to trying
            |to depend on alliances, allies, and support from other players or npc's to advance their conquest of the game's world.
            |
            |Researcher: A researcher is a commander that excels at the creation of technology, magic, or any other resource that
            |would give them the advantage against other nations. They are not great a military, or diplomatic actions and favor
            |using their creations, national resources, and other advancements to make up the difference. They exel at progression
            |compared to the other commander types.
            |
            |Balanced: A balanced commander has no specific strengths or weaknesses and is only average at military, diplomacy, and research.
            |They are less likely to be good at any given task, but also not as likely to be catastrophically bad at a given task.
            |If a commander fails to be any of the other traits, they are defaulted to the Balanced type.
        """.trimMargin())
        .setFooterPrompt("""Commanders can only have one of these four possible traits. You must use the context clues
            |in the description of the commander to decide which of these traits is most logical for the commander to be.
            |Only the most likely trait is the correct trait.
        """.trimMargin())
        .setValidatorFunction {
            //Validate the model produced valid json. If not kill the pipeline here.
            val result = extractJson<AgentCommanderTraitResponse>(it.text) ?: return@setValidatorFunction false
            return@setValidatorFunction true
        }
        .setTransformationFunction {
            //Get required references so we can operate upon filling out our commander traits.
            val pipeRef = it.currentPipe
            val pipelineRef = pipeRef!!.getPipelinesFromInterface()[0]
            val pipelineContext = pipelineRef.context

            //Update our trait for our commander.
            val commanderDataBeingBuilt = deserialize<Commander>(pipelineContext.contextElements[0])
            val pipeLlmResponse = extractJson<AgentCommanderTraitResponse>(it.text)
            commanderDataBeingBuilt!!.trait = pipeLlmResponse?.trait!!

            //Push our new updated commander data back to our content object.
            val finalResult = serialize(commanderDataBeingBuilt)
            it.text = finalResult

            //Push our pipeline context back if we need it.
            pipelineContext.contextElements.add(0, finalResult)
            pipelineRef.context = pipelineContext

            return@setTransformationFunction it
        }


    val pipeline = Pipeline()
        .add(typeIdentifyPipe)
        .add(traitIdentifyPipe)
        .enableTracing()
        .init()

    return pipeline
}