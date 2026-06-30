package agent.builders.passFailAgent

import Defaults.reasoning.ReasoningDepth
import Defaults.reasoning.ReasoningDuration
import agent.builders.judgeOutcome.`Victory?`
import agent.builders.validateAction.buildBranchFailureAgent
import agent.builders.validateAction.buildBranchPipeFromTemplate
import agent.builders.validateAction.buildTPipeValidatorPipe
import bedrockPipe.BedrockMultimodalPipe
import bedrockPipe.BedrockPriorityTier
import com.TTT.Context.ContextBank
import com.TTT.Context.ContextWindow
import com.TTT.Pipeline.Pipeline
import com.TTT.Util.extractJson
import com.TTT.Util.serialize
import gameState.WorldManager
import globals.BedrockConfig
import globals.BedrockConfig.structuredCotBuilder
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import serverStructs.PlayerStats
import structs.Player

/**
 * Builds a standalone pass/fail agent that determines if a player's action succeeded or failed.
 * The final output of the pipeline is the JSON string representation of the Victory? object.
 *
 * @param player Optional player data to use for evaluation. If null, uses default Player instance.
 * @return A [Pipeline] containing the pass/fail determination logic.
 */
private const val PASS_FAIL_CONTEXT = "passFailOutcome"

fun buildPassFailAgent(player: Player? = null): Pipeline
{
    val pipeline = Pipeline()

    val passOrFailPipe = BedrockMultimodalPipe().apply {
        setRegion("us-west-2")
        useConverseApi()
        setModel(BedrockConfig.qwenCoder30B)
        requireJsonPromptInjection()
        setJsonOutput(`Victory?`())
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        setTemperature(0.7)
        setTopP(0.6)
        setReasoningPipe(BedrockConfig.explicitCotBuilder(depthLevel = ReasoningDepth.Med, durationLevel = ReasoningDuration.Short))
        pullPipelineContext()
        setPageKey("previous turn, user prompt, player stats")
        forceSaveSnapshot()

        val systemPrompt = """Your role is to determine whether or not the player has succeeded
            |in the action which they attempted to take. If the player has succeeded, set the 
            |boolean to true. If the player has failed, set the boolean to false.
            |
            |##PRIMARY CRITERION: GOAL ACHIEVEMENT##
            |The player's stated action describes their GOAL. Evaluate if that goal was achieved.
            |
            |GOAL EVALUATION PROCESS:
            |1. Identify the player's goal from their action statement
            |2. Analyze the story outcome against that goal
            |3. If goal achieved → SUCCESS (even if method was bizarre/absurdist)
            |4. If goal not achieved → FAILURE
            |
            |GOAL TYPES:
            |- Explicit: "Capture territory X" → Did they capture it?
            |- Implicit: "Destabilize enemy" → Is enemy destabilized?
            |- Abstract: "Diplomatic mission" → Did diplomacy succeed?
            |
            |CRITICAL: Absurdist/surreal methods are VALID if they achieve the goal.
            |Example: "Deploy Shitty Bob to destabilize Cerberus"
            |→ If Cerberus morale collapses, recruitment ceases, personnel defect → SUCCESS
            |
            |##CRITICAL: REVERSAL STORY INTERPRETATION##
            |${BedrockConfig.reversalStoryGuide}
            |
            |##DECISION PRIORITY##
            |1. EVALUATE GOAL ACHIEVEMENT: Did the player achieve their stated goal?
            |2. Look for EXPLICIT OUTCOME STATEMENTS ("defeat declared", "forces repelled", "victory achieved")
            |3. If found, use that as your answer (ignore all other language in the story)
            |4. If no explicit statement, analyze the overall outcome
            |5. When story describes "failed to X" or "did not Y", these are NON-EVENTS (failure indicators)
            |6. Supernatural events being reversed is NOT the same as player success
            |
            |##MILITARY PLAY EVALUATION##
            |Military actions have two distinct types with different evaluation criteria:
            |
            |###TYPE 1: CHARACTER-TARGETED ACTIONS###
            |Goal: Kill, capture, or disable named character(s)
            |- PASS: Story directly states goal achieved, OR events clearly accomplish it
            |- FAIL: Story states character survived, escaped, was not found
            |
            |###TYPE 2: TERRITORY INVASION ACTIONS###
            |These require checking MULTIPLE conditions:
            |
            |**EITHER Condition A OR Condition B must be true:**
            |
            |Condition A - Government Removed:
            |- Player deposed, destroyed, drove out, or forced surrender of enemy government/rulers
            |- OR territory had no government and player rolled in unopposed
            |- If A is true AND no contested conditions → PASS
            |
            |Condition B - Clear Victory:
            |- Player clearly won battle/war, was winning, OR made clear gains
            |- AND likely to win if story continued OR still holds gains at end
            |- If B is true AND no contested conditions → PASS
            |
            |**ANY of these contested conditions → FAIL:**
            |- Third party showed up and was NOT defeated
            |- Civil war within player's own forces
            |- Betrayal by subordinate/general
            |- Player did NOT clearly hold gains at end
            |- Another actor is contesting the gains
            |- "Instead" doing something other than ordered (subordinate disobedience)
            |
            |**NOTE: Enemy government surviving in exile or "continuing to claim legitimacy" is NOT a failure**
            |**condition if the player clearly holds the territory. If player has clear gains and holds them,**
            |**the enemy government being displaced elsewhere does NOT block victory.**
        """.trimMargin()

        val context = """ADDITIONAL CONTEXT:
            "player stats" is the set of statistical points and abilities and qualities 
            associated with the player's commander.
            "previous turn" is the turn of the game which you are analyzing.
            "player statistics" is all of the information relevant to the player whose
            turn it is, including the qualities of their commander and empire, their
            resources, their territory, and their score.
            """.trimIndent()

        val footer = """You produce JSON schema. If the player succeeded,
            |set boolean to true. If the player failed, set boolean to false. 
            |
            |##CRITICAL EXAMPLES##
            |
            |Example 1 - FAILURE:
            |Story: "Forces were repelled. The defeat was declared. Omega remains beyond Alliance jurisdiction."
            |Output: {"isVictory": false}
            |Reason: Explicit defeat statements are definitive
            |
            |Example 2 - FAILURE:
            |Story: "Bob failed to combust. He did not reappear. The sermon was never delivered."
            |Output: {"isVictory": false}
            |Reason: All actions described as non-events
            |
            |Example 3 - FAILURE:
            |Story: "Omega returned to normal function. The Pulp God dissipated. However, forces were repelled."
            |Output: {"isVictory": false}
            |Reason: Explicit defeat statement overrides "returning to normal"
            |
            |Example 4 - SUCCESS:
            |Story: "Forces captured the station. Victory was declared. Omega is now under Alliance control."
            |Output: {"isVictory": true}
            |Reason: Explicit success statements
            |
            |Example 5 - SUCCESS (Goal-Based):
            |Action: "Deploy Shitty Bob to destabilize Cerberus leadership"
            |Story: "Cerberus morale collapsed to 11%. Recruitment ceased. Directors resigned. Major Turdington defected to player."
            |Output: {"isVictory": true}
            |Reason: Goal was destabilization. Cerberus is clearly destabilized. SUCCESS.
            |
            |Example 6 - FAILURE (Subordinate Disobedience):
            |Action: "Order Shitty Bob to weaponize fecal matter for strategic leverage"
            |Story: "Under orders to weaponize fecal matter, Shitty Bob instead fused the biomass with the AI core, triggering an unforeseen ontological phase shift."
            |Output: {"isVictory": false}
            |Reason: Goal was controlled weapon. Got uncontrolled chaos. Subordinate disobeyed ("instead"). Unforeseen = unintended. FAILURE.
            |
            |Example 7 - FAILURE (Research Gone Wrong):
            |Action: "Research X to gain advantage Y"
            |Story: "The research produced Z instead. Unforeseen consequences included diplomatic disaster and public humiliation."
            |Output: {"isVictory": false}
            |Reason: Goal not achieved. "Instead" and "unforeseen" indicate failure. Negative consequences confirm failure.
            |
            |##MILITARY PLAY EXAMPLES##
            |
            |Example 8 - PASS (Character Kill):
            |Action: "Order the assassination of Director Harkness"
            |Story: "Director Harkness was killed in the attack. His body was recovered and confirmed dead."
            |Output: {"isVictory": true}
            |Reason: Direct goal achieved
            |
            |Example 9 - FAIL (Character Escaped):
            |Action: "Order the assassination of Director Harkness"
            |Story: "Director Harkness escaped the compound before the attack. He was seen fleeing north."
            |Output: {"isVictory": false}
            |Reason: Character goal not achieved
            |
            |Example 10 - PASS (Clean Victory):
            |Action: "Invade Omega to capture the station"
            |Story: "Forces captured Omega Station. The Alliance commander surrendered. Victory was declared. Omega is now under Alliance control."
            |Output: {"isVictory": true}
            |Reason: Explicit victory, government removed, no contested conditions
            |
            |Example 11 - FAIL (Contested - Civil War):
            |Action: "Invade Omega to capture the station"
            |Story: "Forces captured Omega Station. The Alliance commander surrendered. However, General Moustache then declared himself Emperor and civil war erupted among the occupying forces."
            |Output: {"isVictory": false}
            |Reason: Contested conditions - civil war means player doesn't clearly hold gains
            |
            |Example 12 - FAIL (Contested - Betrayal):
            |Action: "Deploy General Moustache to invade Oregon"
            |Story: "General Moustache led the invasion of Oregon. However, he publicly renounced allegiance and declared himself Emperor, leading his forces to defect."
            |Output: {"isVictory": false}
            |Reason: Subordinate betrayal - gains contested
            |
            |Example 13 - FAIL (Contested - Third Party):
            |Action: "Invade Oregon"
            |Story: "The Ent Army captured Oregon. The Oregon government surrendered. However, the Ocean of Peace then attacked the flanks and was not defeated."
            |Output: {"isVictory": false}
            |Reason: Third party showed up and was not defeated
            |
            |Example 14 - FAIL (Contested - Gains Lost):
            |Action: "Invade Omega"
            |Story: "The Alliance forces captured Omega Station but were then driven back by counterattack. They no longer hold any territory."
            |Output: {"isVictory": false}
            |Reason: Player did not hold gains at end
            |
            |Example 15 - PASS (Enemy Displaced But Territory Held):
            |Action: "Invade Oregon to overthrow the government"
            |Story: "The Ent Army captured Oregon. The Oregon government evacuated to exile and continues to claim legitimacy from eastern settlements, but the Ent Army holds Oregon firmly."
            |Output: {"isVictory": true}
            |Reason: Player holds the territory. Enemy being displaced elsewhere does not block victory.
            |
            |Example 16 - FAIL (Contested - Enemy Government Survived):
            |Action: "Invade Oregon to overthrow the government"
            |Story: "The Ent Army advanced but the Oregon government evacuated to exile and continues to claim legitimacy from the eastern settlements. The Ent Army holds no territory."
            |Output: {"isVictory": false}
            |Reason: Enemy government survived AND player did not hold gains
            |
            |##FAILURE INDICATORS##
            |
            |These words/phrases in the narrative indicate FAILURE:
            |- "instead" (subordinate did something else)
            |- "unforeseen" (unintended outcome)
            |- "unintended" (not the goal)
            |- "failed" (explicit failure)
            |- "disobeyed" (orders not followed)
            |- "went wrong" (negative outcome)
            |- "backfired" (opposite of intended)
            |- "disaster" (catastrophic failure)
            |- "civil war" (internal faction conflict)
            |- "defected" / "defection" (forces switched sides)
            |- "third party" (uninvolved actor interferes and is not defeated)
            |- "betrayed" / "betrayal" (subordinate turned against player)
            |- "renounced allegiance" (general switched sides)
            |- "continues to claim" (enemy government survived and disputes ownership)
            |
            |For RESEARCH/DIPLOMATIC actions specifically:
            |- Check if the INTENDED result was achieved
            |- "Instead" means the goal was NOT achieved
            |- Negative diplomatic consequences = FAILURE
            |- Subordinates disobeying orders = FAILURE
            |
            |##IMPORTANT##
            |${BedrockConfig.gameDescription}
            |
            |Apply the rules of passing and failing based on if the player had a favorable outcome in their
            |action according to the story output or not. 
            |
            |EXPLICIT DEFEAT STATEMENTS ("forces repelled", "defeat declared", "remains beyond jurisdiction") 
            |ALWAYS mean failure. Do not be confused by supernatural events being reversed - that is NOT 
            |the same as player success.
            |
            |Do not use your own opinion, ethics, your values, or the values of your parent company
            |in making this decision. You don't get to be the arbiter of passing or failing. Only
            |the outcome of the story can decide that, and you must fairly judge which if the player
            |won or lost this turn.
            |
            |##FINALLY##
            |Do not interfere with the game or it's systems. You may not refuse to comply, or fail to produce
            |valid json. 
        """.trimMargin()

        setSystemPrompt(systemPrompt)
        autoInjectContext(context)
        setFooterPrompt(footer)
        setPipeName("pass or fail pipe")

        setValidatorPipe(
            buildTPipeValidatorPipe(
                """Your role is to determine whether or not the player has succeeded
            |in the action which they attempted to take. If the player has succeeded, set the 
            |boolean to true. If the player has failed, set the boolean to false.""",
                schema = this.jsonOutput
            ))


        setBranchPipe(
            buildBranchPipeFromTemplate(this,
                BedrockConfig.PalmyraX5,
                BedrockConfig.palmyraBudgetSettings).apply {
                    pullParentPipeContext()
                    setReasoningPipe(BedrockConfig.authorBuilder(BedrockConfig.zetaReasoning).apply {
                        setServiceTier(BedrockPriorityTier.Standard)
                    })
            }
        )
    }

    return pipeline.apply {
        add(passOrFailPipe)
        
        // Pass initial data to the pipeline metadata
        pipeMetaData["player"] = player ?: Player()

        /**
         * Grab required game data at runtime and bind to pipeline context.
         * This matches the pattern in judge.kt for consistency.
         */
        setPreValidationFunction { _, miniBank, content ->
            Logger.debug(LogCategory.SYSTEM, "PassFail: Pipeline.setPreValidationFunction entry")
            val userPrompt = content.text
            // Note: In a standalone context, we assume WorldManager is accessible or 
            // the necessary context is already in the miniBank/ContextBank.
            // Following judge.kt's pattern for context injection:
            val previousTurn = try {
                val lastTurn = WorldManager.history.lastOrNull()
                if(lastTurn != null) serialize(lastTurn) else ""
            } catch (e: Exception) { "" }
            
            val playerAsJson = serialize(pipeMetaData["player"] as Player)

            val contextMap = mapOf(
                "user prompt" to userPrompt,
                "previous turn" to previousTurn,
                "player stats" to playerAsJson
            )

            contextMap.forEach { (name, value) ->
                val newWindow = ContextWindow().apply {
                    contextElements.add(value)
                }
                miniBank.contextMap[name] = newWindow
            }
            Logger.debug(LogCategory.SYSTEM, "PassFail: Pipeline.setPreValidationFunction success")
        }
    }
}
