package org.ttt.autogenesis.server

import org.ttt.autogenesis.server.config.AccelByteConfig
import org.ttt.autogenesis.server.config.ServerConfig
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import globals.AwsCredentialsBootstrap
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.LogPriority
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.*
import java.util.UUID
import org.ttt.autogenesis.server.vfs.VirtualFileSystemManager
import org.ttt.autogenesis.server.audio.AudioManager
import org.ttt.autogenesis.audio.AudioObject
import agent.runners.clearTraceDirectory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import accelbyte.dsm.DrainSignalHandler
import accelbyte.dsm.DsHubClient
import accelbyte.session.SessionStorageHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.system.exitProcess

private val rpcRegistry = run {
    registerRpcSystem()
    RpcRegistry(RpcDirection.SERVER)
}

/**
 * Main server entry point and application configuration.
 * Sets up both HTTP/WebSocket and gRPC servers for RPC communication.
 *
 * @param args Command line arguments passed to the server.
 */
fun main(args: Array<String>)
{
    Logger.configure(LogPriority.DEBUG, true, maxLogFiles = 1, serverType = "server")
    Logger.info(LogCategory.SYSTEM, "Server starting (args=${args.joinToString(",")})")

    // Clear trace directory on startup
    clearTraceDirectory()

    // Initialize AccelByte configuration from file or environment
    AccelByteConfig

    val drainSignalHandler = DrainSignalHandler(
        gameState.WorldManager,
        accelbyte.dsm.DSM
    )

    // Initialize DS Hub WebSocket client for AMS integration
    val dsId = AccelByteConfig.getDsId()
    if(dsId.isNotBlank())
    {
        DsHubClient.connect(dsId)
        Logger.info(LogCategory.NETWORK, "DS Hub client connecting with serverId=$dsId")

        // Register this dedicated server with the AccelByte DSM controller so AMS can route
        // matchmaking traffic to it. Safe to call when serverId is blank (skips with INFO log).
        accelbyte.dsm.DedicatedServerRegistration.start(
            serverId = dsId,
            region = AccelByteConfig.getRegion(),
            namespace = System.getenv("AB_NAMESPACE") ?: System.getProperty("AB_NAMESPACE") ?: ""
        )

        // Connect to the AMS watchdog WebSocket so AMS marks this DS as
        // ready in the fleet UI and routes claim traffic to it. The
        // watchdog URL is injected by AMS at fleet placement via the
        // `AB_WATCHDOG_URL` env var. Without this client the DS shows as
        // `provisioning` forever and never receives claim traffic.
        // Block-3 of feature/live-pvp-and-billing.
        accelbyte.ams.AmsWatchdogClient.connect()

        // Subscribe to server claimed events and bind session to WorldManager + TurnHarness
        DsHubClient.onServerClaimed.onEach { event ->
            Logger.info(LogCategory.NETWORK, "DS Hub: Server claimed for session=${event.sessionId}, gameMode=${event.gameMode}, expectedPlayers=${event.matchingAllies.size}")
            gameState.WorldManager.bindSession(event.sessionId, event.matchingAllies)
            org.ttt.autogenesis.server.TurnHarness.onSessionBound(event.sessionId, event.matchingAllies)
        }.launchIn(CoroutineScope(Dispatchers.Default))

        DsHubClient.onDrainSignal.onEach {
            Logger.info(LogCategory.SYSTEM, "Server: Drain signal detected, invoking DrainSignalHandler")
            accelbyte.dsm.DedicatedServerRegistration.drain("dshub_drain_signal")
            drainSignalHandler.handleDrainSignal()
        }.launchIn(CoroutineScope(Dispatchers.Default))

        // Wire backfill acceptance to TurnHarness expected players
        gameState.WorldManager.onBackfillAccepted { userId ->
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.Default) {
                org.ttt.autogenesis.server.TurnHarness.addExpectedPlayer(userId)
                Logger.info(LogCategory.NETWORK, "Server: Backfill candidate $userId added to TurnHarness expected players")
            }
        }

        // Wire match-end to session storage persistence. The handler is fire-and-forget
        // per SessionStorageHandler.writeSessionStorage's contract ("Write failures do NOT
        // block match end - the operation is fire-and-forget."), so we launch on a
        // dedicated Dispatchers.IO scope and never await the write from the gameplay
        // thread. Crash recovery at startup (readSessionStorage) is unchanged.
        gameState.WorldManager.onMatchEnded { event ->
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val sessionId = event.sessionId
                if (sessionId.isBlank()) {
                    Logger.warn(
                        LogCategory.SYSTEM,
                        "Server: onMatchEnded ignored — no active sessionId (outcome=${event.outcome})"
                    )
                    return@launch
                }
                val gameState = accelbyte.session.SessionStorageHandler.buildGameState(
                    matchOutcome = event.outcome,
                    winnerName = event.winnerName
                )
                val result = accelbyte.session.SessionStorageHandler.writeSessionStorage(
                    sessionId = sessionId,
                    gameState = gameState
                )
                if (result.isFailure) {
                    Logger.error(
                        LogCategory.SYSTEM,
                        "Server: session storage write failed for sessionId=$sessionId outcome=${event.outcome}: ${result.exceptionOrNull()?.message}"
                    )
                } else {
                    Logger.info(
                        LogCategory.SYSTEM,
                        "Server: session storage write succeeded for sessionId=$sessionId outcome=${event.outcome}"
                    )
                }
            }
        }

        // Register shutdown hook to disconnect from DS Hub
        Runtime.getRuntime().addShutdownHook(Thread {
            Logger.info(LogCategory.SYSTEM, "DS Hub: Shutdown hook firing, disconnecting...")
            accelbyte.dsm.DedicatedServerRegistration.stop()
            DsHubClient.disconnect()
            accelbyte.ams.AmsWatchdogClient.stopForTest()
        })
    }
    else
    {
        Logger.warn(LogCategory.NETWORK, "DS Hub: AB_DS_ID not configured, skipping DS Hub client initialization")
    }

    AwsCredentialsBootstrap.initialize()

    ServerConfig.args = args.toList()
    VirtualFileSystemManager.initialize(ServerConfig.args)

    if (gameState.WorldManager.activeSessionId.isNotEmpty() && gameState.WorldManager.activeSessionCount > 0)
    {
        Logger.info(
            LogCategory.SYSTEM,
            "Server: Crash recovery detected activeSessionId=${gameState.WorldManager.activeSessionId}, attempting to read session storage"
        )
        val recoveredState = SessionStorageHandler.readSessionStorage(gameState.WorldManager.activeSessionId)
            .getOrElse { e ->
                Logger.error(
                    LogCategory.SYSTEM,
                    "Server: Crash recovery read failed for session=${gameState.WorldManager.activeSessionId}, proceeding without recovered state: ${e.message}"
                )
                null
            }

        if (recoveredState != null) {
            Logger.info(
                LogCategory.SYSTEM,
                "Server: Crash recovery succeeded, game state recovered for session=${gameState.WorldManager.activeSessionId}"
            )
        }
    }

    GrpcServerConfig.host = ServerConfig.host
    GrpcServerConfig.port = ServerConfig.grpcPort
    val grpcServer = GrpcServer(GrpcServerConfig, rpcRegistry)
    grpcServer.start()
    Runtime.getRuntime().addShutdownHook(Thread {
        grpcServer.stop()
    })

    // Seed a default player so the client has an actor to play as
    if(gameState.WorldManager.world.activePlayers.isEmpty())
    {
        Logger.info(LogCategory.GENERAL, "Seeding default 'Player 1'")
        gameState.WorldManager.world.activePlayers.add(structs.Player(name = "Player 1"))
    }

    // Initialize turn timer hooks
    accounting.PromptManager.setupTimerHooks()

    embeddedServer(Netty, host = "0.0.0.0", port = ServerConfig.port) {
        serverModule()
    }.start(wait = true)
}

/**
 * Resolves which AccelByte user id (if any) the in-place auto-restore path
 * should rehydrate from when a WebSocket session connects.
 *
 * The auto-restore on connect (the `onConnected` callback registered in
 * [serverModule]) only fires when all of the following are true:
 *
 * 1. The session is a [SessionRole.PRIMARY] connection. CONTROLLER
 *    (Python/AI) connections are observers and never trigger a rehydrate.
 * 2. [gameState.WorldManager.isWorldEmpty] returns true (no turn has been
 *    played yet — otherwise the game in progress must NEVER be clobbered
 *    by a reconnect).
 * 3. A non-blank AccelByte user id can be resolved from one of:
 *    - [PlayerSession.accelbyteId] (preferred — the id carried on the WS
 *      query string by Fix 1 plumbing),
 *    - the [serverStructs.PlayerStats.accelByteUserId] of the
 *      [serverStructs.PlayerStats] already bound to this connection id,
 *      - or the [serverStructs.PlayerStats.accelByteUserId] of the
 *        [gameState.WorldManager.humanPlayerName] slot.
 *
 * Returns the resolved user id, or `null` when no auto-restore should be
 * attempted. The caller is responsible for actually invoking
 * [TurnHarness.restoreWorldFromUserRecord] on an IO dispatcher.
 *
 * This function is `internal` so it can be unit-tested directly without
 * standing up a Ktor server — see
 * `server/src/test/kotlin/org/ttt/autogenesis/server/ServerAutoRestoreAccelbyteIdTest.kt`.
 * Extracted from the inline body that previously lived in
 * [serverModule]'s `connectionCoordinator.onConnected { ... }` block.
 */
internal fun resolveAutoRestoreUserId(session: PlayerSession): String?
{
    // BUG 26 (2026-06-27): the client spec is "the game does not at all
    // ever auto-resume for any reason ever." Auto-restore is unconditional
    // OFF. The resume flow now happens exclusively via the explicit
    // server.restoreRunningGame RPC, which is invoked from
    // MainMenu.beginResumeSession after the user clicks Resume in
    // ResumeOrNewDialog.
    //
    // The previous version of this helper had three gating dimensions:
    //   1. PRIMARY role (CONTROLLER sessions are observer-only)
    //   2. Empty world (don't clobber an in-progress game)
    //   3. AUTOGENESIS_DISABLE_AUTO_RESTORE env var (dev escape hatch)
    // All three have been collapsed to a single "always null" return.
    // Tests at server/src/test/kotlin/org/ttt/autogenesis/server/AutoRestoreDisabledTest.kt
    // pin this invariant.
    Logger.info(
        LogCategory.SYSTEM,
        "Server: resolveAutoRestoreUserId: auto-restore SUPPRESSED (BUG 26 fix 2026-06-27 — " +
            "the game does not auto-resume for any reason; user must click Resume in the dialog). " +
            "session=${session.playerId} role=${session.role} worldEmpty=${gameState.WorldManager.isWorldEmpty()}"
    )
    return null
}

/**
 * Configures the Ktor application with WebSocket and REST endpoints.
 * Sets up RPC message handling and player connection management.
 */
fun Application.serverModule()
{
    install(ContentNegotiation) {
        json()
    }
    install(WebSockets)
    install(CORS) {
        anyHost()
        allowMethod(HttpMethod.Post)
        allowHeader(HttpHeaders.ContentType)
    }

    var shutdownJob: Job? = null

    val connectionManager = PlayerConnectionManager()
    ActionHistoryRpcHandlers.connectionManager = connectionManager
    UiSignalRpcHandlers.connectionManager = connectionManager

    // Web Push wiring: load VAPID keypair (no-op if missing — push stays
    // disabled). The store and service are nullable on UiSignalRpcHandlers
    // and TurnHarness skips the push trigger if either is null.
    val pushKeypair = org.ttt.autogenesis.server.push.PushVapidConfig.loadKeypair()
    val pushStore = org.ttt.autogenesis.server.push.PushSubscriptionStore()
    UiSignalRpcHandlers.pushSubscriptionStore = pushStore
    // Dev-only endpoint override: when AUTOGENESIS_DEV_PUSH_MOCK_PORT is set,
    // rewrite subscription endpoints to a local URL so e2e tests can
    // intercept pushes without going through FCM/Mozilla/APNs. The env var
    // is honored only when the JVM is running in dev mode (no production
    // exposure of this codepath is possible since the env var must be
    // explicitly set in the deployment environment).
    val devMockBase = System.getenv("AUTOGENESIS_DEV_PUSH_MOCK_PORT")?.takeIf { it.isNotBlank() }
        ?.let { "http://127.0.0.1:$it" }
    if (devMockBase != null)
    {
        Logger.info(LogCategory.SYSTEM, "Server: AUTOGENESIS_DEV_PUSH_MOCK_PORT set — push endpoints will be rewritten to $devMockBase (dev only)")
    }
    UiSignalRpcHandlers.pushNotificationService = if (pushKeypair != null)
    {
        org.ttt.autogenesis.server.push.PushNotificationService(pushStore, pushKeypair, devMockBase)
    }
    else
    {
        Logger.info(LogCategory.SYSTEM, "Server: VAPID keypair not loaded — push notifications disabled (run :kvisionApp:generateVapidKeys to provision)")
        null
    }

    // Surrender-disconnect bridge: when a single-player surrender ends the
    // match, TurnHarness.surrenderPlayer fires this hook with the
    // surrendering player's connectionId (the same key
    // PlayerConnectionManager uses internally). We look up the session and
    // call deregister, which triggers the onDisconnected callback below
    // and arms startSinglePlayerShutdownCountdown. Without this, the
    // surrender path leaves the player's WebSocket alive and the server
    // ticks CountdownTimer for ~28s before the socket finally drops from
    // the client side navigating away.
    //
    // The hook is only invoked when gameEnded && isSinglePlayer inside
    // surrenderPlayer itself (TurnHarness.kt ~L2395), so the multiplayer
    // surrender case is already filtered out — no extra gating needed here.
    //
    // The dispatch fires on Dispatchers.IO so the surrender coroutine isn't
    // blocked on deregister. Deregister failures are logged but not
    // surfaced — the worst case is the surrender path's previous behavior
    // (server keeps ticking, no shutdown timer).
    val onSurrenderDisconnect: suspend (String) -> Unit = label@ { connectionId ->
        val session = connectionManager.findSession(connectionId)
        if(session == null)
        {
            Logger.debug(
                LogCategory.NETWORK,
                "Server: surrender-disconnect: no session for $connectionId — already gone."
            )
            return@label
        }
        try
        {
            connectionManager.deregister(session)
        }
        catch(e: Exception)
        {
            Logger.warn(
                LogCategory.NETWORK,
                "Server: surrender-disconnect: deregister failed for $connectionId: ${e.message}"
            )
        }
    }
    TurnHarness.onPlayerDisconnectedFromSurrender = onSurrenderDisconnect
    val connectionCoordinator = ServerConnectionCoordinator(connectionManager).apply {
        onConnected { session ->
            Logger.info(LogCategory.NETWORK, "Server: Connection established for playerId=${session.playerId}")
            Logger.debug(LogCategory.NETWORK, "Server: onConnected invocation (playerId=${session.playerId}, sessionHash=${session.hashCode()})")
            updatePlayerConnectionStats(session.playerId, true)

            // Shutdown-timer logic is single-player-only. The server only auto-shuts-down
            // when the human player disconnects in single-player mode (see the
            // hasAnyPrimarySession() check in the disconnect handler below). Multiplayer
            // dedicated servers keep running regardless of connection churn.
            if(gameState.WorldManager.isSinglePlayer)
            {
                // Only cancel shutdown for PRIMARY (browser) connections.
                // CONTROLLER (Python/AI) connections do NOT prevent server shutdown.
                if(session.role == SessionRole.PRIMARY)
                {
                    shutdownJob?.cancel()
                    shutdownJob = null
                    Logger.info(LogCategory.SYSTEM, "Server: Shutdown timer cancelled due to PRIMARY connection.")
                }
                else
                {
                    Logger.debug(LogCategory.SYSTEM, "Server: CONTROLLER connection established — shutdown timer unchanged.")
                }
            }

            // BUG 26 (2026-06-27): auto-restore on connect is unconditionally
            // DISABLED. The previous implementation called
            // resolveAutoRestoreUserId here and, if non-null, launched a
            // Dispatchers.IO coroutine that called TurnHarness.restoreWorldFromUserRecord
            // synchronously via Deferred.await() before the initial-sync block.
            // The whole block has been removed. Resume now happens exclusively
            // via the explicit `server.restoreRunningGame` RPC, invoked from
            // MainMenu.beginResumeSession after the user clicks Resume in
            // ResumeOrNewDialog.
            //
            // The helper itself remains in the source for now (it's
            // unit-tested by AutoRestoreDisabledTest and logs a useful audit
            // line), but it always returns null.

            // Initial Sync for real players
            val stats = gameState.WorldManager.findPlayerStatsByConnectionId(session.playerId)
            if(stats != null)
            {
                Logger.debug(LogCategory.NETWORK, "Server: Identified connection ${session.playerId} as player '${stats.playerData.name}' (isNpc=${stats.isControlledByNpc})")
                if(!stats.isControlledByNpc)
                {
                    Logger.info(LogCategory.NETWORK, "Server: Human player ${stats.playerData.name} joined, triggering initial sync")
                    Logger.debug(LogCategory.NETWORK, "Server: Sending initial sync mapLoaded=${gameState.WorldManager.activeMapPackBytes != null} round=${gameState.WorldManager.world.roundNumber} historyEntries=${gameState.WorldManager.history.size}")
                    UiSignalRpcHandlers.sendInitialSync(
                        session.playerId,
                        stats.playerData,
                        gameState.WorldManager.activeMapPackBytes,
                        gameState.WorldManager.world,
                        gameState.WorldManager.history,
                        accelByteUserId = stats.accelByteUserId
                    )
                    
                    // Start Turn Harness if active but not running
                    if(gameState.WorldManager.isGameActive && !TurnHarness.isRunning())
                    {
                        Logger.info(LogCategory.SYSTEM, "Server: Starting Turn Harness loop for ${stats.playerData.name}")
                        TurnHarness.runNextTurn()
                    }
                }
            }
            else
            {
                Logger.warn(LogCategory.NETWORK, "Server: Connection ${session.playerId} did not match any registered player stats (registeredPlayers=${gameState.WorldManager.playerStats.map { it.playerData.name }})")
            }

            val handle = session.invoker.request(
                "client.pong",
                PingResponse(echo = session.playerId, timestamp = System.currentTimeMillis()),
                timeoutMillis = 5_000
            )
            try
            {
                handle.await()
            }
            catch(err: Throwable)
            {
                Logger.warn(LogCategory.NETWORK, "Failed to reach ${session.playerId} via ping: ${err.message}")
            }
        }
        onReconnected { session, _ ->
            updatePlayerConnectionStats(session.playerId, true)
            Logger.info(LogCategory.NETWORK, "Player ${session.playerId} reconnected")

            if(gameState.WorldManager.isSinglePlayer)
            {
                // Only cancel shutdown for PRIMARY (browser) reconnections.
                // CONTROLLER (Python/AI) reconnections do NOT prevent server shutdown.
                if(session.role == SessionRole.PRIMARY)
                {
                    shutdownJob?.cancel()
                    shutdownJob = null
                    Logger.info(LogCategory.SYSTEM, "Server: Shutdown timer cancelled due to PRIMARY reconnection.")
                }
                else
                {
                    Logger.debug(LogCategory.SYSTEM, "Server: CONTROLLER reconnection — shutdown timer unchanged.")
                }
            }
        }
        onDisconnected { playerId ->
            updatePlayerConnectionStats(playerId, false)
            Logger.info(LogCategory.NETWORK, "Player $playerId disconnected")

            if(gameState.WorldManager.isSinglePlayer)
            {
                val hasAnyPrimary = connectionManager.hasAnyPrimarySession()
                Logger.debug(LogCategory.SYSTEM, "Server: Disconnect check - hasAnyPrimarySession()=${hasAnyPrimary}")
                if(!hasAnyPrimary)
                {
                    // Capture the running game into the human player's account record so they
                    // can resume from the exact turn they left on after a reconnect (or a DS
                    // restart). Failure here is logged but does not block the shutdown timer.
                    val humanUserId = gameState.WorldManager.findPlayerFromStats(gameState.WorldManager.humanPlayerName)
                        ?.accelByteUserId
                        .orEmpty()
                    if (shouldPersistOnDisconnect(
                            humanAccelByteUserId = humanUserId,
                            isSinglePlayer = gameState.WorldManager.isSinglePlayer,
                            isGameActive = gameState.WorldManager.isGameActive,
                            humanPlayerHasJoinedOnce = gameState.WorldManager.humanPlayerHasJoinedOnce,
                            historySize = gameState.WorldManager.history.size
                        )
                    )
                    {
                        CoroutineScope(Dispatchers.IO).launch {
                            try
                            {
                                TurnHarness.serializeCurrentWorldSnapshotToUserRecord(humanUserId)
                            }
                            catch (e: Exception)
                            {
                                Logger.error(LogCategory.DATABASE, "Server: Failed to persist running-game on disconnect for user=$humanUserId: ${e.message}")
                            }
                        }
                    }
                    else
                    {
                        Logger.info(
                            LogCategory.DATABASE,
                            "Server: Skipped save-on-disconnect for user='$humanUserId' " +
                                "(humanPlayerHasJoinedOnce=${gameState.WorldManager.humanPlayerHasJoinedOnce}, " +
                                "isGameActive=${gameState.WorldManager.isGameActive}, " +
                                "humanUserIdBlank=${humanUserId.isBlank()}) " +
                                "— game was never joined by the human; resuming would produce a phantom round-1 snapshot."
                        )
                    }

                    // No PRIMARY sessions remain in single-player mode. The
                    // running-game snapshot was already kicked off above (async
                    // on Dispatchers.IO) so the player can resume from the exact
                    // turn they left on; arm the 15-second shutdown countdown
                    // now. The countdown re-checks for a PRIMARY at expiry so a
                    // reconnect during the grace window cancels the exit via the
                    // onConnected callback (see shutdownJob?.cancel() above).
                    //
                    // The countdown length is configurable for the resume-game
                    // dev workflow: in dev mode the developer needs the DS to
                    // stay alive long enough to reconnect from a fresh browser
                    // and resume the saved game (otherwise the 15-second window
                    // is shorter than the time it takes to switch tabs and log
                    // back in). Set AUTOGENESIS_SHUTDOWN_DELAY_MS=600000 in the
                    // dev environment, or pass it as a JVM property.
                    //
                    // NOTE: the defer-while-turn-in-progress logic that
                    // previously lived here was a regression. The predicate
                    // `TurnHarness.isRunning() || WorldManager.isGameActive`
                    // was always true once any turn had run, because
                    // `loopJob.isActive` stays true for the entire game
                    // lifetime (suspending on `deferred.await()` does NOT
                    // flip isActive to false) and `isGameActive` stays true
                    // from `runNextTurn()` until game-over. The defer branch
                    // therefore fired every disconnect and waited up to 10
                    // minutes instead of 15 seconds. See
                    // `ServerShutdownCountdownTest` for regression coverage.
                    val shutdownDelayMs = System.getProperty("AUTOGENESIS_SHUTDOWN_DELAY_MS")?.toLongOrNull()
                        ?: System.getenv("AUTOGENESIS_SHUTDOWN_DELAY_MS")?.toLongOrNull()
                        // Default is 60s (was 15s before BUG-2 fix 2026-06-25) — the 15s
                        // window was too short for the player to switch tabs and log
                        // back in. With 60s as the floor, even a server started without
                        // AUTOGENESIS_SHUTDOWN_DELAY_MS survives a normal user pause.
                        // Dev mode sets this to 600000 (10 minutes) via start_servers.sh.
                        ?: 60_000L
                    if (shutdownDelayMs != 60_000L) {
                        Logger.info(
                            LogCategory.SYSTEM,
                            "Server: using custom shutdown delay of ${shutdownDelayMs}ms " +
                                    "(override via AUTOGENESIS_SHUTDOWN_DELAY_MS)"
                        )
                    }
                    shutdownJob = startSinglePlayerShutdownCountdown(
                        connectionManager = connectionManager,
                        existingJob = shutdownJob,
                        delayMs = shutdownDelayMs,
                        onExpire = { exitProcess(0) }
                    )
                }
            }
        }
    }

    routing {
        get("/health") {
            // Block-4 of feature/live-pvp-and-billing. The main server
            // Dockerfile HEALTHCHECK (see `docs/LIVE_MODE.md:79-83`) probes
            // `curl -fsS http://127.0.0.1:9080/health`. Without this route
            // the curl probe 404s, the HEALTHCHECK fails, AMS considers
            // the DS unhealthy, and the fleet terminates it.
            call.respondText("ok", io.ktor.http.ContentType.Text.Plain, io.ktor.http.HttpStatusCode.OK)
        }
        get("/player") {
            call.respond(mapOf("status" to "alive"))
        }

        post("/api/browser-log") {
            try
            {
                val logMessage = call.receiveText()
                BrowserLogHandler.write(logMessage)
                call.respond(HttpStatusCode.OK)
            }
            catch(e: Exception)
            {
                Logger.error(LogCategory.SYSTEM, "Failed to write browser log: ${e.message}")
                call.respond(HttpStatusCode.InternalServerError)
            }
        }

        // Test-only endpoint: seed a `running-game` snapshot into the user's
        // CloudSave / VFS without going through the in-game save flow.
        //
        // This exists so the resume-bug investigation can repush a
        // known-good snapshot JSON to a freshly-started DS over and over,
        // without re-playing the game each time. The full workflow is:
        //   1. Play a single turn to a stable state (probe-driven).
        //   2. Server auto-saves the snapshot on disconnect.
        //   3. GET the snapshot back via `/debug/fetch-snapshot` (below).
        //   4. Restart the server. POST the JSON to `/debug/seed-snapshot`
        //      to re-arm the resume game. The next user login sees the
        //      ResumeOrNewDialog.
        //
        // Gated on `AUTOGENESIS_DEBUG_SEED=true` (env or system property)
        // so production builds never expose it. The dev-mode
        // `start_servers.sh` script does NOT set this by default — it
        // must be opted into per debug session.
        //
        // Payload (application/json):
        //   { "userId": "00000000000000000000000000000000",
        //     "snapshot": "<raw GameSnapshot JSON, the same shape
        //                   fetched by /debug/fetch-snapshot>" }
        //
        // The snapshot string is stored verbatim under the
        // `running-game` key in the user's VFS. The auto-restore on the
        // next connect consumes it via the standard path.
        if(System.getenv("AUTOGENESIS_DEBUG_SEED") == "true" ||
            System.getProperty("AUTOGENESIS_DEBUG_SEED") == "true")
        {
            post("/debug/seed-snapshot") {
                try
                {
                    val payload = call.receiveText()
                    if (payload.isBlank())
                    {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "empty payload"))
                        return@post
                    }
                    val parsed = kotlinx.serialization.json.Json.parseToJsonElement(payload).let { it as kotlinx.serialization.json.JsonObject }
                    val userId = parsed["userId"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
                    val snapshotJsonElement = parsed["snapshot"]
                        ?: run {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing 'snapshot' field"))
                            return@post
                        }
                    if (userId.isBlank())
                    {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing or blank 'userId' field"))
                        return@post
                    }
                    val vfs = org.ttt.autogenesis.server.vfs.VirtualFileSystemManager.forUser(userId)
                    val snapshotJsonString = snapshotJsonElement.toString()
                    val saveResult = vfs.saveUserRecordFromJsonString(
                        userId,
                        structs.storage.RUNNING_GAME_KEY,
                        snapshotJsonString
                    )
                    val ok = saveResult.isSuccess
                    if (ok)
                    {
                        Logger.info(
                            LogCategory.SYSTEM,
                            "/debug/seed-snapshot: wrote ${snapshotJsonString.length} bytes to running-game for user='$userId'"
                        )
                        // Build the success response as a JsonObject so Ktor's
                        // default serializer doesn't choke on heterogeneous
                        // map values (Map<String, Any> trips it on maps with
                        // mixed-type values like the snapshot object).
                        val respBody = kotlinx.serialization.json.JsonObject(
                            mapOf(
                                "status" to kotlinx.serialization.json.JsonPrimitive("ok"),
                                "userId" to kotlinx.serialization.json.JsonPrimitive(userId),
                                "bytes" to kotlinx.serialization.json.JsonPrimitive(snapshotJsonString.length)
                            )
                        )
                        call.respond(HttpStatusCode.OK, respBody)
                    }
                    else
                    {
                        val msg = saveResult.exceptionOrNull()?.message ?: "unknown error"
                        Logger.error(LogCategory.SYSTEM, "/debug/seed-snapshot: save failed for user='$userId': $msg")
                        val respBody = kotlinx.serialization.json.JsonObject(
                            mapOf(
                                "status" to kotlinx.serialization.json.JsonPrimitive("error"),
                                "error" to kotlinx.serialization.json.JsonPrimitive(msg)
                            )
                        )
                        call.respond(HttpStatusCode.InternalServerError, respBody)
                    }
                }
                catch(e: Exception)
                {
                    Logger.error(LogCategory.SYSTEM, "/debug/seed-snapshot: handler exception: ${e.message}")
                    call.respond(HttpStatusCode.InternalServerError, mapOf("status" to "error", "error" to (e.message ?: e::class.simpleName ?: "unknown")))
                }
            }

            // Companion GET: fetch the current `running-game` snapshot JSON
            // for a given userId so it can be saved to disk and re-pushed
            // after every server restart.
            //
            // Response (application/json):
            //   { "userId": "...", "snapshot": <raw GameSnapshot JSON> }
            // or 404 if no snapshot exists for that user.
            get("/debug/fetch-snapshot") {
                val userId = call.request.queryParameters["userId"].orEmpty()
                if (userId.isBlank())
                {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing ?userId= query param"))
                    return@get
                }
                try
                {
                    val vfs = org.ttt.autogenesis.server.vfs.VirtualFileSystemManager.forUser(userId)
                    val fetch = vfs.fetchUserRecord(userId, structs.storage.RUNNING_GAME_KEY)
                    val recordValue = fetch.getOrNull()?.value
                    if (recordValue == null)
                    {
                        call.respond(HttpStatusCode.NotFound, mapOf("status" to "not_found", "userId" to userId))
                    }
                    else
                    {
                        // The Cloud VFS wraps the payload in a {value: {...}}
                        // envelope. Strip it so the caller gets the raw
                        // GameSnapshot JSON that matches what
                        // serialize() emits, so it round-trips through
                        // /debug/seed-snapshot and TurnHarness.deserialize
                        // without surprises.
                        val unwrapped = if (recordValue is kotlinx.serialization.json.JsonObject && recordValue.containsKey("value"))
                        {
                            recordValue["value"]!!
                        }
                        else
                        {
                            recordValue
                        }
                        val snapshotString = unwrapped.toString()
                        Logger.info(
                            LogCategory.SYSTEM,
                            "/debug/fetch-snapshot: returning ${snapshotString.length} bytes for user='$userId'"
                        )
                        // Ktor's default Json serializer can't handle raw
                        // JsonElement values directly (it tries to look up
                        // a serializer for the JsonLiteral class which is
                        // not @Serializable). Build the response as a
                        // JsonObject with a single string field — the
                        // seed/repush workflow re-parses it back into JSON
                        // on POST.
                        val respBody = kotlinx.serialization.json.JsonObject(
                            mapOf(
                                "userId" to kotlinx.serialization.json.JsonPrimitive(userId),
                                "snapshot" to kotlinx.serialization.json.JsonPrimitive(snapshotString)
                            )
                        )
                        call.respond(HttpStatusCode.OK, respBody)
                    }
                }
                catch(e: Exception)
                {
                    Logger.error(LogCategory.SYSTEM, "/debug/fetch-snapshot: handler exception: ${e.message}")
                    call.respond(HttpStatusCode.InternalServerError, mapOf("status" to "error", "error" to (e.message ?: e::class.simpleName ?: "unknown")))
                }
            }
            // Test-only endpoint: injects a synthetic "skip" submission for a
            // named player so the TurnHarness loop's awaiter resolves and the
            // next iteration can run. Used by the push-turn-start e2e probe
            // to drive the loop past the (disconnected) player's first turn
            // so the NEXT iteration — which IS the human's turn with no
            // PRIMARY session — fires the push trigger.
            //
            // Always-on in dev (matching the existing /debug/seed-snapshot
            // gate style). Production deploys should NOT include this file's
            // test-only routes, or should block /debug/* at a reverse proxy.
            post("/debug/advance-turn") {
                try
                {
                    val playerName = call.request.queryParameters["player"]
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "player query param required")
                        )
                    val completed = TurnHarness.testForceAdvanceAwaiter(playerName)
                    Logger.info(LogCategory.SYSTEM, "/debug/advance-turn: result=$completed for $playerName")
                    call.respond(HttpStatusCode.OK, mapOf("status" to if (completed) "advanced" else "no-awaiter", "player" to playerName))
                }
                catch (e: Exception)
                {
                    Logger.error(LogCategory.SYSTEM, "/debug/advance-turn: handler exception: ${e.message}")
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "unknown")))
                }
            }
        }

            // Test-only endpoint: stores a synthetic push subscription into the user's
            // VFS under `push-subscription`. The push-turn-start e2e probe uses this
            // to inject a subscription pointing at the local mock receiver without
            // going through the browser Service Worker (the Web Push API rejects
            // PushManager.subscribe when Notification.requestPermission is denied
            // in headless Chromium even with `--use-fake-ui-for-media-stream`).
            //
            // Both the private/public auth keys must form a real P-256 keypair —
            // the receiver's p256dh is the raw uncompressed EC point of the
            // auth public key, decoded by the web-push library. The probe
            // generates this keypair in-process and posts it.
            //
            // Gated on a system property (default OFF) so it is unreachable in
            // production builds. Set `-Dpush.test.endpoint=true` to enable.
            if (System.getProperty("push.test.endpoint") == "true")
            {
                post("/debug/seed-push-subscription") {
                    try
                    {
                        val userId = call.request.queryParameters["userId"]
                            ?: return@post call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "userId query param required")
                            )
                        val body = call.receiveText()
                        val dto = Json { ignoreUnknownKeys = true }
                            .decodeFromString(structs.push.PushSubscriptionDto.serializer(), body)
                        val store = UiSignalRpcHandlers.pushSubscriptionStore
                        if (store == null)
                        {
                            Logger.error(LogCategory.SYSTEM, "/debug/seed-push-subscription: push subscription store is null (server was started without VAPID keypair)")
                            return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "push not wired"))
                        }
                        val ok = kotlinx.coroutines.runBlocking { store.put(userId, dto) }
                        Logger.info(
                            LogCategory.SYSTEM,
                            "/debug/seed-push-subscription: stored endpoint=${dto.endpoint.take(80)} for user=$userId (success=$ok)"
                        )
                        call.respond(HttpStatusCode.OK, mapOf("status" to "stored", "user" to userId, "endpoint" to dto.endpoint))
                    }
                    catch (e: Exception)
                    {
                        Logger.error(LogCategory.SYSTEM, "/debug/seed-push-subscription: handler exception: ${e.message}")
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "unknown")))
                    }
                }

                // Drive the TurnHarness loop past a (disconnected) player's
                // suspended awaiter so the NEXT iteration — which is that
                // human again with no PRIMARY session — fires the push
                // trigger. Gated on the same `push.test.endpoint` flag.
                post("/debug/advance-turn") {
                    try
                    {
                        val playerName = call.request.queryParameters["player"]
                            ?: return@post call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "player query param required")
                            )
                        val completed = TurnHarness.testForceAdvanceAwaiter(playerName)
                        Logger.info(LogCategory.SYSTEM, "/debug/advance-turn: result=$completed for $playerName")
                        call.respond(HttpStatusCode.OK, mapOf("status" to if (completed) "advanced" else "no-awaiter", "player" to playerName))
                    }
                    catch (e: Exception)
                    {
                        Logger.error(LogCategory.SYSTEM, "/debug/advance-turn: handler exception: ${e.message}")
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "unknown")))
                    }
                }

                // Fires PushNotificationService.sendTurnStart directly via the
                // wire path the JVM integration test covers. Use this to prove
                // end-to-end the subscription store -> VAPID-signed POST -> mock
                // receiver pipeline without waiting for the LLM-driven turn
                // loop to advance (which can take 5+ minutes per human turn).
                //
                // Same VAPID keypair, same receiver, same payload shape as the
                // production trigger path — only difference is who CALLS it.
                post("/debug/trigger-push-now") {
                    try
                    {
                        val userId = call.request.queryParameters["userId"]
                            ?: return@post call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "userId query param required")
                            )
                        val round = (call.request.queryParameters["round"]?.toIntOrNull()) ?: 99
                        val actor = call.request.queryParameters["actor"] ?: "AUongfa834nfa"
                        val service = UiSignalRpcHandlers.pushNotificationService
                        if (service == null)
                        {
                            return@post call.respond(
                                HttpStatusCode.ServiceUnavailable,
                                mapOf("error" to "push notification service is null (VAPID not loaded)")
                            )
                        }
                        Logger.info(
                            LogCategory.SYSTEM,
                            "/debug/trigger-push-now: calling sendTurnStart user=$userId actor=$actor round=$round"
                        )
                        val sent = kotlinx.coroutines.runBlocking {
                            service.sendTurnStart(userId, actor, round)
                        }
                        Logger.info(
                            LogCategory.SYSTEM,
                            "/debug/trigger-push-now: sendTurnStart returned $sent for user=$userId"
                        )
                        val body = """{"status":"${if (sent) "delivered" else "failed"}","user":"$userId","round":$round}"""
                        call.respondText(body, io.ktor.http.ContentType.Application.Json)
                    }
                    catch (e: Exception)
                    {
                        Logger.error(LogCategory.SYSTEM, "/debug/trigger-push-now: handler exception: ${e.message}")
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "unknown")))
                    }
                }
            }

        // Test-only endpoint: drives AudioManager.schedulePlay from outside the
        // server. Gated on a system property (default OFF) so it is unreachable
        // in production builds. The Playwright E2E test harness sets
        // `-Daudio.test.endpoint=true` to enable it.
        if(System.getProperty("audio.test.endpoint") == "true")
        {
            post("/test/audio/trigger-schedule-play") {
                try
                {
                    val body = call.receiveText()
                    val req = Json { ignoreUnknownKeys = true }
                        .decodeFromString(TestSchedulePlayRequest.serializer(), body)
                    val obj = AudioObject(
                        resourceName = req.resourceName,
                        channelId = req.channelId,
                        volume = req.volume,
                        loop = req.loop,
                        loopStart = req.loopStart,
                        loopEnd = req.loopEnd
                    )
                    AudioManager.schedulePlay(listOf(obj), UiSignalRpcHandlers.connectionManager)
                    Logger.info(
                        LogCategory.GENERAL,
                        "Test endpoint: triggered schedulePlay id=${obj.id} resource=${req.resourceName} channel=${req.channelId} loop=${req.loop} loopStart=${req.loopStart} loopEnd=${req.loopEnd}"
                    )
                    call.respond(HttpStatusCode.OK, mapOf("status" to "scheduled", "id" to obj.id))
                }
                catch(e: Exception)
                {
                    Logger.error(LogCategory.SYSTEM, "Test endpoint schedulePlay failed: ${e.message}")
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "unknown")))
                }
            }
        }

        webSocket("/events") {
            val playerId = call.parameters["playerId"] ?: UUID.randomUUID().toString()
            // BUG-2 fix 2026-06-25: when no accelbyteId is supplied (e.g. the
            // skipLogin / guest flow), default to the playerId so the server
            // sees a stable identity, and force guestMode=true when the
            // placeholder matches. Previously, a missing accelbyteId caused
            // accelbyteId to fall back to playerId (a random kvision-ws-client-N
            // string), which never matches WorldManager.playerStats[*].accelByteUserId
            // and breaks audio.syncState / sendInitialSync routing on resume.
            val accelbyteIdRaw = call.parameters["accelbyteId"]
            val accelbyteId = accelbyteIdRaw?.takeIf { it.isNotBlank() } ?: playerId
            val guestModeParam = call.parameters["guestMode"]?.toBoolean() == true
            val guestMode = guestModeParam || accelbyteIdRaw.isNullOrBlank() || accelbyteId.startsWith("guest")
            val roleParam = call.parameters["role"]?.uppercase()
            val sessionRole = when (roleParam) {
                "CONTROLLER" -> SessionRole.CONTROLLER
                else -> SessionRole.PRIMARY
            }

            if (guestMode)
            {
                Logger.info(LogCategory.NETWORK, "Server: Guest mode detected for connection playerId=$playerId (accelbyteId=$accelbyteId)")
            }

            Logger.info(LogCategory.NETWORK, "WebSocket /events connection attempt for playerId=$playerId (accelbyteId=$accelbyteId, guestMode=$guestMode, role=$sessionRole)")
            var playerSession: PlayerSession? = null
            // Capture WebSocket session reference before entering nested callbacks
            val wsSession = this
            try
            {
                Logger.debug(LogCategory.NETWORK, "Server: registering connection for playerId=$playerId, role=$sessionRole")
                val registration = connectionManager.register(playerId, wsSession, sessionRole, accelbyteId)
                playerSession = registration.session
                Logger.info(LogCategory.NETWORK, "WebSocket connection established for playerId=$playerId (outcome=${registration.outcome})")

                // Identify the player in the world state
                val stats = gameState.WorldManager.playerStats.find { it.playerID == playerId || it.accelByteUserId == accelbyteId }
                if (stats != null) {
                    Logger.info(LogCategory.NETWORK, "Server: Identified connection $playerId as player '${stats.playerData.name}' (isNpc=${stats.isControlledByNpc})")
                    stats.isConnected = true

                    // Phase 3: AMS arrival gate — if session is matched, only allow expected players
                    val playerAccelByteId = stats.accelByteUserId
                    if (gameState.WorldManager.sessionMatched && playerAccelByteId.isNotBlank())
                    {
                        if (!gameState.WorldManager.isPlayerExpected(playerAccelByteId))
                        {
                            Logger.warn(LogCategory.NETWORK, "Server: Player $playerId (accelByteId=$playerAccelByteId) is not in expected player set — rejecting connection")
                            stats.isConnected = false  // Reset before closing so player isn't ghosted
                            val closeReason = CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unexpected player for session")
                            wsSession.close(closeReason)
                            return@webSocket
                        }
                        // Player is expected — notify TurnHarness arrival gate
                        org.ttt.autogenesis.server.TurnHarness.notifyPlayerJoined(playerAccelByteId)
                    }

                    if (!stats.isControlledByNpc) {
                        Logger.info(LogCategory.NETWORK, "Server: Human player ${stats.playerData.name} joined, triggering initial sync")
                        UiSignalRpcHandlers.sendInitialSync(playerId, stats.playerData, gameState.WorldManager.activeMapPackBytes, gameState.WorldManager.world, gameState.WorldManager.history, accelbyteId)
                    }
                } else {
                    Logger.warn(LogCategory.NETWORK, "Server: Could not resolve player identity for connection $playerId (accelbyteId=$accelbyteId)")
                }

                connectionManager.broadcastConnectionEvent(playerId, ConnectionStatus.CONNECTED)
                
                for(frame in incoming)
                {
                    if(frame is Frame.Text)
                    {
                        val raw = frame.readText()
                        Logger.debug(LogCategory.NETWORK, "Received WebSocket frame for playerId=$playerId size=${raw.length}")
                        val completePayload = playerSession?.handleIncomingFrame(raw)
                        if (completePayload != null) {
                            playerSession?.handleFrame(completePayload)
                        }
                    }
                }
            }
            catch(err: Throwable)
            {
                Logger.error(LogCategory.NETWORK, "Error while handling WebSocket for playerId=$playerId: ${err.message}")
                throw err
            }
            finally
            {
                playerSession?.let { connectionManager.deregister(it) }
                connectionManager.broadcastConnectionEvent(playerId, ConnectionStatus.DISCONNECTED)
                Logger.debug(LogCategory.NETWORK, "Server: WebSocket cleanup complete for $playerId (sessionPresent=${playerSession != null}, role=${playerSession?.role})")
                Logger.info(LogCategory.NETWORK, "WebSocket session closed for playerId=$playerId")
            }
        }
    }
}

private fun updatePlayerConnectionStats(playerId: String, connected: Boolean)
{
    val stats = gameState.WorldManager.findPlayerStatsByConnectionId(playerId)
    if(stats != null)
    {
        stats.isConnected = connected
        stats.isControlledByNpc = !connected
        Logger.debug(LogCategory.SYSTEM, "Player ${stats.playerData.name} connection state updated: connected=$connected")
    }
}

/**
 * Processes incoming WebSocket frames and routes RPC messages through the registry.
 * Handles requests, notifications, responses, and stream management.
 *
 * @param raw The raw JSON string containing the RPC message.
 */
private suspend fun PlayerSession.handleFrame(raw: String)
{
    val message = raw.toRpcMessage(RpcJson)
    Logger.debug(LogCategory.NETWORK, "RPC Received: [${message::class.simpleName}] from playerId=$playerId")

    // Per-connection metadata surfaced to every RPC handler via RpcCallContext.
    // `accelbyteId` is the AccelByte user id the WebSocket query string
    // carried in; handlers like GameRestoreRpcHandlers.resolveHumanUserId
    // consult it before falling back to WorldManager.playerStats, which is
    // empty on a fresh dedicated server before the matchmaker injects the
    // human player's stats.
    val contextMetadata = mapOf("accelbyteId" to accelbyteId)

    when(message)
    {
        is RpcMessage.Request ->
        {
            Logger.info(LogCategory.NETWORK, "RPC Dispatching Request: method=${message.method} id=${message.id} from playerId=$playerId")
            val context = RpcCallContext(
                connectionId = playerId,
                metadata = contextMetadata,
                sender = { message -> this.sendRpcMessage(message) }
            )
            val response = try
            {
                rpcRegistry.dispatch(message, context)
            }
            catch(e: Throwable)
            {
                Logger.error(LogCategory.NETWORK, "RPC Dispatch Error in Request: method=${message.method} id=${message.id}: ${e.message}")
                throw e
            }
            
            if(response != null)
            {
                Logger.debug(LogCategory.NETWORK, "RPC Sending Response: method=${message.method} id=${message.id} to playerId=$playerId")
                this.sendRpcMessage(response)
            }
            else
            {
                Logger.debug(LogCategory.NETWORK, "RPC Request Handled (async): method=${message.method} id=${message.id}")
            }
        }
        is RpcMessage.Notification ->
        {
            Logger.info(LogCategory.NETWORK, "RPC Dispatching Notification: method=${message.method} from playerId=$playerId")
            val context = RpcCallContext(
                connectionId = playerId,
                metadata = contextMetadata,
                sender = { message -> this.sendRpcMessage(message) }
            )
            try
            {
                rpcRegistry.dispatchNotification(message, context)
            }
            catch(e: Throwable)
            {
                Logger.error(LogCategory.NETWORK, "RPC Dispatch Error in Notification: method=${message.method}: ${e.message}")
                throw e
            }
        }
        is RpcMessage.Response ->
        {
            Logger.debug(LogCategory.NETWORK, "RPC Handling Response: id=${message.id} from playerId=$playerId")
            invoker.handleResponse(message)
        }
        is RpcMessage.StreamChunk ->
        {
            Logger.debug(LogCategory.NETWORK, "RPC Handling StreamChunk: id=${message.id} from playerId=$playerId")
            invoker.handleStreamChunk(message)
        }
        is RpcMessage.StreamCancel ->
        {
            Logger.debug(LogCategory.NETWORK, "RPC Handling StreamCancel: id=${message.id} from playerId=$playerId")
            if(!invoker.handleStreamCancel(message))
            {
                rpcRegistry.cancelStream(message.id)
            }
        }
        else -> Logger.warn(LogCategory.NETWORK, "Unknown RPC message type received from playerId=$playerId: ${message::class.simpleName}")
    }
}

/**
 * Test-only request body for the gated `/test/audio/trigger-schedule-play`
 * endpoint. Decoded loosely — only the audio fields required to drive a
 * server-side `AudioManager.schedulePlay` are required. Defaults mirror
 * `AudioObject`'s defaults.
 */
@Serializable
private data class TestSchedulePlayRequest(
    val resourceName: String = "test.beep",
    val channelId: String = org.ttt.autogenesis.audio.AudioChannelIds.SFX_CHANNEL_ID,
    val volume: Float = 1.0f,
    val loop: Boolean = false,
    val loopStart: Double? = null,
    val loopEnd: Double? = null
)

/**
 * Cancels any in-flight shutdown countdown and arms a fresh one for
 * single-player mode. The countdown re-checks for primary sessions at
 * expiry so a reconnect during the grace window cancels the exit (the
 * `onConnected` callback cancels the previous job; this function's caller
 * is responsible for assigning the returned [Job] back into the live
 * `shutdownJob` slot).
 *
 * Extracted from [Application.serverModule]'s `onDisconnected` callback so
 * the timer behavior is unit-testable without invoking `exitProcess(0)` in
 * the test process. The original (pre-regression) handler used this same
 * shape — a flat `delay(...) ; exitProcess(0)` — minus the at-expiry
 * re-check that this version preserves.
 *
 * @param connectionManager Used to re-check for primary sessions at expiry.
 * @param existingJob The current `shutdownJob` to cancel before launching a
 *   new one. Nullable for the first arming of a session.
 * @param delayMs Test seam — production uses `15_000L`.
 * @param onExpire Test seam — production calls `exitProcess(0)`.
 * @return The new countdown [Job].
 */
internal fun startSinglePlayerShutdownCountdown(
    connectionManager: PlayerConnectionManager,
    existingJob: Job?,
    delayMs: Long,
    onExpire: () -> Unit
): Job
{
    Logger.warn(
        LogCategory.SYSTEM,
        "Server: No PRIMARY sessions remain for any playerId in single-player mode. " +
                "Starting ${delayMs / 1000}-second shutdown timer."
    )
    existingJob?.cancel()
    return CoroutineScope(Dispatchers.Default).launch {
        delay(delayMs)
        if (!connectionManager.hasAnyPrimarySession())
        {
            Logger.error(LogCategory.SYSTEM, "Server: Shutdown timer expired. Terminating server to prevent runaway tokens.")
            onExpire()
        }
        else
        {
            Logger.info(LogCategory.SYSTEM, "Server: Shutdown timer fired but a PRIMARY reconnected — exit cancelled.")
        }
    }
}

/**
 * Decides whether the server should persist the running game to the human
 * player's cloud-save record on disconnect. Pure function — extracted from
 * [Application.serverModule]'s `onDisconnected` callback so the gate is
 * unit-testable without a live ktor server lifecycle.
 *
 * Returns `true` only when ALL of these are true:
 *   - `isSinglePlayer` (multiplayer resume is out of scope for now)
 *   - `isGameActive` (GameInit has run; world is non-default)
 *   - `humanPlayerHasJoinedOnce` (the human player has reached a reachable
 *     turn in [TurnHarness.awaitPlayerAction]; without this guard a
 *     GameInit-only disconnect — server-extend bridge closing before the
 *     user's WebSocket connects — would persist a phantom round-1 fresh-init
 *     snapshot)
 *   - `humanAccelByteUserId` is non-blank (we need a valid save target)
 *
 * The bug this gate prevents (BUG 25, 2026-06-27): on a clean
 * server-extend-bridge disconnect in the ~1.5s window between `setGameMode`
 * and the user's WS connect, the prior gate (`isNotBlank() && isGameActive`)
 * would persist `round=1, turnIndex=0, historyEntries=0` to the user's
 * account. The next reconnect then "resumed" an empty phantom game.
 * Regression test: `server/src/test/kotlin/org/ttt/autogenesis/server/SaveOnDisconnectGateTest.kt`.
 */
internal fun shouldPersistOnDisconnect(
    humanAccelByteUserId: String,
    isSinglePlayer: Boolean,
    isGameActive: Boolean,
    humanPlayerHasJoinedOnce: Boolean,
    historySize: Int = 0
): Boolean
{
    // The save-on-disconnect gate. Requires the human player to have
    // actually connected (`humanPlayerHasJoinedOnce=true`) so we don't
    // persist a phantom game from a bridge-only session (server-extend
    // opens the game server then disconnects before the human WS arrives).
    //
    // Note: a previous version of this gate also required `historySize > 0`
    // (to skip saving when the user joined but the AI took the first turn
    // and disconnected mid-AI). That heuristic over-skipped legitimate
    // saves: a user who joined, then closed the browser before submitting
    // a turn, would see no Resume dialog on next login even though the
    // humanPlayerHasJoinedOnce flag was correctly set. With BUG 26 in
    // place (auto-restore unconditionally disabled), the explicit-Resume
    // path correctly handles the no-history case: the snapshot is loaded,
    // the world shows round 1 / turnIndex 0 with no events, and the user
    // sees the same fresh-init state they would have seen on a normal
    // "New Game" — exactly the desired user experience.
    return isSinglePlayer
        && isGameActive
        && humanPlayerHasJoinedOnce
        && humanAccelByteUserId.isNotBlank()
}
