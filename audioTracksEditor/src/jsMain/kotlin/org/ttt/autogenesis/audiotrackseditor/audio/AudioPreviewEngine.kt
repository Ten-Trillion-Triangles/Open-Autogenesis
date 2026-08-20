package org.ttt.autogenesis.audiotrackseditor.audio

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.ttt.autogenesis.audio.AudioObject
import org.w3c.files.File

/**
 * Preview-only Web Audio engine for the audio tracks editor.
 *
 * Owns a single shared [AudioContext] (created lazily on the first user
 * gesture to satisfy browser autoplay policy), the master gain node, the
 * buffer cache keyed by track id, and the active per-track players.
 *
 * This engine is intentionally simpler than the in-game
 * `kvisionApp/.../audio/AudioEngine`: there is no channel-master hierarchy,
 * no global volume, no analyser — the preview is just source -> gain ->
 * panner -> master -> destination, and the AudioObject's own `volume`,
 * `panning`, and `speed` are applied directly.
 */
object AudioPreviewEngine {
    private var ctx: AudioContext? = null
    private var master: GainNode? = null
    private var initialized: Boolean = false

    // trackId -> decoded AudioBuffer. Cleared on detach.
    private val buffers: MutableMap<String, AudioBuffer> = mutableMapOf()

    // trackId -> active player.
    private val activePlayers: MutableMap<String, AudioTrackPreviewPlayer> = mutableMapOf()

    // File-name cache for the "Loaded: filename (X.Xs, Nch)" status line.
    private val loadedFileNames: MutableMap<String, String> = mutableMapOf()

    // What mode is currently driving a side-by-side preview.
    // - IDLE: nothing playing.
    // - CATEGORY: per-tab "Play All" active.
    // - GLOBAL: top-bar "Play All (global)" active.
    enum class PreviewMode { IDLE, CATEGORY, GLOBAL }
    var previewMode: PreviewMode = PreviewMode.IDLE
        private set

    /**
     * Synchronous initialization. Must be called from a user-gesture call
     * stack (e.g. inside the file-picker change handler) to satisfy browser
     * autoplay policy. Mirrors `kvisionApp/.../audio/AudioEngine.initContext`
     * (B1 fix).
     */
    fun initContext() {
        if (initialized) return
        ctx = js("new (window.AudioContext || window.webkitAudioContext)()")
        // resume() must be called synchronously in the user-gesture call stack
        // for strict autoplay policies. We use asDynamic() to bypass the
        // Promise wrapper because the typed interface declares it as suspend.
        ctx?.asDynamic()?.resume()
        val newMaster = ctx?.createGain()
        if (newMaster != null && ctx != null) {
            newMaster.gain.value = 1.0f
            newMaster.connect(ctx!!.destination)
            master = newMaster
        }
        initialized = true
    }

    /**
     * True if the engine has been initialized and the AudioContext is
     * available for inspection / use.
     */
    fun isInitialized(): Boolean = initialized && ctx != null

    /**
     * Read the AudioContext state ("suspended", "running", or "closed").
     */
    fun getAudioContextState(): String {
        return (ctx?.asDynamic()?.state as? String) ?: "uninitialized"
    }

    /**
     * Decode a [File] (selected by the user) into an [AudioBuffer] and
     * attach it to [trackId]. Returns true on success. Failures are
     * surfaced via the error banner by the caller.
     */
    fun attachFile(trackId: String, file: File, callback: (success: Boolean, errorMsg: String?) -> Unit) {
        val context = ctx
        if (context == null) {
            callback(false, "AudioContext not initialized")
            return
        }
        // Read the file as ArrayBuffer, then decode it.
        val reader = org.w3c.files.FileReader()
        reader.onload = {
            val ab = reader.result
            try {
                GlobalScope.launch {
                    try {
                        val buffer = context.decodeAudioData(ab).await()
                        attachBuffer(trackId, buffer, file.name)
                        callback(true, null)
                    } catch (e: dynamic) {
                        callback(false, "Could not decode audio: ${e?.message ?: e}")
                    }
                }
            } catch (e: Throwable) {
                callback(false, "Failed to start decode: ${e.message ?: e::class.simpleName}")
            }
        }
        reader.onerror = {
            callback(false, "Failed to read file")
        }
        reader.readAsArrayBuffer(file)
    }

    /**
     * Attach a pre-decoded buffer to a trackId. Used by attachFile and by
     * tests that build an AudioBuffer manually.
     */
    fun attachBuffer(trackId: String, buffer: AudioBuffer, fileName: String = "buffer") {
        buffers[trackId] = buffer
        loadedFileNames[trackId] = fileName
    }

    /**
     * Detach the buffer for [trackId] and stop any active player.
     */
    fun detachBuffer(trackId: String) {
        stopPreview(trackId)
        buffers.remove(trackId)
        loadedFileNames.remove(trackId)
    }

    /**
     * Get the buffer attached to [trackId], or null if none.
     */
    fun getLoadedBuffer(trackId: String): AudioBuffer? = buffers[trackId]

    /**
     * Get the file name associated with [trackId], or null.
     */
    fun getLoadedFileName(trackId: String): String? = loadedFileNames[trackId]

    /**
     * All trackIds with loaded buffers.
     */
    fun getLoadedAudioKeys(): List<String> = buffers.keys.toList()

    /**
     * Start a preview for [trackId] using [draft] as the parameter source.
     * If a player is already active for the trackId, it is replaced.
     * Returns true on success (buffer is loaded and engine is initialized).
     */
    fun startPreview(trackId: String, draft: AudioObject): Boolean {
        val context = ctx
        val masterGain = master
        val buffer = buffers[trackId]
        if (context == null || masterGain == null || buffer == null) return false
        // Stop any existing player for this trackId.
        activePlayers[trackId]?.stop()
        val player = AudioTrackPreviewPlayer(trackId, buffer, context, masterGain)
        activePlayers[trackId] = player
        player.play(draft)
        return true
    }

    /**
     * Stop the preview for [trackId] (if any) and remove the player.
     */
    fun stopPreview(trackId: String) {
        activePlayers.remove(trackId)?.stop()
    }

    /**
     * Stop every active preview player and reset preview mode.
     */
    fun stopAll() {
        activePlayers.values.forEach { it.stop() }
        activePlayers.clear()
        previewMode = PreviewMode.IDLE
    }

    /**
     * Live-update an active player's parameter.
     * [field] is "volume" | "panning" | "speed".
     */
    fun updateLive(trackId: String, field: String, value: Float) {
        val player = activePlayers[trackId] ?: return
        when (field) {
            "volume" -> player.setVolume(value)
            "panning" -> player.setPanning(value)
            "speed" -> player.setSpeed(value)
        }
    }

    /**
     * Snapshot of every active player (for the test inspection hooks).
     */
    fun getActivePreviewPlayers(): List<AudioTrackPreviewPlayer.Snapshot> =
        activePlayers.values.map { it.snapshot() }

    /**
     * True if [trackId] currently has an active player.
     */
    fun isPlaying(trackId: String): Boolean = activePlayers[trackId]?.snapshot()?.isPlaying == true

    /**
     * True if any preview player is currently active.
     */
    fun hasActivePreviews(): Boolean = activePlayers.isNotEmpty()

    /**
     * Start a category-wide side-by-side preview. For every track in
     * [tracks] that has a buffer loaded, start a preview. Sets
     * previewMode = CATEGORY.
     */
    fun startCategoryPreview(tracks: List<AudioObject>) {
        // If a global preview is running, stop it first.
        if (previewMode == PreviewMode.GLOBAL) stopAll()
        tracks.forEach { track ->
            if (buffers.containsKey(track.id)) {
                startPreview(track.id, track)
            }
        }
        previewMode = PreviewMode.CATEGORY
    }

    /**
     * Start a global side-by-side preview across all 8 category lists.
     * Sets previewMode = GLOBAL.
     */
    fun startGlobalPreview(tracks: List<AudioObject>) {
        if (previewMode == PreviewMode.CATEGORY) stopAll()
        tracks.forEach { track ->
            if (buffers.containsKey(track.id)) {
                startPreview(track.id, track)
            }
        }
        previewMode = PreviewMode.GLOBAL
    }

    /**
     * Stop all category/global previews and return to IDLE.
     */
    fun stopSideBySide() {
        if (previewMode != PreviewMode.IDLE) {
            stopAll()
        }
    }

    /**
     * Count of currently active preview players.
     */
    fun getActivePlayerCount(): Int = activePlayers.size

    /**
     * Get the source node for a given trackId (for test inspection).
     * Returns the source node of the active player, or null if no player
     * is active for that trackId.
     */
    fun getSourceNodeFor(trackId: String): AudioBufferSourceNode? =
        activePlayers[trackId]?.getSourceNode()

    /**
     * Read the current gain value for a trackId (or -1.0 if no player).
     * Test-only inspection hook.
     */
    fun getGainValueFor(trackId: String): Double =
        activePlayers[trackId]?.getGainNode()?.gain?.value?.toDouble() ?: -1.0

    /**
     * Read the current panning value for a trackId (or -1.0 if no player).
     */
    fun getPanningValueFor(trackId: String): Double =
        activePlayers[trackId]?.getPannerNode()?.pan?.value?.toDouble() ?: -1.0

    /**
     * Read the current playback rate for a trackId (or -1.0 if no player).
     */
    fun getSpeedValueFor(trackId: String): Double =
        activePlayers[trackId]?.getSourceNode()?.playbackRate?.value?.toDouble() ?: -1.0

    /**
     * Get the AudioContext (for test inspection).
     */
    fun getAudioContext(): AudioContext? = ctx

    /**
     * Get the master GainNode (for test inspection).
     */
    fun getMasterGainNode(): GainNode? = master
}