package matchmaking

import globals.ExtendConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import net.accelbyte.sdk.api.match2.models.ApiMatchTicketRequest
import net.accelbyte.sdk.api.match2.operations.match_tickets.CreateMatchTicket
import net.accelbyte.sdk.api.match2.operations.match_tickets.DeleteMatchTicket
import net.accelbyte.sdk.api.match2.operations.match_tickets.MatchTicketDetails
import net.accelbyte.sdk.api.match2.wrappers.MatchTickets
import net.accelbyte.sdk.api.session.models.ApimodelsUserResponse
import net.accelbyte.sdk.api.session.operations.game_session.GetGameSession
import net.accelbyte.sdk.api.session.wrappers.GameSession
import net.accelbyte.sdk.core.AccelByteSDK
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcDirection
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcMethod
import org.ttt.autogenesis.network.RpcRegistry
import org.ttt.autogenesis.network.WebSocketRpcClient
import org.ttt.autogenesis.network.WebSocketRpcClientConfig
import org.ttt.autogenesis.network.registerRpcSystem
import org.ttt.autogenesis.serverextend.config.AccelByteConfig
import structs.matchmaking.GameRequest
import structs.matchmaking.GameSessionStatus
import structs.matchmaking.GameTicket
import structs.matchmaking.GameTicketStatus
import structs.matchmaking.GameType
import structs.matchmaking.PlayerSessionBundle
import java.util.UUID

/**
 * Server side manager class to handle connecting game clients to the websocket game server that drives the main gameplay
 * loop.
 *
 * The matchmaking flow has three layers:
 *  1. [executeLiveMatchmaking] creates a match2 ticket in the entry pool of [MatchmakingLadder]
 *     (default: `pvp-4`). The ticket's [ApiMatchTicketRequest.attributes] are enriched with the
 *     requester's cost class and subsidy capacity so the custom matchmaker can score the
 *     candidate.
 *  2. A [PromotionLadder] coroutine watches the ticket and, if no match arrives within
 *     [MatchPoolSpec.holdSeconds], deletes the ticket and recreates it in the next pool.
 *  3. The matchmaker (`matchmaker/` module) consumes the cost attributes and proposes matches
 *     that the operator is willing to subsidize.
 *
 * In dev mode ([ExtendConfig.debugMode]) the path is the same as before: a synthetic
 * `GameSessionStatus` is constructed and forwarded to the local DS, with no live match2
 * traffic and no promotion ladder.
 */
object ServerConnector
{
//===========================================Class Variables============================================================
    /**
     * AccelByte SDK instance for matchmaking and session operations.
     */
    internal var sdk: AccelByteSDK = AccelByteSDK(net.accelbyte.sdk.core.AccelByteConfig.getDefault())

    /**
     * MatchTickets wrapper for matchmaking ticket operations.
     */
    internal var matchTicketsWrapper: MatchTickets = MatchTickets(sdk)

    /**
     * GameSession wrapper for game session operations.
     */
    internal var gameSessionWrapper: GameSession = GameSession(sdk)

    /**
     * AccountSettings lookup used to enrich the match2 ticket with cost attributes.
     * Tests inject a fake by replacing the property.
     */
    internal var accountSettingsLookup: AccountSettingsLookup = AccountSettingsLookup()

    /**
     * Ladder that drives the pool promotion timer. Tests inject a custom ladder by
     * replacing the property; production code uses [MatchmakingLadder.DEFAULT].
     */
    internal var ladder: MatchmakingLadder = MatchmakingLadder.DEFAULT

    /**
     * List of all game sessions that are currently in progress. Maps session id to the status data class.
     */
    private val gameSessions = mutableMapOf<String, GameSessionStatus>()

    /**
     * Mutex to ensure thread safe access to the gameSessions map.
     */
    val sessionMutex = Mutex()

    /**
     * Authoritative map of sessionId -> dedicated-server URL for the
     * `server.extend.resolveUrl` hand-off. Populated by [resolveUrl] the
     * moment a URL is about to be returned to a client. Cleared by
     * every listener that detects the URL is stale (cancel, vanish,
     * AccelByte session deletion, etc.). Tests inject a fresh
     * instance via this public-typed setter to keep test cases
     * isolated; production code uses the default singleton.
     *
     * Kept `internal` so unit tests in the same module can swap the
     * instance, but no other module needs this surface.
     */
    internal var urlHandoverRegistry: UrlHandoverRegistry = UrlHandoverRegistry()

    /**
     * Resolves the set of sessionIds owned by [playerId] for the
     * vanish-path staleness listener. A player "owns" a sessionId if
     * the GameSessionStatus stored in [gameSessions] for that
     * sessionId has a PlayerSessionBundle whose websocketId matches
     * [playerId] (or, in dev mode, whose accelByteId matches).
     *
     * Returns an empty set if no matching session is found, so the
     * listener's remove-loop is a safe no-op for the common case
     * where the disconnecting player never owned a session.
     *
     * The lookup is O(n) over [gameSessions] but n is bounded by the
     * number of in-flight matchmaking tickets (typically <100), so
     * the listener is cheap enough to run synchronously on every
     * SSE deregister.
     */
    suspend fun sessionIdsForPlayer(playerId: String): Set<String> = sessionMutex.withLock {
        val owned = mutableSetOf<String>()
        for ((sessionId, status) in gameSessions)
        {
            val matches = status.players.any { bundle ->
                bundle.websocketId == playerId || bundle.accelByteId == playerId
            }
            if (matches)
            {
                owned.add(sessionId)
            }
        }
        owned
    }

    /**
     * Test-only accessor for [gameSessions] keys. Used by tests that
     * need to discover the sessionId a previous requestGame assigned
     * (since `requestGame` returns `Boolean` and does not surface the
     * sessionId to the caller). Returns a snapshot copy under the
     * existing [sessionMutex].
     */
    internal suspend fun gameSessionIds(): List<String> = sessionMutex.withLock {
        gameSessions.keys.toList()
    }

    /**
     * Test-only accessor for a single [GameSessionStatus] by
     * sessionId. Returns null if the sessionId is not in the map.
     * Returns a snapshot copy under the existing [sessionMutex].
     * Used by tests that need to inspect the players list of a
     * specific session to disambiguate entries in a singleton that
     * may have been populated by earlier tests in the same JVM run.
     */
    internal suspend fun gameSessionStatus(sessionId: String): GameSessionStatus? = sessionMutex.withLock {
        gameSessions[sessionId]
    }

    /**
     * Single iteration of the AccelByte session staleness poller. For
     * every sessionId in [registry], calls
     * [net.accelbyte.sdk.api.session.wrappers.GameSession.getGameSession];
     * if the platform returns 404 (the session was deleted by an
     * admin, all players left, or any other implicit close path), the
     * entry is removed. Any other error (network, transient 5xx) is
     * logged and skipped — the next iteration will retry.
     *
     * Made `internal` so the unit test in
     * `matchmaking.UrlHandoverRegistryPollerTest` can drive a single
     * iteration without spinning up the full 30-second periodic loop.
     * The periodic loop lives in
     * [org.ttt.autogenesis.serverextend.ServerExtend.main].
     *
     * Empty registries short-circuit the SDK call to avoid wasted
     * round-trips on a quiet server.
     */
    internal suspend fun pollAccelByteSessionsForStaleness(registry: UrlHandoverRegistry)
    {
        val sessionIds = registry.allKeys()
        if (sessionIds.isEmpty())
        {
            return
        }
        for (sessionId in sessionIds)
        {
            try
            {
                val op = net.accelbyte.sdk.api.session.operations.game_session.GetGameSession.builder()
                    .namespace(AccelByteConfig.getNamespace())
                    .sessionId(sessionId)
                    .build()
                gameSessionWrapper.getGameSession(op)
            }
            catch (err: net.accelbyte.sdk.core.HttpResponseException)
            {
                if (err.httpCode == 404)
                {
                    Logger.info(
                        LogCategory.NETWORK,
                        "ServerConnector: AccelByte session $sessionId no longer exists, removing URL handover"
                    )
                    registry.remove(sessionId)
                }
                else
                {
                    Logger.warn(
                        LogCategory.NETWORK,
                        "ServerConnector: AccelByte session $sessionId poll failed with httpCode=${err.httpCode}: ${err.message}"
                    )
                }
            }
            catch (err: Throwable)
            {
                Logger.warn(
                    LogCategory.NETWORK,
                    "ServerConnector: AccelByte session $sessionId poll failed: ${err.message}"
                )
            }
        }
    }

    private const val GAME_SERVER_STATUS_METHOD = "server.setGameMode"

    @JvmField
    internal var MATCHMAKING_TIMEOUT_MS: Long = 180_000L
    @JvmField
    internal var MATCHMAKING_POLL_INTERVAL_MS: Long = 3_000L
    private const val MATCHMAKING_MIN_CAPACITY = 2

    /**
     * Match pool name used by the live-mode resume flow (Phase C). The
     * matchmaker routes tickets in this pool to a free DS that rehydrates
     * the saved snapshot via the resume fields on setGameMode. Single-player
     * only (1 human + 0..N AI per the request).
     */
    private const val SINGLEPLAYER_RESUME_POOL = "singleplayer-resume"


//===========================================RPC Functions==============================================================

    /**
     * Rpc function to manage game requests. Works in both dev mode, and prod mode. In dev mode it will directly
     * query the local server packaged in the Electron runtime bundle and issue a game type signal and request ready
     * status. In prod mode it will kick off the matchmaking and game session sequence.
     */
    @RpcMethod("server.extend.requestGame", RpcDirection.SERVER)
    suspend fun requestGame(context: RpcCallContext, request: GameRequest) : Boolean
    {
        Logger.info(LogCategory.NETWORK, "ServerConnector: requestGame entry for ${request.userName} (aiOpponents=${request.aiOpponentCount}, aiOnly=${request.aiOnly})")
        /**
         * If in dev mode, bypass accelbyte, spoof requirements, and leapfrog to getting the local server into stage
         * 2 of it's bootup sequence.
         */
        if(ExtendConfig.debugMode)
        {
            Logger.debug(LogCategory.NETWORK, "ServerConnector: Dev mode active, constructing session status")
            /**
             * Dynamically construct our session status now. Because we aren't waiting on any external services from
             * AccelByte to start kicking things off we can make several assumptions about dev mode. Mainly that it
             * is always locally wrapped in a local dev runtime, or in Electron for investor demos, and that we know
             * exactly where the game server is, and that it is always running at localhost if this service is
             * running and has received this rpc call.
             */
            val aiCount = request.aiOpponentCount.coerceAtLeast(0)
            val humanCount = if(request.aiOnly) 0 else 1
            val devSession = GameSessionStatus().apply {
                aiOpponentCount = aiCount
                aiOnly = request.aiOnly

                maxPlayers = humanCount + aiCount
                currentPlayers = maxPlayers
                isFull = true
                serverUrl = "127.0.0.1:9080"
                gameType = request.gameType

                val connectionId = request.websocketId.takeIf { it.isNotBlank() } ?: context.connectionId
                val newPlayerBundle = PlayerSessionBundle(
                    accelByteUserName = request.userName,
                    accelByteId = request.accelByteId,
                    websocketId = connectionId,
                    commander = request.selectedCommander,
                    playerAlias = request.playerAlias.ifBlank { request.accelByteId }
                )

                players.add(newPlayerBundle)
                sessionId = UUID.randomUUID().toString() //Irrelevant because accelbyte is not active at this time.
            }

            Logger.info(LogCategory.NETWORK, "ServerConnector: Session status constructed for ${devSession.sessionId}")
            Logger.debug(LogCategory.NETWORK, "ServerConnector: Session details: players=${devSession.players.size}, server=${devSession.serverUrl}, aiCount=${devSession.aiOpponentCount}")

            Logger.info(LogCategory.NETWORK, "ServerConnector: Notifying game server at ${devSession.serverUrl}...")
            //Contact ams or local server and configure it for the requested game type.
            val acknowledged = notifyGameServer(devSession)
            Logger.info(LogCategory.NETWORK, "ServerConnector: Game server notification result: $acknowledged")

            // Make the session official if we got a response back. Mainly
            // a hack to get things going in dev mode.
            //
            // INVARIANT (pinned by
            // ServerConnectorRequestResumeTest.`dev mode requestResume
            // with failing notifyGameServer leaves registry empty for
            // the new sessionId`): when notifyGameServer returns false
            // the dev session is NOT written to gameSessions, so
            // resolveUrl is never called for this sessionId, so the
            // UrlHandoverRegistry is never written either. A future
            // refactor that pre-writes the registry before the
            // notifyGameServer ack would break this — a stale entry
            // would live forever (vanish listener has no sessionId
            // to match on, AGS poller would not find the session on
            // the platform). The "write only after DS ack" invariant
            // MUST hold.
            if(acknowledged)
            {
                gameSessions[devSession.sessionId] = devSession
                Logger.debug(LogCategory.NETWORK, "ServerConnector: Session ${devSession.sessionId} registered")
            }
            else
            {
                Logger.warn(LogCategory.NETWORK, "ServerConnector: notifyGameServer did not ack dev session, registry untouched")
            }

            //Exit true, allowing the client to make the next request.
            return acknowledged
        }

        Logger.info(LogCategory.NETWORK, "ServerConnector: Live mode — executing matchmaking for ${request.userName}")
        val matchPool = request.matchPool?.takeIf { it.isNotBlank() } ?: ladder.entry.name
        val gameTicket = executeLiveMatchmaking(matchPool, request.accelByteId, request.userName, request.aiOpponentCount, request.aiOnly, request.gameType, request.selectedCommander, request.websocketId, request.playerAlias, context.connectionId)

        if (gameTicket.serverUrl.isBlank())
        {
            Logger.error(LogCategory.NETWORK, "ServerConnector: Live matchmaking failed for ${request.userName}")
            return false
        }

        Logger.info(LogCategory.NETWORK, "ServerConnector: Match found, session=${gameTicket.sessionId}, server=${gameTicket.serverUrl}")

        val liveSession = GameSessionStatus().apply {
            sessionId = gameTicket.sessionId
            serverUrl = gameTicket.serverUrl
            gameType = request.gameType
            aiOpponentCount = request.aiOpponentCount.coerceAtLeast(0)
            aiOnly = request.aiOnly

            val humanCount = if(request.aiOnly) 0 else 1
            maxPlayers = humanCount + aiOpponentCount
            currentPlayers = maxPlayers
            isFull = true

            val connectionId = request.websocketId.takeIf { it.isNotBlank() } ?: context.connectionId
            val playerBundle = PlayerSessionBundle(
                accelByteUserName = request.userName,
                accelByteId = request.accelByteId,
                websocketId = connectionId,
                commander = request.selectedCommander,
                playerAlias = request.playerAlias
            )
            players.add(playerBundle)
        }

        // Make the session official if we got a response back. The
        // live mode `notifyGameServer` returns false on DS handshake
        // failure; in that case we do NOT write to gameSessions, so
        // resolveUrl is never called for this sessionId, so the
        // UrlHandoverRegistry is never written either — same
        // invariant as dev mode (pinned by
        // ServerConnectorRequestResumeTest.`dev mode requestResume
        // with failing notifyGameServer leaves registry empty for
        // the new sessionId`).
        val acknowledged = notifyGameServer(liveSession)
        if (acknowledged)
        {
            gameSessions[liveSession.sessionId] = liveSession
            Logger.info(LogCategory.NETWORK, "ServerConnector: Live session ${liveSession.sessionId} registered")
        }
        else
        {
            Logger.warn(LogCategory.NETWORK, "ServerConnector: Game server did not acknowledge live session, registry untouched")
        }

        return acknowledged
    }

    /**
     * Invoke the matchmaking system and start the process to find a game server to connect to. If in local dev mode
     * it will just return the url of the local game server and exit.
     */
    @RpcMethod("server.extend.invokeMatchMaking")
    suspend fun invokeMatchMaking(context: RpcCallContext, request: GameRequest) : GameTicket
    {
        Logger.info(LogCategory.NETWORK, "ServerConnector: invokeMatchMaking entry for ${request.userName} (matchPool=${request.matchPool})")
        //Return default dev url if in dev mode.
        if(ExtendConfig.debugMode)
        {
            val newGameTicket = GameTicket("", "127.0.0.1:9080")
            Logger.debug(LogCategory.NETWORK, "ServerConnector: Dev mode - returning fixed game ticket")
            return newGameTicket
        }


        Logger.info(LogCategory.NETWORK, "ServerConnector: Live mode - executing matchmaking")
        val pool = request.matchPool?.takeIf { it.isNotBlank() } ?: ladder.entry.name
        return executeLiveMatchmaking(
            matchPool = pool,
            accelByteId = request.accelByteId,
            userName = request.userName,
            aiOpponentCount = request.aiOpponentCount,
            aiOnly = request.aiOnly,
            gameType = request.gameType,
            selectedCommander = request.selectedCommander,
            websocketId = request.websocketId,
            playerAlias = request.playerAlias,
            fallbackConnectionId = context.connectionId
        )
    }

    /**
     * Resume a saved running-game for the calling player. Phase C of the
     * resume-game-architecture plan.
     *
     * Dev mode: same as requestGame but the constructed GameSessionStatus
     * carries `resumeFromVfs = true` and `resumeUserId = <accelByteId>`.
     * The local DS (already running at 127.0.0.1:9080) inspects those
     * fields in its `server.setGameMode` handler (see GameInit.kt) and
     * rehydrates the saved snapshot via the existing
     * `GameRestoreRpcHandlers.restoreRunningGameForUser` path.
     *
     * Live mode: builds a single-player match2 ticket tagged `resume: true`
     * (via a dedicated match pool `singleplayer-resume`). The matchmaker
     * routes to a free DS; the DS inspects the resume fields on its
     * incoming setGameMode payload and rehydrates.
     */
    @RpcMethod("server.extend.requestResume", RpcDirection.SERVER)
    suspend fun requestResume(context: RpcCallContext, request: GameRequest) : GameTicket
    {
        val accelByteId = request.accelByteId
        if (accelByteId.isBlank())
        {
            Logger.warn(LogCategory.NETWORK, "ServerConnector.requestResume: empty accelByteId, cannot resume")
            return GameTicket("", "")
        }
        Logger.info(LogCategory.NETWORK, "ServerConnector: requestResume entry for user=$accelByteId liveMode=${ExtendConfig.resolveLiveMode()}")

        if (ExtendConfig.debugMode)
        {
            val devSession = GameSessionStatus().apply {
                aiOpponentCount = request.aiOpponentCount.coerceAtLeast(0)
                aiOnly = request.aiOnly
                maxPlayers = 1 + aiOpponentCount
                currentPlayers = maxPlayers
                isFull = true
                serverUrl = "127.0.0.1:9080"
                gameType = structs.matchmaking.GameType.SINGLEPLAYER
                resumeFromVfs = true
                resumeUserId = accelByteId
                val connectionId = request.websocketId.takeIf { it.isNotBlank() } ?: context.connectionId
                val playerBundle = PlayerSessionBundle(
                    accelByteUserName = request.userName,
                    accelByteId = accelByteId,
                    websocketId = connectionId,
                    commander = request.selectedCommander,
                    playerAlias = request.playerAlias.ifBlank { accelByteId }
                )
                players.add(playerBundle)
                sessionId = java.util.UUID.randomUUID().toString()
            }
            Logger.info(LogCategory.NETWORK, "ServerConnector: requestResume dev mode — session ${devSession.sessionId} resumeFromVfs=true resumeUser=$accelByteId")
            val acknowledged = notifyGameServer(devSession)
            if (acknowledged)
            {
                gameSessions[devSession.sessionId] = devSession
                Logger.info(LogCategory.NETWORK, "ServerConnector: requestResume dev session ${devSession.sessionId} registered")
                return GameTicket(devSession.sessionId, devSession.serverUrl, matchmakingStarted = false)
            }
            // INVARIANT (pinned by
            // ServerConnectorRequestResumeTest.`dev mode requestResume
            // with failing notifyGameServer leaves registry empty for
            // the new sessionId`): on DS handshake failure the dev
            // session is NOT written to gameSessions, so the
            // UrlHandoverRegistry is never written for this sessionId.
            // We log the failure and return an empty ticket so the
            // client can show "resume failed".
            Logger.warn(LogCategory.NETWORK, "ServerConnector: requestResume dev mode — notifyGameServer did not acknowledge for user=$accelByteId")
            return GameTicket("", "")
        }

        // Live mode: build a single-player match2 ticket via the dedicated
        // resume pool. The matchmaker routes the ticket to a free DS; the DS
        // inspects the resume fields and rehydrates from VFS.
        val gameTicket = executeLiveMatchmaking(
            matchPool = SINGLEPLAYER_RESUME_POOL,
            accelByteId = accelByteId,
            userName = request.userName,
            aiOpponentCount = 0,
            aiOnly = false,
            gameType = structs.matchmaking.GameType.SINGLEPLAYER,
            selectedCommander = request.selectedCommander,
            websocketId = request.websocketId,
            playerAlias = request.playerAlias,
            fallbackConnectionId = context.connectionId,
            resumeFromVfs = true,
            resumeUserId = accelByteId
        )
        if (gameTicket.serverUrl.isBlank())
        {
            Logger.error(LogCategory.NETWORK, "ServerConnector.requestResume: live matchmaking failed for user=$accelByteId")
            return GameTicket("", "")
        }
        // executeLiveMatchmaking already stores a GameSessionStatus in
        // gameSessions[matchId] with resumeFromVfs=true and resumeUserId set
        // (it forwards those fields into the resolveGameSession call). Use
        // the stored status to send server.setGameMode to the DS so it
        // rehydrates the saved snapshot.
        val liveSession = gameSessions[gameTicket.sessionId]
        if (liveSession == null)
        {
            Logger.error(LogCategory.NETWORK, "ServerConnector.requestResume: no liveSession registered for sessionId=${gameTicket.sessionId}")
            return GameTicket("", "")
        }
        val acknowledged = notifyGameServer(liveSession)
        if (!acknowledged)
        {
            Logger.warn(LogCategory.NETWORK, "ServerConnector.requestResume: notifyGameServer did not acknowledge for user=$accelByteId")
            return GameTicket("", "")
        }
        Logger.info(LogCategory.NETWORK, "ServerConnector.requestResume: live match found session=${gameTicket.sessionId} server=${gameTicket.serverUrl}")
        return gameTicket
    }

    /**
     * Executes live matchmaking via AccelByte match2 service.
     *
     * The ticket is created in the entry tier of the [ladder] (default `pvp-4`). A
     * [PromotionLadder] coroutine watches the ticket and recreates it in the next
     * pool after [MatchPoolSpec.holdSeconds] elapses. The polling loop here is
     * unchanged: it returns as soon as the matchmaker reports a match, or the
     * outer `MATCHMAKING_TIMEOUT_MS` elapses.
     *
     * @param matchPool The match pool to join. The promotion ladder ignores this
     *                  initial value — it always starts in [MatchmakingLadder.entry].
     * @return GameTicket containing sessionId and serverUrl, or empty values on failure.
     */
    internal suspend fun executeLiveMatchmaking(
        matchPool: String,
        accelByteId: String = "",
        userName: String = "",
        aiOpponentCount: Int = 0,
        aiOnly: Boolean = false,
        gameType: GameType = GameType.MULTIPLAYER,
        selectedCommander: structs.Commander? = null,
        websocketId: String = "",
        playerAlias: String = "",
        fallbackConnectionId: String = "",
        resumeFromVfs: Boolean = false,
        resumeUserId: String = ""
    ): GameTicket
    {
        val namespace = AccelByteConfig.getNamespace()
        val entryPool = ladder.entry.name
        Logger.info(LogCategory.NETWORK, "ServerConnector: creating match ticket in entry pool=$entryPool (requestedPool=$matchPool)")

        // Build the cost-attribute map. Failures fall back to FREE / 0 / 0 / false.
        val costAttributes: Map<String, JsonElement> = if(accelByteId.isNotBlank())
        {
            runCatching { accountSettingsLookup.attributesFor(accelByteId) }
                .getOrElse { emptyMap() }
        }
        else
        {
            emptyMap()
        }

        // Merge cost attributes with any caller-provided attributes. Callers (i.e., tests
        // injecting the request directly) may pre-populate the map; production callers
        // leave it empty and rely on the lookup above.
        val ticketAttributes: Map<String, Any?> = buildMap {
            putAll(costAttributes)
        }

        val ticketLatencies: Map<String, Int> = emptyMap()

        // 1. Create the initial ticket in the entry pool.
        val ticketRequestBody = ApiMatchTicketRequest.builder()
            .matchPool(entryPool)
            .attributes(ticketAttributes)
            .latencies(ticketLatencies)
            .build()
        val createOp = CreateMatchTicket.builder()
            .body(ticketRequestBody)
            .namespace(namespace)
            .build()

        var currentTicketId = try
        {
            matchTicketsWrapper.createMatchTicket(createOp).matchTicketID
        }
        catch(err: Throwable)
        {
            Logger.error(LogCategory.NETWORK, "ServerConnector: Failed to create match ticket: ${err.message}")
            return GameTicket("", "")
        }
        Logger.info(LogCategory.NETWORK, "ServerConnector: Match ticket created ticketId=$currentTicketId pool=$entryPool")

        // 2. Kick off the promotion ladder coroutine. It runs on a per-ticket scope
        //    so cancellation when the polling loop returns is local and clean.
        val promotionScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val promotion = PromotionLadder(
            scope = promotionScope,
            ladder = ladder,
            matchTickets = MatchTicketsApiBridge(matchTicketsWrapper),
            namespace = namespace,
            attributes = ticketAttributes,
            latencies = ticketLatencies
        )
        promotion.latestTicketId = currentTicketId
        promotion.start()
        try
        {
            // 3. Poll for the match. The promotion coroutine recreates the ticket
            //    in the next pool if the current tier's hold window elapses.
            val deadline = System.currentTimeMillis() + MATCHMAKING_TIMEOUT_MS
            while(System.currentTimeMillis() < deadline)
            {
                if(promotion.isExhausted)
                {
                    Logger.warn(LogCategory.NETWORK, "ServerConnector: promotion ladder exhausted, aborting")
                    return GameTicket("", "")
                }

                val activeTicketId = promotion.latestTicketId
                val detailsOp = MatchTicketDetails.builder()
                    .namespace(namespace)
                    .ticketid(activeTicketId)
                    .build()
                val details = try
                {
                    matchTicketsWrapper.matchTicketDetails(detailsOp)
                }
                catch(err: Throwable)
                {
                    Logger.warn(LogCategory.NETWORK, "ServerConnector: Poll failed for ticketId=$activeTicketId: ${err.message}, continuing...")
                    delay(MATCHMAKING_POLL_INTERVAL_MS)
                    continue
                }

                Logger.debug(LogCategory.NETWORK, "ServerConnector: Ticket matchFound=${details.matchFound} pool=${promotion.latestPoolName}")

                if(details.matchFound)
                {
                    promotion.markMatched()
                    val matchId = details.sessionID
                    if(matchId == null)
                    {
                        Logger.warn(LogCategory.NETWORK, "ServerConnector: Match found but no sessionID in response")
                        return GameTicket("", "")
                    }
                    Logger.info(LogCategory.NETWORK, "ServerConnector: Match found matchId=$matchId pool=${promotion.latestPoolName}")

                    val ticket = resolveGameSession(matchId, accelByteId, userName, aiOpponentCount, aiOnly, gameType, selectedCommander, websocketId, playerAlias, fallbackConnectionId, resumeFromVfs, resumeUserId)
                    return ticket
                }
                else if(!details.isActive)
                {
                    Logger.info(LogCategory.NETWORK, "ServerConnector: Ticket is no longer active, aborting")
                    return GameTicket("", "")
                }

                delay(MATCHMAKING_POLL_INTERVAL_MS)
            }

            // Timeout - cancel the ticket and the promotion coroutine.
            Logger.warn(LogCategory.NETWORK, "ServerConnector: Matchmaking timed out, cancelling ticket")
            try
            {
                val cancelOp = DeleteMatchTicket.builder()
                    .namespace(namespace)
                    .ticketid(promotion.latestTicketId)
                    .build()
                matchTicketsWrapper.deleteMatchTicket(cancelOp)
            }
            catch(_: Exception) { }
            // Belt-and-suspenders: if a future change ever captures
            // the URL into the registry before resolveUrl is called,
            // the timeout path must clear it. Today the blank
            // sessionId returned below means the registry's put()
            // was never invoked with this key, so this remove is a
            // no-op. See
            // `.hermes/plans/2026-06-28_090808-server-extend-url-handover-registry.md`
            // (the listener set).
            urlHandoverRegistry.remove("")
            return GameTicket("", "")
        }
        finally
        {
            promotion.cancel()
            promotionScope.cancel()
        }
    }

    /**
     * Resolves the game session details for [matchId] and forwards the synthesized
     * [GameSessionStatus] to the local session cache. The arrival-gate bind
     * happens on the DS side when `GameInit.defineGameRules` receives this
     * status via `server.setGameMode` (see [notifyGameServer]).
     */
    private suspend fun resolveGameSession(
        matchId: String,
        accelByteId: String,
        userName: String,
        aiOpponentCount: Int,
        aiOnly: Boolean,
        gameType: GameType,
        selectedCommander: structs.Commander?,
        websocketId: String,
        playerAlias: String,
        fallbackConnectionId: String,
        resumeFromVfs: Boolean = false,
        resumeUserId: String = ""
    ): GameTicket
    {
        val namespace = AccelByteConfig.getNamespace()
        val sessionOp = GetGameSession.builder()
            .namespace(namespace)
            .sessionId(matchId)
            .build()
        val session = try
        {
            gameSessionWrapper.getGameSession(sessionOp)
        }
        catch(err: Throwable)
        {
            Logger.warn(LogCategory.NETWORK, "ServerConnector: getGameSession failed for matchId=$matchId: ${err.message}")
            return GameTicket("", "")
        }

        val dsInfo = session.dsInformation
        if(dsInfo == null)
        {
            Logger.warn(LogCategory.NETWORK, "ServerConnector: No dsInformation in game session")
            return GameTicket("", "")
        }
        val server = dsInfo.server
        if(server == null)
        {
            Logger.warn(LogCategory.NETWORK, "ServerConnector: No server in dsInformation")
            return GameTicket("", "")
        }
        val serverIp = server.ip
        if(serverIp == null)
        {
            Logger.warn(LogCategory.NETWORK, "ServerConnector: No IP in server")
            return GameTicket("", "")
        }
        val port = server.port ?: 9080
        val serverUrl = "$serverIp:$port"
        Logger.info(LogCategory.NETWORK, "ServerConnector: Game server resolved to $serverUrl")

        val maxPlayers = session.configuration.maxPlayers
        val members = session.members ?: emptyList()
        val currentPlayers = members.size

        val playerBundles = members.mapNotNull { member: ApimodelsUserResponse ->
            val userId = member.id
            if(userId.isNullOrBlank())
            {
                Logger.warn(LogCategory.NETWORK, "ServerConnector: Member has no id (AccelByte user ID), skipping")
                null
            }
            else
            {
                PlayerSessionBundle(
                    accelByteUserName = userId,
                    accelByteId = userId,
                    websocketId = "",
                    commander = null
                )
            }
        }

        if(playerBundles.isEmpty() && currentPlayers > 0)
        {
            Logger.warn(LogCategory.NETWORK, "ServerConnector: Match found but no player bundles extracted from session members")
        }

        val gameSession = GameSessionStatus(
            sessionId = matchId,
            serverUrl = serverUrl,
            maxPlayers = maxPlayers,
            currentPlayers = currentPlayers,
            players = playerBundles.toMutableList(),
            isFull = currentPlayers >= maxPlayers,
            isStarted = false,
            gameType = GameType.MULTIPLAYER,
            aiOpponentCount = 0,
            aiOnly = false,
            resumeFromVfs = resumeFromVfs,
            resumeUserId = resumeUserId
        )
        sessionMutex.withLock { gameSessions[matchId] = gameSession }
        Logger.info(LogCategory.NETWORK, "ServerConnector: Game session registered sessionId=$matchId ($currentPlayers/$maxPlayers players)")

        Logger.info(
            LogCategory.NETWORK,
            "ServerConnector: Local session cache updated for sessionId=$matchId — DS will bind arrival gate on setGameMode"
        )

        // Touch the request-context parameters so the compiler does not warn about
        // unused parameters on the simplified resolve path. Production callers
        // receive the resulting ticket and the game session already carries
        // every member the matchmaker selected.
        @Suppress("UNUSED_VARIABLE")
        val _unused = listOf(accelByteId, userName, aiOpponentCount, aiOnly, gameType, selectedCommander, websocketId, playerAlias, fallbackConnectionId)

        return GameTicket(matchId, serverUrl, matchmakingStarted = true)
    }

    /**
     * Query function to allow the client to check if the session is ready for a url to be sent.
     */
    @RpcMethod("server.extend.isServerReady", RpcDirection.SERVER)
    suspend fun isServerReady(context: RpcCallContext, ticket: GameTicket) : GameTicketStatus
    {
        val sessionId = ticket.sessionId
        val session = gameSessions[sessionId] ?: return GameTicketStatus(isReady = false, isCancelled = true)
        return GameTicketStatus(isReady = session.serverUrl.isNotEmpty())
    }

    /**
     * Query function to get the server url for the game session. Intended only to be called by the game client
     * after [isServerReady] has returned true. Any other case will result in an empty string which the client
     * will also treat as matchmaking having been cancelled.
     */
    @RpcMethod("server.extend.resolveUrl", RpcDirection.SERVER)
    suspend fun resolveUrl(context: RpcCallContext, ticket: GameTicket) : String
    {
        val sessionId = ticket.sessionId
        val session = gameSessions[sessionId] ?: return ""
        val url = session.serverUrl
        if (url.isNotBlank())
        {
            // Capture the URL the moment we are about to hand it back
            // to a client. This is the registry's "I am about to
            // hand this out" event. Every staleness listener wired
            // elsewhere in this file and in ServerExtend.kt MUST
            // call urlHandoverRegistry.remove(sessionId) when it
            // detects that this URL is no longer reachable. See
            // `.hermes/plans/2026-06-28_090808-server-extend-url-handover-registry.md`.
            urlHandoverRegistry.put(sessionId, url)
        }
        return url
    }

    /**
     * Sends a [GameSessionStatus] payload to the resolved game server's `/events` WebSocket via
     * the `server.setGameMode` RPC. Made `internal` (not `private`) so that integration tests in
     * the same module can spy on it via `mockkObject(ServerConnector)`.
     */
    internal suspend fun notifyGameServer(session: GameSessionStatus) : Boolean
    {
        val baseUrl = formatWebSocketBaseUrl(session.serverUrl)
        val connectorPlayerId = "server-extend-client"
        Logger.debug(LogCategory.NETWORK, "ServerConnector.notifyGameServer: Connecting to game server at $baseUrl as playerId=$connectorPlayerId")
        val registry = RpcRegistry(RpcDirection.CLIENT).also { registerRpcSystem() }
        val client = WebSocketRpcClient(
            WebSocketRpcClientConfig(
                baseUrl = baseUrl,
                playerId = connectorPlayerId,
                // CRITICAL: server-extend's WS to the main game server
                // MUST register as CONTROLLER. If it registers as
                // PRIMARY (the default), the main server's
                // hasAnyPrimarySession() always returns true while
                // server-extend is alive, which blocks the
                // snapshot-on-disconnect path in single-player mode
                // (see server/src/.../Server.kt onDisconnected block —
                // the snapshot is gated on !hasAnyPrimarySession).
                // See sharedModel/.../WebSocketRpcClientConfig.kt for
                // the role= URL parameter contract.
                role = WebSocketRpcClientConfig.Role.CONTROLLER
            ),
            registry
        )

        val readySignal = CompletableDeferred<Unit>()
        client.onConnected {
            Logger.info(LogCategory.NETWORK, "ServerConnector.notifyGameServer: WebSocket connected to game server for playerId=$connectorPlayerId")
            if(!readySignal.isCompleted) readySignal.complete(Unit)
        }

        return try
        {
            client.connect()
            val connected = withTimeoutOrNull(5_000) { readySignal.await() } != null
            if(!connected)
            {
                Logger.warn(LogCategory.NETWORK, "ServerConnector.notifyGameServer: Timed out waiting for dev game server WebSocket")
                return false
            }

            val payload = RpcJson.encodeToJsonElement(GameSessionStatus.serializer(), session)
            Logger.info(LogCategory.NETWORK, "ServerConnector.notifyGameServer: Invoking server.setGameMode RPC...")
            val response = client.rpcInvoker.invoke(GAME_SERVER_STATUS_METHOD, payload)

            val rpcError = response.error
            if (rpcError != null)
            {
                Logger.error(LogCategory.NETWORK, "ServerConnector.notifyGameServer: RPC error from game server: ${rpcError.message} (code: ${rpcError.code})")
                return false
            }

            val result = response.result?.let {
                try {
                    val decoded = RpcJson.decodeFromJsonElement(kotlinx.serialization.serializer<kotlin.Boolean>(), it)
                    Logger.debug(LogCategory.NETWORK, "ServerConnector.notifyGameServer: server.setGameMode returned $decoded")
                    decoded
                } catch (e: Exception) {
                    Logger.error(LogCategory.NETWORK, "ServerConnector.notifyGameServer: Failed to decode result: $it")
                    false
                }
            } ?: false

            result
        }
        catch(err: Throwable)
        {
            Logger.error(LogCategory.NETWORK, "ServerConnector.notifyGameServer: Failed to notify dev game server: ${err.message}")
            false
        }
        finally
        {
            Logger.debug(LogCategory.NETWORK, "ServerConnector.notifyGameServer: Delaying before closing WebSocket client...")
            kotlinx.coroutines.delay(500)
            Logger.debug(LogCategory.NETWORK, "ServerConnector.notifyGameServer: Closing WebSocket client")
            client.close()
        }
    }

    private fun formatWebSocketBaseUrl(serverUrl: String) : String
    {
        return when
        {
            serverUrl.startsWith("ws://") || serverUrl.startsWith("wss://") -> serverUrl
            serverUrl.isBlank() -> "ws://127.0.0.1:9080"
            else -> "ws://$serverUrl"
        }
    }
}

/**
 * Adapts the existing [MatchTickets] SDK wrapper to the narrower
 * [MatchTicketsApi] surface used by [PromotionLadder]. Lets the ladder stay
 * decoupled from the SDK so it can be unit-tested with a mock.
 */
private class MatchTicketsApiBridge(private val delegate: MatchTickets) : MatchTicketsApi
{
    override fun createMatchTicket(op: CreateMatchTicket): net.accelbyte.sdk.api.match2.models.ApiMatchTicketResponse
    {
        return delegate.createMatchTicket(op)
    }

    override fun deleteMatchTicket(op: DeleteMatchTicket)
    {
        delegate.deleteMatchTicket(op)
    }
}