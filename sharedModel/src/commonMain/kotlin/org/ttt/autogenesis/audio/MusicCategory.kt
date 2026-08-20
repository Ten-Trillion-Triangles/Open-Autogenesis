package org.ttt.autogenesis.audio

import kotlinx.serialization.Serializable

/**
 * Classifies a [MusicTrack] in the game's music library.
 *
 * The first three are **special** singletons played under specific game
 * conditions:
 *  - [InitialConditions]: the very first turn of a game.
 *  - [Nemesis]: any turn owned by a Nemesis or Elder God NPC.
 *  - [TerminalConditions]: a turn on which someone could plausibly win
 *    in the next 4 rounds.
 *
 * The remaining four are **regular** categories. The picker picks one
 * track from each of them for every "any other turn" rule, layering
 * drone / melody / rhythm / harmony at the same time.
 */
@Serializable
enum class MusicCategory
{
    InitialConditions,
    Nemesis,
    TerminalConditions,
    Drone,
    Melody,
    Rhythm,
    Harmony
}