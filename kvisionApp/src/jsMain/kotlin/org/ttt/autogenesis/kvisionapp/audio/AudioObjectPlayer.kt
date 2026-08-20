package org.ttt.autogenesis.kvisionapp.audio

import kotlinx.browser.window
import org.ttt.autogenesis.audio.AudioObject
import org.ttt.autogenesis.audio.AudioObjectView
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

/**
 * Idempotent wrapper around AudioBufferSourceNode.stop().
 *
 * Per the Web Audio API spec, stop() may only be called when the source is in
 * SCHEDULED_STATE or PLAYING_STATE. Calling it on an already-ended/finished source
 * throws InvalidStateError. In Kotlin/JS, JS-native errors thrown by the Web Audio
 * API are NOT subclasses of kotlin.Throwable, so a standard try/catch on the Kotlin
 * side does not reliably intercept them.
 *
 * This helper uses a native JS try/catch via js("...") to ensure the error is
 * swallowed at the JS layer regardless of which error class is thrown.
 *
 * (Same helper exists in the jukebox copy of this file; it is duplicated rather
 * than shared because the two AudioObjectPlayer files are not yet consolidated
 * into a shared jsMain module.)
 */
private fun safeStopSource(node: AudioBufferSourceNode) {
    js("try { node.stop() } catch(e) { /* source may already be stopped/ended */ }")
}

/**
 * Generates a random hex UUID string. Duplicated from AudioObject.kt's
 * private helper so [AudioObjectPlayer] can mint a fresh id for the
 * loop-tail successor (the new id prevents the old player's onended
 * cleanup from clobbering the new player in `engine.activePlayers`).
 */
private fun generateUuid(): String {
    val bytes = kotlin.random.Random.nextBytes(16)
    return bytes.joinToString("") { byte ->
        val hex = (byte.toInt() and 0xFF).toString(16)
        if (hex.length == 1) "0$hex" else hex
    }
}

/**
 * Per-object playback controller.
 * Manages AudioBufferSourceNode + GainNode + StereoPannerNode + AnalyserNode.
 * Implements AudioObjectView for runtime state querying.
 */
class AudioObjectPlayer(
    private val audioObj: AudioObject,
    private val buffer: AudioBuffer,
    private val ctx: AudioContext,
    private val engine: AudioEngine,
    /**
     * Stable id of the "music family" this player belongs to. Defaults
     * to the player's own [audioObj] id (i.e. it is the root of the
     * family). When a loop-tail successor is spawned by
     * [scheduleLoopContinuation], the successor inherits the parent's
     * [familyId] so the engine's [AudioEngine.stop] can find the
     * current active player of the family even after the original id
     * has been replaced by a fresh successor id.
     *
     * The engine tracks `familyToPlayer: MutableMap<String, AudioObjectPlayer>`
     * keyed by this value. Pass-through via [AudioEngine.play] /
     * [AudioEngine.playFromCache] so the engine can wire the mapping
     * up at construction time without the player reaching into the
     * engine from its constructor.
     */
    val familyId: String = audioObj.id
) : AudioObjectView
{
    private val log = Logger

    // Expose audioObj for AudioClientHandlers
    val audioObject: AudioObject get() = audioObj

    private var sourceNode: AudioBufferSourceNode = ctx.createBufferSource().also {
        it.buffer = buffer
        it.playbackRate.value = audioObj.speed
        // Native Web Audio loop is only used when [loop] is true AND
        // [loopWithTail] is false. When [loopWithTail] is true, looping is
        // achieved by scheduling a successor source (see [scheduleLoopContinuation])
        // so the existing source can play out its baked-in tail naturally —
        // a native jump-back loop would cut the tail off mid-decay.
        it.loop = audioObj.loop && !audioObj.loopWithTail
        if (audioObj.loop && !audioObj.loopWithTail)
        {
            // Region-loop: explicit [loopStart, loopEnd] (seconds) takes
            // precedence. The legacy time-window fallback (startTimeMs /
            // endTimeMs) is preserved for callers that set loop=true with
            // a wall-clock end but no explicit region.
            when {
                audioObj.loopStart != null || audioObj.loopEnd != null -> {
                    it.loopStart = (audioObj.loopStart ?: 0.0).coerceAtLeast(0.0)
                    it.loopEnd = (audioObj.loopEnd ?: buffer.duration).coerceAtMost(buffer.duration)
                }
                audioObj.endTimeMs != null -> {
                    // loopStart/loopEnd only work meaningfully for loop=true
                    it.loopStart = (audioObj.startTimeMs / 1000.0).coerceAtLeast(0.0)
                    it.loopEnd = (audioObj.endTimeMs!! / 1000.0).coerceAtLeast(0.0)
                }
            }
        }
    }

    private val gainNode: GainNode = ctx.createGain().also {
        val effectiveVolume = audioObj.volume * (engine.getChannelMaster(audioObj.channelId)?.effectiveVolume ?: 1.0f) * engine.globalVolume
        it.gain.value = effectiveVolume
    }

    private val pannerNode: StereoPannerNode = ctx.createStereoPanner().also {
        it.pan.value = audioObj.panning
    }

    private val analyserNode: AnalyserNode = ctx.createAnalyser().also {
        asDynamic().fftSize = 128
        asDynamic().smoothingTimeConstant = 0.8
    }
    // F6a (propagated from jukebox): reusable scratch buffer for the analyser.
    // Allocating once at construction is much cheaper than the per-frame
    // FloatArray(analyserNode.fftSize) that would otherwise run ~60 times a
    // second from tick().
    private val analyserBuffer: FloatArray = FloatArray(analyserNode.fftSize)

    // AudioObjectView implementation
    override val id: String = audioObj.id
    override var currentTimeMs: Long = 0L
    override var currentFrame: Int = 0
    override var sampleData: FloatArray? = null
    override var isPlaying: Boolean = false
    override var isPaused: Boolean = false
    override var isEnded: Boolean = false
    override var volume: Float = audioObj.volume
    override var panning: Float = audioObj.panning
    override var speed: Float = audioObj.speed

    // For pause/resume position storage
    // Single field: audio context time when pause() was called
    private var pauseContextTime: Double = 0.0
    // Accumulated elapsed playback time (in seconds, adjusted for speed) before pause
    private var accumulatedTimeBeforePause: Double = 0.0
    // F5 (propagated from jukebox): mutable loop state. audioObj.loop is
    // val-immutable, so we track it here. The onended closures capture this
    // local rather than the immutable field so they reflect the live loop
    // setting.
    private var loopState: Boolean = audioObj.loop
    // B2 (propagated from jukebox): AudioContext.currentTime at the moment
    // the *current* source node started (or was last re-created via resume).
    // The playback position in seconds is
    // (ctx.currentTime - playbackStartCtxTime) * speed. Previously this
    // class set currentTimeMs to ctx.currentTime * 1000 — the AudioContext
    // wall clock, not the playback position — which made pause/resume
    // ineffective.
    //
    // NOTE: this field is offset-shifted — in play() it is set to
    // `whenTime - offsetSeconds` so that the tick() position formula yields
    // the buffer position directly. For computing "when does the source
    // reach the loop point in ctx time", use [sourceStartCtxTime] instead
    // (which is not offset-shifted and always equals the source's actual
    // start ctx time).
    private var playbackStartCtxTime: Double = 0.0
    // AudioContext.currentTime at which the current source node *actually*
    // started playing (not offset-shifted). Set alongside
    // [playbackStartCtxTime] in play() / resume(). Used by
    // [scheduleLoopContinuation] to compute when the source reaches the
    // loop point — see the comment there for why the two fields must
    // remain distinct.
    private var sourceStartCtxTime: Double = 0.0
    // loopWithTail scheduling state. [nextSourceTimeoutId] is the timer id
    // returned by window.setTimeout for the successor-source spawn; we
    // capture it so pause/stop/cleanup can cancel an orphaned timer rather
    // than letting it fire and create a fresh source after the user has
    // already torn the parent down. [hasScheduledNext] guards against
    // double-scheduling when the same source is recycled (resume resets
    // the guard, the helper sets it back to true on schedule).
    private var nextSourceTimeoutId: Int? = null
    private var hasScheduledNext: Boolean = false

    init
    {
        // Spec signal chain (per AudioEngine architecture):
        //   ctx.destination → globalGain → channelMaster.gain → player.gain → panner → output
        // AnalyserNode is NOT in the signal path — it is a passive side-effect node
        // for tick() reading only. It is created and kept connected to the source node's
        // destination via a passive routing, but does not appear in the audible signal chain.
        val channelGain = engine.getChannelMaster(audioObj.channelId)?.gainNode
        if (channelGain != null)
        {
            sourceNode.connect(gainNode.unsafeCast<AudioNode>())
            gainNode.connect(pannerNode.unsafeCast<AudioNode>())
            pannerNode.connect(channelGain.unsafeCast<AudioNode>())
        }
        log.info(
            LogCategory.SYSTEM,
            "AudioObjectPlayer: constructed id='${audioObj.id}' resource='${audioObj.resourceName}' " +
            "channel='${audioObj.channelId}' volume=${audioObj.volume} panning=${audioObj.panning} " +
            "speed=${audioObj.speed} loop=${audioObj.loop} loopWithTail=${audioObj.loopWithTail} " +
            "fadeInMs=${audioObj.fadeInDurationMs} fadeOutMs=${audioObj.fadeOutDurationMs} " +
            "bufferDuration=${buffer.duration}s " +
            "channelWired=${channelGain != null} familyId='$familyId'"
        )
        // [Bug fix — music transitions] Register in the engine's
        // family map so stop(familyId) resolves to the current active
        // player of this chain. The engine's
        // [AudioEngine.playFromCache] also writes this map, so the
        // order is: playFromCache calls AudioObjectPlayer(this...) ->
        // ctor writes the family map. The ctor side-write is the
        // authoritative path so callers that construct the player
        // directly (e.g. tests) still get the wiring.
        engine.registerFamilyPlayer(familyId, this)
    }

    /**
     * Schedule a successor source to spawn at the configured loop point,
     * implementing the "loop with tail" pattern. The existing source
     * continues to its natural end of buffer (so any baked-in reverb /
     * decay plays out); the new source takes over starting at the loop
     * point and playing from the same offset as the parent.
     *
     * No-op when [AudioObject.loop] is false, [AudioObject.loopWithTail]
     * is false, or a successor has already been scheduled for the current
     * source. The [hasScheduledNext] guard is reset by [resume] (which
     * creates a fresh source) and cleared by [cancelLoopContinuation].
     *
     * @param currentOffsetSeconds The position in the buffer (in seconds)
     *   at which the current source started — `audioObj.startTimeMs / 1000`
     *   for a fresh play or `accumulatedTimeBeforePause` for a resume.
     *   This is the anchor from which the loop point in audio-context
     *   time is computed.
     */
    private fun scheduleLoopContinuation(currentOffsetSeconds: Double)
    {
        if (!loopState || !audioObj.loopWithTail || hasScheduledNext) return

        val loopPointSeconds = audioObj.loopEnd ?: buffer.duration
        // Audio-context time at which the current source reaches the loop
        // point. Derived from [sourceStartCtxTime] (the source's actual
        // start ctx time, not the offset-shifted playbackStartCtxTime)
        // and the configured start offset. Using sourceStartCtxTime here
        // — rather than playbackStartCtxTime, which is `whenTime -
        // offsetSeconds` in play() — keeps the formula correct when
        // startTimeMs > 0; otherwise the successor would fire
        // `offsetSeconds` too early.
        val loopPointCtxTime = sourceStartCtxTime + (loopPointSeconds - currentOffsetSeconds)
        val delayMs = ((loopPointCtxTime - ctx.currentTime) * 1000).toInt().coerceAtLeast(0)

        // Fresh id for the successor so the old player's onended handler
        // (which references the old id) cannot clobber the new player in
        // `engine.activePlayers`. The new player lives independently in
        // the engine's map under its own id and will spawn its own
        // successor in turn.
        //
        // The successor inherits this player's [familyId] so the
        // engine's `familyToPlayer` map stays anchored to the original
        // id the MusicRunner / AudioEngine.stop was given. Without this,
        // every loop iteration would orphan the previous mapping and
        // fade-out / stop calls targeting the original id would miss
        // the actual current player (the WARN we see in
        // browser-2026-06-16-130503.log:17:21:26 of
        // `AudioEngine.stop: no active player for objectId=...`).
        val successorObj = audioObj.copy(id = generateUuid())
        nextSourceTimeoutId = window.setTimeout({
            engine.play(successorObj, 0.0, familyId = familyId)
            log.info(
                LogCategory.SYSTEM,
                "AudioObjectPlayer.scheduleLoopContinuation: fired — successor spawned " +
                "id='${successorObj.id}' familyId='$familyId' (parent id='${audioObj.id}', resource='${audioObj.resourceName}')"
            )
        }, delayMs)
        hasScheduledNext = true
        log.info(
            LogCategory.SYSTEM,
            "AudioObjectPlayer.scheduleLoopContinuation: scheduled successor id='${successorObj.id}' " +
            "for id='${audioObj.id}' at loopPointSeconds=$loopPointSeconds " +
            "loopPointCtxTime=$loopPointCtxTime delayMs=$delayMs"
        )
    }

    /**
     * Cancel any pending loop-tail successor timer and reset the
     * scheduling guard. Called from [pause], [stop], [cleanup]. Without
     * this, a user-initiated pause could be undone a few hundred ms later
     * by a stray timer spawning a fresh player.
     */
    private fun cancelLoopContinuation()
    {
        val hadTimer = nextSourceTimeoutId != null
        nextSourceTimeoutId?.let { window.clearTimeout(it) }
        nextSourceTimeoutId = null
        if (hasScheduledNext || hadTimer) {
            log.debug(
                LogCategory.SYSTEM,
                "AudioObjectPlayer.cancelLoopContinuation: cancelled pending successor timer for id='${audioObj.id}'"
            )
        }
        hasScheduledNext = false
    }

    fun play(startWhenSeconds: Double = 0.0)
    {
        if (isEnded) {
            log.warn(
                LogCategory.SYSTEM,
                "AudioObjectPlayer.play: IGNORED — player already ended id='${audioObj.id}' resource='${audioObj.resourceName}'"
            )
            return
        }

        val offsetSeconds = (audioObj.startTimeMs / 1000.0).coerceAtLeast(0.0)
        val whenTime = if (startWhenSeconds > 0) ctx.currentTime + startWhenSeconds else ctx.currentTime
        // B2 (propagated from jukebox): track the audio-clock time at which
        // the current source actually started. tick() computes position as
        // (ctx.currentTime - playbackStartCtxTime) * speed. The whenTime
        // may be in the future (startWhenSeconds > 0) so we subtract the
        // offset to keep the formula correct.
        playbackStartCtxTime = whenTime - offsetSeconds
        // The ctx time the source actually starts — not offset-shifted.
        // scheduleLoopContinuation uses this to compute the loop point
        // correctly when startTimeMs > 0.
        sourceStartCtxTime = whenTime
        // Reset pause bookkeeping — fresh start.
        accumulatedTimeBeforePause = 0.0
        pauseContextTime = 0.0

        // Schedule fade-in if specified
        if (audioObj.fadeInDurationMs > 0)
        {
            val fadeTarget = audioObj.volume * (engine.getChannelMaster(audioObj.channelId)?.effectiveVolume ?: 1.0f) * engine.globalVolume
            gainNode.gain.setValueAtTime(0.0f, whenTime)
            gainNode.gain.setTargetAtTime(fadeTarget, whenTime, audioObj.fadeInDurationMs / 1000.0 / 3.0)
        }

        // Schedule stop time for non-looping audio. Per the Web Audio API spec,
        // AudioScheduledSourceNode.stop() may ONLY be called after start() —
        // calling stop() before start() throws InvalidStateError. So we call
        // start() first, then schedule stop() with a future time.
        sourceNode.start(whenTime, offsetSeconds)

        if (!loopState && audioObj.endTimeMs != null)
        {
            val durationSeconds = (audioObj.endTimeMs!! - audioObj.startTimeMs) / 1000.0
            val stopTime = (whenTime + offsetSeconds + durationSeconds).coerceAtLeast(ctx.currentTime + 0.001)
            sourceNode.stop(stopTime)
        }
        else if (!loopState)
        {
            val stopTime = (whenTime + offsetSeconds + buffer.duration).coerceAtLeast(ctx.currentTime + 0.001)
            sourceNode.stop(stopTime)
        }
        isPlaying = true
        isPaused = false

        // Handle ended event — capture the mutable loopState in a local so
        // the closure reflects the current loop setting rather than the
        // (immutable) audioObj.loop field. (F5 propagated from jukebox.)
        // For loopWithTail sources we also call cleanup() in the onended
        // handler (matching the jukebox fix) so the parent releases its
        // nodes when its successor takes over — otherwise this player
        // would accumulate in engine.activePlayers indefinitely.
        val effectiveLoop = loopState
        val cleanupOnEnd = audioObj.loopWithTail
        sourceNode.asDynamic().onended = {
            if (!effectiveLoop || cleanupOnEnd)
            {
                isEnded = true
                isPlaying = false
                log.debug(
                    LogCategory.SYSTEM,
                    "AudioObjectPlayer.onended: source ended id='${audioObj.id}' resource='${audioObj.resourceName}' " +
                    "(effectiveLoop=$effectiveLoop, cleanupOnEnd=$cleanupOnEnd)"
                )
                if (cleanupOnEnd) {
                    cleanup()
                }
                // Fire the engine's onTrackEnd hook for real track ends
                // only — skip for looping tracks (they never truly end)
                // and for loopWithTail tracks (this onended is the parent
                // yielding to its successor, not a true end). Wrapped in
                // try/catch because the Web Audio thread silently
                // swallows JS errors and a throwing subscriber would
                // otherwise leave the engine in a bad state.
                if (!audioObj.loop && !audioObj.loopWithTail)
                {
                    try
                    {
                        engine.onTrackEnd?.invoke(audioObj.id)
                    }
                    catch (e: Throwable)
                    {
                        log.warn(
                            LogCategory.SYSTEM,
                            "AudioObjectPlayer.onended: engine.onTrackEnd subscriber threw for id='${audioObj.id}' (non-fatal): ${e.message}"
                        )
                    }
                }
            }
        }

        log.info(
            LogCategory.SYSTEM,
            "AudioObjectPlayer.play: id='${audioObj.id}' resource='${audioObj.resourceName}' " +
            "startWhenSeconds=$startWhenSeconds offsetSeconds=$offsetSeconds " +
            "whenTime=$whenTime (now=${ctx.currentTime}) " +
            "loop=$loopState loopWithTail=${audioObj.loopWithTail}"
        )

        // loopWithTail: schedule a successor source at the loop point so
        // the current source can play out its tail. No-op when the flag
        // is off (or when loop is off — the helper guards that too).
        scheduleLoopContinuation(offsetSeconds)
    }

    fun pause()
    {
        if (!isPlaying) {
            log.debug(
                LogCategory.SYSTEM,
                "AudioObjectPlayer.pause: no-op — not playing id='${audioObj.id}' " +
                "(isPlaying=$isPlaying, isPaused=$isPaused, isEnded=$isEnded)"
            )
            return
        }
        // Cancel any pending loop-tail successor timer — if the user pauses,
        // we don't want a stray timer to spawn a fresh source a few hundred
        // ms later. The successor, if needed, will be re-scheduled by resume().
        cancelLoopContinuation()
        // Capture audio context time at pause
        pauseContextTime = ctx.currentTime
        // B2 (propagated from jukebox): with the playbackStartCtxTime
        // baseline, the elapsed playback time at pause is simply
        // (ctx.currentTime - playbackStartCtxTime) * speed. The previous
        // math (accumulating from the last tick's currentTimeMs) was buggy
        // because currentTimeMs was set to the wall clock, not the
        // playback position.
        accumulatedTimeBeforePause = (ctx.currentTime - playbackStartCtxTime) * speed
        safeStopSource(sourceNode)
        isPlaying = false
        isPaused = true
        log.info(
            LogCategory.SYSTEM,
            "AudioObjectPlayer.pause: id='${audioObj.id}' resource='${audioObj.resourceName}' " +
            "at positionMs=${(accumulatedTimeBeforePause * 1000).toLong()} " +
            "(ctxTime at pause=$pauseContextTime)"
        )
    }

    fun resume()
    {
        if (!isPaused) {
            log.debug(
                LogCategory.SYSTEM,
                "AudioObjectPlayer.resume: no-op — not paused id='${audioObj.id}' " +
                "(isPlaying=$isPlaying, isPaused=$isPaused, isEnded=$isEnded)"
            )
            return
        }

        // Cancel any pending loop-tail timer from the pre-pause source —
        // the new source below needs its own successor schedule. The helper
        // also clears [hasScheduledNext] so the new schedule is allowed.
        cancelLoopContinuation()

        // Position to resume from, in playback-time-seconds. This was captured
        // by the matching pause() call. We do NOT add the time spent paused —
        // instead we reset playbackStartCtxTime below so the new source's
        // position counter starts at 0 and grows from there.
        val resumeOffsetSeconds = accumulatedTimeBeforePause

        // Create a fresh source node (old one was stopped in pause())
        val newSourceNode = ctx.createBufferSource().also {
            it.buffer = buffer
            it.loop = loopState
            if (loopState)
            {
                // Region-loop: explicit [loopStart, loopEnd] (seconds) takes
                // precedence over the legacy startTimeMs/endTimeMs window.
                when {
                    audioObj.loopStart != null || audioObj.loopEnd != null -> {
                        it.loopStart = (audioObj.loopStart ?: 0.0).coerceAtLeast(0.0)
                        it.loopEnd = (audioObj.loopEnd ?: buffer.duration).coerceAtMost(buffer.duration)
                    }
                    audioObj.endTimeMs != null -> {
                        it.loopStart = (audioObj.startTimeMs / 1000.0).coerceAtLeast(0.0)
                        it.loopEnd = (audioObj.endTimeMs!! / 1000.0).coerceAtLeast(0.0)
                    }
                }
            }
        }

        // Apply playbackRate automation — consistent with setSpeed()
        val fadeMs = audioObj.fadeInDurationMs
        val now = ctx.currentTime
        if (fadeMs > 0)
        {
            newSourceNode.playbackRate.setTargetAtTime(speed, now, fadeMs / 1000.0 / 3.0)
        }
        else
        {
            newSourceNode.playbackRate.setValueAtTime(speed, now)
        }

        // B2 (propagated from jukebox): reset the playback-start baseline
        // so the new source's position counter starts at 0. The source
        // begins at the captured resume offset via the `offset` argument
        // to newSourceNode.start() below.
        playbackStartCtxTime = now
        pauseContextTime = 0.0

        // sourceStartCtxTime tracks the resumed source's actual start ctx
        // time. For resume() the source begins at ctx.currentTime, so it
        // equals the offset-shifted playbackStartCtxTime (currentOffset is
        // passed via the source's start() offset arg, not via subtracting
        // from the start ctx time).
        sourceStartCtxTime = now

        sourceNode = newSourceNode

        // Reconnect the audio chain: source → gain → panner → channel
        val channelGain = engine.getChannelMaster(audioObj.channelId)?.gainNode
        if (channelGain != null)
        {
            newSourceNode.connect(gainNode.unsafeCast<AudioNode>())
            gainNode.connect(pannerNode.unsafeCast<AudioNode>())
            pannerNode.connect(channelGain.unsafeCast<AudioNode>())
        }

        // Apply fade-in if specified
        if (audioObj.fadeInDurationMs > 0)
        {
            val fadeTarget = audioObj.volume * (engine.getChannelMaster(audioObj.channelId)?.effectiveVolume ?: 1.0f) * engine.globalVolume
            gainNode.gain.setValueAtTime(0.0f, ctx.currentTime)
            gainNode.gain.setTargetAtTime(fadeTarget, ctx.currentTime, audioObj.fadeInDurationMs / 1000.0 / 3.0)
        }

        // Start playback at the paused position FIRST, then schedule stop().
        // Per Web Audio API spec, stop() may only be called after start().
        newSourceNode.start(ctx.currentTime, resumeOffsetSeconds)

        // Schedule stop time for non-looping audio
        if (!loopState && audioObj.endTimeMs != null)
        {
            val durationSeconds = (audioObj.endTimeMs!! - audioObj.startTimeMs) / 1000.0
            val stopTime = (ctx.currentTime + resumeOffsetSeconds + durationSeconds).coerceAtLeast(ctx.currentTime + 0.001)
            newSourceNode.stop(stopTime)
        }
        else if (!loopState)
        {
            val stopTime = (ctx.currentTime + resumeOffsetSeconds + buffer.duration).coerceAtLeast(ctx.currentTime + 0.001)
            newSourceNode.stop(stopTime)
        }

        // Re-attach ended handler — capture loop state in a local so the
        // closure doesn't depend on the (immutable) audioObj.loop field. (F5)
        // For loopWithTail sources we also call cleanup() in the onended
        // handler (matching the play() note) so the resumed source releases
        // its nodes when its successor takes over.
        val effectiveLoop = loopState
        val cleanupOnEnd = audioObj.loopWithTail
        newSourceNode.asDynamic().onended = {
            if (!effectiveLoop || cleanupOnEnd)
            {
                isEnded = true
                isPlaying = false
                log.debug(
                    LogCategory.SYSTEM,
                    "AudioObjectPlayer.onended (resumed): source ended id='${audioObj.id}' resource='${audioObj.resourceName}' " +
                    "(effectiveLoop=$effectiveLoop, cleanupOnEnd=$cleanupOnEnd)"
                )
                if (cleanupOnEnd) {
                    cleanup()
                }
                // Same fire-on-real-end logic as the play() handler —
                // see the comment there for the loop / loopWithTail
                // rationale.
                if (!audioObj.loop && !audioObj.loopWithTail)
                {
                    try
                    {
                        engine.onTrackEnd?.invoke(audioObj.id)
                    }
                    catch (e: Throwable)
                    {
                        log.warn(
                            LogCategory.SYSTEM,
                            "AudioObjectPlayer.onended (resumed): engine.onTrackEnd subscriber threw for id='${audioObj.id}' (non-fatal): ${e.message}"
                        )
                    }
                }
            }
        }

        log.info(
            LogCategory.SYSTEM,
            "AudioObjectPlayer.resume: id='${audioObj.id}' resource='${audioObj.resourceName}' " +
            "from offsetSeconds=$resumeOffsetSeconds (positionMs=${(resumeOffsetSeconds * 1000).toLong()})"
        )

        // loopWithTail: schedule a successor for the resumed source. The
        // timer is anchored to the resumed position so the new source
        // fires when this one reaches its loop point.
        scheduleLoopContinuation(resumeOffsetSeconds)

        isPaused = false
        isPlaying = true
    }

    fun stop(fadeOutDurationMs: Long)
    {
        log.info(
            LogCategory.SYSTEM,
            "AudioObjectPlayer.stop: ENTER id='${audioObj.id}' resource='${audioObj.resourceName}' " +
            "fadeOutMs=$fadeOutDurationMs (isPlaying=$isPlaying, isPaused=$isPaused, isEnded=$isEnded)"
        )
        // Cancel any pending loop-tail successor timer — once the user
        // requests a stop (faded or immediate), no fresh source should
        // appear. cleanup() also calls this defensively.
        cancelLoopContinuation()
        if (fadeOutDurationMs > 0)
        {
            val now = ctx.currentTime
            gainNode.gain.setTargetAtTime(0.0f, now, fadeOutDurationMs / 1000.0 / 3.0)
            // Schedule actual stop after fade
            val delayMs = fadeOutDurationMs.toDouble()
            window.asDynamic().setTimeout({ cleanup() }, delayMs)
            log.debug(
                LogCategory.SYSTEM,
                "AudioObjectPlayer.stop: fade-out scheduled, cleanup timer id pending for id='${audioObj.id}' " +
                "delayMs=$delayMs"
            )
        }
        else
        {
            cleanup()
        }
    }

    private fun cleanup()
    {
        // Defensive cancellation — if cleanup() is reached via a code
        // path that bypassed stop() (e.g. external clear-out of
        // activePlayers), make sure no stray successor timer fires.
        cancelLoopContinuation()
        try { sourceNode.stop() } catch (e: Throwable) { /* ignore */ }
        sourceNode.disconnect()
        gainNode.disconnect()
        pannerNode.disconnect()
        analyserNode.disconnect()
        isEnded = true
        isPlaying = false
        engine.activePlayers.remove(audioObj.id)
        // [Bug fix — music transitions] Unregister from the family map
        // so the next stop(familyId) doesn't try to act on a
        // disconnected / cleaned-up player. The compare-and-set
        // protects against a successor's `cleanup` from removing a
        // newer player that already replaced us in the family map.
        engine.unregisterFamilyPlayer(familyId, this)
        log.info(
            LogCategory.SYSTEM,
            "AudioObjectPlayer.cleanup: id='${audioObj.id}' resource='${audioObj.resourceName}' " +
            "familyId='$familyId' removed from engine.activePlayers (now=${engine.activePlayers.size}) " +
            "and engine.familyToPlayer (now=${engine.familyToPlayer.size})"
        )
    }

    override fun tick()
    {
        if (isPlaying)
        {
            // B2 (propagated from jukebox): Playback position in ms = elapsed
            // audio-context time since the current source node started, scaled
            // by playback speed. This is the real buffer position, not the
            // audio context's wall clock. The old code at this site reported
            // ctx.currentTime * 1000 which was the AudioContext wall clock,
            // not the playback position within the buffer.
            val positionSeconds = (ctx.currentTime - playbackStartCtxTime) * speed
            currentTimeMs = (positionSeconds * 1000).toLong()
            currentFrame = (currentTimeMs * ctx.sampleRate / 1000).toInt()

            // F6a: reuse the per-player scratch buffer for the analyser.
            // The analyser writes into the passed-in array — we don't need a
            // fresh FloatArray(fftSize) every frame. The [sampleData] field
            // is no longer assigned (F6c) because no consumer reads it.
            analyserNode.getFloatTimeDomainData(analyserBuffer)
        }
    }

    // ─── AudioParam automation for smooth changes ───────────────────────

    fun setVolume(targetVolume: Float, fadeMs: Long)
    {
        val previous = volume
        volume = targetVolume
        val effectiveVolume = targetVolume * (engine.getChannelMaster(audioObj.channelId)?.effectiveVolume ?: 1.0f) * engine.globalVolume
        val now = ctx.currentTime
        if (fadeMs > 0)
        {
            gainNode.gain.setTargetAtTime(effectiveVolume, now, fadeMs / 1000.0 / 3.0)
        }
        else
        {
            gainNode.gain.setValueAtTime(effectiveVolume, now)
        }
        log.info(
            LogCategory.SYSTEM,
            "AudioObjectPlayer.setVolume: id='${audioObj.id}' " +
            "$previous → $targetVolume (effective=$effectiveVolume, fadeMs=$fadeMs)"
        )
    }

    fun setPanning(targetPanning: Float, fadeMs: Long)
    {
        val previous = panning
        panning = targetPanning
        val now = ctx.currentTime
        if (fadeMs > 0)
        {
            pannerNode.pan.setTargetAtTime(targetPanning, now, fadeMs / 1000.0 / 3.0)
        }
        else
        {
            pannerNode.pan.setValueAtTime(targetPanning, now)
        }
        log.info(
            LogCategory.SYSTEM,
            "AudioObjectPlayer.setPanning: id='${audioObj.id}' " +
            "$previous → $targetPanning (fadeMs=$fadeMs)"
        )
    }

    fun setSpeed(targetSpeed: Float, fadeMs: Long)
    {
        val previous = speed
        speed = targetSpeed
        val now = ctx.currentTime
        if (fadeMs > 0)
        {
            sourceNode.playbackRate.setTargetAtTime(targetSpeed, now, fadeMs / 1000.0 / 3.0)
        }
        else
        {
            sourceNode.playbackRate.setValueAtTime(targetSpeed, now)
        }
        log.info(
            LogCategory.SYSTEM,
            "AudioObjectPlayer.setSpeed: id='${audioObj.id}' " +
            "$previous → $targetSpeed (fadeMs=$fadeMs)"
        )
    }
}