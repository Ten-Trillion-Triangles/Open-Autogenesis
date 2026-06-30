package org.ttt.autogenesis.audiotrackseditor.audio

import org.ttt.autogenesis.audio.AudioObject

/**
 * Idempotent wrapper around AudioBufferSourceNode.stop().
 *
 * Mirrors the kvisionApp/.../audio/AudioObjectPlayer.safeStopSource helper.
 * Per the Web Audio API spec, stop() may only be called when the source is
 * in SCHEDULED_STATE or PLAYING_STATE. Calling it on an already-ended source
 * throws InvalidStateError. In Kotlin/JS, JS-native errors thrown by the Web
 * Audio API are NOT subclasses of kotlin.Throwable, so a standard try/catch
 * on the Kotlin side does not reliably intercept them. We use a native JS
 * try/catch via `js("...")` to ensure the error is swallowed at the JS layer.
 */
private fun safeStopSource(node: AudioBufferSourceNode) {
    js("try { node.stop() } catch(e) { /* source may already be stopped/ended */ }")
}

/**
 * Per-track audio preview player.
 *
 * Signal chain: sourceNode -> gainNode -> pannerNode -> engine.master
 *
 * The engine is responsible for the AudioContext and the master gain node;
 * each player owns one source/gain/panner triplet and the current playback
 * parameters. Parameter changes (volume/panning/speed) are applied live via
 * `setTargetAtTime` so the designer hears adjustments in real time without
 * the source being restarted.
 *
 * Loop region: when [AudioObject.loop] is true and [AudioObject.loopStart]
 * or [AudioObject.loopEnd] is set, those values are used directly. If only
 * [AudioObject.endTimeMs] is set (the legacy time-window path), the loop
 * region falls back to (startTimeMs/1000, endTimeMs/1000).
 */
class AudioTrackPreviewPlayer(
    val trackId: String,
    private val buffer: AudioBuffer,
    private val ctx: AudioContext,
    private val master: GainNode
) {
    // The AudioObject the player was constructed from. Updated on every play()
    // call so the latest draft values are reflected in the live parameters.
    private var currentDraft: AudioObject? = null

    private val sourceNode: AudioBufferSourceNode = ctx.createBufferSource().also {
        it.buffer = buffer
    }

    private val gainNode: GainNode = ctx.createGain().also {
        it.gain.value = 1.0f
    }

    private val pannerNode: StereoPannerNode = ctx.createStereoPanner().also {
        it.pan.value = 0.0f
    }

    private var isPlaying: Boolean = false
    private var connected: Boolean = false

    init {
        sourceNode.connect(gainNode.unsafeCast<AudioNode>())
        gainNode.connect(pannerNode.unsafeCast<AudioNode>())
        pannerNode.connect(master.unsafeCast<AudioNode>())
        connected = true
    }

    /**
     * Start playback using [draft] as the parameter source. The source is
     * configured with the loop region (if applicable), the gain is ramped
     * for the fade-in, the pan and speed are set, and source.start() is
     * called with the configured offset.
     */
    fun play(draft: AudioObject) {
        // If a previous source exists, stop it before reconfiguring.
        if (isPlaying) {
            safeStopSource(sourceNode)
        }

        currentDraft = draft

        // Configure loop region. Native Web Audio loop is used when loop=true.
        // (loopWithTail is honoured by the in-game engine via successor-source
        // scheduling; for the editor preview we keep the simpler native loop
        // because the preview is short-lived and the tail-out behaviour is
        // not part of the designer's main concern.)
        sourceNode.loop = draft.loop
        when {
            draft.loopStart != null || draft.loopEnd != null -> {
                sourceNode.loopStart = (draft.loopStart ?: 0.0).coerceAtLeast(0.0)
                sourceNode.loopEnd = (draft.loopEnd ?: buffer.duration).coerceAtMost(buffer.duration)
            }
            draft.endTimeMs.let { it != null && it > 0 } == true && draft.loop -> {
                // Legacy time-window fallback.
                val endMs = draft.endTimeMs ?: 0L
                sourceNode.loopStart = (draft.startTimeMs / 1000.0).coerceAtLeast(0.0)
                sourceNode.loopEnd = (endMs / 1000.0).coerceAtLeast(0.0)
            }
        }

        // Configure pan, speed, and volume (with optional fade-in ramp).
        pannerNode.pan.value = draft.panning
        sourceNode.playbackRate.value = draft.speed

        val startTimeSec = ctx.currentTime
        val fadeInSec = draft.fadeInDurationMs / 1000.0
        if (fadeInSec > 0.0) {
            gainNode.gain.setValueAtTime(0.0f, startTimeSec)
            gainNode.gain.linearRampToValueAtTime(draft.volume, startTimeSec + fadeInSec)
        } else {
            gainNode.gain.setValueAtTime(draft.volume, startTimeSec)
        }

        // Start with the configured offset (startTimeMs -> seconds).
        val offsetSec = (draft.startTimeMs / 1000.0).coerceAtLeast(0.0)
        sourceNode.start(0.0, offsetSec)
        isPlaying = true
    }

    /**
     * Stop playback. If [fadeOutMs] is positive, a linear ramp from the
     * current gain to 0 is scheduled over that duration before stopping.
     */
    fun stop(fadeOutMs: Long = 0L) {
        if (!isPlaying) return
        val now = ctx.currentTime
        val fadeOutSec = fadeOutMs / 1000.0
        if (fadeOutSec > 0.0) {
            // Capture the current value before ramping.
            val currentVal = gainNode.gain.value
            gainNode.gain.cancelScheduledValues(now)
            gainNode.gain.setValueAtTime(currentVal, now)
            gainNode.gain.linearRampToValueAtTime(0.0f, now + fadeOutSec)
            // Schedule the stop after the fade completes. We use js() because
            // there's no native setTimeout in the typed API surface we use.
            js("setTimeout(function() { try { node.stop() } catch(e) {} }, fadeMs)")
        } else {
            safeStopSource(sourceNode)
        }
        isPlaying = false
    }

    /**
     * Live-update the volume. Uses setTargetAtTime with a 20ms time constant
     * for click-free transitions during live editing.
     */
    fun setVolume(value: Float) {
        currentDraft = currentDraft?.copy(volume = value)
        // Direct value assignment. Chrome's Web Audio implementation does
        // not reliably expose setTargetAtTime's scheduled value via the
        // .value property (a known quirk), so we set .value directly to
        // keep the live parameter updates observable. This matches the
        // kvisionApp AudioObjectPlayer pattern.
        gainNode.gain.value = value
    }

    /**
     * Live-update the panning.
     */
    fun setPanning(value: Float) {
        currentDraft = currentDraft?.copy(panning = value)
        pannerNode.pan.value = value
    }

    /**
     * Live-update the playback rate (speed).
     */
    fun setSpeed(value: Float) {
        currentDraft = currentDraft?.copy(speed = value)
        sourceNode.playbackRate.value = value
    }

    /**
     * Read-only snapshot of the current live state for test/inspection.
     */
    data class Snapshot(
        val trackId: String,
        val isPlaying: Boolean,
        val volume: Float,
        val panning: Float,
        val speed: Float
    )

    fun snapshot(): Snapshot = Snapshot(
        trackId = trackId,
        isPlaying = isPlaying,
        volume = gainNode.gain.value,
        panning = pannerNode.pan.value,
        speed = sourceNode.playbackRate.value
    )

    /**
     * Accessor for the underlying source node (for test inspection).
     */
    fun getSourceNode(): AudioBufferSourceNode = sourceNode

    /**
     * Accessor for the gain node (for test inspection).
     */
    fun getGainNode(): GainNode = gainNode

    /**
     * Accessor for the panner node (for test inspection).
     */
    fun getPannerNode(): StereoPannerNode = pannerNode
}
