package structs.audio

import org.ttt.autogenesis.audio.AudioChannel
import org.ttt.autogenesis.audio.AudioObject

/**
 * The full set of music tracks persisted to / loaded from disk by the
 * audio tracks editor.
 *
 * The four **layer** fields (`drone`, `melody`, `rhythm`, `harmony`)
 * are the original musical layers — tracks that loop as a background
 * bed. The four **scenario** fields (`menu`, `start`, `nemesis`,
 * `end`) cover the scenario-level tracks the music-selector pipeline
 * picks under specific game conditions:
 *
 *  - `menu` — the main-menu track (Xilaron theme).
 *  - `start` — the very first turn of a game (Initial Conditions).
 *  - `nemesis` — any turn owned by a Nemesis or Elder God NPC.
 *  - `end` — a turn on which someone could plausibly win in the
 *    next 4 rounds (Terminal Conditions).
 *
 * The [channels] list is the **audio-channel hierarchy** that the
 * runtime engine builds. Each [AudioObject] above declares which
 * channel it plays through via its `channelId` field; the engine
 * uses [channels] to wire gain nodes and parent → child cascades.
 *
 * The expected structure (when present) is:
 *
 * ```
 * Global
 * ├── Music   (master; the options-menu "Music" slider drives this)
 * │   ├── Drone / Melody / Rhythm / Harmony  (the four layer channels)
 * │   └── Menu / Start / Nemesis / End      (the four scenario channels)
 * └── Sfx     (independent; the options-menu "Sfx" slider drives this)
 * ```
 *
 * Tracks default to an empty channel list so old JSON without the
 * field still loads — the engine's fallback default (Music + Sfx,
 * both under global) is used in that case, preserving pre-channel-
 * hierarchy behavior.
 *
 * @property drone Drone layer (background pad)
 * @property melody Melody layer (lead line)
 * @property rhythm Rhythm layer (percussion)
 * @property harmony Harmony layer (chord bed)
 * @property menu Main-menu track
 * @property start Initial-conditions track (first turn of the game)
 * @property nemesis Nemesis / Elder-God track
 * @property end Terminal-conditions track
 * @property channels Audio-channel hierarchy the engine builds at startup.
 *   Empty list = engine uses its built-in Music + Sfx defaults.
 */
@kotlinx.serialization.Serializable
data class AudioTracks(
    var drone: MutableList<AudioObject> = mutableListOf(),
    var melody: MutableList<AudioObject> = mutableListOf(),
    var rhythm: MutableList<AudioObject> = mutableListOf(),
    var harmony: MutableList<AudioObject> = mutableListOf(),
    var menu: MutableList<AudioObject> = mutableListOf(),
    var start: MutableList<AudioObject> = mutableListOf(),
    var nemesis: MutableList<AudioObject> = mutableListOf(),
    var end: MutableList<AudioObject> = mutableListOf(),
    var channels: MutableList<AudioChannel> = mutableListOf()
)