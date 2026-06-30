package org.ttt.autogenesis.kvisionapp.audio

import org.ttt.autogenesis.audio.AudioChannel
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

/**
 * Manages a single audio channel's GainNode and mute state.
 * Chains to parent channel's GainNode if hierarchical.
 */
class AudioChannelMaster(
    val channel: AudioChannel,
    val gainNode: GainNode,
    /**
     * The parent master in the channel hierarchy, or `null` for root
     * channels. Declared `var` so the engine can build the tree in two
     * passes (create all masters with parent=null, then wire parents
     * in topological order) when loading a non-trivial channel list.
     * The cascade math in [effectiveVolume] and [applyState] reads this
     * field lazily, so it must be set BEFORE any [applyState] call but
     * can be assigned after construction.
     */
    var parent: AudioChannelMaster?,
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
        val previousVolume = currentVolume
        val previousMuted = currentMuted
        currentVolume = volume
        currentMuted = muted
        val targetVolume = if (muted) 0.0f else volume * (parent?.effectiveVolume ?: 1.0f)
        try
        {
            val ctx = gainNode.asDynamic().context
            val now = (ctx.asDynamic().currentTime as? Number)?.toDouble() ?: 0.0
            gainNode.gain.setTargetAtTime(targetVolume, now, 0.01)
        }
        catch (e: Throwable)
        {
            // Defensive: some headless test environments return null/undefined
            // for AudioContext.currentTime, which used to throw and hang the
            // channel-init loop. Setting the value directly is the safer fallback.
            try
            {
                gainNode.asDynamic().gain.value = targetVolume.toDouble()
            }
            catch (_: Throwable)
            {
                // Last-resort: swallow so the engine can still initialise
            }
        }
        if (previousVolume != volume || previousMuted != muted) {
            Logger.debug(
                LogCategory.SYSTEM,
                "AudioChannelMaster.applyState: channelId='${channel.id}' " +
                "volume: $previousVolume → $volume, muted: $previousMuted → $muted " +
                "(target gain=$targetVolume, timeConstant=0.01s)"
            )
        }
    }
}
