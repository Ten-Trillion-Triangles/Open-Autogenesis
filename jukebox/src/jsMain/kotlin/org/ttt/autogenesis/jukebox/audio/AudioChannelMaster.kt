package org.ttt.autogenesis.jukebox.audio

import org.ttt.autogenesis.audio.AudioChannel

/**
 * Manages a single audio channel's GainNode and mute state.
 * Chains to parent channel's GainNode if hierarchical.
 */
class AudioChannelMaster(
    val channel: AudioChannel,
    val gainNode: GainNode,
    val parent: AudioChannelMaster?,
    val engine: AudioEngine
)
{
    private var currentVolume: Float = channel.volume
    private var currentMuted: Boolean = channel.muted

    val effectiveVolume: Float
        get()
        {
            val parentVol = parent?.effectiveVolume ?: 1.0f
            return if (currentMuted) 0.0f else currentVolume * parentVol
        }

    /**
     * Apply volume and mute state to the GainNode via automation.
     * Uses setTargetAtTime for smooth transitions.
     */
    fun applyState(volume: Float = currentVolume, muted: Boolean = currentMuted)
    {
        currentVolume = volume
        currentMuted = muted
        val targetVolume = if (muted) 0.0f else volume * (parent?.effectiveVolume ?: 1.0f)
        // Reach the AudioContext.currentTime through the gainNode's context.
        // The Web Audio API's AudioNode.context getter returns the owning
        // BaseAudioContext. In Kotlin/JS external interfaces, the typed
        // `gainNode.context` accessor isn't declared (only AudioContext has
        // currentTime; AudioNode doesn't expose it on the typed side), so
        // we use asDynamic() to reach the property. A single asDynamic() call
        // on the gainNode returns the underlying JS object, on which we can
        // read .context.currentTime.
        val gainAsDynamic: dynamic = gainNode.asDynamic()
        val now: Double = gainAsDynamic.context.currentTime
        gainNode.gain.setTargetAtTime(targetVolume, now, 0.01)
    }

    /**
     * Toggle the mute state without touching the underlying volume. The
     * gain node ramps to 0 when muted, and ramps back to (volume × parent.effectiveVolume)
     * when unmuted — preserving the user's volume setting.
     */
    fun setMuted(muted: Boolean)
    {
        applyState(currentVolume, muted)
    }

    /**
     * Read-only access to the current effective gain (post-mute, post-parent).
     * Used by AudioObjectPlayer when computing per-object gain.
     */
    val muted: Boolean
        get() = currentMuted
}