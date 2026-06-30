package gameInit

import org.ttt.autogenesis.server.config.BootStage
import org.ttt.autogenesis.server.config.RuleSet
import org.ttt.autogenesis.server.config.ServerConfig
import org.ttt.autogenesis.server.config.SystemStartupStage
import enums.CommanderTrait
import enums.CommanderType
import gameState.WorldManager
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcDirection
import org.ttt.autogenesis.network.RpcMethod
import structs.Player
import structs.assignRandomStartingTiles
import structs.assignSecondaryStartingTiles
import structs.determineStartingTurnOrder
import structs.matchmaking.GameSessionStatus
import structs.matchmaking.PlayerSessionBundle
import structs.matchmaking.GameType
import gameInit.AiPromptProvider
import kotlinx.coroutines.launch

/**
 * Init object that is invoked to handle core settings, and inbound communications from system
 * servers, and services outside of player network calls. All rpc's that involve backend communication
 * inbound to this server should be handled here.
 */
object GameInit
{
    /**
     * Stores and binds the game rules to this server moving it into the next phase of it's startup.
     */
    @RpcMethod("server.setGameMode", RpcDirection.SERVER)
    suspend fun defineGameRules(context: RpcCallContext, sessionData: GameSessionStatus) : Boolean
    {
        Logger.info(LogCategory.SYSTEM, "GameInit: Initializing new game session (humanPlayers=${sessionData.players.size}, aiOpponents=${sessionData.aiOpponentCount}, aiOnly=${sessionData.aiOnly})")
        Logger.debug(LogCategory.SYSTEM, "GameInit: Session ID: ${sessionData.sessionId}, Server URL: ${sessionData.serverUrl}")
        Logger.debug(LogCategory.SYSTEM, "GameInit: Incoming human players: ${sessionData.players.map { it.accelByteUserName }}")

        // Phase D (resume-game-architecture): if the incoming session is a
        // resume (resumeFromVfs=true), rehydrate the saved snapshot for
        // resumeUserId BEFORE the fresh-state reset below. The reset wipes
        // WorldManager, so the resume must run first.
        if (sessionData.resumeFromVfs && sessionData.resumeUserId.isNotBlank())
        {
            Logger.info(LogCategory.SYSTEM, "GameInit: resumeFromVfs=true resumeUser=${sessionData.resumeUserId} — rehydrating saved snapshot before fresh-state reset")
            val resumed = org.ttt.autogenesis.server.GameRestoreRpcHandlers.restoreRunningGameForUser(sessionData.resumeUserId)
            if (!resumed)
            {
                Logger.warn(LogCategory.SYSTEM, "GameInit: resumeFromVfs=true but restoreRunningGameForUser failed for user=${sessionData.resumeUserId}; falling through to fresh-session path")
            }
            else
            {
                Logger.info(LogCategory.SYSTEM, "GameInit: rehydrated world for user=${sessionData.resumeUserId} round=${gameState.WorldManager.world.roundNumber}")
            }
        }

        // 1. Reset state for a fresh session
        Logger.info(LogCategory.SYSTEM, "GameInit: Resetting TurnHarness state for fresh start")
        org.ttt.autogenesis.server.TurnHarness.resetState()
        
        WorldManager.isSinglePlayer = sessionData.gameType == GameType.SINGLEPLAYER
        if (WorldManager.isSinglePlayer)
        {
            val humanPlayer = sessionData.players.firstOrNull()
            WorldManager.humanPlayerName = humanPlayer?.commander?.name
                ?.takeIf { it.isNotBlank() }
                ?: humanPlayer?.accelByteUserName
                ?: humanPlayer?.accelByteId
                ?: ""
            Logger.info(LogCategory.SYSTEM, "GameInit: Identified human player for single player mode: ${WorldManager.humanPlayerName}")
        } else {
            WorldManager.humanPlayerName = ""
        }

        RuleSet.data = sessionData

        // Bind the matched session to WorldManager + TurnHarness so the arrival gate at
        // Server.kt's /events WebSocket handler can verify incoming players. This is the DS-side
        // path for live match2 (server-extend drives the matching and ships a GameSessionStatus
        // via `server.setGameMode` to whatever DS it picked). The DS Hub path is unchanged.
        if (sessionData.sessionId.isNotBlank())
        {
            val expectedUserIds = sessionData.players
                .map { it.accelByteId }
                .filter { it.isNotBlank() }
            try
            {
                gameState.WorldManager.bindSession(sessionData.sessionId, expectedUserIds)
                org.ttt.autogenesis.server.TurnHarness.onSessionBound(sessionData.sessionId, expectedUserIds)
                Logger.info(
                    LogCategory.SYSTEM,
                    "GameInit: Bound live session sessionId=${sessionData.sessionId} expectedPlayers=${expectedUserIds.size}"
                )
            }
            catch (e: org.ttt.autogenesis.server.ServerDrainingException)
            {
                Logger.warn(
                    LogCategory.SYSTEM,
                    "GameInit: Server is draining, late bind rejected for sessionId=${sessionData.sessionId}; continuing to enter WAITFORPLAYERS"
                )
            }
        }
        else
        {
            Logger.debug(LogCategory.SYSTEM, "GameInit: No sessionId in incoming session data (likely single-player); skipping live bind")
        }

        BootStage.bootStage = SystemStartupStage.WAITFORPLAYERS
        Logger.debug(LogCategory.SYSTEM, "GameInit: BootStage advanced to WAITFORPLAYERS")

        // 2. Load Map
        // 2-player maps: fixedUSamerica, San_Martello
        // 3-4 player maps: IO-map
        val totalPlayers = sessionData.maxPlayers
        Logger.info(LogCategory.GENERAL, "GameInit: Selecting map for $totalPlayers players...")
        Logger.debug(LogCategory.GENERAL, "GameInit: Map selection starting (activeMapBytesPresent=${WorldManager.activeMapPackBytes != null})")

        val riggedMapName = ServerConfig.rigMapName
        val allMaps = listOf(
            "maps/fixedUSamerica.map",
            "maps/San_Martello.map",
            "maps/IO-map.map",
            "maps/Laurasiagondwana.map",
            "maps/jupiter.map",
            "maps/StartMap.map"
        )
        val riggedMap = riggedMapName?.let { rigName ->
            allMaps.find { map -> map.contains(rigName, ignoreCase = true) }
        }

        if(riggedMap != null)
        {
            try
            {
                Logger.info(LogCategory.SYSTEM, "GameInit: Loading rigged map $riggedMap (riggedName=$riggedMapName)")
                WorldManager.loadMapFromResources(riggedMap)
            }
            catch(e: Exception)
            {
                Logger.warn(LogCategory.GENERAL, "GameInit: Failed to load rigged map $riggedMap: ${e.message}")
            }
        }
        else if(riggedMapName != null)
        {
            Logger.warn(LogCategory.SYSTEM, "GameInit: --map=$riggedMapName did not match any available map, falling back to selection logic")
        }

        // Only run default selection if rigged map was not set or failed to load
        if(riggedMap == null && WorldManager.activeMapPackBytes == null)
        {
            if(totalPlayers == 2)
            {
                // Randomly pick one of the two 2-player maps
                val twoPlayerMaps = listOf("maps/fixedUSamerica.map", "maps/San_Martello.map")
                val chosen = twoPlayerMaps[kotlin.random.Random.nextInt(twoPlayerMaps.size)]
                try
                {
                    Logger.info(LogCategory.GENERAL, "GameInit: Loading 2-player map $chosen")
                    WorldManager.loadMapFromResources(chosen)
                }
                catch(e: Exception)
                {
                    Logger.warn(LogCategory.GENERAL, "GameInit: Failed to load 2-player map $chosen: ${e.message}")
                }
            }
            else if(totalPlayers == 3 || totalPlayers == 4)
            {
                // Randomly pick one of the 3-4 player maps (IO-map, Laurasiagondwana, jupiter)
                val threeFourPlayerMaps = listOf(
                    "maps/IO-map.map",
                    "maps/Laurasiagondwana.map",
                    "maps/jupiter.map"
                )
                val chosen = threeFourPlayerMaps[kotlin.random.Random.nextInt(threeFourPlayerMaps.size)]
                try
                {
                    Logger.info(LogCategory.GENERAL, "GameInit: Loading 3-4 player map $chosen")
                    WorldManager.loadMapFromResources(chosen)
                }
                catch(e: Exception)
                {
                    Logger.warn(LogCategory.GENERAL, "GameInit: Failed to load 3-4 player map $chosen: ${e.message}")
                }
            }
        }

        // Fallback: random map from all packaged + uploaded maps
        if(WorldManager.activeMapPackBytes == null)
        {
            Logger.info(LogCategory.GENERAL, "GameInit: No specific map loaded, picking random map pack...")
            Logger.debug(LogCategory.GENERAL, "GameInit: Random map selection triggered")
            MapSelectionService.loadRandomMapPack()?.let { (descriptor, bytes) ->
                try
                {
                    Logger.info(LogCategory.GENERAL, "GameInit: Loading random map pack ${descriptor.path}")
                    WorldManager.loadMapFromPack(bytes, "selected:${descriptor.path}")
                }
                catch(e: Exception)
                {
                    Logger.warn(LogCategory.GENERAL, "GameInit: Failed to load random map ${descriptor.path}: ${e.message}")
                }
            }
        }

        if(WorldManager.activeMapPackBytes == null)
        {
            try {
                Logger.info(LogCategory.GENERAL, "GameInit: Falling back to default map StartMap.map")
                Logger.debug(LogCategory.GENERAL, "GameInit: Ensuring default map cover when no map bytes are loaded")
                WorldManager.loadMapFromResources("maps/StartMap.map")
                Logger.info(LogCategory.GENERAL, "GameInit: Successfully loaded default resource map")
            } catch (e: Exception) {
                Logger.error(LogCategory.GENERAL, "GameInit: CRITICAL - No default map found at maps/StartMap.map and selection failed: ${e.message}")
            }
        }
        else
        {
            Logger.info(LogCategory.GENERAL, "GameInit: Map confirmed loaded (${WorldManager.activeMapPackBytes?.size} bytes)")
            Logger.debug(LogCategory.GENERAL, "GameInit: Loaded map metadata: name=${WorldManager.world.name}, tiles=${WorldManager.world.mapTiles.size}, scenario=${WorldManager.world.storyScenario}")
        }

        // 2.5 Load the audio-tracks catalog from the bundled
        // `audio/audio-tracks.json` resource and install it onto the
        // world. The user-visible contract is that the audio tracks
        // are loaded AFTER the map has been picked, so the
        // `start` / `nemesis` / `end` track identities can agree
        // with the map the music selector will pair them with. The
        // file is shipped with the server jar (see
        // `sharedModel/src/commonMain/resources/audio/audio-tracks.json`).
        try
        {
            Logger.info(LogCategory.GENERAL, "GameInit: Loading audio tracks from bundled resource audio/audio-tracks.json")
            WorldManager.loadAudioTracksFromResource("audio/audio-tracks.json")
        }
        catch(e: Exception)
        {
            // Audio tracks are nice-to-have; never abort game init
            // because the audio file is missing or malformed. The
            // world keeps its default-empty AudioTracks and the
            // music-selector falls through to its hardcoded
            // MusicTrackCatalog names.
            Logger.warn(LogCategory.GENERAL, "GameInit: Failed to load audio tracks: ${e.message}")
        }

        // 3. Configure Players (Human + AI)
        Logger.info(LogCategory.SYSTEM, "GameInit: Configuring player roster")
        configurePlayersFromSession(sessionData)
        
        // 4. Activate Loop
        Logger.info(LogCategory.SYSTEM, "GameInit: Game world initialized and active. Human player(s) can now connect via WebSocket.")
        gameState.WorldManager.isGameActive = true

        // 5. Retroactve Sync for already connected players
        val connectionManager = org.ttt.autogenesis.server.UiSignalRpcHandlers.connectionManager
        WorldManager.playerStats.filter { !it.isControlledByNpc }.forEach { stats ->
            val session = connectionManager?.findSession(stats.playerID)
            if (session != null)
            {
                // We found their live websocket! The cache is wrong.
                stats.isConnected = true
                Logger.info(LogCategory.SYSTEM, "GameInit: Retroactive sync for already connected human player ${stats.playerData.name}")
                org.ttt.autogenesis.server.UiSignalRpcHandlers.sendInitialSync(
                    stats.playerID,
                    stats.playerData,
                    WorldManager.activeMapPackBytes,
                    WorldManager.world,
                    WorldManager.history
                )
            }
        }

        if (WorldManager.isGameActive && !org.ttt.autogenesis.server.TurnHarness.isRunning()) {
            Logger.info(LogCategory.SYSTEM, "GameInit: Starting Turn Harness loop")
            org.ttt.autogenesis.server.TurnHarness.runNextTurn()
        }

        return true
    }

    /**
     * Registers the human and AI roster from [sessionData] and starts the turn bootstrap helpers.
     *
     * Clears any lingering players/history, inspects the incoming bundles to create human [Player] objects (linking
     * them to their WebSocket `connectionId`), and then delegates to [buildAiPlayers] for prompt-driven opponents.
     * Once the active list is populated this will trigger [finalizeTurnSetup] so tile ownership and turn order
     * are resolved before the gameplay loop begins.
     */
    private suspend fun configurePlayersFromSession(sessionData: GameSessionStatus)
    {
        Logger.info(LogCategory.SYSTEM, "GameInit: Preparing ${if(sessionData.aiOnly) "AI-only" else "mixed"} session data")
        Logger.debug(LogCategory.SYSTEM, "GameInit: Session data reset: activePlayers=${WorldManager.world.activePlayers.size}, turnOrder=${WorldManager.world.turnOrder}")
        WorldManager.world.activePlayers.clear()
        WorldManager.playerStats.clear()
        WorldManager.history.clear()

        if(!sessionData.aiOnly && sessionData.players.isEmpty())
        {
            Logger.warn(LogCategory.GENERAL, "GameInit: Expected at least one human player, but session payload was empty")
        }

        val humanBundles = if(sessionData.aiOnly) emptyList() else sessionData.players
        humanBundles.forEach { bundle ->
            val commander = bundle.commander
            val playerName = commander?.name
                ?.takeIf { it.isNotBlank() }
                ?: bundle.accelByteUserName
                ?: bundle.accelByteId

            // Determine connection ID: prefer websocketId, fallback to generated.
            val connectionId = bundle.websocketId.takeIf { it.isNotBlank() }
                ?: "PLAYER_CONN_${bundle.accelByteUserName}"

            // Phase 5: playerAlias — if set, the Python controller's session ID will
            // contain this alias string. Match against existing playerStats entries
            // so the controller inherits the same player slot as the browser.
            val existingStats = if(bundle.playerAlias.isNotBlank())
            {
                Logger.debug(LogCategory.SYSTEM, "GameInit: playerAlias='${bundle.playerAlias}' set — searching for existing playerStats entry...")
                WorldManager.playerStats.find { stats ->
                    stats.playerID.contains(bundle.playerAlias) ||
                    stats.playerData.name.contains(bundle.playerAlias)
                }.also { match ->
                    if(match != null)
                    {
                        Logger.info(LogCategory.SYSTEM, "GameInit: playerAlias match found — '${match.playerData.name}' (conn=${match.playerID}) will accept controller session as alias '$connectionId'")
                    }
                    else
                    {
                        Logger.warn(LogCategory.SYSTEM, "GameInit: playerAlias='${bundle.playerAlias}' set but no existing playerStats entry matched")
                    }
                }
            }
            else null

            if(existingStats != null)
            {
                // Controller joining browser's existing player slot.
                // Update the connectionId so controller's WS can be reached via ping.
                Logger.info(LogCategory.SYSTEM, "GameInit: Registering controller for existing player '${existingStats.playerData.name}' (accelByteId=${bundle.accelByteId}, aliasConn=$connectionId, role=CONTROLLER)")
                existingStats.playerID = connectionId
                existingStats.isConnected = false // Controller will set true when it connects
                existingStats.isControlledByNpc = false
            }
            else
            {
                // Normal path — browser (or solo player) registering for first time.
                Logger.info(LogCategory.SYSTEM, "GameInit: Registering human player '$playerName' (accelByteId=${bundle.accelByteId}, expectedConn=$connectionId, playerAlias='${bundle.playerAlias}')")

                val player = Player(
                    name = playerName,
                    commanderType = commander?.type ?: CommanderType.Land,
                    trait = commander?.trait ?: CommanderTrait.Balanced,
                    description = commander?.description ?: "Human player",
                    history = "Session request for ${bundle.accelByteUserName}"
                )

                val stats = WorldManager.addPlayerToWorld(player, bundle.accelByteId, connectionId)
                stats.isConnected = false // Will be set to true in Server.kt when they connect
                stats.isControlledByNpc = false
            }
        }

        Logger.debug(LogCategory.SYSTEM, "GameInit: Building ${sessionData.aiOpponentCount} AI opponents")
        val rigSubstrings = ServerConfig.rigAiPlayers
        if(rigSubstrings.isNotEmpty())
        {
            Logger.info(LogCategory.SYSTEM, "GameInit: --rig active: selecting AI descriptors matching $rigSubstrings")
        }
        val aiPlayers = buildAiPlayers(sessionData.aiOpponentCount, rigSubstrings)
        aiPlayers.forEachIndexed { index, aiPlayer ->
            val aiConnection = "AI_CONNECTION_${index + 1}"
            Logger.info(LogCategory.SYSTEM, "GameInit: Registering AI player '${aiPlayer.name}' (conn=$aiConnection)")
            val stats = WorldManager.addPlayerToWorld(aiPlayer, "AI_USER", aiConnection)
            stats.isConnected = false
            stats.isControlledByNpc = true
        }

        if(WorldManager.world.activePlayers.isNotEmpty())
        {
            Logger.info(LogCategory.SYSTEM, "GameInit: Finalizing turn setup for ${WorldManager.world.activePlayers.size} players: ${WorldManager.world.activePlayers.joinToString { it.name }}")
            Logger.debug(LogCategory.SYSTEM, "GameInit: Resolved roster=${WorldManager.world.activePlayers.map { it.name }} turnOrder=${WorldManager.world.turnOrder}")
            finalizeTurnSetup()
        }
        else
        {
             Logger.warn(LogCategory.SYSTEM, "GameInit: No players registered, skip turn setup")
        }
    }

    /**
     * Randomizes starting tiles and records the resulting turn order so the gameplay orchestrators know who goes first.
     *
     * Tile assignment uses [assignRandomStartingTiles], which requires every player to exist on the current map before
     * it can ship the ownership metadata (captured territories) back onto the [Player] objects. When four players are
     * present the function also runs [determineStartingTurnOrder] to map the corner-aligned tiles into a clockwise order;
     * otherwise it keeps the current insertion order. The resolved name list is copied to [World.turnOrder] and the
     * first entry becomes [WorldManager.activeTurnActor].
     */
    private suspend fun finalizeTurnSetup()
    {
        val players = WorldManager.world.activePlayers
        if(players.isEmpty())
        {
            Logger.warn(LogCategory.GENERAL, "GameInit: No players present to finalize turn setup")
            return
        }

        if(WorldManager.world.mapTiles.isEmpty())
        {
            Logger.warn(LogCategory.GENERAL, "GameInit: Cannot assign starting tiles because the current map defines no territories")
            return
        }

        // Try balanced corner assignment first (supports 2-4 players)
        var balancedSuccess = false
        if(players.size in 2..4)
        {
            runCatching {
                structs.assignCornerTerritoriesToPlayers(WorldManager.world)
                balancedSuccess = true
                Logger.info(LogCategory.SYSTEM, "GameInit: Successfully assigned balanced corner tiles for ${players.size} players")
            }.onFailure { err ->
                Logger.warn(LogCategory.GENERAL, "GameInit: Balanced corner assignment failed: ${err.message}. Falling back to random assignment.")
            }
        }

        if(!balancedSuccess)
        {
            runCatching {
                structs.assignRandomStartingTiles(WorldManager.world, players)
            }.onFailure { err ->
                Logger.warn(LogCategory.GENERAL, "GameInit: Failed to assign random starting tiles: ${err.message}")
            }
        }

        // Grant each player a second tile picked at random but not adjacent to their own starting tile.
        // Runs for both the corner-balanced and the random-fallback paths because every player should
        // get the same initial expansion opportunity regardless of which assignment strategy was used.
        runCatching {
            structs.assignSecondaryStartingTiles(WorldManager.world, players)
        }.onFailure { err ->
            Logger.warn(LogCategory.GENERAL, "GameInit: Failed to assign secondary starting tiles: ${err.message}")
        }

        val orderedPlayers = if(players.size in 2..4 && balancedSuccess) {
            runCatching {
                structs.determineStartingTurnOrder(WorldManager.world, players)
            }.getOrNull()?.also { ordered ->
                Logger.info(LogCategory.SYSTEM, "GameInit: Turn order determined from corner assignments: ${ordered.map { it.name }}")
            }
        } else {
            null
        }

        val resolvedOrder = orderedPlayers ?: players
        WorldManager.world.turnOrder.clear()
        WorldManager.world.turnOrder.addAll(resolvedOrder.map { it.name })

        val nextActor = WorldManager.world.turnOrder.firstOrNull() ?: resolvedOrder.firstOrNull()?.name
        if(!nextActor.isNullOrBlank())
        {
            WorldManager.activeTurnActor = nextActor
        }

        // Finalize state sync: Broadcast the completed world (with rulers and turn order) to all clients.
        // This ensures clients who joined during the init process get the "ready to play" data.
        Logger.debug(LogCategory.NETWORK, "GameInit: Broadcasting world update after configuration (round=${WorldManager.world.roundNumber}, turnOrder=${WorldManager.world.turnOrder})")
        org.ttt.autogenesis.server.UiSignalRpcHandlers.broadcastWorldUpdate(WorldManager.world)
    }

    /**
     * Builds AI-controlled players directly from the prompt catalog so their commander data
     * (name, description, type, trait) matches the characters defined in [AiPromptProvider].
     *
     * @param count number of AI players to build
     * @param rigSubstrings optional list of substrings to filter descriptors by name. When provided,
     *                      only descriptors whose names contain a given substring are selected (first-match-wins
     *                      per substring). Falls back to random selection if no matches or too few matches.
     */
    private fun buildAiPlayers(count: Int, rigSubstrings: List<String>? = null): List<Player>
    {
        val normalized = count.coerceAtLeast(0)
        if(normalized == 0)
        {
            return emptyList()
        }

        val descriptors = if(rigSubstrings.isNullOrEmpty())
        {
            AiPromptProvider.randomDescriptors(normalized)
        }
        else
        {
            val matched = AiPromptProvider.descriptorsMatching(rigSubstrings)
            when
            {
                matched.isEmpty() ->
                {
                    Logger.warn(LogCategory.SYSTEM, "GameInit: --rig matched no descriptors for $rigSubstrings, falling back to random")
                    AiPromptProvider.randomDescriptors(normalized)
                }
                matched.size > normalized ->
                {
                    Logger.warn(LogCategory.SYSTEM, "GameInit: --rig matched ${matched.size} descriptors but only ${normalized} needed, taking first $normalized")
                    matched.take(normalized)
                }
                matched.size < normalized ->
                {
                    Logger.warn(LogCategory.SYSTEM, "GameInit: --rig matched only ${matched.size} descriptors for $rigSubstrings, padding with random")
                    matched + AiPromptProvider.randomDescriptors(normalized - matched.size)
                }
                else -> matched
            }
        }

        return descriptors.map { descriptor ->
            Player(
                name = descriptor.name,
                commanderType = descriptor.commanderType,
                trait = descriptor.commanderTrait,
                description = descriptor.prompt,
                shortDescription = descriptor.shortDescription,
                history = "AI prompt: ${descriptor.key}"
            )
        }
    }
}
