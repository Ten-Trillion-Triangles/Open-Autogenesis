package org.ttt.autogenesis.kvisionapp.ui.audio

import io.kvision.core.*
import io.kvision.form.number.rangeInput
import io.kvision.html.Div
import io.kvision.html.Span
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.perc
import io.kvision.utils.px
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.ttt.autogenesis.audio.AudioChannelIds
import org.ttt.autogenesis.audio.AudioChannelUpdate
import org.ttt.autogenesis.audio.AudioGlobalVolumeUpdate
import org.ttt.autogenesis.kvisionapp.audio.AudioEngine
import org.ttt.autogenesis.kvisionapp.RestRpcBridge

/**
 * In-game audio settings panel with player-facing volume sliders.
 *
 * The player sees three controls:
 *  - **Global Volume** — the master output, applied at the AudioContext
 *    destination. Affects every channel.
 *  - **Music Volume** — drives the parent "Music" channel. Every music
 *    category the catalog ships (Drone, Melody, Rhythm, Harmony, Menu,
 *    Start, Nemesis, End) is a child of this master, so this single
 *    slider cascades through all music objects. The per-category
 *    channels exist in the engine for the audio designer's mix-tweaks
 *    (see `structs.audio.AudioTracks.channels`) but are intentionally
 *    not exposed here — the player's "Music" knob is the one-knob-for-
 *    all-music the design called for.
 *  - **SFX Volume** — drives the independent Sfx channel at the root
 *    of the channel tree. Muting Music does not silence SFX and vice
 *    versa.
 *
 * Changes are applied locally via [AudioEngine.setChannelVolume] and
 * mirrored to the server through [AudioRpcClient] so other connected
 * clients pick them up via the audio sync state.
 */
class AudioSettingsPanel : SimplePanel() {

    init {
        width = 300.px
        padding = 10.px

        // Title
        Div("Audio Settings") {
            fontSize = 18.px
            fontWeight = FontWeight.BOLD
        }

        // Global volume — affects every channel.
        addVolumeSlider("Global Volume", (AudioEngine.globalVolume * 100).toInt()) { value ->
            AudioEngine.setGlobalVolume(value.toFloat() / 100f)
            MainScope().launch {
                AudioRpcClient.setGlobalVolume(value.toFloat() / 100f)
            }
        }

        // Music master — the one-knob-for-all-music the design called for.
        renderMasterSlider(AudioChannelIds.MUSIC_MASTER_ID, "Music Volume")

        // SFX — independent of Music. Sits at the root of the tree.
        renderMasterSlider(AudioChannelIds.SFX_CHANNEL_ID, "SFX Volume")
    }

    /**
     * Render a single master-channel slider for [channelId] with the
     * given [label]. No-ops if the engine hasn't loaded that channel
     * yet (e.g., the panel is rendered before
     * [AudioEngine.initChannels] has run).
     */
    private fun renderMasterSlider(channelId: String, label: String)
    {
        val master = AudioEngine.getChannelMaster(channelId) ?: return
        addVolumeSlider(label, (master.channel.volume * 100).toInt()) { value ->
            val muted = master.channel.muted
            AudioEngine.setChannelVolume(channelId, value.toFloat() / 100f, muted)
            MainScope().launch {
                AudioRpcClient.setChannelVolume(channelId, value.toFloat() / 100f, muted)
            }
        }
    }

    private fun addVolumeSlider(label: String, initialValue: Int, onChange: (Int) -> Unit) {
        var currentValue = initialValue
        vPanel(spacing = 4) {
            hPanel(justify = JustifyContent.SPACEBETWEEN) {
                Span(label) { fontSize = 14.px; color = Color.name(Col.WHITE) }
                Span("${currentValue}%") { fontSize = 12.px; color = Color.name(Col.CYAN) }
            }
            rangeInput(min = 0, max = 100, step = 1, value = currentValue) {
                width = 100.perc
                onInput {
                    currentValue = this.value?.toInt() ?: 0
                    onChange(currentValue)
                }
            }
        }
    }
}

/**
 * Client-side RPC helper for sending audio settings to the server.
 *
 * Handles the case when [RestRpcBridge] is not yet connected by queuing
 * calls for retry when the session becomes ready.
 */
object AudioRpcClient {
    /** Pending audio RPC calls awaiting session connection. */
    private val pendingCalls = mutableListOf<suspend () -> Unit>()

    init {
        // Register callback to retry pending calls when connection is established.
        // This ensures queued volume changes are sent once the bridge is ready.
        RestRpcBridge.onConnected {
            retryPendingCalls()
        }
    }

    /**
     * Retries all pending RPC calls that were queued when the bridge was disconnected.
     * Executes in a coroutine since pending calls are suspend functions.
     * Clears the queue after attempting all calls.
     */
    private fun retryPendingCalls() {
        if (pendingCalls.isEmpty()) return
        val callsToRetry = pendingCalls.toList()
        pendingCalls.clear()
        MainScope().launch {
            for (call in callsToRetry) {
                try {
                    call()
                } catch (e: Exception) {
                    // Log error but continue with other pending calls
                    console.error("AudioRpcClient: failed to retry pending call", e)
                }
            }
        }
    }

    /**
     * Sends global volume update to the server via RPC.
     *
     * If the RPC bridge is not yet connected, the call is queued for retry
     * when the session becomes ready.
     *
     * @param volume Volume level between 0.0 and 1.0
     */
    suspend fun setGlobalVolume(volume: Float) {
        if (!RestRpcBridge.isSessionReady) {
            queuePendingCall { setGlobalVolume(volume) }
            return
        }
        RestRpcBridge.rpcInvoker?.invoke<AudioGlobalVolumeUpdate>("audio.setGlobalVolume", AudioGlobalVolumeUpdate(
            volume = volume
        ))
    }

    /**
     * Sends channel volume and mute update to the server via RPC.
     *
     * If the RPC bridge is not yet connected, the call is queued for retry
     * when the session becomes ready.
     *
     * @param channelId Channel identifier
     * @param volume Volume level between 0.0 and 1.0
     * @param muted Whether the channel is muted
     */
    suspend fun setChannelVolume(channelId: String, volume: Float, muted: Boolean) {
        if (!RestRpcBridge.isSessionReady) {
            queuePendingCall { setChannelVolume(channelId, volume, muted) }
            return
        }
        RestRpcBridge.rpcInvoker?.invoke<AudioChannelUpdate>("audio.setChannelVolume", AudioChannelUpdate(
            channelId = channelId,
            volume = volume,
            muted = muted
        ))
    }

    /**
     * Queues a pending call to be retried when the session becomes ready.
     * The retry is handled by the onConnected callback registered in init.
     */
    private fun queuePendingCall(call: suspend () -> Unit) {
        pendingCalls.add(call)
    }
}