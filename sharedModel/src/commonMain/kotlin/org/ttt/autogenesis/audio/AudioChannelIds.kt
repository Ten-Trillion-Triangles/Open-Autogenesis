package org.ttt.autogenesis.audio

/**
 * Canonical string ids for the audio channel tree.
 *
 * These live in [sharedModel] (not the JS-only [org.ttt.autogenesis.kvisionapp.audio.AudioEngine])
 * so the server's JVM code can reference the same source of truth as the
 * client. The audio-tracks JSON, the music-selector pipeline, the
 * Web Audio engine, the music runner, and the options-menu sliders
 * all consult these ids; centralising them in the data-model module
 * is the only way to keep the wire format and the runtime filter
 * from drifting.
 *
 * ## Channel tree
 *
 * The expected tree, defined in [structs.audio.AudioTracks.channels],
 * is:
 *
 * ```
 * Global
 * ├── Music   ← MUSIC_MASTER_ID (the options-menu "Music" slider)
 * │   ├── Drone, Melody, Rhythm, Harmony (the four musical layers)
 * │   └── Menu, Start, Nemesis, End     (the four scenarios)
 * └── Sfx     ← SFX_CHANNEL_ID (the options-menu "Sfx" slider)
 * ```
 *
 * ## Why these are top-level constants, not enum values
 *
 * The channel ids travel over the wire (in [AudioObject.channelId],
 * in [org.ttt.autogenesis.audio.AudioChannel.id], and in the
 * audio-tracks JSON's `channels[].id` and `channels[].parentId`
 * fields) as plain strings. The list of *music-category* channels
 * is closed but extensible — designers can add new category tabs in
 * the editor (and the editor will emit a matching channel id) — so
 * the eight category ids ("Drone", "Melody", ...) are intentionally
 * not enumerated. Only the two **root** ids ("Music", "Sfx") are
 * pinned here, because the rest of the system treats them as
 * well-known names: the engine's [org.ttt.autogenesis.kvisionapp.audio.AudioEngine.isMusicChannelId]
 * helper walks the parent chain looking for [MUSIC_MASTER_ID], and
 * the options-menu panel binds its Music slider to it.
 */
object AudioChannelIds
{
    /**
     * The master channel id whose volume is driven by the
     * options-menu "Music" slider. Every music-category channel
     * (Drone, Melody, Rhythm, Harmony, Menu, Start, Nemesis, End)
     * has this as its top-level ancestor in the default tree.
     *
     * Used by:
     *  - [org.ttt.autogenesis.kvisionapp.audio.AudioEngine.isMusicChannelId]
     *    to decide whether a scheduled AudioObject is a music-channel
     *    object (and so should pass the runner's filter).
     *  - [org.ttt.autogenesis.kvisionapp.audio.AudioEngine.loadChannels]
     *    as the parent id of the eight music-category children.
     *  - [org.ttt.autogenesis.kvisionapp.ui.audio.AudioSettingsPanel]
     *    to bind the player-facing Music slider.
     *  - Server-side code that filters [AudioObject]s by channel
     *    (e.g., `TurnHarness`'s `currentlyPlayingMusicIds` query).
     */
    const val MUSIC_MASTER_ID: String = "Music"

    /**
     * The independent SFX channel id, driven by the options-menu
     * "Sfx" slider. Sits at the root of the channel tree (not a
     * child of Music) so muting Music does not silence SFX and
     * vice versa. See the "Why these are top-level constants"
     * note on the enclosing object for the rationale.
     */
    const val SFX_CHANNEL_ID: String = "Sfx"
}
