package org.ttt.autogenesis.kvisionapp.audio

import org.ttt.autogenesis.audio.AudioObject
import org.ttt.autogenesis.audio.MusicDecision
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

/**
 * Engine-side seam the [MusicRunner] talks to. In production this is
 * implemented by [AudioEngine]; in unit tests a recording fake stands
 * in so we can assert which play/stop calls the runner makes without
 * spinning up an AudioContext.
 */
interface MusicEngineFacade
{
    /**
     * Start (or loop-start) an [AudioObject]. The runner always passes
     * objects whose channel id passes [isMusicChannel]; the engine is
     * responsible for resolving the resource name to a buffer and
     * scheduling playback.
     */
    fun play(obj: AudioObject)

    /**
     * Stop the playback of an audio object id with the given fade-out
     * duration in milliseconds.
     */
    fun stop(id: String, fadeOutMs: Long)

    /**
     * True iff [channelId] is in the Music branch of the engine's
     * channel tree (i.e., the engine would accept it through the
     * music pipeline). The default implementation calls
     * [AudioEngine.isMusicChannelId]; tests can substitute a fake
     * that hard-codes the result so the runner can be exercised
     * without an engine.
     */
    fun isMusicChannel(channelId: String): Boolean

    /**
     * Callback the engine fires when a non-loop, non-`loopWithTail`
     * music track naturally ends (i.e., its `AudioBufferSourceNode`'s
     * `onended` fires and no successor is replacing it). The argument
     * is the *original* `AudioObject.id` of the track that ended.
     *
     * The runner subscribes once in its constructor and uses this
     * signal to apply a pre-picked rule-4 batch that the server
     * attached to a rule-1 (initial conditions) decision's
     * [org.ttt.autogenesis.audio.MusicDecision.onTrackEnd] payload.
     * The production implementation delegates to
     * [AudioEngine.onTrackEnd]; tests substitute a fake that records
     * or fires the callback directly.
     */
    var onTrackEnd: ((objectId: String) -> Unit)?
}

/**
 * Production [MusicEngineFacade] backed by [AudioEngine].
 */
class DefaultMusicEngineFacade : MusicEngineFacade
{
    private val log = Logger
    override fun play(obj: AudioObject)
    {
        log.debug(
            LogCategory.NETWORK,
            "DefaultMusicEngineFacade.play: delegating to AudioEngine.play id=${obj.id} " +
            "resource='${obj.resourceName}' channel='${obj.channelId}'"
        )
        AudioEngine.play(obj, 0.0)
    }

    override fun stop(id: String, fadeOutMs: Long)
    {
        log.debug(
            LogCategory.NETWORK,
            "DefaultMusicEngineFacade.stop: delegating to AudioEngine.stop id='$id' fadeOutMs=$fadeOutMs"
        )
        AudioEngine.stop(id, fadeOutMs)
    }

    override fun isMusicChannel(channelId: String): Boolean =
        AudioEngine.isMusicChannelId(channelId)

    override var onTrackEnd: ((objectId: String) -> Unit)?
        get() = AudioEngine.onTrackEnd
        set(value) { AudioEngine.onTrackEnd = value }
}

/**
 * Client-side runner for the music-selector pipeline.
 *
 * The runner owns the set of music-track ids it has most recently
 * started. When a new [MusicDecision] arrives, it:
 *  1. Fades out every id it currently owns (using the decision's
 *     [MusicDecision.fadeOutDurationMs]).
 *  2. Plays each new track in the decision, resolving the catalog
 *     name to a webpack import path via [MusicResourceResolver].
 *  3. Replaces its "currently playing music" set with the new ids.
 *
 * Tracks whose `channelId` is not `Music` are silently dropped — the
 * server is supposed to only emit Music objects, but a defensive
 * filter keeps a misbehaving selector from ever pushing an SFX through
 * the music pipeline.
 *
 * ## Menu / pre-game tracks
 *
 * The main menu plays a single Music-channel track that the server
 * never schedules. To make sure the first `audio.musicSchedule`
 * cross-fades from menu music into gameplay music, the runner exposes
 * [playMenu] and [stopMenu] helpers that the UI calls on
 * MainMenu mount / unmount. The menu track's id lands in the same
 * `currentMusicIds` set the runner uses for fade-out, so the next
 * [playSchedule] will fade it out with the gameplay track's
 * fade-out duration — no separate coordination needed.
 *
 * The runner is a singleton (`object`) — there is one music stream
 * per browser session and the engine keeps a single set of active
 * players.
 */
class MusicRunner(private val engine: MusicEngineFacade = DefaultMusicEngineFacade())
{
    private val log = Logger

    init
    {
        // Subscribe once for the runner's lifetime. The engine fires
        // the callback with the ended track's id; the runner filters
        // by the current [pendingOnTrackEndId] and applies the batch
        // in [handleTrackEnd]. Subscribing in the init block keeps
        // the field-based and the test-injected facades symmetric.
        engine.onTrackEnd = ::handleTrackEnd
    }

    /**
     * Ids of the music objects the runner most recently started.
     * Replaced wholesale on every [playSchedule] / [playMenu] call.
     * Used so the next call knows what to fade out.
     */
    private val currentMusicIds: MutableSet<String> = mutableSetOf()

    /**
     * Id of the track the [pendingOnTrackEnd] batch is keyed to. When
     * the engine fires [MusicEngineFacade.onTrackEnd] with this id,
     * the runner applies [pendingOnTrackEnd] (if any). Cleared by a
     * subsequent [playSchedule] call or by a successful onTrackEnd
     * application.
     */
    private var pendingOnTrackEndId: String? = null

    /**
     * Pre-picked "play these when the initial conditions track ends"
     * batch sourced from [MusicDecision.onTrackEnd] on the most
     * recent [playSchedule] call (rule 1 / initial conditions). Each
     * entry's [AudioObject.fadeInDurationMs] is the fade the runner
     * applies via the engine. Cleared by a subsequent [playSchedule]
     * call or by a successful application.
     */
    private var pendingOnTrackEnd: List<AudioObject>? = null

    /**
     * Apply a [MusicDecision]: fade the old music out and the new
     * music in. Order of operations is fixed:
     *  1. Fade out the union of `currentMusicIds` (this runner's
     *     local view — includes any menu track registered via
     *     [playMenu]) and `decision.toFadeOut` (the server's
     *     authoritative list of which music ids to stop). The server
     *     is the source of truth for what is *currently scheduled*,
     *     but the local set also covers menu music that the server
     *     never sees, so we union the two.
     *  2. Play each new track in `decision.toPlay` (in order).
     *  3. Replace `currentMusicIds` with the new ids.
     *
     * The fade-out target in [engine] uses the family-id map the
     * engine maintains (see
     * [org.ttt.autogenesis.kvisionapp.audio.AudioEngine.familyToPlayer])
     * so a fade-out by the original id of a loop-with-tail chain
     * reaches the actual current successor. Without that, the old
     * track would keep playing through every `playSchedule`.
     *
     * @param decision the picker-emitted plan; never null.
     */
    fun playSchedule(decision: MusicDecision)
    {
        log.info(
            LogCategory.NETWORK,
            "MusicRunner.playSchedule: ENTER toPlay=${decision.toPlay.size} " +
            "toFadeOut=${decision.toFadeOut.size} " +
            "fadeOutMs=${decision.fadeOutDurationMs} fadeInMs=${decision.fadeInDurationMs} " +
            "currentMusicIds.size=${currentMusicIds.size}"
        )

        // 1. Fade out the union of locally-tracked ids and the
        //    server's authoritative toFadeOut list. Dedupe with
        //    toSet() so the engine never sees a double-stop for the
        //    same id. The server list is included first in the
        //    iteration order so any log entries from the engine
        //    appear in the same order the server asked for them.
        val idsToFade: Set<String> = (decision.toFadeOut + currentMusicIds).toSet()
        if (decision.toFadeOut.isNotEmpty() && currentMusicIds.isNotEmpty())
        {
            val localOnly = currentMusicIds - decision.toFadeOut.toSet()
            val serverOnly = decision.toFadeOut - currentMusicIds.toSet()
            if (localOnly.isNotEmpty() || serverOnly.isNotEmpty())
            {
                log.debug(
                    LogCategory.NETWORK,
                    "MusicRunner.playSchedule: fade-out union diverged — " +
                    "localOnly=[${localOnly.joinToString { "'$it'" }}] " +
                    "serverOnly=[${serverOnly.joinToString { "'$it'" }}] " +
                    "(local covers menu music; server covers any drift in playingObjects)"
                )
            }
        }
        for (id in idsToFade)
        {
            log.debug(
                LogCategory.NETWORK,
                "MusicRunner.playSchedule: fading out id='$id' (fadeOutMs=${decision.fadeOutDurationMs})"
            )
            engine.stop(id, decision.fadeOutDurationMs)
        }
        // We do NOT clear currentMusicIds yet — the engine's
        // [AudioEngine.stop] uses the family map, so the original ids
        // remain valid references for a subsequent fade-out call.
        // The set is replaced wholesale in step 3 below.

        // 2. Play the new tracks. The music pipeline accepts any
        // channel in the Music branch of the engine's channel tree
        // (Drone / Melody / Rhythm / Harmony / Menu / Start / Nemesis
        // / End in the default tree — all children of the Music
        // master). The defensive filter below consults
        // [AudioEngine.isMusicChannelId] so a misbehaving selector
        // that emits a non-music channelId cannot pollute the engine.
        val newIds = mutableSetOf<String>()
        var skippedNonMusic = 0
        var skippedUnresolved = 0
        var threwCount = 0
        for (obj in decision.toPlay)
        {
            if (!engine.isMusicChannel(obj.channelId))
            {
                log.warn(
                    LogCategory.NETWORK,
                    "MusicRunner.playSchedule: skipping non-music-channel object id=${obj.id} (channel=${obj.channelId})"
                )
                skippedNonMusic++
                continue
            }
            val resolvedPath = MusicResourceResolver.resolve(obj.resourceName)
            if (resolvedPath == null)
            {
                log.warn(
                    LogCategory.NETWORK,
                    "MusicRunner.playSchedule: cannot resolve resource name '${obj.resourceName}' for object id=${obj.id} — skipping (check MusicResourceResolver manifest vs MusicTrackCatalog)"
                )
                skippedUnresolved++
                continue
            }
            val scheduled = obj.copy(resourceName = resolvedPath)
            try
            {
                engine.play(scheduled)
                newIds.add(scheduled.id)
                log.debug(
                    LogCategory.NETWORK,
                    "MusicRunner.playSchedule: scheduled id=${scheduled.id} " +
                    "resource='${scheduled.resourceName}' channel='${scheduled.channelId}'"
                )
            }
            catch (e: Throwable)
            {
                log.warn(
                    LogCategory.NETWORK,
                    "MusicRunner.playSchedule: engine.play threw for id=${scheduled.id} (non-fatal): ${e.message}"
                )
                threwCount++
            }
        }

        // 3. Replace the active set with the new track ids.
        currentMusicIds.clear()
        currentMusicIds.addAll(newIds)

        log.info(
            LogCategory.NETWORK,
            "MusicRunner.playSchedule: applied — played=${newIds.size} " +
            "fadedOut=${idsToFade.size} (union of local=${currentMusicIds.size} + server=${decision.toFadeOut.size}) " +
            "skippedNonMusic=$skippedNonMusic skippedUnresolved=$skippedUnresolved " +
            "threw=$threwCount currentMusicIds now=${currentMusicIds.size}"
        )

        // 4. Arm the pending batch (replaces any prior pending). The
        //    server ships a pre-picked rule-4 batch in
        //    [MusicDecision.onTrackEnd] on rule-1 (initial
        //    conditions) decisions; the runner applies it the moment
        //    the initial track naturally ends. A subsequent
        //    playSchedule (the next turn's decision arriving first)
        //    simply overwrites both fields — the previous pending
        //    batch is discarded, matching the existing
        //    currentMusicIds replacement semantics.
        if (decision.onTrackEnd != null && decision.toPlay.isNotEmpty())
        {
            pendingOnTrackEndId = decision.toPlay[0].id
            pendingOnTrackEnd = decision.onTrackEnd
            log.info(
                LogCategory.NETWORK,
                "MusicRunner.playSchedule: armed pending onTrackEnd batch " +
                "(keyId='${pendingOnTrackEndId}', batchSize=${pendingOnTrackEnd?.size})"
            )
        }
        else
        {
            pendingOnTrackEndId = null
            pendingOnTrackEnd = null
        }
    }

    /**
     * Engine-fires-this hook for "a music track ended naturally". The
     * argument is the *original* [AudioObject.id] of the track that
     * ended (per the engine's [AudioEngine.onTrackEnd] contract, only
     * non-loop, non-`loopWithTail` tracks reach this hook). If the
     * id matches the currently-armed [pendingOnTrackEndId] AND
     * [pendingOnTrackEnd] is non-empty, this method:
     *  1. Calls [MusicEngineFacade.stop] on the ended id with no
     *     fade (the audio source is already over; the stop is purely
     *     for engine-side cleanup of the family-map entry).
     *  2. Plays each pending [AudioObject] (each carries its own
     *     [AudioObject.fadeInDurationMs] the server baked in via
     *     [org.ttt.autogenesis.server.audio.MusicSelector]).
     *  3. Replaces [currentMusicIds] with the new ids.
     *  4. Clears the pending state — it has been applied.
     *
     * No-op when the id is not the pending key, or when the pending
     * batch is null/empty (e.g. a subsequent playSchedule already
     * replaced it, or the server attached an empty list).
     */
    private fun handleTrackEnd(endedId: String)
    {
        if (endedId != pendingOnTrackEndId)
        {
            log.debug(
                LogCategory.NETWORK,
                "MusicRunner.handleTrackEnd: id='$endedId' is not the pending key " +
                "(${pendingOnTrackEndId ?: "<none>"}) — ignoring"
            )
            return
        }
        val pending = pendingOnTrackEnd
        if (pending.isNullOrEmpty())
        {
            // Pending was cleared by a more recent playSchedule, or
            // the server attached an empty list. Nothing to apply;
            // just clear and bail.
            pendingOnTrackEndId = null
            pendingOnTrackEnd = null
            log.debug(
                LogCategory.NETWORK,
                "MusicRunner.handleTrackEnd: pending key matched but batch is null/empty — clearing"
            )
            return
        }
        log.info(
            LogCategory.NETWORK,
            "MusicRunner.handleTrackEnd: applying pending batch (size=${pending.size} for endedId='$endedId')"
        )
        // Stop the ended track (no fade — the source is already over;
        // this just unregisters it from the engine's family map).
        engine.stop(endedId, 0L)

        // Play each pending track, mirroring playSchedule's step 2
        // (same channel filter + resource resolver + per-track
        // try/catch).
        val newIds = mutableSetOf<String>()
        var skippedNonMusic = 0
        var skippedUnresolved = 0
        var threwCount = 0
        for (obj in pending)
        {
            if (!engine.isMusicChannel(obj.channelId))
            {
                log.warn(
                    LogCategory.NETWORK,
                    "MusicRunner.handleTrackEnd: skipping non-music-channel object id=${obj.id} (channel=${obj.channelId})"
                )
                skippedNonMusic++
                continue
            }
            val resolvedPath = MusicResourceResolver.resolve(obj.resourceName)
            if (resolvedPath == null)
            {
                log.warn(
                    LogCategory.NETWORK,
                    "MusicRunner.handleTrackEnd: cannot resolve resource name '${obj.resourceName}' for object id=${obj.id} — skipping"
                )
                skippedUnresolved++
                continue
            }
            val scheduled = obj.copy(resourceName = resolvedPath)
            try
            {
                engine.play(scheduled)
                newIds.add(scheduled.id)
                log.debug(
                    LogCategory.NETWORK,
                    "MusicRunner.handleTrackEnd: scheduled id=${scheduled.id} resource='${scheduled.resourceName}'"
                )
            }
            catch (e: Throwable)
            {
                log.warn(
                    LogCategory.NETWORK,
                    "MusicRunner.handleTrackEnd: engine.play threw for id=${scheduled.id} (non-fatal): ${e.message}"
                )
                threwCount++
            }
        }
        currentMusicIds.clear()
        currentMusicIds.addAll(newIds)
        // Clear the pending state — it has been applied.
        pendingOnTrackEndId = null
        pendingOnTrackEnd = null
        log.info(
            LogCategory.NETWORK,
            "MusicRunner.handleTrackEnd: applied — played=${newIds.size} " +
            "fadedOut=1 (endedId='$endedId' with 0ms fade) " +
            "skippedNonMusic=$skippedNonMusic skippedUnresolved=$skippedUnresolved " +
            "threw=$threwCount currentMusicIds now=${currentMusicIds.size}"
        )
    }

    /**
     * Play a main-menu track through the music pipeline. The
     * [AudioObject] is expected to have `channelId == "Music"`,
     * volume / loop / etc. matching the editor's menu entry, and a
     * `resourceName` the [MusicResourceResolver] knows how to look up.
     *
     * The track's id is added to [currentMusicIds] so the next
     * [playSchedule] call automatically cross-fades out of the menu
     * into the first turn's music.
     *
     * @param menuTrack the editor's `World.audioTracks.menu[0]`-shaped
     *   audio object (built by the caller from the manifest or the
     *   server-pushed menu payload).
     * @return the id the runner is now tracking, or `null` if the
     *   object was rejected (non-Music channel or unresolvable name).
     */
    fun playMenu(menuTrack: AudioObject): String?
    {
        log.info(
            LogCategory.NETWORK,
            "MusicRunner.playMenu: ENTER id=${menuTrack.id} " +
            "resource='${menuTrack.resourceName}' channel='${menuTrack.channelId}' " +
            "currentMusicIds.size=${currentMusicIds.size}"
        )
        if (!engine.isMusicChannel(menuTrack.channelId))
        {
            log.warn(
                LogCategory.NETWORK,
                "MusicRunner.playMenu: skipping non-music-channel object id=${menuTrack.id} (channel=${menuTrack.channelId})"
            )
            return null
        }
        val resolvedPath = MusicResourceResolver.resolve(menuTrack.resourceName)
        if (resolvedPath == null)
        {
            log.warn(
                LogCategory.NETWORK,
                "MusicRunner.playMenu: cannot resolve menu resource name '${menuTrack.resourceName}' — skipping (check MusicResourceResolver manifest)"
            )
            return null
        }
        val scheduled = menuTrack.copy(resourceName = resolvedPath)

        // Replace any previously-played menu track (e.g. user
        // navigates away from the menu and back) so we never
        // accumulate ids.
        for (id in currentMusicIds)
        {
            log.debug(
                LogCategory.NETWORK,
                "MusicRunner.playMenu: stopping previously-tracked id='$id' (no fade) to make room for new menu track"
            )
            engine.stop(id, 0L)
        }
        currentMusicIds.clear()

        try
        {
            engine.play(scheduled)
            currentMusicIds.add(scheduled.id)
            log.info(
                LogCategory.NETWORK,
                "MusicRunner.playMenu: started menu track id=${scheduled.id} resource='${scheduled.resourceName}'"
            )
            return scheduled.id
        }
        catch (e: Throwable)
        {
            log.warn(
                LogCategory.NETWORK,
                "MusicRunner.playMenu: engine.play threw for id=${scheduled.id} (non-fatal): ${e.message}"
            )
            return null
        }
    }

    /**
     * Stop a previously-played menu track by id with a fade-out. The
     * id is removed from [currentMusicIds]. No-op if the id is not
     * currently tracked.
     */
    fun stopMenu(id: String, fadeOutMs: Long = 0L)
    {
        val removed = currentMusicIds.remove(id)
        if (!removed)
        {
            log.debug(
                LogCategory.NETWORK,
                "MusicRunner.stopMenu: id='$id' not in currentMusicIds — no-op (fadeOutMs=$fadeOutMs)"
            )
            return
        }
        engine.stop(id, fadeOutMs)
        log.info(
            LogCategory.NETWORK,
            "MusicRunner.stopMenu: stopped menu track id='$id' (fadeOutMs=$fadeOutMs)"
        )
    }

    companion object
    {
        /**
         * @deprecated Replaced by [org.ttt.autogenesis.audio.AudioChannelIds.MUSIC_MASTER_ID] and
         *   [AudioEngine.isMusicChannelId]. Kept as a source-compat
         *   alias for callers that still compare channel ids directly.
         *   New code should use the engine's is-music-channel helper,
         *   which walks the parent chain and matches every channel in
         *   the Music branch (Drone, Melody, Rhythm, Harmony, Menu,
         *   Start, Nemesis, End), not just the master.
         */
        const val MUSIC_CHANNEL: String = "Music"

        /**
         * The shared runner instance used by
         * [AudioClientHandlers.handleMusicSchedule] and the
         * [ui.MainMenu] main-menu music player. Tests construct their
         * own runners with a recording facade.
         */
        val instance: MusicRunner = MusicRunner()
    }
}