package accounting

import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcDirection
import org.ttt.autogenesis.network.ResolutionStep
import org.ttt.autogenesis.network.RpcMethod
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import agent.runners.executeNpcTurn
import structs.Npc
import enums.NpcType

import structs.AgentUsage
import structs.PromptType
import structs.Player
import gameState.WorldManager
import agent.builders.systemActions.UserActionClassification
import agent.builders.systemActions.ActionType
import agent.builders.systemActions.createUserActionClassificationPipeline
import agent.builders.systemActions.buildAnswerAgent
import agent.builders.systemActions.buildOpenWidgetPipeline
import agent.builders.systemActions.OpenUiDecision
import agent.builders.systemActions.OpenWidgetPipeline
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipeline.Pipeline
import com.TTT.Util.extractJson
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceFormat
import org.ttt.autogenesis.network.AgentStreamPhase
import org.ttt.autogenesis.network.OpenWidgetType
import org.ttt.autogenesis.network.CommandClassificationData
import org.ttt.autogenesis.server.UiSignalRpcHandlers
import org.ttt.autogenesis.server.TurnHarness
import org.ttt.autogenesis.server.streamPipelineOutputToAgentWorkBuffer
import agent.runners.getTurnTraceDir
import com.TTT.Config.TPipeConfig
import java.io.File
import java.util.UUID

import agent.builders.systemActions.buildChatAgent
import agent.prompts.Prompts

interface ActionClassifier
{
    suspend fun classify(connectionId: String, prompt: String): UserActionClassification?
}

/**
 * Manager class to account for and limit players spamming prompts to the system. Handles blocking
 * play prompts during an opponents turn, and limits prompts sent to one aux prompt at a time.
 */
object PromptManager
{
    private const val OPEN_AGENT_CONFIDENCE_THRESHOLD = 0.65

    private val systemCharacterPrompts = mapOf(
        "Zuzusarogorata Suguruzands" to "zzs",
        "Haematemesis Coprophobia" to "hc",
        "N'zelquin G'zeeloth" to "nzg",
        "Bigwang McDouchebag" to "bmd",
        "Invis von Disappearo" to "ivd",
        "Big Googar" to "bg",
        "Narjodo Bazingazooka" to "njb",
        "Shitty Bob" to "bob",
        "Officer Dave" to "dave",
        "Robert the Destroyer" to "robert",
        "Quag LoBogon" to "qlb",
        "Zeta Step Reasoner" to "zsr",
        "Narjan Goren" to "njg",
        "Gl’kr’kr’kr’k Shshshsh-shsh-‘’’’////" to "gksh",
        "Nordold Trable" to "ndt"
    )

    //Tracks which prompts are running for which connection id's
    val promptUsage = mutableMapOf<String, AgentUsage>()

    // Dependencies for testing
    var turnRunner: suspend (org.ttt.autogenesis.network.RpcCallContext, Player, String) -> Unit = TurnHarness.registerPlayCallback()
    
    // Default implementation using actual pipeline
    var classifier: ActionClassifier = object : ActionClassifier
    {
        override suspend fun classify(connectionId: String, prompt: String): UserActionClassification?
        {
            Logger.info(LogCategory.SYSTEM, "PromptManager: Starting classification for prompt: ${prompt.take(50)}...")
            val pipe = createUserActionClassificationPipeline().apply {
                streamPipelineOutputToAgentWorkBuffer(connectionId, this)
                enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
                init(true)
            }
            
            try {
                val result = pipe.execute(MultimodalContent(prompt))
                Logger.info(LogCategory.GENERAL, "PromptManager Classifier Raw Output: ${result.text}")
                saveAgentTrace("PromptClassification", connectionId, pipe)
                return extractJson<UserActionClassification>(result.text)
            } catch (e: Exception) {
                Logger.error(LogCategory.SYSTEM, "PromptManager Classifier CRASHED: $${e.message}")
                e.printStackTrace()
                throw e
            }
        }
    }

    private fun saveAgentTrace(subFolder: String, connectionId: String, pipeline: Pipeline, traceContent: String? = null)
    {
        try
        {
            val normalizedId = if(connectionId.isBlank()) "unknown" else connectionId
            val dir = File(File(File(getTurnTraceDir()), subFolder), normalizedId)
            if(!dir.exists())
            {
                dir.mkdirs()
            }
            val baseFileName = "${pipeline.pipelineName.replace(" ", "_")}_${System.currentTimeMillis()}"
            
            // Save JSON version
            val jsonTrace = pipeline.getTraceReport(TraceFormat.JSON)
            com.TTT.Util.writeStringToFile("${dir.absolutePath}/$baseFileName.json", jsonTrace)
            
            // Save HTML version
            val htmlTrace = traceContent ?: pipeline.getTraceReport(TraceFormat.HTML)
            com.TTT.Util.writeStringToFile("${dir.absolutePath}/$baseFileName.html", htmlTrace)
            
            Logger.debug(LogCategory.SYSTEM, "Saved agent traces (JSON/HTML) to ${dir.absolutePath}/$baseFileName.*")
        }
        catch(e: Exception)
        {
            Logger.error(LogCategory.GENERAL, "Failed to save trace for $subFolder (conn=$connectionId): $${e.message}")
        }
    }

    // Flag to enable demo mode for testing without full auth
    var isDemoMode = false

    // Flag to track if demo world has been initialized
    private var demoWorldInitialized = false

    // Guard to ensure we only fire the demo Shepard turn once per session
    private var demoTurnHarnessTriggered = false

    @RpcMethod("server.sendPrompt" ,RpcDirection.SERVER)
    suspend fun sendPromptToServer(context: RpcCallContext, prompt: String)
    {
        val connectionId = context.connectionId
        Logger.info(LogCategory.NETWORK, "PromptManager: sendPromptToServer invoked (connection=$connectionId, promptLength=${prompt.length}).")
        
        // Ensure we have a world if we are in demo mode
        if(isDemoMode)
        {
            val wasInitialized = demoWorldInitialized
            Logger.debug(LogCategory.GENERAL, "PromptManager: Demo mode active, ensuring demo world for connection=$connectionId.")
            ensureDemoWorld()
            mapDemoPlayer(connectionId)
            
            // If we just initialized or re-mapped, force a full UI sync
            if(!wasInitialized || WorldManager.playerStats.none { it.playerID == connectionId })
            {
                Logger.info(LogCategory.NETWORK, "Demo Mode: Forcing full UI sync for connection $connectionId")
                val shepard = WorldManager.world.activePlayers.find { it.name == "Commander Shepard" }
                if(shepard != null)
                {
                    UiSignalRpcHandlers.broadcastWorldUpdate(WorldManager.world)
                    UiSignalRpcHandlers.sendSetLocalPlayer(connectionId, shepard)
                    Logger.debug(LogCategory.NETWORK, "PromptManager: Demo UI sync sent for connection=$connectionId.")
                }
            }
        }

        var playerStats = WorldManager.findPlayerStatsByConnectionId(connectionId)

        if(playerStats == null)
        {
            if(isDemoMode)
            {
                 // Demo Mode: Fallback logic to ensure a player exists
                if(WorldManager.playerStats.isEmpty())
                {
                     val dummyPlayer = structs.Player(name = "DemoPlayer")
                     val dummyStats = serverStructs.PlayerStats(dummyPlayer, connectionId)
                     WorldManager.playerStats.add(dummyStats)
                     playerStats = dummyStats
                     Logger.warn(LogCategory.NETWORK, "Demo Mode: Created dummy DemoPlayer for connection $connectionId")
                }
                else
                {
                     playerStats = WorldManager.playerStats.first()
                     Logger.warn(LogCategory.NETWORK, "Demo Mode: Bypassing auth for connection $connectionId using ${playerStats.playerData.name}")
                }
            }
            else
            {
                Logger.warn(LogCategory.NETWORK, "Received prompt from unknown connection: $connectionId")
                return
            }
        }

        val player = playerStats.playerData

        // Check against global lock or player turn status? 
        // For now, we only enforce that the specific agent type isn't already running.

        val (targetTabId, cleanedPrompt) = parseTabId(prompt)

        if(cleanedPrompt.startsWith("/"))
        {
            handleSlashCommand(context, connectionId, player, cleanedPrompt, targetTabId)
        }
        else
        {
            handleNaturalLanguagePrompt(context, connectionId, player, cleanedPrompt, targetTabId)
        }
    }

    private fun parseTabId(prompt: String): Pair<String, String>
    {
        val regex = Regex("^\\[(.*?)\\] (.*)")
        val match = regex.find(prompt)
        return if(match != null)
        {
            val tabId = match.groupValues[1]
            val rest = match.groupValues[2]
            Pair(tabId, rest)
        }
        else
        {
            Pair("Commander", prompt) // Default to "Commander" tab which exists on client
        }
    }

    /**
     * Generates a fake world state if one does not exist, for testing purposes.
     */
    private fun ensureDemoWorld()
    {
        // Check if already initialized
        if(demoWorldInitialized)
        {
            Logger.info(LogCategory.GENERAL, "Demo Mode: World already initialized, skipping")
            return
        }
        
        // Check if we already have the CORRECT demo data
        val hasShepard = WorldManager.world.activePlayers.any { it.name.contains("Shepard", ignoreCase = true) }
        if(WorldManager.world.activePlayers.isNotEmpty() && hasShepard)
        {
            demoWorldInitialized = true
            Logger.info(LogCategory.GENERAL, "Demo Mode: Detected existing Shepard, marking as initialized")
            return
        }

        // If we have players but not Shepard, it's likely the default "Player 1". Clear it.
        if(WorldManager.world.activePlayers.isNotEmpty())
        {
            Logger.info(LogCategory.GENERAL, "Demo Mode: Detected non-demo data (e.g. Player 1). clearing world to enforce Demo Scenario.")
            WorldManager.world.activePlayers.clear()
            WorldManager.world.mapTiles.clear()
            WorldManager.history.clear()
        }

        Logger.info(LogCategory.GENERAL, "Demo Mode: Generating fake world data...")
        
        // 1. Create Players
        val player = structs.Player(
            name = "Commander Shepard",
            description = "A veteran commander trying to save the galaxy... again.",
            militaryPoints = 80,
            diplomacyPoints = 60,
            researchPoints = 75,
            summitPoints = 50,
            luckPoints = 50,
            reputation = 60,
            might = 50,
            wealth = 50,
            victoryPoints = 12
        )
        Logger.info(LogCategory.GENERAL, "Demo Mode: Created Commander Shepard [hashCode=${player.hashCode()}, diplomacyPoints=${player.diplomacyPoints}]")
        
        val opponent = structs.Player(
            name = "The Illusive Man",
            description = "Leader of Cerberus, playing a dangerous game.",
            militaryPoints = 95,
            diplomacyPoints = 50,
            researchPoints = 80,
            summitPoints = 50,
            luckPoints = 50,
            reputation = 50,
            might = 70,
            wealth = 90,
            victoryPoints = 15
        )
        
        WorldManager.world.activePlayers.add(player)
        WorldManager.world.activePlayers.add(opponent)
        Logger.info(LogCategory.GENERAL, "Demo Mode: Added Shepard to activePlayers [size=${WorldManager.world.activePlayers.size}]")
        
        // 2. Create Territories
        val earth = structs.Territory(
            name = "Earth",
            description = "The homeworld of humanity.",
            ruler = player.name,
            isCaptured = true,
            pointValue = 10
        )
        earth.militaryThreatStat = 75
        earth.diplomacyThreatStat = 40
        player.capturedTerritory.add(earth)
        
        val citadel = structs.Territory(
            name = "The Citadel",
            description = "A massive space station and seat of the Council.",
            ruler = opponent.name,
            isCaptured = true,
            pointValue = 15
        )
        citadel.militaryThreatStat = 40
        citadel.diplomacyThreatStat = 95
        opponent.capturedTerritory.add(citadel)
        
        val omega = structs.Territory(
            name = "Omega",
            description = "Lawless station in the Terminus Systems.",
            ruler = "", // Neutral
            isCaptured = false,
            pointValue = 5
        )
        omega.militaryThreatStat = 60
        omega.diplomacyThreatStat = 20
        
        WorldManager.world.mapTiles.add(earth)
        WorldManager.world.mapTiles.add(citadel)
        WorldManager.world.mapTiles.add(omega)
        
        // 3. Fake History
        WorldManager.history.add(structs.GameHistory(
            turnPlayer = opponent.name,
            turnAction = "Deployed fleets to the Terminus Systems.",
            turnResult = "Success. Influence expanded.",
            wasPlayerSuccessful = true
        ))
        
        WorldManager.history.add(structs.GameHistory(
            turnPlayer = player.name,
            turnAction = "Diplomatic mission to Tuchanka.",
            turnResult = "Mixed success. Krogan support secured but at a cost.",
            wasPlayerSuccessful = true
        ))

        WorldManager.activePlayerCount = 2
        
        // 4. Map Players to Connection IDs for Demo Mode
        // This ensures the current connection is bound to 'Commander Shepard'
        WorldManager.playerStats.clear()
        val shepardStats = serverStructs.PlayerStats(player, "DEMO_CONNECTION_ID") // Placeholder, real id mapped below
        val illusiveStats = serverStructs.PlayerStats(opponent, "AI_CONNECTION_ID").apply {
            isConnected = false
            isControlledByNpc = true
        }
        
        WorldManager.playerStats.add(shepardStats)
        WorldManager.playerStats.add(illusiveStats)
        
        Logger.info(LogCategory.GENERAL, "Demo Mode: World generation complete.")

        // 5. Start the turn timer for the demo player
        GlobalScope.launch()
        {
            WorldManager.startTurnTimer(player.name, WorldManager.TURN_DURATION_SECONDS)
        }
        
        demoWorldInitialized = true
        Logger.info(LogCategory.GENERAL, "Demo Mode: World initialization complete")
    }

    /**
     * Resets the demo world initialization flag and clears world state.
     * Useful for testing or when demo world needs to be recreated.
     */
    private suspend fun resetDemoWorld()
    {
        demoWorldInitialized = false
        demoTurnHarnessTriggered = false
        WorldManager.world.activePlayers.clear()
        WorldManager.world.mapTiles.clear()
        WorldManager.history.clear()
        WorldManager.playerStats.clear()
        
        // Reset turn harness state (including round counters and turn order)
        org.ttt.autogenesis.server.TurnHarness.resetState()
        
        Logger.info(LogCategory.GENERAL, "Demo Mode: World reset complete")
    }

    /**
     * Maps the current connection to the designated demo player.
     */
    private fun mapDemoPlayer(connectionId: String)
    {
        Logger.debug(LogCategory.NETWORK, "PromptManager: Mapping demo player for connection $connectionId.")
        val player = WorldManager.world.activePlayers.find { it.name == "Commander Shepard" } ?: return
        Logger.info(LogCategory.GENERAL, "Demo Mode: Found Shepard in activePlayers [hashCode=${player.hashCode()}, diplomacyPoints=${player.diplomacyPoints}]")
        
        // Check if mapping already exists with correct player reference
        val existing = WorldManager.playerStats.find { it.playerID == connectionId }
        if(existing != null && existing.playerData === player)
        {
            Logger.info(LogCategory.NETWORK, "Demo Mode: Connection $connectionId already mapped to Shepard")
            return
        }
        
        // Remove old mapping if exists
        WorldManager.playerStats.removeIf { it.playerID == connectionId }
        
        // Add new mapping
        val stats = serverStructs.PlayerStats(playerData = player, playerID = connectionId)
        WorldManager.playerStats.add(stats)
        Logger.info(LogCategory.NETWORK, "Demo Mode: Mapped $connectionId to ${player.name} [hashCode=${player.hashCode()}]")
    }

    /**
     * Handles the fixed slash commands entered through the UI command box.
     *
     * `/ask` triggers [executeAnswerAgent], `/npc` executes NPC helpers, and other commands either open
     * auxiliary UIs or log that the command is unsupported. Each branch logs the intent, guards against
     * re-entrancy, and delegates to the appropriate agent or notification helper so downstream tracing
     * can pick up the request.
     *
     * @param context RPC context used for logging/auditing.
     * @param connectionId Player connection ID (also passed into the answer agent for streaming/tracing).
     * @param player Resolved player data for the caller.
     * @param prompt The slash command string (starts with '/').
     * @param targetTabId Optional NeuralLink tab target extracted by [parseTabId].
     */
    private suspend fun handleSlashCommand(context: RpcCallContext, connectionId: String, player: Player, prompt: String, targetTabId: String)
    {
        val parts = prompt.split(" ", limit = 2)
        val command = parts[0].lowercase()
        val args = if(parts.size > 1) parts[1] else ""

        val isGameplayCommand = command == "/play" && args.isNotBlank()
        notifyCommandClassification(connectionId, prompt, targetTabId, isGameplayCommand, command)

        when(command)
        {
            "/play" ->
            {
                if(isDemoMode && args.isBlank() && !demoTurnHarnessTriggered)
                {
                    Logger.info(LogCategory.GENERAL, "PromptManager: Demo /play with no args triggered. Starting Shepard turn harness.")
                    triggerDemoShepardTurn(context, connectionId, player)
                    return
                }

                executeGameplayAction(context, player, args)
            }
            "/neural" ->
            {
                // Mock streaming response for testing Neural Link
                Logger.info(LogCategory.GENERAL, "Initiating Neural Link test stream for $connectionId")
                GlobalScope.launch {
                    val tabId = "Commander" // Default test tab
                    val testMessage = "Neural Link Established. System online.\\nData stream active...\\nAnalyzing sector parameters..."
                    
                    testMessage.split("\\n").forEach { line ->
                        UiSignalRpcHandlers.broadcastAgentStream(connectionId, tabId, line + "\n", false)
                        kotlinx.coroutines.delay(500)
                    }
                    UiSignalRpcHandlers.broadcastAgentStream(connectionId, tabId, "[END TRANSMISSION]", true)
                }
            }
            "/ask" ->
            {
                Logger.info(LogCategory.GENERAL, "PromptManager: /ask command received for $connectionId, routing to Answer Agent.")
                executeAnswerAgent(connectionId, args, targetTabId)
            }
            "/chat" ->
            {
                Logger.info(LogCategory.GENERAL, "PromptManager: /chat command received for $connectionId, routing to Chat Agent.")
                executeChatAgent(connectionId, args, targetTabId)
            }
            "/open" ->
            {
                Logger.info(LogCategory.GENERAL, "PromptManager: /open command received for $connectionId, routing to Open Agent.")
                executeOpenAgent(connectionId, args, targetTabId)
            }
            "/npc" ->
            {
                executeNpcAction(connectionId, args)
            }
            "/resetdemo" ->
            {
                if(isDemoMode)
                {
                    resetDemoWorld()
                    ensureDemoWorld()
                    mapDemoPlayer(connectionId)
                    UiSignalRpcHandlers.broadcastShowMessageBox(connectionId, "Demo Reset", "Demo world has been reset", showOk = true)
                }
                else
                {
                    UiSignalRpcHandlers.broadcastShowMessageBox(connectionId, "Error", "Not in demo mode", showOk = true)
                }
            }
            else ->
            {
                // Unknown command, perhaps log or notify user
                Logger.info(LogCategory.GENERAL, "Unknown slash command: $command")
            }
        }
    }

    /**
     * Prepares the demo Shepard turn so the UI can show the START page before the player submits actual text.
     */
    private suspend fun triggerDemoShepardTurn(context: RpcCallContext, connectionId: String, player: Player)
    {
        if(!isDemoMode || demoTurnHarnessTriggered)
        {
            return
        }

        ensureDemoWorld()
        mapDemoPlayer(connectionId)

        val resolvedShepard = WorldManager.worldMutex.withLock {
            val world = WorldManager.world
            val index = world.activePlayers.indexOfFirst { it.name.equals("Commander Shepard", ignoreCase = true) }
            val shepard = if(index >= 0) world.activePlayers.removeAt(index) else world.activePlayers.firstOrNull()
            if(shepard == null)
            {
                return@withLock null
            }
            world.activePlayers.removeIf { it.name.equals(shepard.name, ignoreCase = true) }
            world.activePlayers.add(0, shepard)
            world.turnOrder.clear()
            world.turnOrder.addAll(world.activePlayers.map { it.name })
            world.activeTurnActor = shepard.name
            WorldManager.activeTurnActor = shepard.name
            shepard.name
        }

        if(resolvedShepard.isNullOrBlank())
        {
            Logger.warn(LogCategory.GENERAL, "Demo Trigger: Unable to find Commander Shepard in activePlayers.")
            return
        }

        demoTurnHarnessTriggered = true
        Logger.info(LogCategory.GENERAL, "Demo Trigger: Starting turn harness for Commander Shepard (connection=$connectionId).")
        TurnHarness.runNextTurn()
    }

    /**
     * Broadcasts the [CommandClassificationData] result for the current connection.
     *
     * Called from both slash-command handlers and the NL classifier so the UI can suppress
     * the turn-resolution widget unless the prompt actually represents a gameplay turn.
     */
    private suspend fun notifyCommandClassification(connectionId: String, prompt: String, tabId: String, isGameplay: Boolean, hint: String? = null)
    {
        UiSignalRpcHandlers.sendCommandClassification(
            connectionId,
            CommandClassificationData(
                connectionId = connectionId,
                tabId = tabId,
                prompt = prompt,
                isGameplay = isGameplay,
                hint = hint
            )
        )
    }

    /**
     * Classifies free-form player prompts and routes them to gameplay, question, or UI flows.
     *
     * The UI is notified about whether the prompt maps to gameplay before gameplay UI transitions fire.
     */
    private suspend fun handleNaturalLanguagePrompt(context: RpcCallContext, connectionId: String, player: Player, prompt: String, targetTabId: String)
    {
        // Use Action Detection Agent
        Logger.info(LogCategory.GENERAL, "Classifying natural language prompt for ${player.name}...")
        
        // We notify client we are "thinking" / classifying
        // For now, let's map this to an "OPEN" status or similar if needed, implies system overhead.
        
        // Ensure we have a world if we are in demo mode (just in case NL prompt hits first)
        if(isDemoMode)
        {
             ensureDemoWorld()
             mapDemoPlayer(connectionId)
        }
        
        GlobalScope.launch {
            var wasPaused = false
            try
            {
                Logger.info(LogCategory.SYSTEM, "NL Prompt Pipeline: Phase 1 - Classification...")
                setAgentRunState(connectionId, PromptType.CLASSIFYING, true)

                val isPlayerTurn = WorldManager.activeTurnActor.isNotBlank() && WorldManager.activeTurnActor.equals(player.name, ignoreCase = true)
                if(isPlayerTurn && WorldManager.gameTimer.isRunning)
                {
                    Logger.info(LogCategory.GENERAL, "PromptManager: Pausing turn timer for ${player.name} during classification.")
                    WorldManager.pauseTurnTimer()
                    wasPaused = true
                }

                val classification = classifier.classify(connectionId, prompt)

                if(wasPaused)
                {
                    Logger.info(LogCategory.GENERAL, "PromptManager: Resuming turn timer for ${player.name} after classification.")
                    WorldManager.resumeTurnTimer()
                    wasPaused = false
                }

                setAgentRunState(connectionId, PromptType.CLASSIFYING, false)

                val actionType = classification?.actionType
                val isGameplayCommand = actionType == ActionType.GAMEPLAY
                notifyCommandClassification(connectionId, prompt, targetTabId, isGameplayCommand, actionType?.name)

                if(actionType != null)
                {
                    Logger.info(LogCategory.GENERAL, "Classified action as: $actionType (Confidence: ${classification?.confidence})")
                    Logger.info(LogCategory.SYSTEM, "NL Prompt Pipeline: Phase 2 - Routing to $actionType...")
                    
                    when(actionType)
                    {
                        ActionType.GAMEPLAY ->
                        {
                            executeGameplayAction(context, player, prompt)
                        }
                        ActionType.QUESTION ->
                        {
                            Logger.info(LogCategory.GENERAL, "Question detected via NL. Auto-executing Answer Agent.")
                            executeAnswerAgent(connectionId, prompt, targetTabId)
                        }
                        ActionType.UI_COMMAND ->
                        {
                            Logger.info(LogCategory.GENERAL, "UI Command detected: $prompt")
                            executeOpenAgent(connectionId, prompt, targetTabId)
                        }
                        ActionType.CHAT ->
                        {
                            Logger.info(LogCategory.GENERAL, "Chat intent detected via NL. Routing to Chat Agent.")
                            executeChatAgent(connectionId, prompt, targetTabId)
                        }
                    }
                }
                else
                {
                    Logger.warn(LogCategory.GENERAL, "Failed to classify prompt.")
                    Logger.warn(LogCategory.SYSTEM, "NL Prompt Pipeline: Terminated early due to null classification.")
                }
            }
            catch(e: Exception)
            {
                Logger.error(LogCategory.GENERAL, "Error in Action Classification: $${e.message}")
                Logger.error(LogCategory.SYSTEM, "NL Prompt Pipeline: CRASHED during classification/routing.")
                notifyCommandClassification(connectionId, prompt, targetTabId, false, "classification-error")
                e.printStackTrace()
            }
            finally {
                if(wasPaused)
                {
                    Logger.info(LogCategory.GENERAL, "PromptManager: Resuming turn timer for ${player.name} after classification exception.")
                    WorldManager.resumeTurnTimer()
                }
            }
        }
    }

    private suspend fun executeChatAgent(connectionId: String, prompt: String, targetTabId: String)
    {
        val usage = promptUsage.getOrPut(connectionId) { AgentUsage() }
        if(usage.runningChatAgent)
        {
            Logger.warn(LogCategory.GENERAL, "Chat Agent already running for $connectionId")
            Logger.warn(LogCategory.SYSTEM, "PromptManager: REJECTING Chat Agent for $connectionId - Agent already busy.")
            return
        }

        // Improved parsing to identify the target character from the prompt
        var npcName = ""
        var message = ""

        // 1. Check if targetTabId is already an NPC name (from NeuralLinkWindow)
        val worldNpcNames = WorldManager.world.npc.map { it.name }
        val worldPlayerNames = WorldManager.world.activePlayers.map { it.name }
        val systemNames = systemCharacterPrompts.keys
        val knownNames = (worldNpcNames + worldPlayerNames + systemNames).sortedByDescending { it.length }

        if(knownNames.any { it.equals(targetTabId, ignoreCase = true) })
        {
            npcName = targetTabId
            message = prompt
        }
        else
        {
            // 2. Parse from prompt (e.g. "/chat @King Candy Hello" or "Talk to @King Candy")
            val mentionRegex = Regex("@([\\w\\s'’/\\-]+)")
            val match = mentionRegex.find(prompt)
            if(match != null)
            {
                val candidateName = match.groupValues[1].trim()
                val matchedName = knownNames.find { it.equals(candidateName, ignoreCase = true) || candidateName.startsWith(it, ignoreCase = true) }
                if(matchedName != null)
                {
                    npcName = matchedName
                    message = prompt.replace(match.value, "").trim()
                }
            }
            
            // 3. Greedy fallback if no @ mention (check prompt start)
            if(npcName.isEmpty())
            {
                val matchedName = knownNames.find { prompt.startsWith(it, ignoreCase = true) }
                if(matchedName != null)
                {
                    npcName = matchedName
                    message = prompt.substring(matchedName.length).trim()
                }
            }
        }

        if(npcName.isEmpty())
        {
            Logger.warn(LogCategory.GENERAL, "Chat Agent: Could not identify target character in prompt: '$prompt'")
            UiSignalRpcHandlers.broadcastShowMessageBox(connectionId, "Chat Error", "I couldn't figure out who you want to talk to. Try using @CharacterName.", showOk = true)
            return
        }

        val npc = WorldManager.world.npc.find { it.name.equals(npcName, ignoreCase = true) }
            ?: WorldManager.world.activePlayers.find { it.name.equals(npcName, ignoreCase = true) }?.let {
                // Convert Player to Npc-like structure for the chat agent
                Npc(name = it.name, description = it.description, personality = it.history)
            }
            ?: systemCharacterPrompts.entries.find { it.key.equals(npcName, ignoreCase = true) }?.let { entry ->
                val promptText = Prompts.promptMap[entry.value] ?: ""
                Npc(name = entry.key, description = promptText, personality = "As defined in system prompt.")
            }

        if(npc == null)
        {
            Logger.warn(LogCategory.GENERAL, "Chat Agent: Character '$npcName' not found in world or system prompts.")
            return
        }

        setAgentRunState(connectionId, PromptType.CHAT, true)

        // Modern thread-safe way to handle chat history
        val chatContextKey = "chat-$connectionId-${npc.name}"
        // Initialize history if it doesn't exist
        val chatContextWindow = com.TTT.Context.ContextBank.getContextFromBankSuspend(chatContextKey)
        com.TTT.Context.ContextBank.emplaceSuspend(chatContextKey, chatContextWindow, com.TTT.Context.StorageMode.DISK_ONLY)

        GlobalScope.launch {
            try
            {
                val streamId = UUID.randomUUID().toString()
                Logger.info(LogCategory.SYSTEM, "Chat Agent Pipeline: Starting for $connectionId with ${npc.name} (streamId=$streamId)...")
                
                UiSignalRpcHandlers.sendAgentStreamEvent(
                    connectionId = connectionId,
                    tabId = npc.name, // Use NPC name as tabId for separate chatbox
                    phase = AgentStreamPhase.START,
                    streamId = streamId,
                    commandContext = "/chat @${npc.name}"
                )

                org.ttt.autogenesis.server.AgentWorkStreamDispatcher.subscribe(connectionId)
                val pipe = buildChatAgent(npc, connectionId, targetTabId = npc.name, streamId = streamId)
                pipe.enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
                pipe.enablePipeTimeout(
                    applyRecursively = true,
                    duration = 180000,
                    autoRetry = true,
                    retryLimit = 5
                )
                pipe.init(true)

                pipe.execute(MultimodalContent(message.ifBlank { "Hello!" }))
                
                Logger.info(LogCategory.SYSTEM, "Chat Agent Pipeline: Finished for $connectionId with ${npc.name}.")
                val trace = pipe.getTraceReport(TraceFormat.HTML)
                saveAgentTrace("ChatAgent", connectionId, pipe, trace)
                // Phase 4 of feature/live-pvp-and-billing: flush the chat agent usage to the
                // persistent ledger so the operator dashboard reflects it.
                val chatRecord = accounting.Billing.recordAuxAgentBilling(connectionId, "ChatAgent")
                if (chatRecord != null)
                {
                    try
                    {
                        accounting.BillingSync.flushTurnUsage(listOf(chatRecord))
                    }
                    catch (err: Throwable)
                    {
                        Logger.warn(
                            LogCategory.SYSTEM,
                            "PromptManager: failed to flush ChatAgent usage for conn=$connectionId: ${err.message}"
                        )
                    }
                }
            }
            catch(e: Exception)
            {
                Logger.error(LogCategory.GENERAL, "Error in Chat Agent: $${e.message}")
                e.printStackTrace()
            }
            finally
            {
                setAgentRunState(connectionId, PromptType.CHAT, false)
            }
        }
    }

    private suspend fun executeOpenAgent(connectionId: String, prompt: String, targetTabId: String)
    {
        val usage = promptUsage.getOrPut(connectionId) { AgentUsage() }
        if(usage.runningOpenAgent)
        {
            Logger.warn(LogCategory.GENERAL, "Open Agent already running for $connectionId")
            Logger.warn(LogCategory.SYSTEM, "PromptManager: REJECTING Open Agent for $connectionId - Agent already busy.")
            return
        }

        if(prompt.isBlank())
        {
            showOpenUsage(connectionId)
            return
        }

        setAgentRunState(connectionId, PromptType.OPEN, true)

        GlobalScope.launch {
            try
            {
                val trimmed = prompt.trim()
                Logger.info(LogCategory.NETWORK, "Open Agent executing for $connectionId (tab=$targetTabId, prompt=${trimmed.take(120)})")
                Logger.info(LogCategory.SYSTEM, "Open Agent Pipeline: Phase 1 - Decision...")
                val openWidgetPipeline = buildOpenWidgetPipeline(connectionId, trimmed, targetTabId)
                val decisionText = runDecisionPipeline(connectionId, trimmed, openWidgetPipeline.decisionPipeline)
                val decision = extractJson<OpenUiDecision>(decisionText)
                Logger.info(LogCategory.NETWORK, "Open Agent PCP output: actionable=${decision?.isActionable}, widget=${decision?.widget}, confidence=${decision?.confidence}")
                Logger.info(LogCategory.SYSTEM, "Open Agent Pipeline: Phase 2 - Handling Decision...")
                handleOpenDecision(connectionId, decision, openWidgetPipeline, trimmed)
                Logger.info(LogCategory.SYSTEM, "Open Agent Pipeline: Finished for $connectionId.")
            }
            catch(e: Exception)
            {
                Logger.error(LogCategory.GENERAL, "Open Agent error: $${e.message}")
                Logger.error(LogCategory.SYSTEM, "Open Agent Pipeline: CRASHED for $connectionId.")
                e.printStackTrace()
                showOpenFailure(connectionId, "Open Agent failed to process the request.")
            }
            finally
            {
                setAgentRunState(connectionId, PromptType.OPEN, false)
            }
        }
    }

    private suspend fun handleOpenDecision(connectionId: String, decision: OpenUiDecision?, pipeline: OpenWidgetPipeline, prompt: String)
    {
        if(decision == null)
        {
            showOpenFailure(connectionId, "Open Agent did not return a valid decision.")
            return
        }

        if(!decision.isActionable || decision.widget == null || decision.confidence < OPEN_AGENT_CONFIDENCE_THRESHOLD)
        {
            showOpenSuggestions(connectionId, decision.suggestions)
            return
        }

        executePcpPipeline(connectionId, decision, pipeline, prompt)
    }

    private suspend fun executePcpPipeline(connectionId: String, decision: OpenUiDecision, pipeline: OpenWidgetPipeline, prompt: String)
    {
        val pcpPipeline = pipeline.pcpPipeline.apply {
            streamPipelineOutputToAgentWorkBuffer(connectionId, this)
            enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
            enablePipeTimeout(
                applyRecursively = true,
                duration = 180000,
                autoRetry = true,
                retryLimit = 5
            )
            init(true)
        }
        Logger.debug(LogCategory.NETWORK, "Open Agent PCP pipeline streaming setup for connection=$connectionId")
        Logger.info(LogCategory.NETWORK, "Open Agent invoking PCP tool open_widget=${decision.widget} tab=${decision.tabId}")
        val result = pcpPipeline.execute(MultimodalContent(prompt))
        saveAgentTrace("OpenAgent", connectionId, pcpPipeline, pcpPipeline.getTraceReport(TraceFormat.HTML))
        val pcpResult = pipeline.pcpPipe.processPcpResponse(result.text)
        if(!pcpResult.success)
        {
            val errors = pcpResult.errors.joinToString("; ") { it }
            Logger.warn(LogCategory.NETWORK, "Open Agent PCP execution failed: $errors")
            Logger.debug(LogCategory.NETWORK, "Open Agent PCP response content: ${result.text.take(1000)}")
            showOpenFailure(connectionId, "Open tool execution failed: $errors")
        }
        else
        {
            Logger.info(LogCategory.NETWORK, "Open Agent PCP execution succeeded for ${decision.widget} (connection=$connectionId)")
        }
    }

    private suspend fun runDecisionPipeline(connectionId: String, prompt: String, pipeline: Pipeline): String
    {
        Logger.info(LogCategory.NETWORK, "Open Agent decision pipeline executing for connection=$connectionId (prompt=${prompt.take(120)})")
        pipeline.apply {
            enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
            enablePipeTimeout(
                applyRecursively = true,
                duration = 180000,
                autoRetry = true,
                retryLimit = 5
            )
            init(true)
        }
        val result = pipeline.execute(MultimodalContent(prompt))
        Logger.debug(LogCategory.NETWORK, "Open Agent decision output (connection=$connectionId): ${result.text.take(200)}")
        return result.text
    }

    private suspend fun showOpenUsage(connectionId: String)
    {
        val availableWidgets = OpenWidgetType.values().joinToString(", ") { it.name }
        val message = "Use `/open <widget>` (e.g., `/open NEURAL_LINK`) or describe the panel you want. Available widgets: $availableWidgets."
        UiSignalRpcHandlers.broadcastShowMessageBox(connectionId, "Open UI Help", message, showOk = true)
    }

    private suspend fun showOpenFailure(connectionId: String, reason: String)
    {
        Logger.warn(LogCategory.GENERAL, "PromptManager: Open command failed for $connectionId: $reason")
        UiSignalRpcHandlers.broadcastShowMessageBox(connectionId, "Open UI Failed", reason, showOk = true)
    }

    private suspend fun showOpenSuggestions(connectionId: String, suggestions: List<OpenWidgetType>)
    {
        val suggestionText = if(suggestions.isEmpty())
        {
            "Try `/open WORLD_STATS` or `/open NEURAL_LINK`."
        }
        else
        {
            suggestions.joinToString(", ") { it.name }
        }
        val message = "I was uncertain which widget to open. You could try asking for: $suggestionText"
        UiSignalRpcHandlers.broadcastShowMessageBox(connectionId, "Open UI Suggestions", message, showOk = true)
    }

    private suspend fun executeGameplayAction(context: org.ttt.autogenesis.network.RpcCallContext, player: Player, action: String)
    {
        val connectionId = context.connectionId
        Logger.info(LogCategory.GENERAL, "PromptManager: executeGameplayAction invoked (connection=$connectionId, player=${player.name}).")
        Logger.info(LogCategory.SYSTEM, "PromptManager: Ensuring TurnHarness is running...")
        TurnHarness.runNextTurn()
        
        // Enforce Turn Check
        if(!isDemoMode && WorldManager.activeTurnActor.isNotBlank() && WorldManager.activeTurnActor != player.name)
        {
            Logger.warn(LogCategory.GENERAL, "PromptManager: REJECTING play action for ${player.name}. It is NOT their turn. Active Actor: ${WorldManager.activeTurnActor}")
            Logger.info(LogCategory.NETWORK, "PromptManager: Sending 'Not Your Turn' MessageBox RPC to connection $connectionId")
            UiSignalRpcHandlers.broadcastShowMessageBox(
                connectionId, 
                "Not Your Turn", 
                "It is currently ${WorldManager.activeTurnActor}'s turn. Please wait for your turn to make a move.",
                showOk = true
            )
            return
        }

        val usage = promptUsage.getOrPut(connectionId) { AgentUsage() }
        
        if(usage.runningPlayAgent)
        {
             Logger.warn(LogCategory.GENERAL, "Player ${player.name} attempted play action while already running.")
             Logger.warn(LogCategory.SYSTEM, "PromptManager: REJECTING play action for ${player.name} - Agent already busy.")
             // Optional: Notify client of rejection?
             return
        }

        // Lock
        setAgentRunState(connectionId, PromptType.PLAY, true)

        Logger.debug(LogCategory.NETWORK, "PromptManager: Broadcasting resolution step for player ${player.name}.")
        UiSignalRpcHandlers.broadcastResolutionStep(ResolutionStep.PLAYER_ACTION, "Player ${player.name} submitted an action.")

        GlobalScope.launch {
            try
            {
                Logger.info(LogCategory.GENERAL, "PromptManager: Submitting action to turnRunner for player ${player.name}.")
                Logger.info(LogCategory.SYSTEM, "PromptManager: Calling turnRunner callback for ${player.name}...")
                turnRunner(context, player, action)
                Logger.info(LogCategory.SYSTEM, "PromptManager: turnRunner callback returned for ${player.name}.")
            }
            catch(e: Exception)
            {
                 Logger.error(LogCategory.GENERAL, "Error executing player turn: $${e.message}")
                 Logger.error(LogCategory.SYSTEM, "PromptManager: executeGameplayAction CRASHED during turnRunner execution.")
                 e.printStackTrace()
            }
            finally
            {
                // Unlock
                setAgentRunState(connectionId, PromptType.PLAY, false)
            }
        }
    }

    /**
     * Updates the running state of a specific agent for a player (by connection ID) and broadcasts the update.
     */
    suspend fun setAgentRunState(connectionId: String, type: PromptType, isRunning: Boolean)
    {
        val usage = promptUsage.getOrPut(connectionId) { AgentUsage() }
        var changed = false

        when(type)
        {
            PromptType.PLAY ->
            {
                if(usage.runningPlayAgent != isRunning)
                {
                    usage.runningPlayAgent = isRunning
                    changed = true
                }
            }
            PromptType.ANSWER ->
            {
                if(usage.runningAnswerAgent != isRunning)
                {
                    usage.runningAnswerAgent = isRunning
                    changed = true
                }
            }
            PromptType.CHAT ->
            {
                if(usage.runningChatAgent != isRunning)
                {
                    usage.runningChatAgent = isRunning
                    changed = true
                }
            }
            PromptType.OPEN ->
            {
                if(usage.runningOpenAgent != isRunning)
                {
                    usage.runningOpenAgent = isRunning
                    changed = true
                }
            }
            PromptType.CLASSIFYING ->
            {
                if(usage.runningClassifier != isRunning)
                {
                    usage.runningClassifier = isRunning
                    changed = true
                }
            }
            PromptType.NONE -> {}
        }

        if(changed)
        {
            Logger.info(LogCategory.NETWORK, "PromptManager: Agent run state changed for $connectionId: type=$type, isRunning=$isRunning. Broadcasting...")
            UiSignalRpcHandlers.broadcastPromptStatus(connectionId, usage)
        }
    }

    /**
     * Orchestrates the /ask answer agent lifecycle and logging route.
     *
     * Sends `ui.openNeuralLink` to the client, streams reasoning through
     * [streamPipelineOutputToAgentWorkBuffer], enables tracing, and persists the resulting HTML
     * so we can follow the full pipeline output from server to browser.
     *
     * @param connectionId Player connection used for streaming and trace files.
     * @param prompt Cleaned prompt text to feed into the answer agent.
     * @param targetTabId NeuralLink tab identifier that will receive streamed chunks.
     */
    private suspend fun executeAnswerAgent(connectionId: String, prompt: String, targetTabId: String)
    {
         val usage = promptUsage.getOrPut(connectionId) { AgentUsage() }
         if(usage.runningAnswerAgent)
         {
             Logger.warn(LogCategory.GENERAL, "Answer Agent is already running for $connectionId")
             Logger.warn(LogCategory.SYSTEM, "PromptManager: REJECTING Answer Agent for $connectionId - Agent already busy.")
             return
         }

         setAgentRunState(connectionId, PromptType.ANSWER, true)

         Logger.info(LogCategory.GENERAL, "PromptManager: invoking Answer Agent.")

         GlobalScope.launch {
             try
             {
                val streamId = UUID.randomUUID().toString()
                Logger.info(LogCategory.SYSTEM, "Answer Agent Pipeline: Starting for $connectionId (streamId=$streamId)...")
                
                UiSignalRpcHandlers.sendAgentStreamEvent(
                    connectionId = connectionId,
                    tabId = targetTabId,
                    phase = AgentStreamPhase.START,
                    streamId = streamId,
                    commandContext = "/ask"
                )

                org.ttt.autogenesis.server.AgentWorkStreamDispatcher.subscribe(connectionId)
                val pipe = buildAnswerAgent(connectionId, targetTabId, streamId)
                pipe.enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
                pipe.enablePipeTimeout(
                    applyRecursively = true,
                    duration = 180000,
                    autoRetry = true,
                    retryLimit = 5
                )
                Logger.info(LogCategory.GENERAL, "Initializing Answer Agent pipeline for $connectionId...")
                pipe.init(true)

                Logger.info(LogCategory.GENERAL, "Starting Answer Agent pipeline for $connectionId...")
                pipe.execute(MultimodalContent(prompt))
                Logger.info(LogCategory.GENERAL, "Answer Agent pipeline finished for $connectionId")
                Logger.info(LogCategory.SYSTEM, "Answer Agent Pipeline: Finished for $connectionId.")

                val trace = pipe.getTraceReport()
                saveAgentTrace("AnswerAgent", connectionId, pipe, trace)
                // Phase 4 of feature/live-pvp-and-billing: flush the answer agent usage to the
                // persistent ledger so the operator dashboard reflects it.
                val answerRecord = accounting.Billing.recordAuxAgentBilling(connectionId, "AnswerAgent")
                if (answerRecord != null)
                {
                    try
                    {
                        accounting.BillingSync.flushTurnUsage(listOf(answerRecord))
                    }
                    catch (err: Throwable)
                    {
                        Logger.warn(
                            LogCategory.SYSTEM,
                            "PromptManager: failed to flush AnswerAgent usage for conn=$connectionId: ${err.message}"
                        )
                    }
                }
                Logger.info(LogCategory.GENERAL, "TRACE REPORT SAVED for AnswerAgent pipeline.")
             }
             catch(e: Exception)
             {
                  Logger.error(LogCategory.GENERAL, "Error answering question: $${e.message}")
                  Logger.error(LogCategory.SYSTEM, "Answer Agent Pipeline: CRASHED for $connectionId.")
                  e.printStackTrace()
             }
             finally
             {
                 setAgentRunState(connectionId, PromptType.ANSWER, false)
             }
         }
    }

    private suspend fun executeNpcAction(connectionId: String, args: String)
    {
        // Improved parsing to handle "King Candy", "Captain Hook", etc.
        var npcName = ""
        var npcAction = ""

        // 1. Check for Quoted Name
        val quoteRegex = Regex("^[\"'](.*?)[\"']\\s+(.*)")
        val quoteMatch = quoteRegex.find(args)
        if(quoteMatch != null)
        {
            npcName = quoteMatch.groupValues[1]
            npcAction = quoteMatch.groupValues[2]
        }
        else
        {
            // 2. Check against Known NPCs (Greedy Match)
            // Sort by length descending to match "King Candy" before "King"
            val knownNames = WorldManager.world.npc.map { it.name }.sortedByDescending { it.length }
            val matchedName = knownNames.find { args.startsWith(it, ignoreCase = true) }
            
            if(matchedName != null)
            {
                npcName = matchedName
                npcAction = args.substring(matchedName.length).trim()
            }
            else
            {
                // 3. Check for Titles (Heuristic)
                val parts = args.split(" ", limit = 3)
                val firstWord = parts.firstOrNull() ?: ""
                val titles = setOf("King", "Queen", "Prince", "Princess", "Captain", "Cmdr", "Doctor", "Dr", "Lady", "Lord", "Darth", "Master")
                
                if(parts.size >= 3 && titles.any { firstWord.equals(it, ignoreCase = true) })
                {
                    npcName = "${parts[0]} ${parts[1]}"
                    npcAction = if(parts.size > 2) parts[2] else ""
                }
                else
                {
                    // 4. Default Fallback (First word is name)
                    val defaultParts = args.split(" ", limit = 2)
                    npcName = defaultParts[0]
                    npcAction = if(defaultParts.size > 1) defaultParts[1] else ""
                }
            }
        }

        var npc = WorldManager.world.npc.find { it.name.equals(npcName, ignoreCase = true) }
        if(npc == null)
        {
            Logger.info(LogCategory.GENERAL, "NPC $npcName not found. Creating temporary test NPC.")
            npc = Npc(name = npcName, description = "A test NPC created by debug command.", type = NpcType.Nemesis)
            WorldManager.world.npc.add(npc)
        }

        GlobalScope.launch {
            try
            {
                Logger.info(
                    LogCategory.GENERAL,
                    "PromptManager: Launching NPC turn for '$npcName' (originConnectionId=$connectionId, actionLength=${npcAction.length})"
                )
                Logger.info(LogCategory.SYSTEM, "PromptManager: Calling executeNpcTurn for $npcName...")
                executeNpcTurn(npc!!, npcAction, originConnectionId = connectionId)
                Logger.info(LogCategory.SYSTEM, "PromptManager: executeNpcTurn completed for $npcName.")
            }
            catch(e: Exception)
            {
                Logger.error(LogCategory.GENERAL, "Error executing NPC turn for $npcName: $${e.message}")
                Logger.error(LogCategory.SYSTEM, "PromptManager: executeNpcTurn CRASHED for $npcName.")
                e.printStackTrace()
            }
        }
    }

    /**
     * Initializes the turn timer expiration hooks.
     * Note: Does NOT auto-start the turn harness. Call TurnHarness.runNextTurn() explicitly when ready.
     */
    fun setupTimerHooks()
    {
        Logger.info(LogCategory.GENERAL, "PromptManager: Setting up turn timer expiration hooks")
        WorldManager.onTurnTimerExpired = { actorName ->
            Logger.info(LogCategory.GENERAL, "PromptManager: Turn Timer Expired for $actorName. Forwarding AI takeover to TurnHarness.")
            TurnHarness.handleAiTakeover(actorName)
        }
        Logger.debug(LogCategory.GENERAL, "PromptManager: Timer hooks configured. Turn harness will start when first player connects.")
    }

    private fun executeAiTakeover(player: Player)
    {
        Logger.info(LogCategory.GENERAL, "PromptManager: Executing AI Takeover for ${player.name}")
        
        // Find a connection ID for this player to simulate a turn
        val stats = WorldManager.playerStats.find { it.playerData.name.equals(player.name, ignoreCase = true) }
        val connectionId = stats?.playerID ?: "AI_TAKEOVER_SYSTEM"
        
        val context = RpcCallContext(connectionId) { /* Dummy sender */ }
        
        GlobalScope.launch {
            try
            {
                // We run a default "Maintenance/Defense" action
                val action = "Maintaining defensive posture and consolidating resources due to momentary command silence."
                agent.runners.executePlayerTurn(context, player, action)
            }
            catch(e: Exception)
            {
                Logger.error(LogCategory.GENERAL, "PromptManager: AI Takeover failed for ${player.name}: $${e.message}")
            }
        }
    }
}
