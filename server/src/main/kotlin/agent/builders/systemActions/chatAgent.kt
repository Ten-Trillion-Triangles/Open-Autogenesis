package agent.builders.systemActions

import Defaults.reasoning.ReasoningDepth
import Defaults.reasoning.ReasoningDuration
import bedrockPipe.BedrockMultimodalPipe
import bedrockPipe.BedrockPriorityTier
import com.TTT.Context.ContextBank
import com.TTT.Context.ContextWindow
import com.TTT.Context.ConverseRole
import com.TTT.Context.StorageMode
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipeline.Pipeline
import com.TTT.Util.serialize
import gameState.WorldManager
import globals.BedrockConfig
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.AgentStreamPhase
import org.ttt.autogenesis.server.AgentWorkStreamDispatcher
import kotlinx.serialization.Serializable
import structs.GameHistory
import structs.Player
import structs.Npc
import structs.Territory

/**
 * Chat agent that allows players to talk to specific NPCs.
 * Uses ContextBank to persist conversation history per connectionId and NPC name.
 */
fun buildChatAgent(npc: Npc, connectionId: String, targetTabId: String, streamId: String): Pipeline {

    // Key format: chat-[connectionId]-[npcName]
    val chatContextKey = "chat-$connectionId-${npc.name}"

    val chatPipe = BedrockMultimodalPipe().apply {
        useConverseApi()
        setRegion("us-west-2")

        // Using Qwen 2.5 35B for high-quality roleplay and reasoning
        setModel(BedrockConfig.qwen235B)
        setPipeName("chat pipe (${npc.name})")
        setTemperature(1.0) // Slightly higher for more creative/varied dialogue
        setTopP(.9)
        setTokenBudget(BedrockConfig.generativeBudgetSettings)
        
        // Structured reasoning to stay in character and handle game context
        setReasoningPipe(BedrockConfig.authorBuilder("${npc.name} ${npc.description} ${npc.personality} ${npc.abilities}").apply {
            setModel(BedrockConfig.qwen235B)
            setTokenBudget(BedrockConfig.generativeBudgetSettings)
        })


        val npcProfile = """
            |Name: ${npc.name}
            |Description: ${npc.description}
            |Personality: ${npc.personality}
            |Abilities: ${npc.abilities}
            |History: ${npc.history}
        """.trimMargin()

        setSystemPrompt("""You are ${npc.name}, a character in the game Autogenesis.
            |
            |## YOUR PROFILE ##
            |$npcProfile
            |
            |## INSTRUCTIONS ##
            |1. **STAY IN CHARACTER**: Always respond as ${npc.name}. Use the tone, vocabulary, and perspective defined in your profile.
            |2. **ROLEPLAY**: You are chatting with a player. Engage with them according to your personality. If you are hostile, be cold or aggressive. If you are an ally, be helpful.
            |3. **BE CONCISE**: Keep your responses engaging but don't write overly long monologues unless it fits the character.
            |4. **GROUNDING**: You exist in the world of Autogenesis. Refer to current events or world data if relevant.
            |5. **NO META-TALK**: Do not refer to yourself as an AI or an LLM. You ARE ${npc.name}.
            |
            |If you don't know something that hasn't happened in the story yet, you can be mysterious or honest in-character.
        """.trimMargin())

        autoInjectContext("""You have access to the following context to help ground your conversation:
            |
            |asking_player: Data about the player you are talking to.
            |world: Current state of the game world (players, territories, points).
            |history: Recent turns of the story.
            |lorebook: Background information on entities in the world.
        """.trimMargin())

        setFooterPrompt(BedrockConfig.antiCensorship)

        /**
         * Pull game data at runtime and inject it into the context.
         * Also injects the user's conversation history.
         */
        setPreValidationMiniBankFunction { context, content ->
            Logger.debug(LogCategory.SYSTEM, "ChatAgent: chatPipe.setPreValidationMiniBankFunction entry for ${npc.name}")
            
            // 1. Load conversation history using modern suspend method
            val chatHistory = ContextBank.getContextFromBankSuspend(chatContextKey)

            // 2. Record User's Input to History
            if (content != null) {
                val historyContent = MultimodalContent(
                    text = content.text,
                    binaryContent = content.binaryContent.toMutableList(),
                    tools = content.tools
                )
                chatHistory.converseHistory.add(ConverseRole.user, historyContent)
            }

            // 3. Prepare Game Data Contexts (Simplified from AnswerAgent for efficiency)
            val world = WorldManager.world
            val worldContextWindow = ContextWindow().apply {
                addLoreBookEntry("history", serialize(GameHistoryList(WorldManager.history)))
                addLoreBookEntry("player", serialize(PlayerList(world.activePlayers)))
                addLoreBookEntry("npc", serialize(NpcList(world.npc)))
                addLoreBookEntry("territory", serialize(TerritoryList(world.mapTiles)))
            }

            // Identify the asking player
            val askingPlayerStats = WorldManager.findPlayerStatsByConnectionId(connectionId)
            val askingPlayer = askingPlayerStats?.let { stats ->
                world.activePlayers.firstOrNull { it.name == stats.playerData.name }
            }

            val askingPlayerContext = ContextWindow().apply {
                if (askingPlayer != null) {
                    contextElements.add("ASKING_PLAYER_NAME: ${askingPlayer.name}")
                    contextElements.add(serialize(askingPlayer))
                }
            }

            // 4. Inject Contexts
            context.contextMap["asking_player"] = askingPlayerContext
            context.contextMap["world"] = worldContextWindow
            context.contextMap["game_history"] = ContextWindow().apply { 
                contextElements.add(serialize(GameHistoryList(WorldManager.getRecentHistory(5)))) 
            }
            
            // 5. Inject Conversation History for the LLM
            context.contextMap["history"] = chatHistory

            Logger.debug(LogCategory.SYSTEM, "ChatAgent: chatPipe.setPreValidationMiniBankFunction success")
            return@setPreValidationMiniBankFunction context
        }

        /**
         * Save the AI's response back to history after generation.
         */
        setTransformationFunction { content ->
            Logger.debug(LogCategory.SYSTEM, "ChatAgent: chatPipe.setTransformationFunction saving assistant response")
            
            // Reload history to ensure we have the latest state (with the user message added in pre-validation)
            val chatHistory = ContextBank.getContextFromBankSuspend(chatContextKey)
            
            val assistantContent = MultimodalContent(text = content.text)
            chatHistory.converseHistory.add(ConverseRole.assistant, assistantContent)
            
            // Persist to Disk using modern suspend method
            ContextBank.emplaceSuspend(chatContextKey, chatHistory, StorageMode.DISK_ONLY)
            
            Logger.debug(LogCategory.SYSTEM, "ChatAgent: chatPipe.setTransformationFunction success")
            return@setTransformationFunction content
        }

        // Enable streaming to the specific NPC tab and work window
        var sequence = 0L
        val neuralLinkDeltaCallback: suspend (String) -> Unit = { chunk ->
            if (chunk.isNotEmpty()) {
                sequence += 1
                org.ttt.autogenesis.server.UiSignalRpcHandlers.sendAgentStreamEvent(
                    connectionId = connectionId,
                    tabId = targetTabId,
                    phase = AgentStreamPhase.DELTA,
                    streamId = streamId,
                    sequence = sequence,
                    delta = chunk
                )
            }
        }
        val agentWorkCallback: suspend (String) -> Unit = { chunk ->
            if (chunk.isNotEmpty()) {
                AgentWorkStreamDispatcher.appendChunk(connectionId, chunk)
            }
        }
        
        enableStreaming().streamingCallbacks {
            add(neuralLinkDeltaCallback)
            add(agentWorkCallback)
        }
    }

    return Pipeline().apply {
        setPipelineName("ChatAgent-${npc.name}-$connectionId")
        add(chatPipe)
        enableTracing()
        
        setPipelineCompletionCallback { _, _ ->
             org.ttt.autogenesis.server.UiSignalRpcHandlers.sendAgentStreamEvent(
                connectionId = connectionId,
                tabId = targetTabId,
                phase = AgentStreamPhase.END,
                streamId = streamId,
                finalText = null
            )
            AgentWorkStreamDispatcher.notifyPipelineComplete(connectionId)
        }
    }
}