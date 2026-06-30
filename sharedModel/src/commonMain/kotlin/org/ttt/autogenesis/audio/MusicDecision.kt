package org.ttt.autogenesis.audio

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/**
 * Pure-data output of the music selector.
 *
 * @param toPlay the AudioObjects to schedule on the client. The list
 *   order is significant: the runner plays them in the order received.
 *   Each AudioObject's [AudioObject.resourceName] is a catalog name; the
 *   client resolves it to a real file via fuzzy search.
 * @param toFadeOut ids of every currently-playing music object that the
 *   runner should fade out. The runner is responsible for actually
 *   issuing the stop with the configured fade.
 * @param fadeOutDurationMs how long the fade-out should take. Same value
 *   applied to every id in [toFadeOut].
 * @param fadeInDurationMs how long the fade-in should take. Each entry
 *   in [toPlay] gets this set on its [AudioObject.fadeInDurationMs].
 * @param onTrackEnd optional pre-picked "play these when [toPlay]'s
 *   track naturally ends" payload. Used by rule 1 (initial conditions):
 *   the server pre-picks the rule-4 random batch and ships it with the
 *   decision so the [org.ttt.autogenesis.kvisionapp.audio.MusicRunner]
 *   can fade in the four layers the moment the initial-conditions
 *   track's `onended` fires, instead of waiting in silence for the
 *   next turn's decision. Omitted from the wire format when null
 *   (rules 2 / 3 / 4 don't attach a pending batch) so older clients
 *   decode non-initial decisions exactly as before. A subsequent
 *   [org.ttt.autogenesis.kvisionapp.audio.MusicRunner.playSchedule]
 *   call (the next turn's decision arriving first) discards any
 *   unconsumed [onTrackEnd] batch.
 */
@Serializable
data class MusicDecision(
    val toPlay: List<AudioObject>,
    val toFadeOut: List<String>,
    val fadeOutDurationMs: Long = 2000L,
    val fadeInDurationMs: Long = 2000L,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val onTrackEnd: List<AudioObject>? = null
)
