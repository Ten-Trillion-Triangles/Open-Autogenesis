package org.ttt.autogenesis.kvisionapp

import globals.ClientDebug
import globals.ElectronEnvironment
import globals.KEnv
import globals.ServerExtendConfig
import globals.WebsocketConfig
import org.ttt.autogenesis.kvisionapp.DebugSignalBridge
import ui.AutogenesisConsole
import io.kvision.Application
import io.kvision.BootstrapModule
import io.kvision.CoreModule
import io.kvision.core.CssSize
import io.kvision.core.UNIT
import io.kvision.panel.root
import io.kvision.panel.stackPanel
import io.kvision.startApplication
import io.kvision.utils.perc
import io.kvision.utils.px
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.LogPriority
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.ServerExtendTransport
import org.ttt.autogenesis.kvisionapp.assets.AssetLoader
import org.ttt.autogenesis.kvisionapp.assets.Mp3AssetLoader
import structs.storage.MasterRecordStorage
import ui.billing.BillingState
import structs.matchmaking.GameTicket
import accelbyte.user.getUserSdkInstance
import ui.gameplay.GameplayUI
import ui.LoadingScreen
import ui.LoginPage
import ui.MapViewer
import ui.fetchMapPackFromUrl
import ui.gameplay.networking.ActionHistoryClientHandlers
import ui.gameplay.networking.UiSignalClientHandlers
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.kvisionapp.audio.AudioClientHandlers
import kotlin.js.js
import kotlinx.browser.window

/**
 * Entry point for the KVision application.
 */
class AutogenesisApp : Application()
{
    /**
     * Builds the root UI and wires the viewport to the global environment.
     *
     * @param state Unused application state map that KVision passes to [start].
     */
    override fun start(state: Map<String, Any>)
    {
        Logger.info(LogCategory.SYSTEM, "Main: App.start() ENTER, building root UI")
        // DCE keepalive: Kotlin/JS dead-code-eliminates PushNotificationService
        // because KVision event handlers (onClick { ... }) invoke it
        // reflectively through stored lambdas — the linker can't see those
        // call sites. Storing a reference here forces the entire
        // PushNotificationService object (and its companion-method call
        // graph) to be retained in the production webpack bundle.
        // Without this, `subscribeIfPermitted` is missing from the
        // kvisionApp.js output and the Web Push pipeline is unreachable.
        val pushServiceKeepalive: Any = PushNotificationService
        Logger.debug(LogCategory.SYSTEM, "Main: PushNotificationService keepalive bound (ref=$pushServiceKeepalive)")
        root("kvapp") {
            KEnv.mainRoot = this // Bind screen to global so we can control what is drawn on it.
            // NOTE: background image is set AFTER the loading screen, because the
            // loading screen paints its own full-bleed background. Setting
            // AutogenesisTitle.png here would briefly show through any transparent
            // gap during the loading screen's initial paint.

            // CRITICAL: register the resume-game listener on the shared
            // RpcRegistry IMMEDIATELY at startup, not lazily from MainMenu.
            // The server-extend `client.resumeAvailable` push can arrive within
            // ~500ms of the WS handshake (the SSE rebind fires
            // `triggerSseResumePush(accelbyteId)` immediately on connect), but MainMenu mounts
            // later in the init flow. If we register lazily from MainMenu, the
            // push arrives at the client before the handler is in the registry,
            // `dispatchNotification` silently drops it, and the
            // ResumeOrNewDialog modal never renders. Registering eagerly here means
            // the handler is in place by the time the WS handshake completes and
            // server-extend starts pushing. The `if (registered) return` early
            // exit in ResumeAvailabilityListener.register() keeps this idempotent
            // for any later MainMenu-initiated wireResumeDialog call.
            ResumeAvailabilityListener.register()
            // Service worker for Web Push notifications. Registered eagerly so
            // the SW is ready by the time the user clicks Play and we attempt
            // pushManager.subscribe().
            PushNotificationService.registerServiceWorker()
            // Listen for postMessage from the service worker (fired when the
            // user taps a "Your turn" notification, and on subscription
            // rotation per RFC 8030). The SW posts plain JS objects:
            //   { type: 'autogenesis.resumeTurn' }      — for notification taps
            //   { type: 'autogenesis.subscriptionChanged', subscription: {...} }  — for rotation
            // The page-side handler invokes server.restoreRunningGame on the
            // existing WS bridge for resume, and re-registers the rotated
            // subscription via client.registerPushSubscription for change
            // events. Both paths share the same WS bridge the Resume dialog
            // uses.
            window.addEventListener("message", { event ->
                val data: dynamic = event.asDynamic().data
                val type: String? = data?.type as? String
                when (type)
                {
                    "autogenesis.resumeTurn" -> {
                        PushNotificationService.handleResumeTurnMessage()
                    }
                    "autogenesis.subscriptionChanged" -> {
                        val subscription: dynamic = js("({})")
                        if (data != null) {
                            subscription.endpoint = data.subscription?.endpoint
                            subscription.keys = data.subscription?.keys
                        }
                        PushNotificationService.handleSubscriptionChanged(subscription)
                    }
                }
            })

            val stack = stackPanel {
                width = 100.perc
                height = 100.perc
                position = io.kvision.core.Position.RELATIVE
            }
            // In test mode the stackPanel collapses to its
            // intrinsic content height in flex layout (KVision
            // quirk), which clips the MapViewer. Force an explicit
            // viewport-sized layout on the stack's outer element.
            stack.setStyle("position", "relative")
            stack.setStyle("width", "100vw")
            stack.setStyle("height", "100vh")
            KEnv.appStack = stack

            // Pre-MainMenu loading screen: gates browser audio on a user click and
            // pre-warms webpack splitChunks (main menu music) before the next
            // screen mounts. See .omx/plans/prometheus-strict/loading-screen.md
            // for the design contract.
            Logger.info(LogCategory.SYSTEM, "Main: building loading assets (Mp3AssetLoader for main-menu-music)")
            val loadingAssets: List<AssetLoader> = listOf(
                Mp3AssetLoader(
                    id = "main-menu-music",
                    humanLabel = "Main menu music",
                    resourceName = "music.menu",
                    // The editor's main-menu track in
                    // `sharedModel/src/commonMain/resources/audio/audio-tracks.json`
                    // is `Xilaron and Eleuryiyidict wet final`. The
                    // Mp3AssetLoader pre-warms the actual mp3 the
                    // browser will play, not the first-turn track.
                    fallbackPath = "audio/music/Xilaron and Eleuryiyidict wet final.mp3"
                )
            )
            Logger.info(LogCategory.UI, "Main: instantiating LoadingScreen")
            val loadingScreen = LoadingScreen(assets = loadingAssets)
            stack.add(loadingScreen)
            Logger.info(LogCategory.UI, "Main: LoadingScreen mounted, awaiting user click + pipeline")

            MainScope().launch {
                Logger.debug(LogCategory.SYSTEM, "Main: suspending on loadingScreen.awaitReady()")
                loadingScreen.awaitReady()
                Logger.info(LogCategory.SYSTEM, "Main: loadingScreen.awaitReady() resolved, removing loading screen from stack")
                // LoadingScreen is now done (success or partial failure). Remove
                // it from the stack and mount the next screen.
                stack.remove(loadingScreen)
                KEnv.setBackgroundImage("img/AutogenesisTitle.png")

                if(KEnv.skipLogin)
                {
                    Logger.info(LogCategory.SYSTEM, "Main: skipLogin enabled, bypassing login screen")
                    // Provide dummy credentials for guest mode
                    globals.AccelByteEnv.userId = "guest-user"
                    globals.AccelByteEnv.userName = "Guest"
                    globals.AccelByteEnv.displayName = "Guest Commander"

                    // Reconnect both bridges with the guest accelbyteId so the
                    // post-skipLogin flow (REST/SSE master record, billing,
                    // matchmaking) runs against a single bound client from the
                    // start. Doing the rebind here (before MainMenu mounts) means
                    // no anonymous REST session is ever in flight for a request.
                    //
                    // CRITICAL ORDERING: the REST/SSE bridge MUST rebind BEFORE
                    // the WebSocket bridge. The SSE rebind is what fires the
                    // server-extend `triggerSseResumePush(accelbyteId)` check —
                    // if that check finds a saved snapshot, it pushes a
                    // `client.resumeAvailable` notification to the WS session.
                    // If the WS rebinds FIRST and triggers the server's
                    // auto-restore on connect, the snapshot is consumed and
                    // replaced with a `{"consumed": true}` sentinel BEFORE
                    // server-extend gets a chance to read it — the resume
                    // push never fires, and the ResumeOrNewDialog modal
                    // never renders. This is the difference between the
                    // user's "intended" cross-session resume flow (which
                    // presents a modal) and the silent in-place auto-restore.
                    // In dev mode (no AMS-provisioned fresh DS) the in-place
                    // auto-restore is the only option, but the SSE rebind
                    // MUST still happen first so server-extend can race the
                    // auto-restore; in live mode the SSE rebind is the
                    // authoritative signal that drives the modal.
                    MainScope().launch {
                        Logger.info(LogCategory.NETWORK, "Main: rebinding post-auth bridges with accelbyteId=${globals.AccelByteEnv.userId} (REST/SSE first, then WS)")
                        RestRpcBridge.connect(accelbyteId = globals.AccelByteEnv.userId)
                        WebSocketRpcBridge.connect(accelbyteId = globals.AccelByteEnv.userId)
                    }

                    if(KEnv.testMode)
                    {
                        Logger.info(LogCategory.SYSTEM, "Main: testMode enabled, mounting MapViewer directly (skipping MainMenu and full GameplayUI)")
                        // The GameplayUI is a complex shell that is
                        // brittle to drive from Playwright (the
                        // dockPanel layout can fail to render in
                        // the headless browser, and the test never
                        // needs the description / stats widgets
                        // anyway). For the hover-border-lines test
                        // we only need a real MapViewer mounted on
                        // the stack, so we mount one directly.
                        val testViewer = ui.MapViewer()
                        testViewer.width = io.kvision.core.CssSize(100, io.kvision.core.UNIT.perc)
                        testViewer.height = io.kvision.core.CssSize(100, io.kvision.core.UNIT.perc)
                        // Test-mode hook: expose the MapViewer and a
                        // stable `loadMapForTest(bytes)` helper on
                        // `window` for Playwright.
                        if(KEnv.testMode)
                        {
                            kotlinx.browser.window.asDynamic().mapViewer = testViewer
                            kotlinx.browser.window.asDynamic().loadMapForTest = { bytes: Array<Number> ->
                                val ba = ByteArray(bytes.size)
                                for(i in 0 until bytes.size)
                                {
                                    val v = bytes[i]
                                    ba[i] = v.toByte()
                                }
                                kotlinx.coroutines.MainScope().launch {
                                    testViewer.loadMapPack(ba)
                                }
                            }
                            Logger.info(LogCategory.SYSTEM, "Main: testMode active, window.mapViewer and window.loadMapForTest exposed for e2e")
                        }
                        stack.add(testViewer)
                        stack.activeIndex = stack.getChildren().indexOf(testViewer)
                    }
                    else
                    {
                        stack.add(ui.MainMenu())
                        // In skipLogin mode, start debug signal polling immediately so
                        // the Python controller can drive the browser UI
                        DebugSignalBridge.startPolling()
                    }
                }
                else
                {
                    val loginPage = LoginPage()
                    KEnv.currentLoginPage = loginPage
                    stack.add(loginPage)
                    // Start debug signal polling for manual click mode too
                    DebugSignalBridge.startPolling()
                }
            }

            BrowserSmokeState.refresh()
        }
    }
}

/**
 * Application entry point that detects the backend environment and wires up
 * the RPC bridge before starting KVision.
 */
fun main()
{
    // Configure Logger before any logging occurs
    Logger.configure(LogPriority.DEBUG, true)
    
    // Support skipLogin via URL parameter
    val searchParams = kotlinx.browser.window.location.search
    if (searchParams.contains("skipLogin=true"))
    {
        KEnv.skipLogin = true
        Logger.info(LogCategory.SYSTEM, "Main: skipLogin enabled via URL parameter")
    }

    // Support testMode via URL parameter. When enabled, the
    // gameplay UI exposes itself on `window` so the Playwright
    // e2e harness can drive a real `MapViewer` without a server.
    if (searchParams.contains("testMode=true"))
    {
        KEnv.testMode = true
        Logger.info(LogCategory.SYSTEM, "Main: testMode enabled via URL parameter — gameplay widgets will be exposed on window")
    }

    // Support playerId override via URL parameter (for dual-control with Python controller)
    queryParameter("playerId")?.let { playerId ->
        if(playerId.isNotBlank()) {
            kotlin.js.eval("window.PLAYER_ID = window.decodeURIComponent('$playerId')")
            Logger.info(LogCategory.NETWORK, "Main: PLAYER_ID override set to '$playerId'")
        }
    }

    queryParameter("serverExtendTransport")?.let { value ->
        val transport = ServerExtendTransport.fromValue(value)
        ServerExtendConfig.transport = transport
        Logger.info(LogCategory.NETWORK, "Main: serverExtendTransport override set to $transport")
    }

    val browserSmokeEnabled = queryParameter("browserSmoke")?.toBooleanStrictOrNull() == true
    if(browserSmokeEnabled)
    {
        ServerExtendConfig.useGrpcWebTransport()
        Logger.info(LogCategory.NETWORK, "Main: browser smoke mode forcing grpc-web transport")
    }
    BrowserSmokeState.install(browserSmokeEnabled)
    BrowserSmokeState.setTransport(ServerExtendConfig.transport)

    val runningInElectron = ElectronEnvironment.isElectron
    if(runningInElectron)
    {
        // Electron bundles the local server stack, so local-mode URLs are always
        // correct regardless of the build-time `kvision.liveMode` flag. The flag
        // only affects deployed-browser builds; Electron always runs against the
        // embedded services. We force the local URL via `useLocalServer()` instead
        // of mutating `ClientDebug.debugMode` (which is now a build-time `val`).
        ElectronEnvironment.localServerUrl?.let { ServerExtendConfig.localServerUrl = it }
        ServerExtendConfig.useLocalServer()
        KEnv.isLocalDevMode = true
        Logger.info(LogCategory.SYSTEM, "Main: Running inside Electron, defaulting to embedded services")
    }

    // Initialize server detection and RPC bridge
    Logger.debug(LogCategory.NETWORK, "DEBUG: [Main] About to configure MasterRecordStorage")
    MasterRecordStorage.configureSdk() {
        Logger.debug(LogCategory.NETWORK, "DEBUG: [Main] MasterRecordStorage requesting SDK instance")
        val sdkInstance = getUserSdkInstance()
        Logger.debug(LogCategory.NETWORK, "DEBUG: [Main] MasterRecordStorage got SDK instance: $sdkInstance")
        sdkInstance
    }
    val launcherScope = MainScope()
    launcherScope.launch {
        Logger.info(LogCategory.NETWORK, "Main: Starting local server detection")
        val detectionDeferred = async {
            if(runningInElectron)
            {
                true
            }
            else
            {
                LocalDevDetector.detectLocalDev()
            }
        }

        // Start the WebSocket bridge immediately without waiting on detector
        launch {
                try
                {
                    // Manually register client-side handlers since JS reflection/auto-load is limited.
                    //
                    // Each `registerXxxClientHandlersRpcHandlers(this, ...)` call
                    // both registers the typed handlers AND forces the KSP-generated
                    // top-level `_*xxxClientHandlersRpcHandlersProvider` val to
                    // initialize, which is what wires the file's provider into
                    // `RpcRegistrationCollector`. Without the explicit call, the
                    // file is never referenced by the bundle, the provider is never
                    // registered, and `RpcRegistry` silently drops the matching
                    // server notifications — that was the root cause of the
                    // gameplay music picker never engaging: `audio.musicSchedule`
                    // was sent by the server on every turn but the client never
                    // routed it to `AudioClientHandlers.handleMusicSchedule`.
                    WebSocketRpcBridge.registerHandlers {
                    ui.gameplay.networking.registerUiSignalClientHandlersRpcHandlers(
                        this,
                        UiSignalClientHandlers
                    )
                    ui.gameplay.networking.registerActionHistoryClientHandlersRpcHandlers(
                        this,
                        ActionHistoryClientHandlers
                    )
                    org.ttt.autogenesis.kvisionapp.audio.registerAudioClientHandlersRpcHandlers(
                        this,
                        AudioClientHandlers
                    )
                }
                Logger.info(LogCategory.NETWORK, "Main: UiSignal/ActionHistory/Audio client handlers registered on WebSocketRpcBridge")

                // If in electron, we must use the bridge provided URL or 127.0.0.1 to avoid localhost issues
                val wsUrl = if (runningInElectron) {
                    ElectronEnvironment.mainServerUrl?.replace("http://", "ws://")?.replace("https://", "wss://")
                        ?: "ws://127.0.0.1:9080"
                } else {
                    "ws://127.0.0.1:9080"
                }

                WebSocketRpcBridge.connect(baseUrl = wsUrl)
                WebSocketRpcBridge.onConnected {
                    WebsocketConfig.websocketId = WebSocketRpcBridge.connectionId ?: ""
                    // Connect the REST bridge and wire AudioClientHandlers.rpcInvoker
                    // so sendReportState() actually transmits AudioReportState to the server.
                    // Using a flag prevents duplicate configure() calls.
                    var restBridgeInitialized = false
                    RestRpcBridge.onConnected {
                        if (!restBridgeInitialized)
                        {
                            restBridgeInitialized = true
                            RestRpcBridge.rpcInvoker?.let { invoker ->
                                AudioClientHandlers.configure(invoker)
                                Logger.info(LogCategory.SYSTEM, "Main: AudioClientHandlers configured with RestRpcBridge.rpcInvoker")
                            }
                        }
                    }
                }
                Logger.info(LogCategory.NETWORK, "Main: WebSocket RPC bridge connected successfully")
            }
            catch(e: Exception)
            {
                Logger.warn(LogCategory.NETWORK, "Main: WebSocket RPC bridge connection failed: ${e.message}")
            }
        }

        val detectionResult = try {
            detectionDeferred.await()
        } catch (err: Throwable) {
            Logger.warn(LogCategory.NETWORK, "Main: Local server detection failed: ${err.message}")
            false
        }
        Logger.info(LogCategory.NETWORK, "Main: Local server detection result = $detectionResult")

        if(runningInElectron || detectionResult)
        {
            if(!runningInElectron)
            {
                ServerExtendConfig.useLocalServer()
                Logger.info(LogCategory.NETWORK, "Starting client in local mode")
            }
        }
        else
        {
            ServerExtendConfig.useLiveServer()
            Logger.info(LogCategory.NETWORK, "Starting client in live mode")
        }

        KEnv.isLocalDevMode = runningInElectron || detectionResult

        try
        {
            ServerExtendBridge.connect()
            BrowserSmokeState.setConnected(true)
            BrowserSmokeState.setTransport(ServerExtendBridge.activeTransport())
            Logger.info(LogCategory.NETWORK, "Main: server-extend bridge connected successfully")

            // Fire-and-forget: warm the billing/usage cache so the top-bar pill and any
            // early-opened Shop/Usage overlay see an accurate credit balance without a
            // visible loading flash. Overlays also re-pull on open.
            BillingState.loadOnLogin()

            if(browserSmokeEnabled)
            {
                val smokeTransport = ServerExtendBridge.activeTransport()
                if(smokeTransport != ServerExtendTransport.GRPC_WEB)
                {
                    while(!ServerExtendBridge.isSessionReady)
                    {
                        delay(50)
                    }
                    BrowserSmokeState.setSessionReady(true)
                }

                val response = ServerExtendBridge.rpcInvoker?.invoke("server.extend.invokeMatchMaking", null)
                    ?: error("RPC invoker unavailable during browser smoke probe")
                if(response.error != null)
                {
                    val message = response.error!!.message
                    BrowserSmokeState.setProbeFailed(message)
                    error("Browser smoke probe failed: ${response.error!!.message}")
                }

                val ticket = response.result?.let {
                    RpcJson.decodeFromJsonElement(GameTicket.serializer(), it)
                } ?: error("Browser smoke probe returned no result")
                BrowserSmokeState.setSessionReady(true)
                BrowserSmokeState.setProbePassed(
                    "server.extend.invokeMatchMaking",
                    ticket.serverUrl
                )
                Logger.info(LogCategory.NETWORK, "Main: browser smoke probe completed with serverUrl=${ticket.serverUrl}")
            }
        }
        catch(e: Exception)
        {
            BrowserSmokeState.setProbeFailed(e.message ?: "unknown error")
            Logger.warn(LogCategory.NETWORK, "Main: server-extend bridge connection failed: ${e.message}")
        }
    }
    
    startApplication(
        ::AutogenesisApp,
        js("module.hot"),
        BootstrapModule,
        CoreModule
    )
}

private fun queryParameter(name: String): String? =
    try
    {
        val params = js("new URLSearchParams(window.location.search)")
        params.get(name) as String?
    }
    catch(_: Throwable)
    {
        null
    }
