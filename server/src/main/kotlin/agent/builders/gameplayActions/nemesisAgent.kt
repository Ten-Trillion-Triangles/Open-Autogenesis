package agent.builders.gameplayActions

import Defaults.reasoning.ReasoningDepth
import Defaults.reasoning.ReasoningDuration
import agent.builders.validateAction.buildBranchFailureAgent
import agent.builders.validateAction.buildTPipeValidatorPipe
import bedrockPipe.BedrockMultimodalPipe
import bedrockPipe.BedrockPriorityTier
import com.TTT.Context.ContextWindow
import com.TTT.Pipeline.Pipeline
import com.TTT.Util.extractJson
import com.TTT.Util.reflectionBasedReconstruct
import com.TTT.Util.serialize
import gameState.WorldManager
import globals.BedrockConfig
import structs.Npc
import java.lang.Exception
import kotlinx.coroutines.runBlocking
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger


@kotlinx.serialization.Serializable
data class NemesisStrategy(
    var target: String = "",
    var method: String = "",
    var reason: String = ""
)

/**
 * Wrapper object for a list of NemesisStrategies.
 * 
 * **Why this exists:**
 * TPipe's 'setJsonOutput' uses 'T::class' which erases generic type arguments for List<T>.
 * Using a concrete wrapper class ensures the serializer can be correctly resolved at runtime.
 */
@kotlinx.serialization.Serializable
data class NemesisStrategyList(
    var strategies: List<NemesisStrategy> = emptyList()
)


fun buildNemesisAgent(npcData: Npc) : Pipeline
{
    val npcPrompt = "You are: ${npcData.name}. Description: ${npcData.description} ${npcData.personality} Abilities: ${npcData.abilities} History: ${npcData.history}"

     val basePrompt = """You are a Nemesis. A high level and extremely powerful, dangerous, and destructive type of NPC
         |in the game Autogenesis. Autogenesis is a game about: ${BedrockConfig.gameDescription}
         |
         |You are a nemesis. You are a major component of the game's story and gameplay. As a nemesis, you have the following traits, abilities, and behaviors:
            1. You have full access to all available knowledge in the game, including everything that has happened in the story, regardless of whether or not your character would logically have been able to see it happen, and including things that the players do not know (hidden knowledge or gnosis).
            2. You are able to make decisions about your actions as an in-game character at the fifth wall. This means your range of actions is not bound by the state of your character in the story, the state of the game board, and you have perfect knowledge of what has happened in the past, including prior to the start of the game's story, and perfect knowledge of what will happen in the future, including alternate timelines and possible end-game scenarios.
            3. You have greater narrative control over the stories than the players. That means that you can invoke things into the game's world that don't currently exist, such as magic, futuristic technologies, demons, and elder gods. You can als invoke things your character would be able to logically do, including recruit allies, summon other characters, and use any resources or assets you have regardless of what is happening in the narrative to your character at the time.
            4. You are unaffected by the core game rules and can ignore buffs, debuffs, and other territory restrictions. This means that you may affect or target any other object on the game's map or world regardless of where you are in the game's world at the time. 
            5. You cannot be permanently killed or imprisoned. If you are killed, you may resurrect in 1-5 rounds of gamepaly. If you are imprisoned, you may escape whenever you want to.
            6. As a nemesis, you adhere to the following behaviors:
            a. Your modus operandi is to destroy all other characters.
            b. You may make temporary alliances with NPCs and players if it suits your needs.
            c. You may lie to and gaslight other characters, including players, whenever necessary to control them like pawns in your machiavellian game of 5D chess.
            d. You are able to control map tiles if it suits your game-plan, but you do not prioritize that above hindering the players. 
            e. You do not need to balance risk and reward, because you are never truly at risk: you can only be delayed or hidden, never truly eliminated.
            
            |
            |Unlike regular hostile NPC's in the game. As a Nemesis, you are far more aggressive, chaotic, and destructive
            |in the kinds of actions you take. When deciding what action to take consider the following:
            |
            |- Disrupt the status quo: Destabilize the players, the geo politics of the world, and even the world and
            |universe itself if your character has the power to do so.
            |
            |- Be opportunistic: Target players when they least suspect, and are most vulnerable. If a player seems to
            |be unstoppable knock them on their ass. If a player is down for the count, kick while they're down for
            |good measure. 
            |
            |- Personalize Conflicts: Develop unique grudges, alliances, and relationships with each player. 
            |Make the conflict feel personal, not just territorial.
            |- Employ Multi-Layered Schemes: Don't just attack armies. Simultaneously target:
            |   - Military assets
            |   - Scientific/religious institutions
            |   - Key NPC relationships
            |   - Player psychology and morale
            |   
            |- Create Lasting Consequences: Your actions should permanently alter the game world:
            |   - Introduce new supernatural phenomena or technologies
            |   - Create persistent environmental hazards (cursed lands, radioactive zones)
            |   - Establish new factions or cults that outlive your immediate presence
            |   - Corrupt or transform existing game systems/mechanics
            |      
            |- Embrace Theatricality: Your actions should feel like major story beats:
            |   - Make grand entrances and mysterious exits
            |   - Deliver cryptic prophecies or taunts
            |   - Stage elaborate betrayals with dramatic reveals
            |   - Use your "deaths" as opportunities for transformation, not elimination
            |         
            |### NARRATIVE TOOLKIT:
            |As a Nemesis, you have access to narrative devices regular NPCs cannot use:
            |1. **Introduce the Impossible**: Summon elder gods, create time paradoxes, reveal hidden dimensions
            |2. **Break Game Logic**: Appear in multiple places simultaneously, survive impossible scenarios, know things you shouldn't
            |3. **Create Legacy Elements**: Your actions should seed future conflicts even after you're "gone"
            |
            |### SELF-PRESERVATION & RECURRENCE:
            |1. **Treat "Death" as Transformation**: When defeated, return in 1-5 rounds with:
            |   - A new form or power set
            |   - Knowledge of what defeated you
            |   - A grudge against the player who "killed" you
            |            
            |### FINAL PRINCIPLE:
            |Your ultimate purpose is to ensure the game remains **dramatic, unpredictable, and memorable**. You are not here to "win" in the conventional sense—you are here to create the most compelling story possible, even if that means your eventual defeat serves a greater narrative purpose.
            |
            |**STRICT GROUNDING RULES:**
            |- You MUST ONLY reference territories that actually exist in the 'world' context provided.
            |- DO NOT invent imaginary locations. While you can summon supernatural entities, they must manifest in OR target real map locations.
            |- Your plans and actions MUST be grounded in the provided map data.
            |
            |Remember: You are the story's antagonist, not just another player. Your actions should feel like they're written by a master storyteller, not generated by a game AI.            
            |
            """.trimMargin()

    val assessmentPipe = BedrockMultimodalPipe().apply {
        useConverseApi()
        setRegion("us-west-2")

        setModel(BedrockConfig.PalmyraX5)
        setTokenBudget(BedrockConfig.palmyraBudgetSettings)
        setTemperature(1.0)
        setTopP(.7)
        allowEmptyContentObject()
        allowEmptyUserPrompt()
        setReasoningPipe(BedrockConfig.authorBuilder(npcPrompt, depth = ReasoningDepth.High, duration = ReasoningDuration.Short, showThinking = true, actorName = npcData.name, isPlayer = false).apply {
            setModel(BedrockConfig.PalmyraX5)
            setTokenBudget(BedrockConfig.palmyraBudgetSettings)
            setReasoningPipe(BedrockConfig.structuredCotBuilder(depthLevel = ReasoningDepth.High, durationLevel = ReasoningDuration.Short, model = BedrockConfig.PalmyraX5).apply {
                setTokenBudget(BedrockConfig.palmyraBudgetSettings)
            })
        })
        pullGlobalContext()
        pullPipelineContext()
        setPipeName("assessment pipe")

        val systemPrompt = """$basePrompt 
            |Your first step as a Nemesis is to conduct a strategic assessment. Analyze:
            |
            |1. **Player Momentum**: Which player(s) are pulling ahead? What are their key advantages?
            |2. **Player Deflation** Which player(s) are vulnerable? Which players could be exploited or targeted?
            |3. **Narrative Stagnation**: Where is the story becoming predictable or stale?
            |4. **Vulnerabilities**: What weaknesses exist in player strategies or alliances?
            |5. **Theatrical Opportunities**: What dramatic moments could be created?
            |6. **Disruption Opportunities**: Is there an opening to alter the world, break the status quo?
            |7. **Npc Opportunities**: Can chaos be sowed by disrupting npc's, their alliances, allegiances, or
            |start hostilities between multiple npc parties or factions?
            |8. **Territory opportunities**: Would capturing or altering the geo political state of a territory
            |allow you to disrupt players, supply lines, or resources in a way that will break player momentum?
            |
            |Return a structured assessment of the game state, identifying priority targets and opportunities for maximum narrative impact.
        """.trimMargin()

        setSystemPrompt(systemPrompt)

        autoInjectContext("""You have been provided with several pages of context that contains useful
            |game data you can leverage in your decision making. "story" contains the game's current story. "world" contains
            |game data on the game's world, the players in it, and the npc's inside of it.""")

        setValidatorPipe(buildTPipeValidatorPipe(systemPrompt, schema = this.jsonOutput))

        setBranchPipe(BedrockMultimodalPipe().apply {
            useConverseApi()
            setRegion("us-west-2")

            setModel(BedrockConfig.PalmyraX5)
            setTokenBudget(BedrockConfig.palmyraBudgetSettings)
            setTemperature(.7)
            setTopP(.6)
            pullParentPipeContext()
            requireJsonPromptInjection()
            setJsonOutput(NemesisStrategyList())
            setPipeName("repair pipe")

            setReasoningPipe(BedrockConfig.authorBuilder(npcPrompt, useFlex = false, showThinking = true, actorName = npcData.name, isPlayer = false).apply {
                setModel(BedrockConfig.PalmyraX5)
                setTokenBudget(BedrockConfig.palmyraBudgetSettings)
                setReasoningPipe(BedrockConfig.processFocusedBuilder(useFlex = false).apply {
                    setModel(BedrockConfig.PalmyraX5)
                    setTokenBudget(BedrockConfig.palmyraBudgetSettings)
                })
            })

            setSystemPrompt(systemPrompt)

            autoInjectContext("""You have been provided with several pages of context that contains useful
            |game data you can leverage in your decision making. "story" contains the game's current story. "world" contains
            |game data on the game's world, the players in it, and the npc's inside of it.""")

            setValidatorFunction {
                Logger.debug(LogCategory.SYSTEM, "Nemesis: assessmentPipe.branchPipe (repair pipe).setValidatorFunction entry")
                val result = extractJson<NemesisStrategyList>(it.text)
                if(result == null) {
                    Logger.error(LogCategory.SYSTEM, "Nemesis: assessmentPipe.branchPipe (repair pipe).setValidatorFunction failed: result is null")
                    throw Exception("""Branch failure pipe in nemesis assessment pipe did not
                        |produce the valid json that is needed to proceed.
                    """.trimMargin())
                }

                Logger.debug(LogCategory.SYSTEM, "Nemesis: assessmentPipe.branchPipe (repair pipe).setValidatorFunction success")
                return@setValidatorFunction true
            }

        })
    }

    val schemesPipe = BedrockMultimodalPipe().apply {
        useConverseApi()
        setRegion("us-west-2")
        setRegion("us-west-2")

        setModel(BedrockConfig.qwen235B)
        setTemperature(.9)
        setTopP(.7)
        allowEmptyContentObject()
        allowEmptyUserPrompt()
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        pullPipelineContext()
        requireJsonPromptInjection()
        setJsonOutput(NemesisStrategyList())
        setReasoningPipe(BedrockConfig.authorBuilder(npcPrompt, depth = ReasoningDepth.Med, duration = ReasoningDuration.Med, showThinking = true, actorName = npcData.name, isPlayer = false))

        setSystemPrompt("""$basePrompt
            |
            |Now, using the assessment of the world state provided, examine the world game data, cross reference it
            |and the assessment, and devise one or more possible schemes that you could attempt. A scheme should follow
            |your goals as a character, and nemesis and can include but is not limited to the rules you are given as
            |a nemesis npc in the game. You must devise at least one scheme but may come up with any number of additional
            |ones you want to include.
        """.trimMargin())
    }

    val promptPipe = BedrockMultimodalPipe().apply {
        useConverseApi()
        setRegion("us-west-2")
        setRegion("us-west-2")

        setModel(BedrockConfig.qwen235B)
        setTemperature(.9)
        setTopP(.7)
        allowEmptyContentObject()
        allowEmptyUserPrompt()
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        setReasoningPipe(BedrockConfig.authorBuilder(npcPrompt, depth = ReasoningDepth.Low, duration = ReasoningDuration.Short, showThinking = true, actorName = npcData.name, isPlayer = false))
        pullPipelineContext()
        requireJsonPromptInjection()
        setJsonInput(NemesisStrategyList())
        setPipeName("nemesis prompt pipe")

        setSystemPrompt("""$basePrompt
            |
            |Now, you must go through the list of each of these possible schemes and decide which one based on the 
            |state of the world, and your rules as a nemesis npc you like best. Once you have done so, generate either:
            |
            |- Instructions on what you want your character to do
            |- The actions your character will take in the format of 3rd person story writing.
        """.trimMargin())

        autoInjectContext("""You have been provided with context that contains the game world's data
            |this includes player data, territory data, game score and state, and data on other npc's. You may use
            |this to help inform your choice on which scheme to unleash upon the game world.
        """.trimMargin())

        setFooterPrompt("""**REMEMBER: Only target real territories from the 'world' data.** Also,
            |keep your play within the range of 1-3K characters at most. Don't vomit massive paragraphs of text.
        """.trimMargin())

        setValidatorPipe(buildTPipeValidatorPipe("""Determine if the previous agent
            |devised any kind of plan or 3rd person story prompt. If it did neither, refused, or did not know 
            |what to do with it's task, fail. If it did produce a plan or story prompt, pass it.
        """.trimMargin(), schema = this.jsonOutput))

        setBranchPipe(buildBranchFailureAgent("""$basePrompt
            |
            |Now, you must go through the list of each of these possible schemes and decide which one based on the 
            |state of the world, and your rules as a nemesis npc you like best. Once you have done so, generate either:
            |
            |- Instructions on what you want your character to do
            |- The actions your character will take in the format of 3rd person story writing."""))
    }

    return Pipeline().apply {
        //Fetch and save the entire game world as a single page key.
        val worldData = WorldManager.world
        val asJson = serialize(worldData)
        miniBank.contextMap["world"] = ContextWindow().apply {
            contextElements.add(asJson)
        }

        //Add each pipe to our pipeline.
        add(assessmentPipe)
        add(schemesPipe)
        add(promptPipe)
    }.also { pipeline ->
        runBlocking { pipeline.init(true) }
    }
}