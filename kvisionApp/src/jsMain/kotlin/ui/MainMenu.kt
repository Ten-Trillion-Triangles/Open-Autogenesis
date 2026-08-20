package ui

import globals.AccelByteEnv
import globals.KEnv
import globals.World
import io.kvision.core.*
import io.kvision.html.*
import io.kvision.panel.*
import io.kvision.utils.perc
import io.kvision.utils.px
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.ttt.autogenesis.kvisionapp.CommanderCache
import org.ttt.autogenesis.kvisionapp.ResumeAvailabilityListener
import ui.billing.BillingState
import ui.billing.BillingFormatting
import ui.billing.ShopOverlay
import ui.billing.UsageOverlay
import org.ttt.autogenesis.kvisionapp.audio.AudioEngine
import org.ttt.autogenesis.kvisionapp.audio.MenuMusicPlayer
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import structs.Commander
import structs.Player
import structs.matchmaking.GameType
import ui.gameplay.AudioSettings
import ui.gameplay.GameplayUI
import ui.gameplay.SettingsWidget

/**
 * Main menu class that is loaded after the login menu. Sits above the initial background for the game and the bottom
 * general Z layer. Houses the play button, any collection or options buttons, and the tutorial widgets which
 * will play for the first time if the user does not have a save file confirming they have cleared the
 * tutorial.
 */
class MainMenu : VPanel(
    spacing = 0
)
{
    private val collectionOverlay = CollectionOverlay()
    private val settingsWidget = SettingsWidget()
    private val shopOverlay = try { Logger.info(LogCategory.UI, "MainMenu: constructing ShopOverlay"); ShopOverlay().also { Logger.info(LogCategory.UI, "MainMenu: ShopOverlay OK") } } catch (e: Throwable) { Logger.error(LogCategory.UI, "MainMenu: ShopOverlay THREW: " + e::class.simpleName + " msg=" + e.message); throw e }
    private val usageOverlay = try { Logger.info(LogCategory.UI, "MainMenu: constructing UsageOverlay"); UsageOverlay().also { Logger.info(LogCategory.UI, "MainMenu: UsageOverlay OK") } } catch (e: Throwable) { Logger.error(LogCategory.UI, "MainMenu: UsageOverlay THREW: " + e::class.simpleName + " msg=" + e.message); throw e }
    private val scope = kotlinx.coroutines.MainScope()

    /** Id of the currently-playing menu track, if any. Set once the catalog fetch completes, cleared on dispose. */
    private var menuMusicId: String? = null

    init
    {
        // Clear any stale gameplay UI reference when returning to main menu
        globals.KEnv.currentGameplayUI = null

        // Stable e2e test selectors. The data-testid and data-accelbyte-*
        // attributes let Playwright probes confirm the MainMenu mounted
        // and (critically) verify the *real* accelbyteId is present — the
        // `?skipLogin=true` path uses the synthetic `accelbyteId="guest-user"`
        // placeholder, so a probe asserting the attribute is non-empty AND
        // not the placeholder has actually proven a real AccelByte login.
        addCssClass("main-menu")
        setAttribute("data-testid", "main-menu")
        setAttribute("data-accelbyte-user-id", globals.AccelByteEnv.userId)
        setAttribute("data-accelbyte-display-name", globals.AccelByteEnv.displayName)

        // Start the editor's main-menu track on the Music channel.
        // The AudioContext is already resumed (the pre-MainMenu loading
        // screen or a prior user gesture initialized it), so the eventual
        // play() call is the one that actually starts the music
        // playing through the speakers. The first `audio.musicSchedule`
        // that arrives on game start will cross-fade from this menu
        // track into the gameplay music — see [MenuMusicPlayer].
        //
        // The catalog fetch is async, so we launch on the widget's
        // own scope. By the time the user can navigate away the
        // id will be in place; if the widget is disposed first the
        // launched coroutine will see the field still null and bail.
        // [Bug fix] MainMenu is also the kick-off site for
        // AudioEngine.initChannels() on the skipLogin path. On the
        // normal flow LoginWidgets launches `initChannels()` in a
        // fire-and-forget coroutine after auth succeeds, so by the
        // time MainMenu mounts the engine is normally already
        // initialised and awaitReady() returns immediately. On the
        // skipLogin path (and any future code path that reaches
        // MainMenu without going through LoginWidgets) the engine
        // stays at isInitialized=false and awaitReady() will time
        // out, dropping the menu-music call. We therefore also
        // launch initChannels() here as a defensive backup, then
        // await its completion. The call is idempotent: if the
        // engine is already initialised by the time the coroutine
        // runs, initChannels() returns immediately.
        GlobalScope.launch { AudioEngine.initChannels() }

        scope.launch {
            try
            {
                Logger.info(LogCategory.NETWORK, "MainMenu.init: awaiting AudioEngine ready before starting menu music")
                AudioEngine.awaitReady()
                Logger.info(LogCategory.NETWORK, "MainMenu.init: AudioEngine ready, starting menu music")
                // Push the persisted Music + SFX volumes to the engine BEFORE
                // MenuMusicPlayer.start() so the first audible menu note is at
                // the user's chosen volume. applyPersistedToEngine() is a no-op
                // when the engine isn't ready, so the gate above is the only
                // safety net we need.
                Logger.info(LogCategory.UI, "MainMenu.init: pushing persisted audio settings to engine")
                AudioSettings.applyPersistedToEngine()
                val id = MenuMusicPlayer.start()
                if (id != null) menuMusicId = id
            }
            catch (e: Throwable)
            {
                Logger.warn(LogCategory.NETWORK, "MainMenu.init: MenuMusicPlayer.start failed (non-fatal): ${e.message}")
            }
        }

        // Refresh the credit balance so the top-bar pill is accurate on first render
        // and stay subscribed to updates for as long as the menu is alive.
        BillingState.addListener { refreshCreditPill() }
        scope.launch {
            try
            {
                BillingState.refreshBilling()
            }
            catch (e: Throwable)
            {
                Logger.warn(LogCategory.NETWORK, "MainMenu: initial BillingState.refreshBilling failed: ${e.message}")
            }
        }

        // Phase B: the server-pushed `client.resumeAvailable` notification
        // is authoritative for the resume modal. The previous client-pulled
        // `MatchmakingClient.hasRunningGame()` one-shot check that lived
        // here (lines 118-190 prior) raced the auto-restore and missed the
        // consumed-sentinel; that path is replaced by the
        // [ResumeAvailabilityListener] registered in [Main] when the WS
        // connects. No pull-side check is needed.

        width = 100.perc
        height = CssSize(100, UNIT.vh)
        justifyContent = JustifyContent.SPACEBETWEEN
        alignItems = AlignItems.STRETCH

        // ========== TOP BAR ==========
        hPanel(
            className = "main-menu-header",
            alignItems = AlignItems.CENTER,
            justify = JustifyContent.SPACEBETWEEN
        ) {
            height = 80.px
            width = 100.perc
            padding = 0.px
            paddingLeft =20.px
            paddingRight = 20.px

            // Left side: Display Name + Version
            hPanel(alignItems = AlignItems.CENTER, spacing = 20) {
                // Display Name
                span("${AccelByteEnv.displayName}") {
                    addCssClass("display-name")
                }

                // Version Number
                span("v1.0.0") {
                    addCssClass("version-text")
                }
            }

            // Right side: Credits + Options
            hPanel(alignItems = AlignItems.CENTER, spacing = 15) {
                // Credits Display
                hPanel(alignItems = AlignItems.CENTER, spacing = 8) {
                    addCssClass("credits-container")
                    
                    span("💎") {
                        addCssClass("credit-icon")
                    }
                   span(BillingState.billing?.let { BillingFormatting.formatCredits(it.credits) } ?: "—") {
                        addCssClass("credit-amount")
                    }
                    button("+", className = "btn btn-add-credits")
                }

                // Shop button
                button("🛒 Shop", className = "btn btn-secondary-action") {
                    onClick {
                        openShop()
                    }
                }

                // Usage button
                button("📊 Usage", className = "btn btn-secondary-action") {
                    onClick {
                        openUsage()
                    }
                }

                // Options Button (gear icon)
                button("⚙", className = "btn btn-options") {
                    onClick {
                        openSettings()
                    }
                }
            }
        }

        // ========== CENTER SPACER (for background to show through) ==========
        div {
            addCssClass("main-menu-center")
            height = 0.px
            flexGrow = 1
        }

        // ========== BOTTOM SECTION ==========
        hPanel(
            className = "main-menu-bottom",
            alignItems = AlignItems.FLEXEND,
            justify = JustifyContent.SPACEBETWEEN
        ) {
            width = 100.perc
            padding = 30.px

            // Bottom Left: Friends List Icon
            button("👥", className = "btn btn-friends")

            // Bottom Center/Right: Action Buttons Container
            hPanel(alignItems = AlignItems.FLEXEND, spacing = 20) {
                // Collection Button
                button("Collection", className = "btn btn-secondary-action") {
                    onClick {
                        openCollection()
                    }
                }

                // New Commander Button
                button("New Commander +", className = "btn btn-secondary-action") {
                    onClick {
                        openCommanderCreationDialog()
                    }
                }

                // Giant PLAY Button
                button("PLAY", className = "btn btn-play") {
                    onClick {
                        openPlayFlow()
                    }
                }
            }
        }
        wireResumeDialog()
    }

    /**
     * Wires [ResumeAvailabilityListener] callbacks so the server-pushed
     * `client.resumeAvailable` notification mounts a [ui.ResumeOrNewDialog]
     * that routes the three buttons back to this menu. Also calls
     * [ResumeAvailabilityListener.register] to subscribe to the WS handler
     * (idempotent — only the first call attaches).
     *
     * Phase B of the resume-game-architecture plan replaces the previous
     * client-pulled `hasRunningGame` check (see
     * `.hermes/plans/resume-game-architecture/plan.md`) with this server
     * push; without this wiring the modal is non-functional.
     */
    private fun wireResumeDialog()
    {
        ResumeAvailabilityListener.dialogOnResume = { _ -> beginResumeSession() }
        ResumeAvailabilityListener.dialogOnNewGame = { openPlayFlow() }
        ResumeAvailabilityListener.dialogOnCancel = { /* dialog hides itself */ }
        ResumeAvailabilityListener.register()
        Logger.info(LogCategory.NETWORK, "MainMenu.wireResumeDialog: resume dialog callbacks registered")
    }

    private fun beginSinglePlayerSession(commander: Commander, aiOpponentCount: Int)
    {
        val messageBox = MessageBox(
            boxTitle = "Matchmaking",
            message = "Contacting local game server...",
            showThrobber = true
        )
        KEnv.mainRoot?.add(messageBox)
        World.localPlayer = buildLocalPlayerFromCommander(commander)
        // AudioContext is now initialized by the pre-MainMenu LoadingScreen
        // (see ui.LoadingScreen and .omx/plans/prometheus-strict/loading-screen.md).
        // These calls are kept here as defensive no-ops in case a future code path
        // reaches MainMenu without going through the loading screen (e.g. tests,
        // embedded launchers). They are idempotent and cheap.
        // B1 invariant: if a NEW user gesture ever lands here, initContext() MUST
        // run synchronously on the call stack so AudioContext.resume() satisfies
        // browser autoplay policy. Do NOT defer to GlobalScope.
        AudioEngine.initContext()
        GlobalScope.launch { AudioEngine.initChannels() }

        MainScope().launch {
            // Clear any saved snapshot before kicking off a new match so the
            // next disconnect captures the new game's state cleanly. Best-
            // effort: a failed clear just means the next disconnect's
            // serializeCurrentWorldSnapshotToUserRecord will overwrite the
            // stale record, so we do not gate game flow on the result.
            try
            {
                MatchmakingClient.clearRunningGame()
            }
            catch (err: Throwable)
            {
                Logger.warn(LogCategory.NETWORK, "MainMenu: clearRunningGame failed (non-fatal): ${err.message}")
            }

            val success = try
            {
                MatchmakingClient.requestSinglePlayerMatch(commander, aiOpponentCount)
            }
            catch(err: Throwable)
            {
                Logger.warn(LogCategory.NETWORK, "MainMenu: Matchmaking request failed: ${err.message}")
                false
            }

            messageBox.setThrobber(false)

            if(success)
            {
                messageBox
                    .setTitle("Match Ready")
                    .setMessage("Local session configured. Opening gameplay.")
                    .setButtons(ok = true, cancel = false)
                messageBox.onConfirm = {
                    KEnv.mainRoot?.remove(messageBox)

                    // BUG 27 (2026-06-27): stop menu music before any gameplay-side work.
                    // The identity-sync hot path can construct a GameplayUI BEFORE
                    // the user clicks Play, so by the time we get here `mountGameplayUI`
                    // may be a no-op (existing instance found). The stop needs to
                    // happen regardless of which mount path we take.
                    stopMenuMusicForGameplayHandoff("beginGameStartSession-identity-sync")

                    val existing = globals.KEnv.currentGameplayUI
                    if(existing != null)
                    {
                        Logger.info(LogCategory.NETWORK, "MainMenu: GameplayUI already exists (from identity sync), activating existing instance")
                        if(KEnv.appStack?.getChildren()?.contains(existing) == false)
                        {
                            KEnv.appStack?.add(existing)
                        }
                        KEnv.appStack?.activeIndex = KEnv.appStack?.getChildren()?.indexOf(existing) ?: 2
                    }
                    else
                    {
                        Logger.info(LogCategory.NETWORK, "MainMenu: No existing GameplayUI, instantiating now")
                        val gp = GameplayUI()
                        KEnv.appStack?.add(gp)
                        KEnv.appStack?.activeIndex = KEnv.appStack?.getChildren()?.indexOf(gp) ?: 2
                    }
                }
            }
            else
            {
                messageBox
                    .setTitle("Matchmaking Failed")
                    .setMessage("Unable to reach the local server. Double-check it is running.")
                    .setButtons(ok = true, cancel = false)
                messageBox.onConfirm = {
                    KEnv.mainRoot?.remove(messageBox)
                }
            }
        }
    }


    /**
     * Drives the "Resume saved game?" path. Shows a single throbber
     * [MessageBox] while the server rehydrates [gameState.WorldManager] from
     * the user's VFS record, then mounts [GameplayUI] the same way
     * [beginSinglePlayerSession] does on success.
     *
     * Failure modes:
     *  - [MatchmakingClient.ResumeOutcome.NoneSaved] — the server has no
     *    record. Swap the throbber to "No saved game found." and let the
     *    user fall back to the normal new-game flow.
     *  - [MatchmakingClient.ResumeOutcome.Failed] — RPC error. Show
     *    "Failed to resume saved game." and stay in the menu.
     *
     * Mode branch (Phase C): in dev mode the local server handles
     * `server.restoreRunningGame` directly (no match2 needed). In live
     * mode the call goes through server-extend's `requestResume` RPC
     * which builds a single-player match2 ticket with `resumeFromVfs`
     * and routes it to a free DS; the new WS connection to that DS
     * triggers [UiSignalRpcHandlers.sendInitialSync] with the resumed
     * world state (Phase D).
     */
    private fun beginResumeSession()
    {
        val messageBox = MessageBox(
            boxTitle = "Resuming",
            message = "Resuming saved game...",
            showThrobber = true
        )
        KEnv.mainRoot?.add(messageBox)

        MainScope().launch {
            if (globals.LiveMode.liveMode)
            {
                // Live mode: ask server-extend to provision a resume session.
                // server-extend builds a single-player match2 ticket tagged
                // resume:true, routes it to a DS, and the DS rehydrates the
                // snapshot on setGameMode. The returned GameTicket carries
                // the DS serverUrl; we must reconnect the WS to that URL so
                // the existing client.initialSync path delivers the resumed
                // state and gameplay mounts from there.
                val liveTicket = try
                {
                    MatchmakingClient.requestResumeLive()
                }
                catch (err: Throwable)
                {
                    Logger.warn(LogCategory.NETWORK, "MainMenu.beginResumeSession: requestResumeLive threw: ${err.message}")
                    null
                }
                messageBox.setThrobber(false)
                if (liveTicket == null || liveTicket.serverUrl.isBlank())
                {
                    messageBox
                        .setTitle("Resume Failed")
                        .setMessage("Unable to provision a live resume session. Try again or start a new game.")
                        .setButtons(ok = true, cancel = false)
                    messageBox.onConfirm = {
                        KEnv.mainRoot?.remove(messageBox)
                    }
                    return@launch
                }
                val connected = try
                {
                    MatchmakingClient.connectToGameServer(liveTicket.serverUrl)
                }
                catch (err: Throwable)
                {
                    Logger.warn(LogCategory.NETWORK, "MainMenu.beginResumeSession: connectToGameServer threw: ${err.message}")
                    false
                }
                if (!connected)
                {
                    messageBox
                        .setTitle("Resume Failed")
                        .setMessage("Matched, but could not reach the resumed server at ${liveTicket.serverUrl}.")
                        .setButtons(ok = true, cancel = false)
                    messageBox.onConfirm = {
                        KEnv.mainRoot?.remove(messageBox)
                    }
                    return@launch
                }
                messageBox
                    .setTitle("Match Resumed")
                    .setMessage("Saved game restored on the live server. Connecting to the resumed session...")
                    .setButtons(ok = true, cancel = false)
                messageBox.onConfirm = {
                    KEnv.mainRoot?.remove(messageBox)
                    mountGameplayUI()
                }
                return@launch
            }

            val outcome = try
            {
                MatchmakingClient.requestResume()
            }
            catch (err: Throwable)
            {
                Logger.warn(LogCategory.NETWORK, "MainMenu.beginResumeSession: requestResume threw: ${err.message}")
                MatchmakingClient.ResumeOutcome.Failed(err.message ?: "unknown error")
            }

            messageBox.setThrobber(false)
            when (outcome)
            {
                MatchmakingClient.ResumeOutcome.Restored -> {
                    messageBox
                        .setTitle("Match Resumed")
                        .setMessage("Saved game restored. Opening gameplay.")
                        .setButtons(ok = true, cancel = false)
                    messageBox.onConfirm = {
                        KEnv.mainRoot?.remove(messageBox)
                        mountGameplayUI()
                    }
                }
                MatchmakingClient.ResumeOutcome.NoneSaved -> {
                    messageBox
                        .setTitle("No Saved Game")
                        .setMessage("No saved game found on the server.")
                        .setButtons(ok = true, cancel = false)
                    messageBox.onConfirm = {
                        KEnv.mainRoot?.remove(messageBox)
                    }
                }
                is MatchmakingClient.ResumeOutcome.Failed -> {
                    messageBox
                        .setTitle("Resume Failed")
                        .setMessage("Failed to resume saved game: ${outcome.reason}")
                        .setButtons(ok = true, cancel = false)
                    messageBox.onConfirm = {
                        KEnv.mainRoot?.remove(messageBox)
                    }
                }
            }
        }
    }

    /**
     * Entry point for the matchmaking flow. Switches between the local dev path ([GameType.SINGLEPLAYER])
     * and the live PvP path ([GameType.MULTIPLAYER]) and surfaces a single `MessageBox` for the
     * user during the wait.
     */
    private fun beginMatchSession(commander: Commander, gameType: GameType, aiOpponentCount: Int)
    {
        // Web Push subscription: ask the user for notification permission and
        // register a push subscription on the server. Called inside the
        // Play button click handler so the browser treats this as a user
        // gesture (the Push API rejects subscribe() calls outside one).
        // The subscribe RPC runs asynchronously — the game proceeds without
        // waiting for the push registration to complete.
        org.ttt.autogenesis.kvisionapp.PushNotificationService.subscribeIfPermitted()

        when (gameType)
        {
            GameType.SINGLEPLAYER -> beginSinglePlayerSession(commander, aiOpponentCount)
            GameType.MULTIPLAYER -> beginMultiplayerSession(commander, matchPool = "default")
            GameType.FRIEND -> {
                Logger.warn(LogCategory.NETWORK, "MainMenu: FRIEND game type selected but not implemented; falling back to single player")
                beginSinglePlayerSession(commander, aiOpponentCount)
            }
        }
    }

    /**
     * Drives the live PvP matchmaking flow: throbber + "Searching for opponents..." → live
     * match2 ticket via server-extend → reconnect [WebSocketRpcBridge] to the resolved dedicated
     * server URL → mount [GameplayUI].
     */
    private fun beginMultiplayerSession(commander: Commander, matchPool: String)
    {
        val messageBox = MessageBox(
            boxTitle = "Matchmaking",
            message = "Searching for opponents...",
            showThrobber = true
        )
        KEnv.mainRoot?.add(messageBox)
        World.localPlayer = buildLocalPlayerFromCommander(commander)
        // AudioContext is initialized by the pre-MainMenu LoadingScreen; initContext() remains a
        // defensive no-op for embedded launchers / tests.
        AudioEngine.initContext()
        GlobalScope.launch { AudioEngine.initChannels() }

        MainScope().launch {
            val outcome = try
            {
                MatchmakingClient.requestMultiplayerMatch(commander, matchPool)
            }
            catch (err: Throwable)
            {
                Logger.warn(LogCategory.NETWORK, "MainMenu: Multiplayer matchmaking request failed: ${err.message}")
                MatchOutcome.Failed(err.message ?: "matchmaking request threw")
            }

            when (outcome)
            {
                is MatchOutcome.Ready -> {
                    val connected = try
                    {
                        MatchmakingClient.connectToGameServer(outcome.serverUrl)
                    }
                    catch (err: Throwable)
                    {
                        Logger.warn(LogCategory.NETWORK, "MainMenu: connectToGameServer failed: ${err.message}")
                        false
                    }

                    if (!connected)
                    {
                        messageBox.setThrobber(false)
                        messageBox
                            .setTitle("Matchmaking Failed")
                            .setMessage("Matched, but could not reach the game server at ${outcome.serverUrl}.")
                            .setButtons(ok = true, cancel = false)
                        messageBox.onConfirm = {
                            KEnv.mainRoot?.remove(messageBox)
                        }
                        return@launch
                    }

                    messageBox.setThrobber(false)
                    messageBox
                        .setTitle("Match Found")
                        .setMessage("Match ready at ${outcome.serverUrl}. Opening gameplay.")
                        .setButtons(ok = true, cancel = false)
                    messageBox.onConfirm = {
                        KEnv.mainRoot?.remove(messageBox)
                        mountGameplayUI()
                    }
                }
                is MatchOutcome.Cancelled -> {
                    messageBox.setThrobber(false)
                    messageBox
                        .setTitle("Matchmaking Cancelled")
                        .setMessage("The match was cancelled before opponents were found.")
                        .setButtons(ok = true, cancel = false)
                    messageBox.onConfirm = {
                        KEnv.mainRoot?.remove(messageBox)
                    }
                }
                is MatchOutcome.Failed -> {
                    messageBox.setThrobber(false)
                    messageBox
                        .setTitle("Matchmaking Failed")
                        .setMessage(outcome.reason)
                        .setButtons(ok = true, cancel = false)
                    messageBox.onConfirm = {
                        KEnv.mainRoot?.remove(messageBox)
                    }
                }
            }
        }
    }

    /**
     * Mounts [GameplayUI] onto the active app stack, reusing the existing instance if one is
     * already alive (e.g. created by an identity-sync hot path).
     */
    private fun mountGameplayUI()
    {
        // BUG 27 (2026-06-27): stop menu music BEFORE any gameplay-side
        // work begins (mounting GameplayUI, dispatching initial sync, etc).
        // Without this, the playSchedule cross-fade that fires inside
        // GameInit fights the still-running menu-music track, and the user
        // hears both audio sources overlap for ~2s. The cross-fade assumes
        // the menu track has been removed from the playingObjects set;
        // stopMenu() does that synchronously and is idempotent.
        //
        // Putting this here (rather than at every call site) covers all
        // three mount paths: resume (beginResumeSession), matchmaking
        // (server found), and identity-sync hot path.
        stopMenuMusicForGameplayHandoff("mountGameplayUI")

        val existing = globals.KEnv.currentGameplayUI
        if (existing != null)
        {
            Logger.info(LogCategory.NETWORK, "MainMenu: GameplayUI already exists (from identity sync), activating existing instance")
            if (KEnv.appStack?.getChildren()?.contains(existing) == false)
            {
                KEnv.appStack?.add(existing)
            }
            KEnv.appStack?.activeIndex = KEnv.appStack?.getChildren()?.indexOf(existing) ?: 2
        }
        else
        {
            Logger.info(LogCategory.NETWORK, "MainMenu: No existing GameplayUI, instantiating now")
            val gp = GameplayUI()
            KEnv.appStack?.add(gp)
            KEnv.appStack?.activeIndex = KEnv.appStack?.getChildren()?.indexOf(gp) ?: 2
        }
    }

    private fun buildLocalPlayerFromCommander(commander: Commander): Player
    {
        return Player(
            name = commander.name,
            commanderType = commander.type,
            trait = commander.trait,
            description = commander.description,
            history = "Deployed via commander selection"
        )
    }

    private fun gatherPlayableCommanders(): List<Commander>
    {
        val seenKeys = mutableSetOf<String>()
        val commanders = mutableListOf<Commander>()

        fun addIfUnique(candidate: Commander)
        {
            val normalizedKey = candidate.name.ifBlank { "unnamed" }.trim().lowercase()
            if(normalizedKey.isBlank()) return
            if(seenKeys.add(normalizedKey))
            {
                commanders.add(candidate)
            }
        }

        World.availableCommanders.forEach(::addIfUnique)
        CommanderCache.cachedCommanderNames().forEach { name ->
            CommanderCache.loadCommanderRecord(name)?.let { cached ->
                addIfUnique(cached)
            }
        }

        return commanders
    }

    /**
     * Opens the commander creation dialog popup.
     */
    /**
     * Opens the New-Game / play flow. Gathers the user's playable
     * commanders, shows an error if there are none, and otherwise
     * opens the [CommanderSelectionDialog] that lets the user pick a
     * commander, game type, and AI opponent count before matchmaking
     * begins. Also called by the resume dialog's "New Game" button —
     * see [wireResumeDialog].
     */
    private fun openPlayFlow()
    {
        val playableCommanders = gatherPlayableCommanders()
        if(playableCommanders.isEmpty())
        {
            KEnv.mainRoot?.add(
                MessageBox(
                    boxTitle = "Error",
                    message = "Please create a commander before playing.",
                    showOk = true
                )
            )
            return
        }

        val selectionDialog = CommanderSelectionDialog(
            playableCommanders,
            onConfirmSelection = { commander, gameType, aiCount ->
                Logger.debug(LogCategory.UI, "MainMenu: confirmed commander '${commander.name}', gameType=$gameType, AI opponents: $aiCount, starting matchmaking")
                beginMatchSession(commander, gameType, aiCount)
            },
            onCancelSelection = {
                Logger.debug(LogCategory.UI, "MainMenu: commander selection cancelled.")
            }
        )

        KEnv.mainRoot?.add(selectionDialog)
    }

    private fun openCommanderCreationDialog()
    {
        val dialog = CommanderCreationDialog(
            onCreateCommander = { name, description, nationDesc ->
                // The actual save path lives inside CommanderCreationDialog —
                // it calls extend.saveCommander directly so this callback is
                // never fired today. Logged at DEBUG so a future caller that
                // wires the callback up has a tracer for the data flow.
                Logger.debug(
                    LogCategory.UI,
                    "MainMenu.openCommanderCreationDialog.onCreateCommander: " +
                        "name='$name' description.len=${description.length} " +
                        "nationDescription.len=${nationDesc.length}"
                )
            },
            onCancel = {
                Logger.debug(LogCategory.UI, "MainMenu.openCommanderCreationDialog.onCancel")
            }
        )
        
        KEnv.mainRoot?.add(dialog)
    }

    private fun openCollection()
    {
        if(collectionOverlay.parent == null)
        {
            KEnv.mainRoot?.add(collectionOverlay)
        }
        collectionOverlay.show()
    }

    private fun openShop()
    {
        Logger.debug(LogCategory.UI, "MainMenu.openShop: ENTER (Modal-based; auto-registered with Root in init)")
        // The Modal auto-registers itself with Root in its constructor via
        // KVision's Modal.addModal() — no need to add it to a StackPanel or
        // the root manually. Just call show() to open Bootstrap's modal.
        shopOverlay.showOverlay()
        Logger.debug(LogCategory.UI, "MainMenu.openShop: showOverlay called")
        // Re-pull on open so the balance is fresh.
        scope.launch {
            try
            {
                BillingState.refreshBilling()
            }
            catch (e: Throwable)
            {
                Logger.warn(LogCategory.NETWORK, "MainMenu: openShop refresh failed: ${e.message}")
            }
        }
    }

    private fun openUsage()
    {
        Logger.debug(LogCategory.UI, "MainMenu.openUsage: ENTER (Modal-based; auto-registered with Root in init)")
        // The Modal auto-registers itself with Root in its constructor.
        // We just call show() to open Bootstrap's modal.
        usageOverlay.showOverlay()
        Logger.debug(LogCategory.UI, "MainMenu.openUsage: showOverlay called")
    }

    private fun openSettings()
    {
        if(settingsWidget.parent == null)
        {
            KEnv.mainRoot?.add(settingsWidget)
        }
        settingsWidget.show()
    }

    /**
     * Updates the credit amount text inside the top-bar credits pill by
     * scanning the rendered DOM for the `.credit-amount` element. Triggered
     * by [BillingState] listener callbacks.
     */
    private fun refreshCreditPill()
    {
        val newAmount = BillingState.billing?.let { BillingFormatting.formatCredits(it.credits) } ?: return
        val doc = kotlinx.browser.document
        val nodes = doc.querySelectorAll(".main-menu-header .credit-amount")
        // Walk the NodeList using a typed for loop. JS NodeList lacks Kotlin's list
        // extension; the getLength()/item() API is the portable bridge.
        for (i in 0 until nodes.length)
        {
            val node = nodes.item(i)
            if (node is org.w3c.dom.HTMLElement)
            {
                node.textContent = newAmount
            }
        }
    }
    /**
     * Stop the menu-music track (if any) when the widget is torn
     * down. Normal game start does not flow through here — the
     * `playSchedule` cross-fade handles that — but logout,
     * page-reload, or any other path that removes the menu from
     * the DOM without starting a game would otherwise leave the
     * menu music running forever. The runner treats a double-stop
     * as a no-op, so this is safe to call even if `playSchedule`
     * has already removed the id from the tracking set.
     */
    override fun dispose()
    {
        val id = menuMusicId
        if (id != null)
        {
            try
            {
                MenuMusicPlayer.stop(id)
            }
            catch (e: Throwable)
            {
                Logger.warn(LogCategory.NETWORK, "MainMenu.dispose: MenuMusicPlayer.stop failed (non-fatal): ${e.message}")
            }
            menuMusicId = null
        }
        super.dispose()
    }

    /**
     * Synchronously stop the menu-music track (if any) and clear the
     * tracking field. Called from [mountGameplayUI] before any gameplay-side
     * work to prevent menu-music-overlap-with-gameplay-audio (BUG 27,
     * 2026-06-27).
     *
     * Idempotent: safe to call when no track is playing. Logs at INFO when
     * a track was actually stopped so the audit trail captures which call
     * site triggered the handoff.
     */
    internal fun stopMenuMusicForGameplayHandoff(caller: String)
    {
        val id = menuMusicId
        if (id == null)
        {
            Logger.debug(LogCategory.NETWORK, "MainMenu.stopMenuMusicForGameplayHandoff: no menu music to stop (caller=$caller)")
            return
        }
        try
        {
            MenuMusicPlayer.stop(id)
            Logger.info(LogCategory.NETWORK, "MainMenu.stopMenuMusicForGameplayHandoff: stopped menu-music id=$id (caller=$caller)")
        }
        catch (e: Throwable)
        {
            Logger.warn(LogCategory.NETWORK, "MainMenu.stopMenuMusicForGameplayHandoff: MenuMusicPlayer.stop failed (non-fatal, caller=$caller): ${e.message}")
        }
        finally
        {
            menuMusicId = null
        }
    }
}