package org.ttt.autogenesis.audiotrackseditor

/**
 * The eight categories the audio tracks editor exposes as tabs.
 *
 * Declaration order matters: [Category.values] is iterated in this
 * order to build the tab strip in [Render.renderTabStrip], and the
 * product spec lists the four scenario tabs first, then the four
 * layer tabs. Adding a new value to this enum automatically grows
 * the tab strip.
 *
 * The first four (`DRONE`, `MELODY`, `RHYTHM`, `HARMONY`) are the
 * musical-layer categories. The latter four (`MENU`, `START`,
 * `NEMESIS`, `END`) are scenario-level categories corresponding to
 * the [org.ttt.autogenesis.audio.MusicCategory] scenario tracks the
 * music-selector pipeline picks under specific game conditions:
 *
 *  - `MENU` — the main-menu track
 *  - `START` — the initial-conditions track (first turn of the game)
 *  - `NEMESIS` — the track played for any Nemesis or Elder God turn
 *  - `END` — the terminal-conditions track (someone could win in
 *    the next 4 rounds)
 *
 * Each value maps 1:1 to a field of the same name on
 * [structs.audio.AudioTracks] (lowercased).
 */
enum class Category
{
    MENU,
    START,
    NEMESIS,
    END,
    DRONE,
    MELODY,
    RHYTHM,
    HARMONY
}