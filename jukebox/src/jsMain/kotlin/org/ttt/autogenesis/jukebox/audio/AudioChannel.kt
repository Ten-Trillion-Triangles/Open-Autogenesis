package org.ttt.autogenesis.jukebox.audio

import kotlinx.serialization.Serializable

/**
 * Represents an audio channel with its own volume control.
 * Channels form a hierarchy via parentId — e.g., "Music" is parent of "Music.Bass".
 *
 * @param id Unique channel identifier (e.g., "Music", "Sfx", "Music.Bass")
 * @param name Display name (e.g., "Music", "Sfx", "Music Bass")
 * @param parentId Parent channel id for hierarchy; null for root channels
 * @param volume 0.0–1.0, global volume for this channel
 * @param muted Whether this channel is muted
 */
@Serializable
data class AudioChannel(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val volume: Float = 1.0f,
    val muted: Boolean = false
)