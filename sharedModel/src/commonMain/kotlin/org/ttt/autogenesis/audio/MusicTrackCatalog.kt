package org.ttt.autogenesis.audio

import org.ttt.autogenesis.audio.MusicCategory.Drone
import org.ttt.autogenesis.audio.MusicCategory.Harmony
import org.ttt.autogenesis.audio.MusicCategory.InitialConditions
import org.ttt.autogenesis.audio.MusicCategory.Melody
import org.ttt.autogenesis.audio.MusicCategory.Nemesis
import org.ttt.autogenesis.audio.MusicCategory.Rhythm
import org.ttt.autogenesis.audio.MusicCategory.TerminalConditions
import structs.audio.AudioTracks

/**
 * Catalog of music tracks the picker draws from.
 *
 * There are two ways to build a catalog:
 *  - **[default]** — the legacy hand-curated singleton with the 5
 *    base D-Tracks, 8 base melody tracks, 6 R-Tracks, and 7 harmony
 *    tracks. [MusicTrack.audioObject] is `null` for every entry, so
 *    the picker falls back to a generic Music-channel object. Kept
 *    around because the original `MusicTrackCatalogTest` and the
 *    `MusicResourceResolverTest.everyCatalogNameResolvesToAPath`
 *    drift detector still target these base names.
 *  - **[fromAudioTracks]** — the runtime factory that builds a catalog
 *    from the editor's [AudioTracks] payload (loaded at game init from
 *    `audio/audio-tracks.json` on the server classpath). The resulting
 *    catalog reflects whatever the audio editor has saved and covers
 *    all 79 entries including Retrograde / Inversion / Variant /
 *    Rotation / Centered / Slow Rotation variants. Each [MusicTrack]
 *    carries the editor-supplied [MusicTrack.audioObject] so the picker
 *    preserves the per-track volume / loop / loopEnd / loopWithTail
 *    settings the editor set.
 *
 * Folder mapping (used by both the legacy catalog and the runtime
 * factory):
 *  - D-Tracks/        → [drone]
 *  - Melody Tracks/   → [melody]
 *  - R-Tracks/        → [rhythm]
 *  - Harmony Tracks/  → [harmony]
 *  - Initial/Nemesis/Terminal mp3s at the music root → their scenario lists
 *  - Xilaron and Eleuryiyidict wet final.mp3 at the music root → [menu]
 *
 * The "Retrograde" / "Inversion" / "Variant" / "Slow Rotation" /
 * "Rotated" / "Centered" variants are deliberately **not** in the
 * legacy [default] pool — only the base tracks. The runtime
 * [fromAudioTracks] factory pulls them in straight from the editor
 * payload, which is the whole point of using the editor.
 */
data class MusicTrackCatalog(
    /** Played on the very first turn of the game (rule 1). */
    val initialConditions: List<MusicTrack>,
    /** Played for Nemesis and Elder God turns (rule 2). */
    val nemesis: List<MusicTrack>,
    /** Played when someone could plausibly win in the next 4 rounds (rule 3). */
    val terminalConditions: List<MusicTrack>,
    /** Drone layer — the "D-Tracks" folder. */
    val drone: List<MusicTrack>,
    /** Melody layer — the "Melody Tracks" folder (base tracks in the legacy catalog). */
    val melody: List<MusicTrack>,
    /** Rhythm layer — the "R-Tracks" folder. */
    val rhythm: List<MusicTrack>,
    /** Harmony layer — the "Harmony Tracks" folder (base tracks in the legacy catalog). */
    val harmony: List<MusicTrack>
)
{
    /**
     * Flat union of every [MusicTrack.resourceName] across all
     * categories. Useful for cross-checks (e.g., the client's resolver
     * asserts every catalog name has a path in the webpack manifest).
     */
    fun allNames(): List<String> =
        (initialConditions + nemesis + terminalConditions +
         drone + melody + rhythm + harmony).map { it.resourceName }

    /**
     * Look up a track by its canonical [resourceName]. Returns null if
     * the name is unknown so callers can fall through to fuzzy matching.
     */
    fun findByName(name: String): MusicTrack?
    {
        if (name.isEmpty()) return null
        return (initialConditions + nemesis + terminalConditions +
                drone + melody + rhythm + harmony).firstOrNull { it.resourceName == name }
    }

    companion object
    {
        /**
         * Legacy hand-curated catalog. Same content the original
         * `object MusicTrackCatalog` exposed, with the same names
         * the existing `MusicTrackCatalogTest` and the
         * `MusicResourceResolverTest.everyCatalogNameResolvesToAPath`
         * drift detector cover. Every [MusicTrack.audioObject] is
         * `null` so the picker falls back to a generic Music-channel
         * object.
         */
        val default: MusicTrackCatalog = MusicTrackCatalog(
            initialConditions = listOf(
                MusicTrack("Initial Conditions wet 1", InitialConditions)
            ),
            nemesis = listOf(
                MusicTrack("Nemesis wet 1", Nemesis)
            ),
            terminalConditions = listOf(
                MusicTrack("Terminal Conditions wet 1", TerminalConditions)
            ),
            drone = listOf(
                MusicTrack("D-Track 1", Drone),
                MusicTrack("D-Track 2", Drone),
                MusicTrack("D-Track 3", Drone),
                MusicTrack("D-Track 4", Drone),
                MusicTrack("D-Track 5", Drone)
            ),
            melody = listOf(
                MusicTrack("Melody-Etnahta", Melody),
                MusicTrack("Melody-Mayela and Khefulah", Melody),
                MusicTrack("Melody-Pashta", Melody),
                MusicTrack("Melody-Shalshelet", Melody),
                MusicTrack("Melody-Siluk", Melody),
                MusicTrack("Melody-Tevir", Melody),
                MusicTrack("Melody-Tippeha", Melody),
                MusicTrack("Melody-Zakef", Melody)
            ),
            rhythm = listOf(
                MusicTrack("R-Track 1", Rhythm),
                MusicTrack("R-Track 2", Rhythm),
                MusicTrack("R-Track 3", Rhythm),
                MusicTrack("R-Track 4", Rhythm),
                MusicTrack("R-Track 5", Rhythm),
                MusicTrack("R-Track 6", Rhythm)
            ),
            harmony = listOf(
                MusicTrack("Harmony-1", Harmony),
                MusicTrack("Harmony-2", Harmony),
                MusicTrack("Harmony-3", Harmony),
                MusicTrack("Harmony-E", Harmony),
                MusicTrack("Harmony-F", Harmony),
                MusicTrack("Harmony-R", Harmony),
                MusicTrack("Harmony-Y", Harmony)
            )
        )

        /**
         * Build a runtime catalog from the editor's [AudioTracks]
         * payload. Every [AudioObject] in the payload becomes a
         * [MusicTrack] tagged with the matching [MusicCategory] and
         * carrying the original [AudioObject] verbatim — the picker
         * emits it as-is so the editor's volume / loop / loopEnd /
         * loopWithTail settings survive the round trip.
         *
         * Tracks are bucketed by their position in [AudioTracks]:
         *  - [AudioTracks.initialConditions] → [MusicCategory.InitialConditions]
         *  - [AudioTracks.nemesis]          → [MusicCategory.Nemesis]
         *  - [AudioTracks.end]              → [MusicCategory.TerminalConditions]
         *  - [AudioTracks.drone]            → [MusicCategory.Drone]
         *  - [AudioTracks.melody]           → [MusicCategory.Melody]
         *  - [AudioTracks.rhythm]           → [MusicCategory.Rhythm]
         *  - [AudioTracks.harmony]          → [MusicCategory.Harmony]
         *
         * The [AudioTracks.menu] list is **not** bucketed into the
         * picker (it is one track the menu plays, not a layer the
         * rule-4 fallback draws from) but the runtime exposes it via
         * the [menu] field so the menu player can read it directly.
         *
         * @param audio the editor payload loaded from
         *   `audio/audio-tracks.json`.
         * @return a catalog whose [drone] / [melody] / [rhythm] /
         *   [harmony] lists include every variant the editor saved
         *   (Retrograde / Inversion / Variant / Rotation / Centered /
         *   Slow Rotation). The picker draws from these expanded
         *   pools at runtime, replacing the 5/8/6/7 base tracks the
         *   legacy [default] exposed.
         */
        fun fromAudioTracks(audio: AudioTracks): MusicTrackCatalog
        {
            fun toTrack(obj: AudioObject, category: MusicCategory) =
                MusicTrack(resourceName = obj.resourceName, category = category, audioObject = obj)

            return MusicTrackCatalog(
                initialConditions = audio.start.map { toTrack(it, InitialConditions) },
                nemesis = audio.nemesis.map { toTrack(it, Nemesis) },
                terminalConditions = audio.end.map { toTrack(it, TerminalConditions) },
                drone = audio.drone.map { toTrack(it, Drone) },
                melody = audio.melody.map { toTrack(it, Melody) },
                rhythm = audio.rhythm.map { toTrack(it, Rhythm) },
                harmony = audio.harmony.map { toTrack(it, Harmony) }
            )
        }
    }
}
