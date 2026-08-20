package org.ttt.autogenesis.jukebox.audio

import kotlinx.serialization.Serializable

/**
 * Player state exported to JS for debugging/monitoring.
 *
 * Extracted from the now-deleted `AudioClientHandlers.kt` (F2). The
 * `PlayerState` was the only remaining useful export from that file once
 * the dead RPC stubs and action log were removed; the rest of
 * `AudioClientHandlers` is gone.
 */
@JsExport
@Serializable
data class PlayerState(
    val id: String,
    val resourceName: String,
    val channelId: String,
    val volume: Float,
    val panning: Float,
    val speed: Float,
    val loop: Boolean,
    val currentTimeMs: Long,
    val isPlaying: Boolean,
    val isPaused: Boolean,
    val isEnded: Boolean,
    /**
     * The current value of the per-source GainNode, sampled at every tick.
     * Compared to the target gain (volume * channel.effectiveVolume *
     * globalVolume) this tells the renderer whether a fade-in / fade-out is
     * in progress so the UI can show a "FADING" indicator. The analyser sits
     * before the gainNode in the signal chain, so the analyser data alone
     * cannot tell us the effective gain.
     */
    val currentGain: Float = 1.0f,
    /**
     * The expected steady-state gain for this player: `volume * channel.effectiveVolume
     * * globalVolume`. The renderer compares `currentGain` to this to detect
     * a fade in progress.
     */
    val targetGain: Float = 1.0f,
    /**
     * True while a fade-out is in progress (the player is ramping gain toward
     * 0 ahead of a cleanup). The renderer uses this to choose the badge
     * direction ("IN" vs "OUT") — `currentGain < targetGain` alone can't
     * distinguish a fade-in from a fade-out.
     */
    val isFadingOut: Boolean = false
)