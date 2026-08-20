package org.ttt.autogenesis.audio

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Contract for [AudioChannelIds] — the canonical string ids the audio
 * channel tree is keyed by.
 *
 * The ids travel over the wire (in [AudioObject.channelId], in the
 * `channels[].id` / `channels[].parentId` fields of the audio-tracks
 * JSON, and in the server-side filtering code). A rename would
 * silently desync the wire format from the runtime filter, so the
 * values are pinned here. If you need to rename a channel id, update
 * this test, the production JSON, the editor's [Category] enum, and
 * every server-side [AudioChannel.id] literal in one atomic commit.
 */
class AudioChannelIdsTest
{
    @Test
    fun musicMasterId_isTheExpectedString()
    {
        // The Music master is what the options-menu "Music" slider
        // binds to and what the engine's isMusicChannelId helper
        // walks the parent chain looking for. It must be exactly
        // "Music" — see the design doc in [AudioChannelIds].
        assertEquals("Music", AudioChannelIds.MUSIC_MASTER_ID)
    }

    @Test
    fun sfxChannelId_isTheExpectedString()
    {
        // The Sfx channel is the root of the independent SFX bus.
        // It must NOT be a child of Music (so the Sfx slider can
        // mute SFX without muting music and vice versa), and its id
        // must match the `parentId = null` row in the audio-tracks
        // JSON's `channels` array.
        assertEquals("Sfx", AudioChannelIds.SFX_CHANNEL_ID)
    }
}