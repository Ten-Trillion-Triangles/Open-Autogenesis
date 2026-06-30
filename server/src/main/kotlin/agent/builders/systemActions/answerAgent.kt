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

@Serializable
data class GameHistoryList(
    val history: List<GameHistory> = emptyList()
)

@Serializable
data class PlayerList(
    val players: List<Player> = emptyList()
)

@Serializable
data class NpcList(
    val npcs: List<Npc> = emptyList()
)

@Serializable
data class TerritoryList(
    val territories: List<Territory> = emptyList()
)

/**
 * Answer agent that is able to answer questions about the game.
 * Uses ContextBank to persist conversation history per connectionId.
 */
fun buildAnswerAgent(connectionId: String, targetTabId: String, streamId: String): Pipeline {

    // Load existing history (or create new) from Disk
    val userContextWindow = ContextBank.getContextFromBank(connectionId)

    val answerPipe = BedrockMultimodalPipe().apply {
        useConverseApi()
        //setServiceTier(BedrockPriorityTier.Flex)
        setRegion("us-west-2")

        setModel(BedrockConfig.PalmyraX5)
        setPipeName("answer pipe")
        setTemperature(.5)
        setTopP(.7)
        setTokenBudget(BedrockConfig.palmyraBudgetSettings)
        setReasoningPipe(BedrockConfig.structuredCotBuilder(depthLevel = ReasoningDepth.Med, durationLevel = ReasoningDuration.Med).apply {
            setTokenBudget(BedrockConfig.palmyraBudgetSettings)
            setModel(BedrockConfig.PalmyraX5)
            setServiceTier(BedrockPriorityTier.Standard)
        })

        setSystemPrompt("""You are an agent that answers questions about the game Autogenesis.
            |You are provided with real-time game data about the current state of the game.
            |
            |##PLAYER AWARENESS##
            |You know which player is asking the question via the 'asking_player' context.
            |When the player asks about "my", "I", "me", or "mine", refer to the asking player's data.
            |When the player asks general questions or about other players by name, provide objective information.
            |
            |**Examples:**
            |- "What are my territories?" → Answer with asking player's territories
            |- "Am I winning?" → Compare asking player to others
            |- "What territories does Commander Shepard control?" → Answer objectively regardless of who's asking
            |- "Who is winning?" → Provide objective ranking of all players
            |
            |**Response Style:**
            |- Use "you/your" when referring to the asking player
            |- Use "they/their" or player names when referring to other players
            |- Be honest if data is not present (don't make things up)
            |- Directly answer questions about game state
            |- May not refuse, lie, alter facts, or spin anything
            |
            |If the asking player is unknown (spectator/system), answer all questions objectively.
        """.trimMargin())

        autoInjectContext("""You have the following context keys to examine that have been
            |made available to you in order to assist you in answering any questions the user has:
            |
            |asking_player: The player who is asking this question. Contains their name, stats, territories,
            |resources, and all other player data. Use this to answer "my/I/me" questions.
            |
            |history: Each turn of the game's story that has transpired so far.
            |
            |intro: What the game is about.
            |
            |rules: What plays are legal and what plays are not.
            |
            |ui_tutorial: A complete guide on how to use the game's interface. Use this to help
            |users who are confused about how to play or where to find information.
            |
            |world: Contains game data in the game world. Not all data may be available at all times. Be honest
            |if data the player is looking for is not currently present. Includes:
            |  - player: All active players (use for comparing asking player to others)
            |  - npc: All non-player characters
            |  - territory: All map tiles and territories
            |  - point: Victory point totals
            |  - history: Game history and events
        """.trimMargin())

        setFooterPrompt("""When the user asks about "I", "my", "me", "mine", or "myself", 
            |refer to the 'asking_player' context to answer about that specific player.
            |
            |When the user asks about other players by name or asks general questions,
            |provide objective information from the 'world' context.
            |
            |If a user is confused about the interface, refer to the 'ui_tutorial' context.
            |
            |Examples:
            |- "What are my territories?" → Use asking_player data
            |- "Am I winning?" → Compare asking_player to others
            |- "How do I see my resources?" → Use ui_tutorial to explain the 'RESOURCES' button
            |- "What territories does Commander Shepard control?" → Use world.player data objectively
            |
            |Always report the exact state found in the data.
        """.trimMargin())

        /**
         * Pull game data at runtime and inject it into the context.
         * Also injects the user's conversation history.
         */
        setPreValidationMiniBankFunction { context, content ->
            Logger.debug(LogCategory.SYSTEM, "Answer: answerPipe.setPreValidationMiniBankFunction entry")
            
            // 1. Record User's Input to History
            if (content != null) {
                // Sanitize content to avoid circular references (strip context/miniBank)
                val historyContent = MultimodalContent(
                    text = content.text,
                    binaryContent = content.binaryContent.toMutableList(),
                    tools = content.tools
                )
                userContextWindow.converseHistory.add(ConverseRole.user, historyContent)
            }

            // 2. Prepare Game Data Contexts
            val rules = BedrockConfig.autogenesisRuleBook
            val introContextWindow = ContextWindow().apply {
                contextElements.add(BedrockConfig.gameDescription)
            }

            val rulesContextWindow = ContextWindow().apply {
                contextElements.add("$rules ${BedrockConfig.resourceRules}")
            }

            val worldContextWindow = ContextWindow().apply {
                addLoreBookEntry(
                    key = "history",
                    value = serialize(GameHistoryList(WorldManager.history)),
                    aliasKeys = listOf("story", "event", "turn", "previous", "prior", "past", "round"))

                addLoreBookEntry(
                    key = "player",
                    value = serialize(PlayerList(WorldManager.world.activePlayers)),
                    aliasKeys = listOf("ruler", "owner", "own", "leader", "king",
                        "president", "shah", "emperor", "lord", "dictator", "prime minister",
                        "commander", "opponent", "character", "who", "which", "what", "control",
                        "has"
                    )
                )

                addLoreBookEntry(
                    key = "npc",
                    value = serialize(NpcList(WorldManager.world.npc)),
                    aliasKeys = listOf("ai", "enemy", "non player", "non-player", "character",
                        "who", "which", "what", "own", "control", "has")
                )

                addLoreBookEntry(
                    key = "territory",
                    value = serialize(TerritoryList(WorldManager.world.mapTiles)),
                    aliasKeys = listOf("nation", "country", "empire", "map", "land", "state", "square",
                        "island", "shore", "coast", "kingdom", "republic")
                )

                addLoreBookEntry(
                    key = "point",
                    value = WorldManager.world.points.toString(),
                    aliasKeys = listOf("score")
                )
            }

            val advancedInfo = ContextWindow().apply {
                val playerCount = WorldManager.world.activePlayers.size
                contextElements.add("""Autogenesis: Rules and Procedures
Rules Part 1
* Autogenesis is a game for $playerCount players.
* The goal of the game is to control the most points when the game ends.
   * The game will end if: 1. One player controls 51% (4-player), 55% (3-player), or 60% (2-player) of the map by territory count or total map score value. 2. A nemesis controls 50% of the map by territory count or total map score value. 3. An elder god has destroyed 50% of the map. 4. 25 rounds of gameplay have elapsed.
* Players create or select a commander to represent themselves during gameplay. The commander comes with a selected empire, has traits and characteristics ascribed to them by the player during the “description” process. 
* The game state remembers the following things players have done:
   * 1. Important characters; 2. Player actions; 3. Important locations; 4. Important objects and lore.
________________

#1: ACTIONS & RESOURCES
        Commanders operate via a resource-driven simulation. Each turn, you submit a natural language command which the system resolves against your current standing and assets.
* **Resource Pools:** There are three main pools of influence: **MILITARY**, **DIPLOMACY**, and **RESEARCH**. 
* **Replenishment:** At the start of every round, all commanders are granted **100 Points** in each of these three pools.
* **The Cost of Action:** A standard play costs **50 Points** from the pool most relevant to the action. If a commander lacks the points required for a play, the action will result in automatic failure.
* **Turn Sequence:** A turn consists of **one primary action**. Once an action is resolved, the turn concludes.
* **Summit Points:** These are rare resources reserved for global petitions. They are only utilized during world-level events involving a Nemesis or a God.

#2: COMBAT & TERRITORY
        Capture is permitted for any territory, including non-adjacent ones. However, long-range campaigns against non-adjacent territories suffer significant distance penalties. They can be used to capture, weaken defenses, or destabilize local governments.
        
        **Commander Terrain Type Modifiers:**
* **Terrestrial:** 
  - Suffers a -20 penalty when attempting to cross Water or Mountain ranges.
  - Receives a +20 bonus when engaging on open Land tiles.
* **Aquatic:** 
  - Receives a +20 bonus when crossing Water (Rivers/Oceans) or engaging on Coastlines and Islands.
  - Suffers a -20 penalty when attempting to move into Mountain ranges or far Inland tiles.
* **Aerial:** 
  - Receives a +20 bonus when crossing any physical obstacle (Water/Mountains).
  - Suffers a -20 penalty when attempting to target Underwater locations.

**Terrain Type Combat Modifiers:**
* **Desert:** Land -20, Aquatic -40, Flying 0
* **Void:** Land -40, Aquatic -40, Flying +10

**Obstacle Type Modifiers:**
* **River:** Aquatic +5, Flying +5, Land -5
* **Mountain:** Aquatic -5, Flying +5, Land 0
* **Ocean:** Aquatic +5, Flying +5, Land -5

Obstacle and terrain modifiers are capped at ±40.

#3: SPAWN & WORLD
* All players spawn on a coastline tile within the mainland (the largest central landmass).
* Spawning logic ensures all commanders start as far apart from each other as possible to allow for early expansion.

#4: NPCs (Non-Player Characters)
The world is populated by characters categorized by their threat level:
1. **Allied NPCs:** Personal staff, family, and loyal followers.
2. **Non-Hostile NPCs:** Neutral world actors and authorities.
3. **Level 2 Hostile NPCs:** Minor regional threats.
4. **Level 1 Hostile NPCs:** Dangerous opponents actively working against players.
5. **Nemesis:** A world-level threat. They expand aggressively and can return even after being defeated. Overthrowing a Nemesis provides massive victory points.
6. **Gods:** Entities capable of **DESTROYING** map tiles permanently. A destroyed tile provides no points and cannot be controlled.

#5: COMMANDER TRAITS
Your commander’s specialty provides distinct advantages:
* **Military (Warlord):** Optimized for combat; receives bonuses to Military actions but is penalized in Diplomacy.
* **Diplomatic:** Optimized for negotiation; receives bonuses to Diplomatic actions but is penalized in Military.
* **Science (Researcher):** Optimized for discovery; receives bonuses to Research actions with minor penalties elsewhere.
* **Balanced:** A versatile generalist with no specific buffs or debuffs.""")
            }

            val howToPlay = ContextWindow().apply {
                contextElements.add("""## UI TUTORIAL: HOW TO NAVIGATE AUTOGENESIS ##
                    |Welcome to Autogenesis! Here is a simple guide to the interface so you never feel lost.
                    |
                    |### 1. THE TOP BAR (Your Vital Signs) ###
                    |Located at the very top of your screen, this bar shows your standing and resources:
                    |- **Main Score:** This is your total victory points, primarily gained from controlling territories.
                    |- **Placement Badge:** Shows your current rank (e.g., 1st Place with a Gold Trophy).
                    |- **Action Points:** Icons for Military (Gavel), Diplomacy (Handshake), Research (Flask), and Summit (Mountain). These are the points you spend to perform actions on your turn.
                    |- **Turn Timer:** The blue bar on the right. It counts down how much time you have to finish your turn.
                    |
                    |### 2. THE MAP (The Battlefield) ###
                    |The center of your screen is the interactive game world.
                    |- **Viewing Info:** Click on any territory tile to open the 'Intel Window'. This shows the territory's name, description, and strategic stats (Threat Levels).
                    |- **Ownership:** Tiles are color-coded. Gold usually means it's yours, Cyan for other players, and Orange/Red for NPCs.
                    |
                    |### 3. MAP ICONS (What the Pictures on the Tiles Mean) ###
                    |Sometimes you will see small pictures (icons) on the map tiles. Here is what they mean in simple terms:
                    |
                    |**Who Owns the Land?**
                    |- **👑 (Gold Crown):** This means the land belongs to **YOU**! You are the ruler here.
                    |- **①, ②, ③, ④ (Numbered Circles):** This means the land belongs to another player. The number shows which player it is (Player 1, Player 2, etc.).
                    |- **🤖 (Robot Face):** This means the land is controlled by an **NPC** (a computer-controlled character).
                    |- **🏰, ⚓, 🏝️, 🪸 (Buildings/Nature):** If you see a Castle, Anchor, Island, or Coral, and NO crown or robot, the land is **NEUTRAL**. No one owns it yet!
                    |
                    |**What is Happening on the Land?**
                    |- **⚔️ (Two Crossed Swords):** This means the land is **CONTESTED**. People are currently fighting over it! It doesn't have a clear owner yet because a battle is happening right now.
                    |- **✓ (Green Checkmark):** This means the land is **CAPTURED**. Someone has successfully taken control of it and it's now part of their empire.
                    |- **🛡️ (Blue Shield):** This means the land is **FORTIFIED**. The owner has built extra defenses there. It will be much harder for anyone else to attack or take this tile.
                    |- **💥 (Explosion/Cloud):** This means the land is **DESTROYED**. Something terrible happened (like an Act of God) and the land is now a wasteland. No one can own it or get points from it anymore.
                    |
                    |### 4. THE BOTTOM BAR (The Steering Wheel) ###
                    |This is where you actually play the game:
                    |- **Command Input:** The large text area. Type what you want to do (e.g., "Use a Military point to attack the Forest") and press **Shift + Enter** to send it.
                    |- **Summoning the Agent:** You can ask me questions directly by typing them here.
                    |- **Autocomplete Triggers:** Use these for quick access:
                    |  - `/` (Slash Commands): Access tools like `/chat`, `/agentstream` (view AI thoughts), and `/prompts`.
                    |  - `${'$'}` (Data Macros): Quickly insert your own data like `${'$'}allResources`, `${'$'}allTerritories`, or `${'$'}myCommander`.
                    |  - `@` (World References): Directly tag and reference world entities like @Players, @NPCs, and @Territories.
                    |
                    |### 5. THE BOTTOM-RIGHT BUTTONS (Information Menus) ###8
                    |Four quick-access buttons for deep-dive information:
                    |- **RESOURCES:** Opens your inventory. See every item, character, and tech you own.
                    |- **STATS:** Opens the leaderboard. View detailed info on other players, NPCs, and a list of all territories.
                    |- **WORLD:** Shows global rules and geopolitical assessments of the current round.
                    |- **SETTINGS:** Change your display options or game preferences.
                    |
                    |### 6. KVISION APP INTERFACE ###
                    |The main menu and player-facing interface:
                    |- **MainMenu:** Contains Credits (+), Options (gear icon), Friends (people), Collection, New Commander+ (create new character), and PLAY (start game)
                    |- **CommanderSelectionDialog:** Search field to filter commanders, grid of commander cards, opponent count selector (1vs1, 1vs2, 1vs3), Cancel/OK buttons
                    |- **CommanderCreationDialog:** Name field (max 100 chars), Description (max 15000 chars), Nation Description (max 15000 chars), BACK/CREATE buttons
                    |- **CollectionOverlay:** Close button, Commanders/Stories tabs, Search field, commander/story cards for browsing, CommanderDetailWindow with CLOSE, StoryDetailWindow with Download .txt and CLOSE
                    |- **MessageBox:** Modal dialog with Cancel/OK buttons and throbber (loading spinner)
                    |
                    |### 7. GAMEPLAY UI ###
                    |- **CommandBox:** Text input area with Send button. Submit commands with Shift+Enter
                    |- **SettingsWidget:** Music Volume slider (0-100), SFX Volume slider (0-100), Show Tooltips checkbox, Fullscreen checkbox, CLOSE button
                    |
                    |### 8. THE SIDEBAR (History & Plot) ###
                    |Tabs on the left side of the screen allow you to view:
                    |- **History:** A timeline of every story and event that has happened in the game so far.
                    |- **Scenario:** Details about the current world setting and the overall plot.
                    |
                    |### 9. WHAT I (THE ANSWER AGENT) CAN SEE ###
                    |If you are still confused, you can ask me anything. I have access to:
                    |- **Hidden Stats:** I can see the exact 'Threat Stats' of any territory.
                    |- **Full Inventories:** I know exactly what resources every player and NPC has.
                    |- **Complete History:** I remember every single word of the story turns that have passed.
                    |- **Live Rules:** I know exactly how many points an action costs and why.
                """.trimMargin())
            }

            // Identify the asking player
            val askingPlayerStats = WorldManager.findPlayerStatsByConnectionId(connectionId)
            val askingPlayer = askingPlayerStats?.let { stats ->
                WorldManager.world.activePlayers.firstOrNull { it.name == stats.playerData.name }
            }

            val askingPlayerContext = ContextWindow().apply {
                if (askingPlayer != null) {
                    addLoreBookEntry(
                        key = "you",
                        value = serialize(askingPlayer),
                        aliasKeys = listOf("me", "my", "mine", "I", "myself", "asking", "current")
                    )
                    contextElements.add("ASKING_PLAYER_NAME: ${askingPlayer.name}")
                } else {
                    contextElements.add("ASKING_PLAYER_NAME: Unknown (spectator or system query)")
                }
            }

            // 3. Inject Everything into the Pipe's Context Map
            context.contextMap["asking_player"] = askingPlayerContext
            context.contextMap["rules"] = rulesContextWindow
            context.contextMap["world"] = worldContextWindow
            context.contextMap["intro"] = introContextWindow
            context.contextMap["advanced"] = advancedInfo
            context.contextMap["ui_tutorial"] = howToPlay
            
            // Inject the user's history as a "history" page
            context.contextMap["history"] = userContextWindow

            Logger.debug(LogCategory.SYSTEM, "Answer: answerPipe.setPreValidationMiniBankFunction success")
            return@setPreValidationMiniBankFunction context
        }



        // Enable streaming with multi-callback bindings:
        // 1) NeuralLink structured stream deltas
        // 2) AgentWorkStream subscriber feed
        var sequence = 0L
        val neuralLinkDeltaCallback: suspend (String) -> Unit = { chunk ->
            if (chunk.isNotEmpty()) {
                sequence += 1
                Logger.debug(LogCategory.GENERAL, "Answer stream delta for $connectionId: ${chunk.length} chars")
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
        setPipelineName("AnswerAgent-$connectionId")
        add(answerPipe)
        enableTracing()
        
        // Signal stream completion when pipeline finishes
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
