package ui.gameplay

import io.kvision.core.*
import io.kvision.form.check.checkBox
import io.kvision.form.number.rangeInput
import io.kvision.html.*
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.perc
import io.kvision.utils.px
import kotlinx.browser.localStorage
import kotlinx.coroutines.*
import org.ttt.autogenesis.audio.AudioChannelIds
import org.ttt.autogenesis.kvisionapp.audio.AudioEngine
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.SurrenderRequest
import org.ttt.autogenesis.network.SurrenderResponse
import org.ttt.autogenesis.kvisionapp.WebSocketRpcBridge
import ui.MainMenu
import globals.KEnv
import globals.World

/**
 * Modal presenting quick gameplay settings toggles.
 */
class SettingsWidget(
    private val showSurrender: Boolean = false
) : SimplePanel(className = "login-widget-window")
{
    private var surrenderDialog: SurrenderConfirmDialog? = null

    init
    {
        // Shared styling & positioning — mirrors DelegateWidget.kt:62-87.
        // Anchor to the safe viewport region (100px score bar at the top,
        // 200px command box at the bottom). The modal fills the middle band
        // and centers its content there. `top`/`bottom` insets (instead of
        // `top: 50%; transform`) keep the modal fully inside the safe zone
        // regardless of viewport height — the previous `top: 50%` + fixed
        // `height: 600px` placed the bottom edge of the modal behind the
        // command box once the in-game SURRENDER button pushed the natural
        // content height past 600px.
        //
        // Horizontal centering uses `left: calc(50% - 300px)` (half the 600px
        // width) instead of `left: 50%` + `transform: translateX(-50%)`. The
        // CSS `dialogFadeIn` animation (see night-mode.css) touches `transform`
        // on its `from`/`to` keyframes — using `translateX(-50%)` would be
        // clobbered by the animation, leaving the modal pinned to the right
        // edge during fade-in before snapping to center on completion.
        width = 600.px
        height = io.kvision.core.CssSize(100, io.kvision.core.UNIT.perc)
        position = Position.FIXED
        top = 120.px
        bottom = 220.px
        setStyle("left", "calc(50% - 300px)")
        setStyle("max-width", "calc(100vw - 40px)")
        setStyle("max-height", "calc(100vh - 340px)")
        zIndex = 200
        padding = 30.px

        // box-sizing: border-box so padding is included in width/height
        setStyle("box-sizing", "border-box")
        // Clip anything that escapes the rounded modal frame; the inner
        // content vPanel uses `overflow: auto` to scroll if it overflows.
        overflow = Overflow.HIDDEN

        display = Display.NONE
        flexDirection = FlexDirection.COLUMN
        alignItems = AlignItems.CENTER
        // Pack from top; the content vPanel uses flexGrow=1 to fill the
        // available vertical band between the header and the footer
        // CLOSE button. If content overflows the band, the vPanel
        // scrolls internally instead of pushing CLOSE off-screen.
        justifyContent = JustifyContent.FLEXSTART

        // Animation
        setStyle("animation", "dialogFadeIn 0.3s ease-out")

        // Header (fixed at top of flex column)
        h4("Game Settings") {
            color = Color.name(Col.CYAN)
            fontSize = 32.px
            fontWeight = FontWeight.BOLD
            marginBottom = 24.px
            textShadow = TextShadow(0.px, 0.px, 5.px, Color.name(Col.CYAN))
        }

        // Content (scrollable middle band — flexGrow=1 + overflow=AUTO so
        // the inner section scrolls when the modal's available height
        // (calc(100vh - 340px)) is smaller than the natural content
        // height. `min-height: 0` lets the flex child shrink below its
        // content's natural size, which is required for `overflow: auto`
        // to engage on a flex item.)
        vPanel(spacing = 24, alignItems = AlignItems.STRETCH) {
            width = 90.perc
            flexGrow = 1
            overflow = Overflow.AUTO
            setStyle("min-height", "0")

            // Music Volume
            vPanel(spacing = 8) {
                var musicLabel: io.kvision.html.Span? = null
                hPanel(justify = JustifyContent.SPACEBETWEEN) {
                    span("Music Volume") { 
                        color = Color.name(Col.WHITE)
                        fontSize = 18.px
                    }
                    musicLabel = span("${AudioSettings.musicPercent()}%") { 
                        color = Color.name(Col.CYAN)
                        fontSize = 18.px
                    }
                }
                
                val musicVolumeValue = AudioSettings.musicPercent()
                rangeInput(min = 0, max = 100, step = 1, value = musicVolumeValue) {
                    width = 100.perc
                    onInput {
                        val newValue = (this.value?.toInt() ?: musicVolumeValue)
                        musicLabel?.content = "${newValue}%"
                        // Persist FIRST, then push to engine. If the push
                        // throws, the persisted state still matches what
                        // the user just dragged to.
                        AudioSettings.saveMusicPercent(newValue)
                        Logger.debug(
                            LogCategory.UI,
                            "SettingsWidget.onMusicSliderInput: ${newValue}% → " +
                            "AudioEngine.setChannelVolume('${AudioChannelIds.MUSIC_MASTER_ID}', ${newValue / 100f})"
                        )
                        AudioEngine.setChannelVolume(
                            AudioChannelIds.MUSIC_MASTER_ID,
                            newValue / 100f,
                            false
                        )
                    }
                }
            }

            // SFX Volume
            vPanel(spacing = 8) {
                var sfxLabel: io.kvision.html.Span? = null
                hPanel(justify = JustifyContent.SPACEBETWEEN) {
                    span("SFX Volume") { 
                        color = Color.name(Col.WHITE)
                        fontSize = 18.px
                    }
                    sfxLabel = span("${AudioSettings.sfxPercent()}%") { 
                        color = Color.name(Col.CYAN)
                        fontSize = 18.px
                    }
                }
                
                val sfxVolumeValue = AudioSettings.sfxPercent()
                rangeInput(min = 0, max = 100, step = 1, value = sfxVolumeValue) {
                    width = 100.perc
                    onInput {
                        val newValue = (this.value?.toInt() ?: sfxVolumeValue)
                        sfxLabel?.content = "${newValue}%"
                        // Persist FIRST, then push to engine. See Music
                        // branch for the rationale.
                        AudioSettings.saveSfxPercent(newValue)
                        Logger.debug(
                            LogCategory.UI,
                            "SettingsWidget.onSfxSliderInput: ${newValue}% → " +
                            "AudioEngine.setChannelVolume('${AudioChannelIds.SFX_CHANNEL_ID}', ${newValue / 100f})"
                        )
                        AudioEngine.setChannelVolume(
                            AudioChannelIds.SFX_CHANNEL_ID,
                            newValue / 100f,
                            false
                        )
                    }
                }
            }

            // Toggles
            hPanel(justify = JustifyContent.SPACEBETWEEN, alignItems = AlignItems.CENTER) {
                span("Show Tooltips") { 
                    color = Color.name(Col.WHITE)
                    fontSize = 18.px
                }
                checkBox(value = loadBoolean(SHOW_TOOLTIPS_KEY, true)) {
                    setStyle("cursor", "pointer")
                    onClick {
                        saveBoolean(SHOW_TOOLTIPS_KEY, this.value ?: true)
                    }
                }
            }
            
            hPanel(justify = JustifyContent.SPACEBETWEEN, alignItems = AlignItems.CENTER) {
                span("Fullscreen Mode") {
                    color = Color.name(Col.WHITE)
                    fontSize = 18.px
                }
                checkBox(value = loadBoolean(FULLSCREEN_KEY, false)) {
                    setStyle("cursor", "pointer")
                    onClick {
                        saveBoolean(FULLSCREEN_KEY, this.value ?: false)
                    }
                }
            }

            // Surrender (in-game only) — red button centered below toggles
            if (showSurrender) {
                div {
                    height = 1.px
                    background = Background(Color.rgba(255, 80, 80, 60))
                    marginTop = 12.px
                    marginBottom = 4.px
                }
                button("SURRENDER", icon = "fas fa-flag", className = "btn btn-surrender") {
                    width = 100.perc
                    height = 50.px
                    fontSize = 18.px
                    fontWeight = FontWeight.BOLD
                    color = Color.name(Col.WHITE)
                    setStyle("background", "linear-gradient(135deg, #8b1a1a, #b22222)")
                    setStyle("border", "1px solid #ff4444")
                    setStyle("cursor", "pointer")
                    marginTop = 8.px
                    onClick {
                        if (surrenderDialog == null) {
                            surrenderDialog = SurrenderConfirmDialog(
                                onConfirm = { fireSurrenderRpc() }
                            )
                            // Attach to mainRoot, NOT as a child of SettingsWidget.
                            // SettingsWidget carries backdrop-filter via .login-widget-window,
                            // which would make it the dialog's containing block and
                            // break position:fixed (kvision skill pitfall #5).
                            // mainRoot has no transform/filter/backdrop-filter ancestors,
                            // so fixed positioning resolves against the viewport.
                            if(surrenderDialog?.parent == null)
                            {
                                KEnv.mainRoot?.add(surrenderDialog!!)
                            }
                        }
                        Logger.debug(LogCategory.UI, "SettingsWidget: SURRENDER clicked, opening confirmation dialog")
                        surrenderDialog?.show()
                    }
                }
            }
        }

        // Footer (Close Button) — fixed at the bottom of the flex column.
        // Lives OUTSIDE the scrollable content vPanel so it stays pinned
        // to the bottom of the modal regardless of how much content
        // scrolls inside the vPanel.
        button("CLOSE", icon = "fas fa-times", className = "btn btn-play") {
            width = 200.px
            height = 60.px
            fontSize = 24.px
            fontWeight = FontWeight.BOLD
            color = Color.name(Col.WHITE)
            marginTop = 20.px
            marginBottom = 10.px
            onClick {
                // Resume AudioContext from a user gesture context to satisfy browser autoplay policy.
                // B1 fix: initContext() must run SYNCHRONOUSLY in the click handler so
                // AudioContext.resume() is on the user-gesture call stack.
                AudioEngine.initContext()
                GlobalScope.launch { AudioEngine.initChannels() }
                this@SettingsWidget.hide()
            }
        }
    }

    /**
     * Displays the settings overlay.
     *
     * Re-applies the persisted Music + SFX volumes to the engine on
     * every show. This is a no-op if the engine is already at the
     * right level (idempotent — see [AudioSettings.applyPersistedToEngine]),
     * and it is a no-op when the engine isn't ready yet (the call
     * site is gated on [AudioEngine.isReady]). The MainMenu init
     * path is the primary push; this is the belt-and-suspenders
     * guarantee for any code path that constructs the widget after
     * the engine is already live.
     */
    override fun show()
    {
        display = Display.FLEX
        visible = true
        if (AudioEngine.isReady) {
            Logger.debug(
                LogCategory.UI,
                "SettingsWidget.show: re-applying persisted audio settings to engine"
            )
            AudioSettings.applyPersistedToEngine()
        }
    }

    /**
     * Hides the settings overlay.
     */
    override fun hide()
    {
        display = Display.NONE
        visible = false
    }

    /**
     * Fires `game.surrender` for the local player. On `accepted=true`,
     * hides the settings modal and navigates to MainMenu. On rejection
     * or RPC failure, logs the reason and keeps the user in-game (they
     * can retry or take a different action).
     *
     * Mirrors [DelegateWidget.save]'s `MainScope().launch { ... }`
     * coroutine pattern. The local player name is the canonical
     * surrender target — server validates ownership via
     * `WorldManager.findPlayerFromStats`.
     */
    private fun fireSurrenderRpc()
    {
        val playerName = World.localPlayer.name
        if (playerName.isBlank()) {
            Logger.warn(LogCategory.UI, "SettingsWidget.fireSurrenderRpc: local player name is blank; refusing to surrender")
            return
        }

        MainScope().launch {
            val invoker = WebSocketRpcBridge.rpcInvoker
            if (invoker == null) {
                Logger.warn(LogCategory.UI, "SettingsWidget.fireSurrenderRpc: WebSocketRpcBridge.rpcInvoker is null; cannot surrender")
                return@launch
            }
            try {
                val response = invoker.invoke(
                    "game.surrender",
                    SurrenderRequest(playerName = playerName, reason = "user_initiated")
                )

                if (response.error != null) {
                    Logger.error(LogCategory.UI, "SettingsWidget.fireSurrenderRpc: RPC error='${response.error}'")
                    return@launch
                }

                val payload = response.result?.let {
                    RpcJson.decodeFromJsonElement(SurrenderResponse.serializer(), it)
                }
                if (payload == null) {
                    Logger.error(LogCategory.UI, "SettingsWidget.fireSurrenderRpc: response result was null")
                    return@launch
                }

                if (!payload.accepted) {
                    Logger.warn(
                        LogCategory.UI,
                        "SettingsWidget.fireSurrenderRpc: surrender rejected by server (reason='${payload.reason}')"
                    )
                    return@launch
                }

                Logger.info(
                    LogCategory.UI,
                    "SettingsWidget.fireSurrenderRpc: surrender accepted (gameEnded=${payload.gameEnded}, winnerName=${payload.winnerName}); navigating to MainMenu"
                )
                this@SettingsWidget.hide()
                // When the surrender ended the game, close our WebSocket
                // BEFORE navigating to MainMenu. The server-side
                // TurnHarness.onPlayerDisconnectedFromSurrender hook (see
                // Server.kt:249) also kicks off a deregister, but that runs
                // on Dispatchers.IO and races the navigation. Closing the
                // socket from the client side makes the disconnect instant
                // and ensures MainMenu never renders while a half-dead
                // session is still receiving ui.updateTurnTimer broadcasts.
                // In multiplayer this branch is not taken (gameEnded is
                // false when other humans + NPCs continue), so the socket
                // stays alive — the surrendered player keeps playing in
                // their still-running match.
                if(payload.gameEnded)
                {
                    Logger.info(LogCategory.UI, "SettingsWidget.fireSurrenderRpc: closing WebSocket — server reported gameEnded=true")
                    try
                    {
                        WebSocketRpcBridge.close()
                    }
                    catch(e: Exception)
                    {
                        Logger.warn(LogCategory.UI, "SettingsWidget.fireSurrenderRpc: WebSocket close failed (non-fatal): ${e.message}")
                    }
                }
                KEnv.appStack?.add(MainMenu())
                KEnv.appStack?.activeIndex = 1
            } catch (e: Exception) {
                Logger.error(LogCategory.UI, "SettingsWidget.fireSurrenderRpc: failed to invoke game.surrender: ${e.message}")
            }
        }
    }

    private companion object
    {
        // Audio settings keys and defaults live in [AudioSettings] so
        // the MainMenu init path can read the same persisted values
        // the widget writes. Toggles remain local to this widget —
        // nothing else in the app reads them today.
        private const val SHOW_TOOLTIPS_KEY = "gameSettings_showTooltips"
        private const val FULLSCREEN_KEY = "gameSettings_fullscreenMode"

        private fun loadBoolean(key: String, default: Boolean): Boolean
        {
            return localStorage.getItem(key)?.toBooleanStrictOrNull() ?: default
        }

        private fun saveBoolean(key: String, value: Boolean)
        {
            localStorage.setItem(key, value.toString())
        }
    }
}
