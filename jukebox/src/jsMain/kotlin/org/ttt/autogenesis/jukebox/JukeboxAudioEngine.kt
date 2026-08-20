package org.ttt.autogenesis.jukebox

import kotlinx.coroutines.*
import org.ttt.autogenesis.audio.AudioObject
import org.ttt.autogenesis.jukebox.audio.*

/**
 * The main JS API facade. Exposed as `window.JukeboxAudioEngine` by
 * [JukeboxMain.main] (the bundle entry point) when the bundle loads.
 *
 * The jukebox is a **standalone developer tool** — it does not connect to a
 * game server. All audio operations are local to the browser. The action log
 * is local-only; no RPC calls are made.
 */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsName("JukeboxAudioEngine")
@JsExport
object JukeboxAudioEngine {
    private var isReady = false
    private var initCallback: (() -> Unit)? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pendingFadeInMs: Int = 0
    private var pendingFadeOutMs: Int = 0
    private var defaultPanning: Float = 0.0f
    private var defaultSpeed: Float = 1.0f
    // A3 fix: facade-level default loop. Applied to every subsequent
    // [jukeboxPlay] call until changed. The UI exposes this as the
    // "loop mode" toggle in the Fade section (B6).
    private var defaultLoop: Boolean = false

    /**
     * Initialize the audio engine. Must be called from a user gesture context.
     *
     * The split between [AudioEngine.initContext] (synchronous, in the user
     * gesture) and [AudioEngine.initChannels] (suspend, in a coroutine) is
     * load-bearing: `AudioContext.resume()` must run while the click handler
     * is still on the call stack, otherwise strict autoplay policies will
     * leave the context suspended. The channel/gain-node setup, by contrast,
     * is not user-gesture-gated and can run in a coroutine.
     *
     * @param onReady Optional callback when initialization completes
     */
    @JsName("initJukebox")
    fun initJukebox(onReady: () -> Unit = {}) {
        initCallback = onReady
        // Phase 1: synchronous, on the user-gesture call stack. Creates the
        // AudioContext and calls resume() while the click is still active.
        AudioEngine.initContext()
        // Phase 2: deferred to a coroutine. Channel setup, tick loop start,
        // and the isReady flag flip happen off the click handler.
        scope.launch {
            AudioEngine.initChannels()
            isReady = true
            initCallback?.invoke()
        }
    }

    /**
     * Play an audio resource by name.
     * Creates a unique ID for the player and returns it.
     */
    @JsName("jukeboxPlay")
    fun jukeboxPlay(resourceName: String): String {
        if (!isReady) {
            console.warn("JukeboxAudioEngine: Not initialized yet")
            return ""
        }
        return jukeboxPlayWithRegion(resourceName, null, null)
    }

    /**
     * Play with an optional sub-region loop. Pass `loopStartSeconds` /
     * `loopEndSeconds` to confine looping to a sub-region of the buffer
     * (in seconds). Pass `null` for both to loop the whole file. This
     * is the developer-facing entry point for region-looping — the
     * `AudioBufferSourceNode.loopStart` / `loopEnd` are set on the
     * source node by [AudioObjectPlayer].
     */
    @JsName("jukeboxPlayWithRegion")
    fun jukeboxPlayWithRegion(
        resourceName: String,
        loopStartSeconds: Double?,
        loopEndSeconds: Double?
    ): String {
        if (!isReady) {
            console.warn("JukeboxAudioEngine: Not initialized yet")
            return ""
        }
        val id = "jukebox_${js("Date.now()")}_${js("Math.random().toString().substring(2)")}"
        val obj = AudioObject(
            id = id,
            resourceName = resourceName,
            channelId = if (resourceName.startsWith("sfx.")) "Sfx" else "Music",
            volume = 1.0f,
            panning = defaultPanning,
            speed = defaultSpeed,
            // A3 fix: read the facade-level default loop toggle. The UI
            // sets this via [setDefaultLoop] from the Fade section.
            loop = defaultLoop,
            startTimeMs = 0,
            fadeInDurationMs = pendingFadeInMs.toLong(),
            loopStart = loopStartSeconds,
            loopEnd = loopEndSeconds
        )
        AudioEngine.play(obj)
        return id
    }

    /**
     * Stop a playing audio object.
     */
    @JsName("jukeboxStop")
    fun jukeboxStop(objectId: String, fadeOutMs: Int = 0) {
        if (!isReady) return
        val effectiveFade = if (fadeOutMs > 0) fadeOutMs.toLong() else pendingFadeOutMs.toLong()
        AudioEngine.stop(objectId, effectiveFade)
    }

    /**
     * Pause a playing audio object.
     */
    @JsName("jukeboxPause")
    fun jukeboxPause(objectId: String) {
        if (!isReady) return
        AudioEngine.pause(objectId)
    }

    /**
     * Resume a paused audio object.
     */
    @JsName("jukeboxResume")
    fun jukeboxResume(objectId: String) {
        if (!isReady) return
        AudioEngine.resume(objectId)
    }

    /**
     * Set volume for an audio object.
     */
    @JsName("jukeboxSetVolume")
    fun jukeboxSetVolume(objectId: String, volume: Float, fadeMs: Int = 0) {
        if (!isReady) return
        AudioEngine.setVolume(objectId, volume, fadeMs.toLong())
    }

    /**
     * Set panning for an audio object.
     */
    @JsName("jukeboxSetPanning")
    fun jukeboxSetPanning(objectId: String, panning: Float, fadeMs: Int = 0) {
        if (!isReady) return
        AudioEngine.setPanning(objectId, panning, fadeMs.toLong())
    }

    /**
     * Set playback speed for an audio object.
     */
    @JsName("jukeboxSetSpeed")
    fun jukeboxSetSpeed(objectId: String, speed: Float, fadeMs: Int = 0) {
        if (!isReady) return
        AudioEngine.setSpeed(objectId, speed, fadeMs.toLong())
    }

    /**
     * Set loop state for an active audio object. Recreates the AudioBufferSourceNode
     * to apply the new loop state, preserving current playback position.
     */
    @JsName("jukeboxSetLoop")
    fun jukeboxSetLoop(objectId: String, loop: Boolean) {
        if (!isReady) return
        val player = AudioEngine.activePlayers[objectId] ?: return
        player.setLoop(loop)
    }

    /**
     * Set the default panning applied to all subsequently played audio objects,
     * AND propagate the new value to every active player so currently-playing
     * audio updates immediately. Without the propagation the slider would
     * only affect the next play, which is invisible to the user.
     */
    @JsName("jukeboxSetDefaultPanning")
    fun jukeboxSetDefaultPanning(panning: Float) {
        val coerced = panning.coerceIn(-1.0f, 1.0f)
        defaultPanning = coerced
        for (player in AudioEngine.activePlayers.values) {
            player.setPanning(coerced, 0L)
        }
        console.info("JukeboxAudioEngine: default panning set to $defaultPanning")
    }

    /**
     * Set the default playback speed applied to all subsequently played audio
     * objects, AND propagate the new value to every active player so
     * currently-playing audio updates immediately. Without the propagation
     * the slider would only affect the next play, which is invisible to the
     * user.
     */
    @JsName("jukeboxSetDefaultSpeed")
    fun jukeboxSetDefaultSpeed(speed: Float) {
        val coerced = speed.coerceIn(0.25f, 2.0f)
        defaultSpeed = coerced
        for (player in AudioEngine.activePlayers.values) {
            player.setSpeed(coerced, 0L)
        }
        console.info("JukeboxAudioEngine: default speed set to $defaultSpeed")
    }

    /**
     * Set global fade-in duration for subsequent plays.
     */
    @JsName("jukeboxSetFadeIn")
    fun jukeboxSetFadeIn(durationMs: Int) {
        pendingFadeInMs = durationMs
        console.info("JukeboxAudioEngine: Fade in set to $durationMs ms for subsequent plays")
    }

    /**
     * Set global fade-out duration for subsequent stops.
     */
    @JsName("jukeboxSetFadeOut")
    fun jukeboxSetFadeOut(durationMs: Int) {
        pendingFadeOutMs = durationMs
        console.info("JukeboxAudioEngine: Fade out set to $durationMs ms")
    }

    /**
     * Get all active players and their states.
     */
    @JsName("jukeboxGetActivePlayers")
    fun jukeboxGetActivePlayers(): Array<PlayerState> {
        if (!isReady) return emptyArray()
        return AudioEngine.activePlayers.map { (_, player) ->
            val channelMaster = AudioEngine.getChannelMaster(player.audioObject.channelId)
            val channelEffective = channelMaster?.effectiveVolume ?: 1.0f
            val targetGain = player.volume * channelEffective * AudioEngine.globalVolume
            PlayerState(
                id = player.audioObject.id,
                resourceName = player.audioObject.resourceName,
                channelId = player.audioObject.channelId,
                volume = player.volume,
                panning = player.panning,
                speed = player.speed,
                loop = player.getLoop(),
                currentTimeMs = player.currentTimeMs,
                isPlaying = player.isPlaying,
                isPaused = player.isPaused,
                isEnded = player.isEnded,
                currentGain = player.getCurrentGain(),
                targetGain = targetGain,
                isFadingOut = player.isFadingOut
            )
        }.toTypedArray()
    }

    /**
     * Preload a buffer so the next play is instant.
     */
    @JsName("jukeboxPreload")
    fun jukeboxPreload(resourceName: String) {
        if (!isReady) return
        scope.launch {
            // F10: launched on JukeboxAudioEngine.scope (cancelled by close()).
            // preloadBuffer itself is a regular suspend function.
            AudioEngine.preloadBuffer(resourceName)
        }
    }

    /**
     * Set global volume.
     */
    @JsName("jukeboxSetGlobalVolume")
    fun jukeboxSetGlobalVolume(volume: Float) {
        if (!isReady) return
        AudioEngine.setGlobalVolume(volume)
    }

    /**
     * Set channel volume.
     */
    @JsName("jukeboxSetChannelVolume")
    fun jukeboxSetChannelVolume(channelId: String, volume: Float, muted: Boolean = false) {
        if (!isReady) return
        // Channel IDs are stored under uppercase keys ("Music", "Sfx") in
        // AudioEngine.channels. The UI hands us lowercase ids ("music", "sfx"),
        // so a raw lookup is a silent no-op. Normalize the same way
        // jukeboxSetChannelMute does, then forward to the engine with the
        // canonical uppercase id.
        val normalized = when {
            channelId.contains("music", ignoreCase = true) -> "Music"
            channelId.contains("sfx", ignoreCase = true) ||
                channelId.contains("sound", ignoreCase = true) -> "Sfx"
            else -> channelId
        }
        AudioEngine.setChannelVolume(normalized, volume, muted)
    }

    @JsName("jukeboxSetChannelMute")
    fun jukeboxSetChannelMute(channelId: String, muted: Boolean) {
        if (!isReady) return
        // Mirror the lowercase-tolerant normalization from
        // jukeboxSetChannelVolume so the mute path agrees with the volume path.
        val channelLower = channelId.lowercase()
        when {
            channelLower.contains("music") -> AudioEngine.setChannelMuted("Music", muted)
            channelLower.contains("sfx") || channelLower.contains("sound") -> AudioEngine.setChannelMuted("Sfx", muted)
        }
    }

    @JsName("jukeboxSetPlayerVolume")
    fun jukeboxSetPlayerVolume(objectId: String, volume: Float, fadeMs: Int = 0) {
        if (!isReady) return
        // The UI converts dB → linear (Math.pow(10, dB/20)) before calling,
        // so `volume` arrives here as a linear gain scalar in [0, 1+]. The
        // engine applies this directly to the per-object gainNode.
        AudioEngine.setVolume(objectId, volume, fadeMs.toLong())
    }

    @JsName("jukeboxSetPlayerPan")
    fun jukeboxSetPlayerPan(objectId: String, panning: Float, fadeMs: Int = 0) {
        if (!isReady) return
        AudioEngine.setPanning(objectId, panning, fadeMs.toLong())
    }

    @JsName("jukeboxSetPlayerSpeed")
    fun jukeboxSetPlayerSpeed(objectId: String, speed: Float, fadeMs: Int = 0) {
        if (!isReady) return
        AudioEngine.setSpeed(objectId, speed, fadeMs.toLong())
    }

    /**
     * Stop every active player with a 100ms fade. Used by the Stop All
     * button in the UI (B5). No-op if the engine is not initialised or
     * there are no active players.
     */
    @JsName("stopAll")
    fun stopAll() {
        if (!isReady) return
        // Snapshot the keys first — [AudioEngine.stop] mutates
        // activePlayers, so iterating the live map would skip entries.
        val toStop = AudioEngine.activePlayers.keys.toList()
        for (id in toStop) {
            AudioEngine.stop(id, 100)
        }
    }

    /**
     * Read the current default-loop value applied to subsequent plays.
     * Initial value is `false`. The UI reads this in the Fade section
     * to render the toggle's on/off state on initial paint.
     *
     * @return the current default loop flag
     */
    @JsName("getDefaultLoop")
    fun getDefaultLoop(): Boolean = defaultLoop

    /**
     * Set the default-loop flag. Subsequent calls to [jukeboxPlay] will
     * pass this value to the underlying [AudioObject.loop] field. Used
     * by the loop-mode toggle in the Fade section (B6).
     *
     * @param loop the new default loop flag
     */
    @JsName("setDefaultLoop")
    fun setDefaultLoop(loop: Boolean) {
        defaultLoop = loop
    }

    @JsName("jukeboxGetFrequencyData")
    fun jukeboxGetFrequencyData(objectId: String): FloatArray {
        if (!isReady) return FloatArray(0)
        return AudioEngine.activePlayers[objectId]?.getFrequencyData() ?: FloatArray(0)
    }

    /**
     * Tear down the audio engine completely. Idempotent — safe to call
     * multiple times. After close(), [initJukebox] may be called again
     * to re-initialize from scratch.
     *
     * B4 fix: this used to be a stub that only cancelled the coroutine
     * scope, leaving the requestAnimationFrame tick loop, AudioContext,
     * and active player nodes running. Now it:
     *   1. Iterates activePlayers and calls cleanup() on each
     *   2. Stops the rAF tick loop
     *   3. Closes the AudioContext
     *   4. Clears channels/activePlayers/bufferCache
     *   5. Resets isInitialized so a future init() will rebuild
     */
    @JsName("close")
    fun close() {
        scope.cancel()
        // Cancel the engine's preload scope too — a still-in-flight
        // preloadBuffer coroutine launched by AudioEngine.play() would
        // otherwise run on a closed AudioContext and raise decodeAudioData
        // errors. (F10 follow-up: was missing in the original F10 fix.)
        AudioEngine.cancelEngineScope()
        // Iterate over a snapshot because cleanup() mutates activePlayers.
        val players = AudioEngine.activePlayers.values.toList()
        for (player in players) {
            try {
                player.stop(0)
            } catch (e: dynamic) {
                // best-effort; if a player is already in a bad state, skip
            }
        }
        // F9: no need to clear() activePlayers here — the loop above called
        // player.stop(0) on each, which (with fadeOutDurationMs=0) calls
        // cleanup() synchronously, which removes the player from
        // AudioEngine.activePlayers. The map is already empty.
        AudioEngine.channels.values.forEach { it.gainNode.disconnect() }
        AudioEngine.channels.clear()
        AudioEngine.bufferCache.clear()
        AudioEngine.stopTickLoop()
        try {
            // AudioContext.close() isn't declared on the typed interface
            // (only resume() is). Use asDynamic() to reach the property.
            AudioEngine.ctx.asDynamic().close()
        } catch (e: dynamic) {
            // best-effort; ctx may already be closed
        }
        AudioEngine.reset()
        isReady = false
    }
}
