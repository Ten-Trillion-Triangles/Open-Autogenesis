package org.ttt.autogenesis.audio

import kotlinx.serialization.Serializable

/**
 * Pure-data input to the music selector.
 *
 * The picker is a pure function over a [TurnContext]; it has no I/O, no
 * coroutines, no global state. The caller (TurnHarness) builds a fresh
 * [TurnContext] at the start of every turn and passes it in.
 *
 * @param actorName whose turn it is (player or NPC name).
 * @param roundNumber the world round (1-based).
 * @param turnOrderIndex position in the round's turn order (0-based).
 * @param isFirstTurn true for the first turn of the entire game
 *   (round 1 + first actor in the turn order). Drives rule 1.
 * @param actorIsNemesisOrElderGod true if the actor is a Nemesis or
 *   Elder God NPC. Drives rule 2.
 * @param canWinInNext4Rounds true if the [MusicSelector.canWinInNext4Rounds]
 *   heuristic says someone could plausibly win inside four rounds.
 *   Drives rule 3.
 * @param currentlyPlayingMusicIds ids of every AudioObject in the Music
 *   channel that is currently scheduled on the server. The picker
 *   populates [MusicDecision.toFadeOut] from this list.
 */
@Serializable
data class TurnContext(
    val actorName: String,
    val roundNumber: Int,
    val turnOrderIndex: Int,
    val isFirstTurn: Boolean,
    val actorIsNemesisOrElderGod: Boolean,
    val canWinInNext4Rounds: Boolean,
    val currentlyPlayingMusicIds: List<String>
)
