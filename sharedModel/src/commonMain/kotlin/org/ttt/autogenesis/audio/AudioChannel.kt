package org.ttt.autogenesis.audio

import kotlinx.serialization.Serializable

/**
 * Represents an audio channel with its own volume control.
 * Channels form a hierarchy via parentId — e.g., "Music" is parent of
 * "Music.Bass" or, in the standard tree, "Music" is parent of every
 * music-category channel (Drone, Melody, Rhythm, Harmony, Menu, Start,
 * Nemesis, End).
 *
 * @param id Unique channel identifier (e.g., "Music", "Sfx", "Melody")
 * @param name Display name (e.g., "Music", "Sfx", "Melody")
 * @param parentId Parent channel id for hierarchy; null for root channels
 * @param volume Volume multiplier for this channel. The conventional
 *   range is `0.0..1.0` (attenuation) and is what the options-menu
 *   sliders expose. Values **above 1.0 are allowed** for amplification
 *   (e.g., the audio designer may set a per-category channel like
 *   `Melody` to `1.15` to boost that layer in the mix); the runtime
 *   passes these straight to a Web Audio `GainNode.gain` and does not
 *   clamp them. Note that pushing the destination mix above 1.0 will
 *   cause digital clipping — designers should verify their bus
 *   structure on representative scenes before shipping.
 * @param muted Whether this channel is muted. When true, the cascade
 *   in [org.ttt.autogenesis.kvisionapp.audio.AudioChannelMaster.effectiveVolume]
 *   short-circuits the entire subtree to 0 regardless of [volume].
 */
@Serializable
data class AudioChannel(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val volume: Float = 1.0f,
    val muted: Boolean = false
)
