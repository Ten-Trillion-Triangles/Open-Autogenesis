package org.ttt.autogenesis.jukebox.audio

import kotlinx.browser.window
import org.ttt.autogenesis.audio.AudioObject
import org.ttt.autogenesis.audio.AudioObjectView

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
    private val engine: AudioEngine
) : AudioObjectView
{
    // Expose audioObj for the action log (the only consumer in the standalone
    // jukebox is the active-players panel renderer; the original AudioClientHandlers
    // log is gone with F2).
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
    // Reusable scratch buffer for the analyser. Allocating once at construction
    // is much cheaper than the per-frame FloatArray(analyserNode.fftSize) that
    // would otherwise run ~60 times a second from tick(). (F6a)
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
    /**
     * Set by [stop] when a fade-out is in progress; cleared by [cleanup].
     * Exposed to the JS facade so the renderer can show the correct fade
     * direction ("FADING OUT") on the active-players row.
     */
    var isFadingOut: Boolean = false
        @JsName("isFadingOut") get
        @JsName("setFadingOut") set

    // For pause/resume position storage
    // Single field: audio context time when pause() was called
    private var pauseContextTime: Double = 0.0
    // Accumulated elapsed playback time (in seconds, adjusted for speed) before pause
    private var accumulatedTimeBeforePause: Double = 0.0
    // Mutable loop state (audioObj.loop is val-immutable, so we track it here)
    private var loopState: Boolean = audioObj.loop
    // AudioContext.currentTime at the moment the *current* source node started
    // (or was last re-created via resume / setLoop). The playback position
    // in seconds is (ctx.currentTime - playbackStartCtxTime) * speed.
    // This is the load-bearing fix for B2: previously currentTimeMs was set
    // to the audio context's wall clock, which gave no information about
    // playback position within the buffer.
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
    // [playbackStartCtxTime] in play() / resume() / setLoop(). Used by
    // [scheduleLoopContinuation] to compute when the source reaches the
    // loop point — see the comment there for why the two fields must
    // remain distinct.
    private var sourceStartCtxTime: Double = 0.0
    // loopWithTail scheduling state. [nextSourceTimeoutId] is the timer id
    // returned by window.setTimeout for the successor-source spawn; we
    // capture it so pause/stop/cleanup can cancel an orphaned timer rather
    // than letting it fire and create a fresh source after the user has
    // already torn the parent down. [hasScheduledNext] guards against
    // double-scheduling when the same source is recycled (resume / setLoop
    // reset the guard, the helper sets it back to true on schedule).
    private var nextSourceTimeoutId: Int? = null
    private var hasScheduledNext: Boolean = false

    /**
     * Read the runtime loop state, which may differ from [audioObject].loop
     * if the user has toggled loop via [setLoop]. Exposed with @JsName so
     * the JS bridge can see the live value.
     */
    @JsName("getLoop")
    fun getLoop(): Boolean = loopState

    init
    {
        // Signal chain (per AudioEngine architecture):
        //   sourceNode → analyserNode → gainNode → pannerNode → channelMaster.gainNode
        //                                              → globalGainNode → ctx.destination
        //
        // The AnalyserNode IS in the signal path here (not just a passive tap)
        // because it must propagate the same audio to downstream nodes. Web Audio
        // AnalyserNodes are zero-latency and do not alter the signal — passing
        // audio through them is the standard pattern for visualization. tick()
        // reads time-domain data from the analyserNode via getFloatTimeDomainData.
        val channelGain = engine.getChannelMaster(audioObj.channelId)?.gainNode
        if (channelGain != null)
        {
            sourceNode.connect(analyserNode.unsafeCast<AudioNode>())
            analyserNode.connect(gainNode.unsafeCast<AudioNode>())
            gainNode.connect(pannerNode.unsafeCast<AudioNode>())
            pannerNode.connect(channelGain.unsafeCast<AudioNode>())
        }
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
     * source. The [hasScheduledNext] guard is reset by [resume] and
     * [setLoop] (which create a fresh source) and cleared by
     * [cancelLoopContinuation].
     *
     * @param currentOffsetSeconds The position in the buffer (in seconds)
     *   at which the current source started — `audioObj.startTimeMs / 1000`
     *   for a fresh play, `accumulatedTimeBeforePause` for a resume, or
     *   the preserved position for a setLoop. This is the anchor from
     *   which the loop point in audio-context time is computed.
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
        val successorObj = audioObj.copy(id = generateUuid())
        nextSourceTimeoutId = window.setTimeout({
            engine.play(successorObj, 0.0)
        }, delayMs)
        hasScheduledNext = true
    }

    /**
     * Cancel any pending loop-tail successor timer and reset the
     * scheduling guard. Called from [pause], [stop], [cleanup], and at
     * the start of [setLoop] (which replaces the current source). Without
     * this, a user-initiated pause could be undone a few hundred ms later
     * by a stray timer spawning a fresh player.
     */
    private fun cancelLoopContinuation()
    {
        nextSourceTimeoutId?.let { window.clearTimeout(it) }
        nextSourceTimeoutId = null
        hasScheduledNext = false
    }

    fun play(startWhenSeconds: Double = 0.0)
    {
        if (isEnded) return

        val offsetSeconds = (audioObj.startTimeMs / 1000.0).coerceAtLeast(0.0)
        val whenTime = if (startWhenSeconds > 0) ctx.currentTime + startWhenSeconds else ctx.currentTime
        // The audio clock at which the current source actually started playing
        // is `whenTime` (not `ctx.currentTime`, because we may have scheduled
        // the start in the future). Playback position in seconds is then
        // (ctx.currentTime - playbackStartCtxTime) * speed, where the start
        // point accounts for the offset.
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
        // (immutable) audioObj.loop field. Matches the pattern in resume()
        // and setLoop() (F5).
        val effectiveLoop = loopState
        // loopWithTail sources rely on a successor source to take over at
        // the loop point, so this source's natural end still means "we are
        // done with this player" — we must call cleanup() to release the
        // audio nodes and remove ourselves from engine.activePlayers.
        // Without this, the player accumulates in activePlayers forever
        // because effectiveLoop is true and the cleanup body is otherwise
        // skipped. cleanup() is idempotent (safeStopSource swallows
        // errors, disconnect() is safe on a disconnected node, Map.remove
        // is a no-op when the key is missing), so calling it here AND
        // from stop() is safe.
        val cleanupOnEnd = audioObj.loopWithTail
        sourceNode.asDynamic().onended = {
            if (!effectiveLoop || cleanupOnEnd)
            {
                isEnded = true
                isPlaying = false
                if (cleanupOnEnd) {
                    cleanup()
                }
            }
        }

        // loopWithTail: schedule a successor source at the loop point so
        // the current source can play out its tail. No-op when the flag
        // is off (or when loop is off — the helper guards that too).
        scheduleLoopContinuation(offsetSeconds)
    }

    fun pause()
    {
        if (!isPlaying) return
        // Cancel any pending loop-tail successor timer — if the user pauses,
        // we don't want a stray timer to spawn a fresh source a few hundred
        // ms later. The successor, if needed, will be re-scheduled by resume().
        cancelLoopContinuation()
        // Capture audio context time at pause
        pauseContextTime = ctx.currentTime
        // Capture accumulated elapsed playback time (in seconds, adjusted for
        // speed) at the moment of pause. With the new playbackStartCtxTime
        // tracking, this is just (ctx.currentTime - playbackStartCtxTime) * speed.
        // Note: we OVERWRITE (not add) because playbackStartCtxTime already
        // accounts for any prior pause/resume cycles — the offset field
        // accumulates across cycles, but the start time tracks the most recent
        // resume. So this line is the correct single computation.
        accumulatedTimeBeforePause = (ctx.currentTime - playbackStartCtxTime) * speed
        safeStopSource(sourceNode)
        isPlaying = false
        isPaused = true
    }

    fun resume()
    {
        if (!isPaused) return

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

        // Reset the playback-start baseline so that the new source's position
        // counter (ctx.currentTime - playbackStartCtxTime) * speed starts at
        // 0 and the source begins at the captured resume offset via the
        // source's `offset` argument below.
        playbackStartCtxTime = now
        pauseContextTime = 0.0

        // sourceStartCtxTime tracks the resumed source's actual start ctx
        // time. For resume() the source begins at ctx.currentTime, so it
        // equals the offset-shifted playbackStartCtxTime (currentOffset is
        // passed via the source's start() offset arg, not via subtracting
        // from the start ctx time).
        sourceStartCtxTime = now

        sourceNode = newSourceNode

        // Reconnect the audio chain: source → analyser → gain → panner → channel
        // The analyser is in the signal path here so it receives the same audio
        // that the rest of the chain sees. Web Audio AnalyserNodes are
        // zero-latency pass-through nodes, so this does not affect playback.
        val channelGain = engine.getChannelMaster(audioObj.channelId)?.gainNode
        if (channelGain != null)
        {
            newSourceNode.connect(analyserNode.unsafeCast<AudioNode>())
            analyserNode.connect(gainNode.unsafeCast<AudioNode>())
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
        // handler (see the matching note in play()) so the resumed source
        // releases its nodes when its successor takes over.
        val effectiveLoop = loopState
        val cleanupOnEnd = audioObj.loopWithTail
        newSourceNode.asDynamic().onended = {
            if (!effectiveLoop || cleanupOnEnd)
            {
                isEnded = true
                isPlaying = false
                if (cleanupOnEnd) {
                    cleanup()
                }
            }
        }

        // loopWithTail: schedule a successor for the resumed source. The
        // timer is anchored to the resumed position so the new source
        // fires when this one reaches its loop point.
        scheduleLoopContinuation(resumeOffsetSeconds)

        isPaused = false
        isPlaying = true
    }

    fun stop(fadeOutDurationMs: Long)
    {
        // Cancel any pending loop-tail successor timer — once the user
        // requests a stop (faded or immediate), no fresh source should
        // appear. cleanup() also calls this defensively.
        cancelLoopContinuation()
        if (fadeOutDurationMs > 0)
        {
            val now = ctx.currentTime
            isFadingOut = true
            gainNode.gain.setTargetAtTime(0.0f, now, fadeOutDurationMs / 1000.0 / 3.0)
            // Schedule actual stop after fade
            val delayMs = fadeOutDurationMs.toDouble()
            window.asDynamic().setTimeout({ cleanup() }, delayMs)
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
        safeStopSource(sourceNode)
        sourceNode.disconnect()
        gainNode.disconnect()
        pannerNode.disconnect()
        analyserNode.disconnect()
        isEnded = true
        isPlaying = false
        isFadingOut = false
        engine.activePlayers.remove(audioObj.id)
    }

    override fun tick()
    {
        if (isPlaying)
        {
            // Playback position in ms = elapsed audio-context time since the
            // current source node started, scaled by playback speed. This is
            // the real buffer position, not the audio context's wall clock.
            val positionSeconds = (ctx.currentTime - playbackStartCtxTime) * speed
            currentTimeMs = (positionSeconds * 1000).toLong()
            currentFrame = (currentTimeMs * ctx.sampleRate / 1000).toInt()

            // F6a: reuse the per-player scratch buffer for the analyser. The
            // analyser writes into the passed-in array — we don't need a
            // fresh FloatArray(fftSize) every frame. The values land in
            // analyserBuffer; the [sampleData] field is no longer assigned
            // (F6c) because no consumer reads it.
            analyserNode.getFloatTimeDomainData(analyserBuffer)
        }
    }

    /**
     * Read the current value of the per-source GainNode. Used by the JS
     * facade to expose [PlayerState.currentGain] so the UI can show a fade
     * indicator. The analyser sits before the gainNode in the signal chain,
     * so the analyser's freq / time-domain data does NOT reflect the gain —
     * only a direct read of `gainNode.gain.value` does.
     */
    @JsName("getCurrentGain")
    fun getCurrentGain(): Float = gainNode.gain.value

    // ─── Loop toggle (recreates source — Web Audio nodes are single-use) ──

    fun setLoop(loop: Boolean) {
        if (loopState == loop) return
        loopState = loop

        if (!isPlaying && !isPaused) return

        // Replace any pending loop-tail successor — the new source gets
        // its own schedule below. cancelLoopContinuation() also clears
        // hasScheduledNext so the new schedule is allowed.
        cancelLoopContinuation()

        // Compute current playback position in seconds (preserve current position)
        val positionSeconds = if (isPaused) {
            accumulatedTimeBeforePause
        } else {
            // (ctx.currentTime - playbackStartCtxTime) * speed is the live
            // playback position in seconds. We add accumulatedTimeBeforePause
            // in case the player was paused/resumed mid-track — but with
            // the new pause() that OVERWRITES accumulatedTimeBeforePause to
            // the snapshot at pause time (instead of accumulating), this is
            // already correct. The addition is a no-op in the common case.
            accumulatedTimeBeforePause + (ctx.currentTime - playbackStartCtxTime) * speed
        }.coerceIn(0.0, buffer.duration.toDouble())

        // Stop and disconnect the old source node
        safeStopSource(sourceNode)
        sourceNode.disconnect()

        // Create new source with updated loop state. Native Web Audio
        // loop is only used when both `loop` is true AND the audioObj
        // does not want the tail-preserving pattern.
        val newSource = ctx.createBufferSource().also {
            it.buffer = buffer
            it.loop = loop && !audioObj.loopWithTail
            it.playbackRate.value = speed
            if (loop && !audioObj.loopWithTail && audioObj.endTimeMs != null) {
                it.loopStart = (audioObj.startTimeMs / 1000.0).coerceAtLeast(0.0)
                it.loopEnd = (audioObj.endTimeMs!! / 1000.0).coerceAtLeast(0.0)
            }
        }

        // Reconnect the signal chain: source → analyser → gain → panner → channel
        val channelGain = engine.getChannelMaster(audioObj.channelId)?.gainNode
        if (channelGain != null) {
            newSource.connect(analyserNode.unsafeCast<AudioNode>())
            analyserNode.connect(gainNode.unsafeCast<AudioNode>())
            gainNode.connect(pannerNode.unsafeCast<AudioNode>())
            pannerNode.connect(channelGain.unsafeCast<AudioNode>())
        }

        // Re-attach onended — capture the loop value in a local so the closure
        // doesn't depend on the (immutable) audioObj.loop field. (F5)
        // For loopWithTail sources the new source's successor takes over at
        // the loop point, so we still need to cleanup() this player on its
        // natural end to avoid leaking nodes into engine.activePlayers.
        val effectiveLoop = loop
        val cleanupOnEnd = audioObj.loopWithTail
        newSource.asDynamic().onended = {
            if (!effectiveLoop || cleanupOnEnd) {
                isEnded = true
                isPlaying = false
                if (cleanupOnEnd) {
                    cleanup()
                }
            }
        }

        // Reset the playback-start baseline so the new source's position
        // counter starts at 0 and the source begins at the preserved offset
        // via the `offset` argument below.
        playbackStartCtxTime = ctx.currentTime
        // See the resume() note: positionSeconds is passed as the source's
        // start offset, not subtracted from the start ctx time.
        sourceStartCtxTime = ctx.currentTime
        newSource.start(ctx.currentTime, positionSeconds)
        sourceNode = newSource

        // loopWithTail: if the new loop state is "on" and the audioObj
        // requested the tail-preserving pattern, schedule the successor
        // from this fresh anchor. No-op when loop is now false.
        scheduleLoopContinuation(positionSeconds)
    }

    // ─── AudioParam automation for smooth changes ───────────────────────

    fun setVolume(targetVolume: Float, fadeMs: Long)
    {
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
    }

    fun setPanning(targetPanning: Float, fadeMs: Long)
    {
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
    }

    fun setSpeed(targetSpeed: Float, fadeMs: Long)
    {
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
    }

    /**
     * Get the current frequency-domain data for the analyser.
     *
     * **Reuse contract**: the returned array is the per-player scratch
     * buffer, NOT a freshly allocated copy. The contents are valid only
     * until the next call to [getFrequencyData] (or [tick], which also
     * writes into the same buffer in the time domain). Callers must read
     * the data synchronously and not retain the reference across ticks.
     *
     * This avoids the per-call `FloatArray(analyserNode.fftSize)` allocation
     * (Issue 4 fix) and is consistent with how [tick] writes time-domain
     * data into the same scratch buffer.
     */
    @JsName("getFrequencyData")
    fun getFrequencyData(): FloatArray
    {
        analyserNode.asDynamic().getFloatFrequencyData(analyserBuffer)
        return analyserBuffer
    }
}