package org.ttt.autogenesis.server.audio

import org.ttt.autogenesis.audio.AudioObject
import org.ttt.autogenesis.audio.MusicCategory
import org.ttt.autogenesis.audio.MusicDecision
import org.ttt.autogenesis.audio.MusicTrack
import org.ttt.autogenesis.audio.MusicTrackCatalog
import org.ttt.autogenesis.audio.TurnContext
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import enums.NpcType
import structs.World
import kotlin.random.Random

/**
 * Server-side music picker.
 *
 * The selector is a pure function over a [TurnContext] — no I/O, no
 * coroutines, no global state. The caller (TurnHarness) builds a fresh
 * context at the start of every turn and passes it in. The selector
 * returns a [MusicDecision] describing which tracks to play and which
 * existing music-track ids to fade out. AudioManager is then responsible
 * for broadcasting the decision to all clients.
 *
 * ## Selection rules (in order of precedence)
 *
 *  1. **First turn of the game** ([TurnContext.isFirstTurn]) — play
 *     the catalog's [MusicTrackCatalog.initialConditions] (the
 *     `Initial Conditions wet 1` track); fade out everything currently
 *     playing on the Music channel.
 *  2. **Nemesis or Elder God turn**
 *     ([TurnContext.actorIsNemesisOrElderGod]) — play the catalog's
 *     [MusicTrackCatalog.nemesis] (the `Nemesis wet 1` track); fade
 *     out current music. Both NPC types share the same track by design
 *     (the music library has no elder-god-specific mp3).
 *  3. **Someone can plausibly win in the next 4 rounds**
 *     ([TurnContext.canWinInNext4Rounds]) — play the catalog's
 *     [MusicTrackCatalog.terminalConditions] (the `Terminal Conditions
 *     wet 1` track); fade out current music.
 *  4. **Any other turn** — pick one random track from each of drone /
 *     melody / rhythm / harmony (four simultaneous layers); fade out
 *     current music.
 *
 * Precedence is strict: rule 1 wins over rule 2 wins over rule 3 wins
 * over rule 4. A first turn where the actor is a Nemesis still plays
 * the Initial Conditions track.
 *
 * ## Track data source
 *
 * The [catalog] is the source of the track [MusicTrack] labels, but the
 * emitted [AudioObject]s come from the editor's payload when present:
 * if a [MusicTrack] carries an [MusicTrack.audioObject], the selector
 * passes it through verbatim so the editor's per-track volume / loop /
 * loopEnd / loopWithTail settings survive the round trip. For the
 * legacy [MusicTrackCatalog.default] (which has no editor payload),
 * the selector falls back to a generic Music-channel object built
 * from the track's [MusicTrack.resourceName].
 *
 * @param catalog source of the track names / playback configs (default
 *   [MusicTrackCatalog.default] for tests; in production
 *   [TurnHarness] builds a runtime catalog via
 *   [MusicTrackCatalog.fromAudioTracks] from the loaded
 *   `audio/audio-tracks.json`).
 * @param random injected for deterministic tests
 *   ([kotlin.random.Random.Default] in production).
 * @param fadeOutMs default fade duration when the decision does not
 *   override it; the runner uses this on every entry in
 *   [MusicDecision.toFadeOut].
 * @param fadeInMs default fade duration applied to every emitted
 *   [AudioObject.fadeInDurationMs] when the editor did not set one.
 */
class MusicSelector(
    private val catalog: MusicTrackCatalog = MusicTrackCatalog.default,
    private val random: Random = Random.Default,
    private val fadeOutMs: Long = 2000L,
    private val fadeInMs: Long = 2000L
)
{
    /**
     * Pick the music for a single turn.
     *
     * @return a [MusicDecision] whose [MusicDecision.toPlay] is non-empty
     *   and whose [MusicDecision.toFadeOut] is exactly the caller's
     *   [TurnContext.currentlyPlayingMusicIds] (so the runner can stop
     *   them with the configured fade).
     */
    fun selectForTurn(ctx: TurnContext): MusicDecision
    {
        Logger.debug(
            LogCategory.SYSTEM,
            "MusicSelector.selectForTurn: ENTER actor='${ctx.actorName}' round=${ctx.roundNumber} " +
            "turnOrderIndex=${ctx.turnOrderIndex} isFirstTurn=${ctx.isFirstTurn} " +
            "actorIsNemesisOrElderGod=${ctx.actorIsNemesisOrElderGod} " +
            "canWinInNext4Rounds=${ctx.canWinInNext4Rounds} " +
            "currentlyPlayingMusicIds.size=${ctx.currentlyPlayingMusicIds.size}"
        )

        val rule: Int
        val tracks: List<MusicTrack>
        val bucketName: String
        when
        {
            ctx.isFirstTurn -> {
                rule = 1
                bucketName = "initialConditions"
                tracks = catalog.initialConditions
            }
            ctx.actorIsNemesisOrElderGod -> {
                rule = 2
                bucketName = "nemesis"
                tracks = catalog.nemesis
            }
            ctx.canWinInNext4Rounds -> {
                rule = 3
                bucketName = "terminalConditions"
                tracks = catalog.terminalConditions
            }
            else -> {
                rule = 4
                bucketName = "oneOfEachLayer(drone/melody/rhythm/harmony)"
                tracks = pickOneOfEachLayer()
            }
        }

        if (tracks.isEmpty())
        {
            Logger.warn(
                LogCategory.SYSTEM,
                "MusicSelector.selectForTurn: rule $rule fired (bucket=$bucketName) but catalog returned 0 tracks " +
                "— emitting an empty toPlay list (client will receive no music for actor='${ctx.actorName}')"
            )
        }
        else
        {
            Logger.info(
                LogCategory.SYSTEM,
                "MusicSelector.selectForTurn: rule $rule fired → bucket=$bucketName " +
                "(${tracks.size} tracks: ${tracks.joinToString { it.resourceName }}) " +
                "for actor='${ctx.actorName}' round=${ctx.roundNumber}"
            )
        }

        val toPlay: List<AudioObject> = tracks.map { it.toAudioObject(fadeInMs) }
        // Rule 1 attaches a pre-picked rule-4 batch in onTrackEnd so the
        // client can fade in the four layers the moment the initial
        // conditions track naturally ends, instead of waiting in silence
        // for the next turn's decision. Rules 2 / 3 / 4 leave onTrackEnd
        // null — a subsequent playSchedule call (the next turn arriving
        // first) discards any unconsumed batch from the wire.
        val onTrackEnd: List<AudioObject>? =
            if (rule == 1) prePickRule4Batch(fadeInMs) else null
        val decision = MusicDecision(
            toPlay = toPlay,
            toFadeOut = ctx.currentlyPlayingMusicIds,
            fadeOutDurationMs = fadeOutMs,
            fadeInDurationMs = fadeInMs,
            onTrackEnd = onTrackEnd
        )
        Logger.debug(
            LogCategory.SYSTEM,
            "MusicSelector.selectForTurn: EXIT toPlay.size=${decision.toPlay.size} " +
            "toFadeOut.size=${decision.toFadeOut.size} fadeOutMs=${decision.fadeOutDurationMs} " +
            "fadeInMs=${decision.fadeInDurationMs}"
        )
        return decision
    }

    /**
     * Pre-pick the rule-4 batch (one track from each of drone / melody /
     * rhythm / harmony) for the first-turn decision's
     * [MusicDecision.onTrackEnd] payload. Returns null if any of the
     * four layer pools is empty so the runner doesn't try to schedule
     * an incomplete batch — in that case the client falls back to the
     * existing "wait for the next turn's decision" behaviour.
     */
    private fun prePickRule4Batch(fadeInMs: Long): List<AudioObject>?
    {
        if (catalog.drone.isEmpty() || catalog.melody.isEmpty() ||
            catalog.rhythm.isEmpty() || catalog.harmony.isEmpty())
        {
            Logger.warn(
                LogCategory.SYSTEM,
                "MusicSelector.prePickRule4Batch: cannot pre-pick the rule-4 batch for the " +
                "first-turn onTrackEnd payload — at least one layer pool is empty " +
                "(drone=${catalog.drone.size} melody=${catalog.melody.size} " +
                "rhythm=${catalog.rhythm.size} harmony=${catalog.harmony.size}). " +
                "The client will fall back to silence-until-next-turn."
            )
            return null
        }
        val picked = pickOneOfEachLayer()
        Logger.info(
            LogCategory.SYSTEM,
            "MusicSelector.prePickRule4Batch: pre-picked rule-4 batch for first-turn onTrackEnd " +
            "— drone='${picked[0].resourceName}' melody='${picked[1].resourceName}' " +
            "rhythm='${picked[2].resourceName}' harmony='${picked[3].resourceName}'"
        )
        return picked.map { it.toAudioObject(fadeInMs) }
    }

    /**
     * Pick one random track from each of the four layered categories
     * (drone / melody / rhythm / harmony) for the rule-4 fallback.
     * Order is preserved: drone, melody, rhythm, harmony.
     */
    private fun pickOneOfEachLayer(): List<MusicTrack>
    {
        val drone = catalog.drone.random(random)
        val melody = catalog.melody.random(random)
        val rhythm = catalog.rhythm.random(random)
        val harmony = catalog.harmony.random(random)
        val picked = listOf(drone, melody, rhythm, harmony)
        Logger.debug(
            LogCategory.SYSTEM,
            "MusicSelector.pickOneOfEachLayer: picked drone='${drone.resourceName}' " +
            "melody='${melody.resourceName}' rhythm='${rhythm.resourceName}' " +
            "harmony='${harmony.resourceName}'"
        )
        return picked
    }

    /**
     * Mid-turn reroll (see file KDoc for the design rationale).
     * Returns null when the previous decision was scenario-bound
     * (rule 1 initial, rule 2 nemesis, rule 3 terminal) or the
     * random-layer pools are empty.
     */
    fun reselectRandomLayers(
        previousDecision: MusicDecision,
        fadeOutMs: Long = this.fadeOutMs,
        fadeInMs: Long = this.fadeInMs
    ): MusicDecision?
    {
        Logger.debug(
            LogCategory.SYSTEM,
            "MusicSelector.reselectRandomLayers: ENTER previous.toPlay.size=${previousDecision.toPlay.size} " +
            "previous.toFadeOut.size=${previousDecision.toFadeOut.size}"
        )
        for(obj in previousDecision.toPlay)
        {
            val resolved = catalog.findByName(obj.resourceName)
            if(resolved == null)
            {
                Logger.debug(
                    LogCategory.SYSTEM,
                    "MusicSelector.reselectRandomLayers: previous track '${obj.resourceName}' not in catalog — returning null (treat as scenario-bound)"
                )
                return null
            }
            when(resolved.category)
            {
                MusicCategory.InitialConditions,
                MusicCategory.Nemesis,
                MusicCategory.TerminalConditions -> {
                    Logger.debug(
                        LogCategory.SYSTEM,
                        "MusicSelector.reselectRandomLayers: previous track '${obj.resourceName}' scenario-bound " +
                        "(category=${resolved.category}) — returning null"
                    )
                    return null
                }
                MusicCategory.Drone, MusicCategory.Melody, MusicCategory.Rhythm, MusicCategory.Harmony -> { }
            }
        }
        if(catalog.drone.isEmpty() || catalog.melody.isEmpty() ||
           catalog.rhythm.isEmpty() || catalog.harmony.isEmpty())
        {
            Logger.warn(
                LogCategory.SYSTEM,
                "MusicSelector.reselectRandomLayers: empty random-layer pool " +
                "(drone=${catalog.drone.size} melody=${catalog.melody.size} " +
                "rhythm=${catalog.rhythm.size} harmony=${catalog.harmony.size}) — returning null"
            )
            return null
        }
        val newTracks = pickOneOfEachLayer()
        val newToPlay = newTracks.map { it.toAudioObject(fadeInMs) }
        val newToFadeOut = previousDecision.toPlay.map { it.id }
        val newDecision = MusicDecision(
            toPlay = newToPlay,
            toFadeOut = newToFadeOut,
            fadeOutDurationMs = fadeOutMs,
            fadeInDurationMs = fadeInMs
        )
        Logger.info(
            LogCategory.SYSTEM,
            "MusicSelector.reselectRandomLayers: EXIT reroll applied — " +
            "+${newToPlay.size} new, -${newToFadeOut.size} fade-outs, " +
            "new=[${newToPlay.joinToString { "'${it.resourceName}'" }}]"
        )
        return newDecision
    }

    companion object
    {
        /**
         * Heuristic for "someone could plausibly win in the next 4 rounds".
         *
         * Two branches:
         *  1. **Territory branch** — any non-blank owner holds ≥
         *     [thresholdPercent] of *active* (non-destroyed) territory
         *     point value. With the default 60% threshold and the game's
         *     75% immediate-win threshold, this fires when a player is
         *     within striking distance.
         *  2. **Elder-God branch** — any **undefeated** Nemesis or
         *     Elder God NPC is present AND the destroyed-territory share
         *     is ≥ 50%. The Elder God destroys a tile every round (per
         *     NpcType docstring), so a 50% destruction share with an
         *     alive Elder God is plausibly terminal within 4 rounds.
         *
         * Returns false on an empty map or when no active map points
         * remain (nothing to win).
         *
         * @param thresholdPercent territory-share threshold (0..100,
         *   clamped). Defaults to 60%.
         */
        fun canWinInNext4Rounds(
            world: World,
            thresholdPercent: Double = 60.0
        ): Boolean
        {
            val activeTiles = world.mapTiles.filter { !it.isDestroyed }
            val totalActivePoints = activeTiles.sumOf { it.pointValue }
            if(totalActivePoints <= 0)
            {
                Logger.debug(
                    LogCategory.SYSTEM,
                    "MusicSelector.canWinInNext4Rounds: no active map points — returning false"
                )
                return false
            }

            val owners = activeTiles
                .map { it.ruler.trim() }
                .filter { it.isNotBlank() }
                .distinct()
            val clamped = thresholdPercent.coerceIn(0.0, 100.0)
            for(owner in owners)
            {
                val ownerPoints = activeTiles
                    .filter { it.ruler.trim().equals(owner, ignoreCase = true) }
                    .sumOf { it.pointValue }
                val share = ownerPoints.toDouble() / totalActivePoints.toDouble() * 100.0
                if(share >= clamped)
                {
                    Logger.info(
                        LogCategory.SYSTEM,
                        "MusicSelector.canWinInNext4Rounds: territory branch hit — owner='$owner' " +
                        "holds ${"%.1f".format(share)}% (>= ${clamped}%) of active points " +
                        "(${ownerPoints}/${totalActivePoints})"
                    )
                    return true
                }
            }

            val hasSummitThreat = world.npc.any {
                (it.type == NpcType.Nemesis || it.type == NpcType.ElderGod) && !it.isDefeated
            }
            if(!hasSummitThreat)
            {
                Logger.debug(
                    LogCategory.SYSTEM,
                    "MusicSelector.canWinInNext4Rounds: no territory hit and no undefeated Nemesis/ElderGod — returning false"
                )
                return false
            }

            val totalTiles = world.mapTiles.size
            if(totalTiles <= 0)
            {
                Logger.debug(
                    LogCategory.SYSTEM,
                    "MusicSelector.canWinInNext4Rounds: undefeated summit NPC present but map has 0 tiles — returning false"
                )
                return false
            }
            val destroyedShare = world.mapTiles.count { it.isDestroyed }.toDouble() /
                totalTiles.toDouble() * 100.0
            val hit = destroyedShare >= 50.0
            Logger.info(
                LogCategory.SYSTEM,
                "MusicSelector.canWinInNext4Rounds: elder-god branch — destroyedShare=${"%.1f".format(destroyedShare)}% " +
                "(threshold=50.0%) → ${if (hit) "HIT" else "miss"}"
            )
            return hit
        }
    }
}

/**
 * Build an [AudioObject] for the runner to play. If the track carries
 * the editor's [MusicTrack.audioObject] payload, the editor's data is
 * preserved verbatim (volume / loop / loopEnd / loopWithTail / …) and
 * a fresh playback id is generated. Otherwise a generic Music-channel
 * object is built from the track's name.
 *
 * Fade-in: the editor typically leaves `fadeInDurationMs` at 0 because
 * the music is meant to layer in under the previous track. We override
 * any zero fade-in with the configured [fadeInMs] so the runner always
 * applies a smooth ramp instead of popping a track in at full volume.
 */
private fun MusicTrack.toAudioObject(fadeInMs: Long): AudioObject
{
    val editor = audioObject
    if (editor != null)
    {
        val effectiveFadeIn =
            if (editor.fadeInDurationMs <= 0) fadeInMs else editor.fadeInDurationMs
        // Reconstruct rather than copy() so the data class default
        // `id = generateUuid()` runs and each emission gets a fresh
        // playback id (matching the legacy hardcoded-catalog path).
        return AudioObject(
            resourceName = editor.resourceName,
            channelId = editor.channelId,
            volume = editor.volume,
            panning = editor.panning,
            speed = editor.speed,
            loop = editor.loop,
            startTimeMs = editor.startTimeMs,
            startFrame = editor.startFrame,
            startSample = editor.startSample,
            endTimeMs = editor.endTimeMs,
            fadeInDurationMs = effectiveFadeIn,
            fadeOutDurationMs = editor.fadeOutDurationMs,
            loopStart = editor.loopStart,
            loopEnd = editor.loopEnd,
            loopWithTail = editor.loopWithTail
        )
    }
    return AudioObject(
        resourceName = resourceName,
        channelId = org.ttt.autogenesis.audio.AudioChannelIds.MUSIC_MASTER_ID,
        volume = 1.0f,
        loop = true,
        fadeInDurationMs = fadeInMs
    )
}