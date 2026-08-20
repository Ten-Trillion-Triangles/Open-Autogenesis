package org.ttt.autogenesis.kvisionapp.audio

import kotlinx.browser.window
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.ttt.autogenesis.audio.AudioChannel
import org.ttt.autogenesis.audio.AudioChannelIds
import org.ttt.autogenesis.audio.AudioObject
import org.ttt.autogenesis.audio.AudioObjectView
import org.ttt.autogenesis.audio.AudioSyncState
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import ui.gameplay.AudioEngineLike
import kotlin.js.Promise
import kotlin.time.TimeSource

/**
 * Main Web Audio API engine for the browser client.
 * Manages AudioContext, global/channel/object gain nodes,
 * playback state, and the tick loop.
 */
object AudioEngine : AudioEngineLike
{
    private val log = Logger
    private lateinit var ctx: AudioContext
    lateinit var globalGainNode: GainNode
        private set

    val channels: MutableMap<String, AudioChannelMaster> = mutableMapOf()
    val activePlayers: MutableMap<String, AudioObjectPlayer> = mutableMapOf()
    val bufferCache: MutableMap<String, AudioBuffer> = mutableMapOf()
    /**
     * Family-to-current-player map used to make [stop] / [pause] /
     * [resume] / [setVolume] etc. resolve the *current* active player
     * of a loop-with-tail chain even after the original id has been
     * replaced by a fresh successor id.
     *
     * Keyed by [AudioObjectPlayer.familyId] (the original id of the
     * first player in the chain, stable across loop iterations).
     * Mutated by [registerFamilyPlayer] (called from the
     * `AudioObjectPlayer` constructor) and [unregisterFamilyPlayer]
     * (called from `cleanup`). [stop] consults this map before
     * [activePlayers] so a fade-out by original id reaches the actual
     * successor currently playing.
     *
     * Visible to the kvisionApp (no `private`) so unit tests can
     * assert against it without an AudioContext.
     */
    val familyToPlayer: MutableMap<String, AudioObjectPlayer> = mutableMapOf()

    /**
     * Fires when a non-loop, non-`loopWithTail` music track naturally
     * ends (its `AudioBufferSourceNode` `onended` callback runs and
     * no successor is taking over). The argument is the *original*
     * `AudioObject.id` of the track that ended. Looping tracks never
     * fire this — their source nodes do not emit `onended` while the
     * browser keeps them in a loop, and a `loopWithTail` chain's
     * `onended` is the parent yielding to its successor (not a true
     * track end).
     *
     * Subscribed to by
     * [org.ttt.autogenesis.kvisionapp.audio.MusicRunner] so the
     * runner can apply a server-pre-picked rule-4 batch the moment
     * the rule-1 initial-conditions track ends. Defaults to null
     * (no subscriber) so non-music callers (SFX, jukebox, etc.) are
     * unaffected.
     */
    var onTrackEnd: ((objectId: String) -> Unit)? = null

    /**
     * Register [player] as the current active member of [familyId]'s
     * family. Called by [AudioObjectPlayer]'s constructor so callers
     * who construct the player directly (e.g. tests) get the wiring
     * even when they bypass [playFromCache]. A second call with the
     * same familyId overwrites the previous entry — this is
     * intentional, because the loop-tail successor that just replaced
     * the prior player IS the new current member of the family.
     */
    fun registerFamilyPlayer(familyId: String, player: AudioObjectPlayer)
    {
        val previous = familyToPlayer.put(familyId, player)
        if (previous != null && previous !== player)
        {
            log.debug(
                LogCategory.SYSTEM,
                "AudioEngine.registerFamilyPlayer: familyId='$familyId' rotated " +
                "from previous id='${previous.audioObject.id}' to new id='${player.audioObject.id}' " +
                "(resource='${player.audioObject.resourceName}')"
            )
        }
    }

    /**
     * Compare-and-set: remove [familyId]'s entry only if it still
     * points at [player]. Called by `AudioObjectPlayer.cleanup()` so
     * a late cleanup of a player that has already been replaced by a
     * successor does NOT clear the family map of the now-current
     * player. Returns true if the entry was removed, false otherwise.
     */
    fun unregisterFamilyPlayer(familyId: String, player: AudioObjectPlayer): Boolean
    {
        val current = familyToPlayer[familyId]
        if (current === player)
        {
            familyToPlayer.remove(familyId)
            return true
        }
        return false
    }

    var globalVolume: Float = 1.0f
        private set

    private var animationFrameId: Int = 0
    private var isInitialized: Boolean = false
    private val resourceLoader = AudioResourceLoader()
    // Queue of objects waiting for their buffer to finish loading before playback
    private val pendingPlays: MutableList<AudioObject> = mutableListOf()
    // F10 (propagated from jukebox): own scope for preload coroutines. The
    // previous code used GlobalScope.launch which is not scoped to this
    // engine's lifetime. The kvisionApp has no close() today, but using
    // a named scope makes future teardown and unit-test isolation
    // straightforward.
    private val engineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        log.info(
            LogCategory.SYSTEM,
            "AudioEngine: singleton instance created (initContext not yet called, isInitialized=false)"
        )
    }

    /**
     * Handshake that completes the first time [loadChannels] finishes (i.e.
     * `isInitialized` flips to `true` via the public [initChannels] path).
     *
     * ## Why this exists
     *
     * The engine has two halves of init: a synchronous [initContext] (must
     * run in a user-gesture call stack so AudioContext.resume() satisfies
     * browser autoplay policy) and a suspending [initChannels] (loads the
     * channel tree, sets `isInitialized = true`). UI code that wants to
     * start playback right after login (`MainMenu.init` -> `MenuMusicPlayer.start`)
     * races against the `GlobalScope.launch { initChannels() }` issued by
     * `LoginWidgets.messageBox.onConfirm`. The race is benign on the happy
     * path but `AudioEngine.play()` silently drops calls when
     * `isInitialized` is still false — making the bug invisible in the
     * file logger.
     *
     * `awaitReady()` is the explicit gate: callers that want to play
     * audio right after login should `await AudioEngine.awaitReady()`
     * first. Once [initChannels] completes, every future call returns
     * immediately (single boolean check, no suspension).
     *
     * ## Lifetime
     *
     * The deferred is `private` to the engine and never re-armed. If a
     * future caller adds a "dispose and re-init" path, this becomes a
     * `var` re-assigned in the re-init flow. Out of scope for plan A.
     */
    private val engineReady: CompletableDeferred<Unit> = CompletableDeferred()

    /**
     * True iff the engine has finished initialising (channel tree loaded,
     * `isInitialized = true`). Cheap synchronous predicate for callers
     * that don't want to suspend.
     */
    override val isReady: Boolean
        get() = isInitialized

    /**
     * Suspend until the engine is ready (i.e. [initChannels] has
     * completed). Returns immediately on the happy path; on the race
     * path it waits up to [timeoutMs] for `initChannels` to finish.
     *
     * If the timeout fires, a WARN is logged with enough context to
     * diagnose the wedged init (`isInitialized`, `ctx` initialised
     * flag), and the function returns without throwing. Callers that
     * need strict failure semantics should use a small `try { ... }`
     * around the `MenuMusicPlayer.start()` call they were about to make
     * (the existing `MainMenu` block already does this).
     *
     * @param timeoutMs budget for waiting on the init handshake. Default
     *   5s — the legitimate path is sub-millisecond, the budget is wide
     *   enough to absorb slow CI / debug builds but tight enough to
     *   surface a wedged `initChannels` in the same browser session.
     */
    suspend fun awaitReady(timeoutMs: Long = 5_000L)
    {
        if (isReady) {
            log.debug(LogCategory.SYSTEM, "AudioEngine.awaitReady: already ready, returning immediately")
            return
        }
        log.info(
            LogCategory.SYSTEM,
            "AudioEngine.awaitReady: not ready yet — waiting up to ${timeoutMs}ms for initChannels() to complete"
        )
        val resolved = withTimeoutOrNull(timeoutMs) { engineReady.await() }
        if (resolved == null)
        {
            log.warn(
                LogCategory.SYSTEM,
                "AudioEngine.awaitReady: timed out after ${timeoutMs}ms — initChannels() never completed " +
                "(isInitialized=$isInitialized, ctx initialized=${::ctx.isInitialized})"
            )
        }
    }

    /**
     * Initialize AudioContext on first user gesture.
     *
     * ## Valid Call Sites
     * This function **must** be called from a user gesture context (click/touch handler)
     * to satisfy browser autoplay policy. Valid call sites:
     * - [SettingsWidget][ui.gameplay.SettingsWidget] close button onClick (existing)
     * - [LoginPage][ui.LoginWidgets] login button success path (on successful auth completion)
     * - [MainMenu][ui.MainMenu] PLAY button / beginSinglePlayerSession()
     *
     * ## Idempotency
     * Subsequent calls return immediately without re-initialization — it is safe to call
     * from multiple user gesture entry points.
     *
     * ## Browser Autoplay Policy
     * `resume()` is called synchronously within the user gesture to unlock the AudioContext.
     * Do NOT defer to GlobalScope or other async context — the call must be on the call stack
     * of a direct user interaction event.
     */
    /**
     * Synchronous half of init. MUST be called from a user-gesture call stack
     * (e.g. directly inside a click handler). This is where the AudioContext
     * is created and `resume()`'d — both must run while the user gesture is
     * still on the call stack, otherwise strict autoplay policies leave the
     * context `suspended` and audio is silent.
     *
     * After this returns, [initChannels] can run in a coroutine — those calls
     * (createGain, connect, AudioChannelMaster construction) are not
     * user-gesture-gated.
     *
     * (B1 propagation from the jukebox review: kvisionApp previously did this
     * in a single suspend `init()` which deferred the AudioContext construction
     * to a coroutine. That violated browser autoplay policy on strict platforms.)
     */
    fun initContext()
    {
        log.info(
            LogCategory.SYSTEM,
            "AudioEngine.initContext() ENTER, isInitialized=$isInitialized, ctxInitialized=${::ctx.isInitialized}, " +
            "AudioContext available=${js("typeof window.AudioContext !== 'undefined' || typeof window.webkitAudioContext !== 'undefined'")}"
        )
        if (isInitialized) {
            log.debug(LogCategory.SYSTEM, "AudioEngine.initContext() already initialized, returning")
            return
        }
        // Idempotency fix: if the AudioContext already exists (e.g. a prior
        // user gesture created it, then the user navigated through screens
        // that re-fired initContext), DO NOT create a new one. Creating
        // multiple AudioContexts leaks the first one's resume() Promise and
        // the Web Audio API will refuse to actually start the new one until
        // the old one is explicitly close()d. We just ensure the existing
        // context is in the "running" state and move on.
        if (::ctx.isInitialized) {
            val currentState: String = ctx.asDynamic().state as String
            if (currentState == "suspended") {
                // resume() must be called synchronously within the user
                // gesture to unlock browser autoplay policy. The current
                // call IS on a user-gesture call stack (the click handler),
                // so calling resume() again is safe.
                ctx.asDynamic().resume()
            }
            val sampleRate: Double = (ctx.asDynamic().sampleRate as Number).toDouble()
            log.info(
                LogCategory.SYSTEM,
                "AudioEngine.initContext() DONE (reused existing ctx), state=$currentState, sampleRate=$sampleRate"
            )
            return
        }
        // First-time init: create the AudioContext and call resume() on
        // the user-gesture call stack so the browser allows the resume.
        ctx = js("new (window.AudioContext || window.webkitAudioContext)()")
        // resume() must be called synchronously within the user gesture to unlock
        // browser autoplay policy — do NOT defer to GlobalScope or other async context.
        // The typed interface declares `suspend fun resume()` but since
        // initContext itself is non-suspend (it must run on the user-gesture
        // call stack), we reach resume() through asDynamic() to call the JS
        // method directly. This is the standard pattern when the same JS
        // method has both a "fire once" and a "promise-returning" overload.
        ctx.asDynamic().resume()
        globalGainNode = ctx.createGain()
        globalGainNode.connect(ctx.destination)
        // Read live state from the AudioContext through asDynamic() so the
        // Kotlin/JS compiler emits an indirect JS access (otherwise the
        // `ctx` local is a Kotlin-side variable, not a JS binding).
        val ctxState: String = ctx.asDynamic().state as String
        val ctxSampleRate: Double = (ctx.asDynamic().sampleRate as Number).toDouble()
        log.info(
            LogCategory.SYSTEM,
            "AudioEngine.initContext() DONE, ctx.state=$ctxState, sampleRate=$ctxSampleRate"
        )
    }

    /**
     * Asynchronous half of init. Safe to call from a coroutine — runs after
     * [initContext] has created the AudioContext within a user gesture.
     *
     * If [loadChannels] has already been called with an explicit channel
     * list, this is a no-op (the engine is already initialized). Otherwise
     * it falls back to [defaultChannelConfigs] — the standard Music master
     * + 8 music-category children + an independent Sfx channel.
     *
     * New callers that need a non-default channel tree should call
     * [loadChannels] directly with the configs from their catalog before
     * relying on this method.
     */
    suspend fun initChannels()
    {
        log.info(LogCategory.SYSTEM, "AudioEngine.initChannels() ENTER, isInitialized=$isInitialized")
        if (isInitialized) {
            log.debug(LogCategory.SYSTEM, "AudioEngine.initChannels() already initialized, returning")
            return
        }
        // F11: defensive guard. If a future caller invokes initChannels()
        // without first calling initContext(), [ctx] is uninitialized and
        // the calls below would throw UninitializedPropertyAccessException.
        if (!::ctx.isInitialized)
        {
            log.warn(
                LogCategory.SYSTEM,
                "AudioEngine.initChannels: ctx not initialized; call initContext() first — aborting initChannels"
            )
            return
        }
        val defaultConfigs = defaultChannelConfigs()
        log.info(
            LogCategory.SYSTEM,
            "AudioEngine.initChannels: loading default channel configs (${defaultConfigs.size} channels)"
        )
        // [Bug fix] Wait for the AudioContext to actually reach the
        // "running" state before declaring init done. The resume() Promise
        // in initContext is fire-and-forget so the state may still be
        // "suspended" for a few microtasks after initContext returns. If
        // initChannels completes before that, the first play() lands
        // while the context is suspended and the browser silently drops
        // the buffer source. We poll ctx.state with a tiny backoff up
        // to a generous 2s budget — the legitimate path resolves in
        // <10ms on a healthy browser.
        if (::ctx.isInitialized) {
            val startMark = TimeSource.Monotonic.markNow()
            val maxWaitMs = 2_000L
            while (true) {
                val state: String = ctx.asDynamic().state as String
                if (state == "running") {
                    log.info(
                        LogCategory.SYSTEM,
                        "AudioEngine.initChannels: AudioContext is running (took " +
                        "${startMark.elapsedNow().inWholeMilliseconds}ms to reach running state)"
                    )
                    break
                }
                if (startMark.elapsedNow().inWholeMilliseconds >= maxWaitMs) {
                    log.warn(
                        LogCategory.SYSTEM,
                        "AudioEngine.initChannels: AudioContext still in state='$state' " +
                        "after ${maxWaitMs}ms — proceeding anyway (play() will surface a WARN " +
                        "if the context refuses to schedule)"
                    )
                    break
                }
                // Re-fire resume() in case the original call was lost
                // (e.g. user navigated away during the gesture lifetime).
                if (state == "suspended") {
                    ctx.asDynamic().resume()
                }
                // Yield to the JS event loop so the resume() microtask
                // can land before the next poll.
                kotlinx.coroutines.delay(10)
            }
        }
        loadChannels(defaultConfigs)
        // [Plan A] Complete the readiness handshake now that the channel
        // tree is wired and `isInitialized = true`. Anyone awaiting
        // [awaitReady] resumes here. We deliberately do NOT complete
        // [engineReady] in the early-return branches above — a caller
        // that hits the `ctx not initialized` defensive guard is in
        // genuine "engine will never be ready" territory and should
        // not be told it is.
        if (!engineReady.isCompleted) {
            engineReady.complete(Unit)
            log.info(
                LogCategory.SYSTEM,
                "AudioEngine.initChannels: handshake complete — engineReady is now signalled, awaitReady() callers will resume"
            )
        }
    }

    /**
     * Build the audio channel hierarchy from an explicit list of
     * [AudioChannel] configs (typically read from
     * `structs.audio.AudioTracks.channels` in the bundled JSON).
     *
     * Replaces the legacy hardcoded Music+Sfx setup. The build is a
     * two-phase topological walk:
     *  1. **Phase 1** — create one [AudioChannelMaster] per config with
     *     `parent = null`. Gain nodes are created but not yet connected.
     *  2. **Phase 2** — repeatedly wire any channel whose `parentId`
     *     resolves (either null for roots, or a registered master id)
     *     until no more progress. Each child's gain node is connected
     *     to its parent's gain node; root channels connect to
     *     [globalGainNode].
     *
     * After wiring, [AudioChannelMaster.applyState] pushes each
     * config's `volume` / `muted` into the gain node via
     * `setTargetAtTime` for a smooth transition. The parent chain
     * then drives the multiplicative cascade in
     * [AudioChannelMaster.effectiveVolume].
     *
     * Robustness: any channel whose `parentId` is missing or forms a
     * cycle after the iterative pass is logged at WARN and routed
     * directly to [globalGainNode] (effectively treated as a root).
     * This keeps the engine running rather than throwing on a malformed
     * config.
     *
     * Idempotent: a second call after the engine is already initialized
     * is a no-op so the legacy [initChannels] path still works.
     */
    fun loadChannels(channelConfigs: List<AudioChannel>)
    {
        log.info(
            LogCategory.SYSTEM,
            "AudioEngine.loadChannels() ENTER channelConfigs.size=${channelConfigs.size} isInitialized=$isInitialized"
        )
        if (isInitialized) {
            log.debug(LogCategory.SYSTEM, "AudioEngine.loadChannels() already initialized, returning")
            return
        }
        if (!::ctx.isInitialized)
        {
            log.warn(
                LogCategory.SYSTEM,
                "AudioEngine.loadChannels: ctx not initialized; call initContext() first — aborting loadChannels"
            )
            return
        }

        // Phase 1: clear and create all masters with parent=null.
        channels.clear()
        for (cfg in channelConfigs)
        {
            if (channels.containsKey(cfg.id))
            {
                log.warn(
                    LogCategory.SYSTEM,
                    "AudioEngine.loadChannels: duplicate channel id '\''${cfg.id}'\'' in config, skipping duplicate"
                )
                continue
            }
            val gain = ctx.createGain()
            channels[cfg.id] = AudioChannelMaster(cfg, gain, null, this)
        }
        log.info(
            LogCategory.SYSTEM,
            "AudioEngine.loadChannels: phase 1 complete — created ${channels.size} channel masters with parent=null"
        )

        // Phase 2: wire parents in topological order. BFS-ish: each
        // pass picks up every channel whose parent is now resolvable.
        val remaining = channelConfigs.filter { it.id in channels.keys }.toMutableList()
        val maxIterations = channelConfigs.size + 1
        var progress = true
        var iterations = 0
        var wiredCount = 0
        log.info(
            LogCategory.SYSTEM,
            "AudioEngine.loadChannels: phase 2 START — remaining.size=${remaining.size}, maxIterations=$maxIterations"
        )
        while (remaining.isNotEmpty() && progress && iterations < maxIterations)
        {
            progress = false
            
            val iter = remaining.iterator()
            while (iter.hasNext())
            {
                val cfg = iter.next()
                
                val master = channels[cfg.id]!!
                val parentMaster = if (cfg.parentId == null) null else channels[cfg.parentId]
                
                if (cfg.parentId == null || parentMaster != null)
                {
                    master.parent = parentMaster
                    val parentGain = parentMaster?.gainNode ?: globalGainNode
                    
                    master.gainNode.connect(parentGain.unsafeCast<AudioNode>())
                    
                    master.applyState()
                    
                    iter.remove()
                    progress = true
                    wiredCount++
                }
            }
            iterations++
            
        }

        // Anything still in `remaining` after the pass has an
        // unresolvable parent (missing id or a cycle). Route to
        // global gain so it still plays; log a warning so the
        // designer can fix the config.
        for (cfg in remaining)
        {
            log.warn(
                LogCategory.SYSTEM,
                "AudioEngine.loadChannels: channel '\''${cfg.id}'\'' has unresolved parentId='\''${cfg.parentId}'\'' (missing or cycle); routing to global gain"
            )
            val master = channels[cfg.id]!!
            master.parent = null
            master.gainNode.connect(globalGainNode.unsafeCast<AudioNode>())
            master.applyState()
        }

        log.info(
            LogCategory.SYSTEM,
            "AudioEngine.loadChannels: phase 2 complete — wired=$wiredCount, " +
            "unresolved=${remaining.size}, total iterations=$iterations"
        )

        startTickLoop()
        isInitialized = true
        log.info(
            LogCategory.SYSTEM,
            "AudioEngine.loadChannels() DONE — engine is now READY (isInitialized=true, " +
            "channels=${channels.size}, tick loop started)"
        )
        // [Bug fix] Drain the play() self-heal queue. When play() was
        // called before initChannels completed (e.g. from MenuMusicPlayer
        // on the skipLogin path, where LoginWidgets never launched
        // initChannels), the play was added to pendingPlays and a
        // initChannels() was kicked off. Now that init has completed,
        // we replay the queued plays so the user actually hears the
        // menu music instead of getting a silent engine.
        val drainQueue = pendingPlays.toList()
        pendingPlays.clear()
        if (drainQueue.isNotEmpty())
        {
            log.info(
                LogCategory.SYSTEM,
                "AudioEngine.loadChannels: draining ${drainQueue.size} pending play(s) queued by self-heal"
            )
            for (o in drainQueue) {
                play(o, 0.0)
            }
        }
    }

    /**
     * True iff [channelId] is in the Music branch of the channel tree
     * (its top-level ancestor is [AudioChannelIds.MUSIC_MASTER_ID]). Walks the parent
     * chain defensively, treating cycles and missing links as
     * non-music. Used by the music runner to filter schedules.
     */
    fun isMusicChannelId(channelId: String): Boolean
    {
        val visited = mutableSetOf<String>()
        var current: String? = channelId
        while (current != null)
        {
            if (current == AudioChannelIds.MUSIC_MASTER_ID) return true
            if (!visited.add(current)) return false
            val master = channels[current] ?: return false
            current = master.channel.parentId
        }
        return false
    }

    /**
     * The default channel tree when no explicit config is provided:
     *
     * ```
     * Global
     * ├── Music   (master — driven by the options-menu "Music" slider)
     * │   ├── Drone / Melody / Rhythm / Harmony   (the four musical layers)
     * │   └── Menu / Start / Nemesis / End       (the four scenarios)
     * └── Sfx     (independent — driven by the options-menu "Sfx" slider)
     * ```
     *
     * All volumes default to 1.0f. Designers can override per-channel
     * volumes in the audio-tracks JSON's `channels` array, which
     * [loadChannels] reads instead.
     */
    private fun defaultChannelConfigs(): List<AudioChannel> = listOf(
        AudioChannel(id = "Music",   name = "Music",   parentId = null),
        AudioChannel(id = "Drone",   name = "Drone",   parentId = "Music"),
        AudioChannel(id = "Melody",  name = "Melody",  parentId = "Music"),
        AudioChannel(id = "Rhythm",  name = "Rhythm",  parentId = "Music"),
        AudioChannel(id = "Harmony", name = "Harmony", parentId = "Music"),
        AudioChannel(id = "Menu",    name = "Menu",    parentId = "Music"),
        AudioChannel(id = "Start",   name = "Start",   parentId = "Music"),
        AudioChannel(id = "Nemesis", name = "Nemesis", parentId = "Music"),
        AudioChannel(id = "End",     name = "End",     parentId = "Music"),
        AudioChannel(id = "Sfx",     name = "SFX",     parentId = null)
    )

    /**
     * @deprecated Kept for source-compatibility; new callers should use
     * [initContext] (synchronous, in user gesture) + [initChannels]
     * (suspend, in coroutine). This wraps both for any caller that
     * pre-dates the split. WARNING: this wrapper does NOT preserve the
     * autoplay-policy guarantee — the AudioContext construction happens
     * inside the coroutine, not in the user gesture. New UI code MUST use
     * the two-step API.
     */
    suspend fun init()
    {
        log.warn(
            LogCategory.SYSTEM,
            "AudioEngine.init() (deprecated wrapper) called — new code should use initContext() + initChannels() separately"
        )
        if (isInitialized) return
        initContext()
        initChannels()
    }

    private fun startTickLoop()
    {
        var tickCount = 0L
        fun tick(time: Double)
        {
            for (player in activePlayers.values)
            {
                player.tick()
            }
            tickCount++
            if (tickCount % 600L == 0L)
            {
                // ~10s heartbeat at 60fps — confirms tick loop is alive without spamming.
                log.debug(
                    LogCategory.SYSTEM,
                    "AudioEngine.tick loop: heartbeat tickCount=$tickCount activePlayers=${activePlayers.size}"
                )
            }
            animationFrameId = window.requestAnimationFrame { timestamp -> tick(timestamp) }.toInt()
        }
        animationFrameId = window.requestAnimationFrame { timestamp -> tick(timestamp) }.toInt()
        log.info(LogCategory.SYSTEM, "AudioEngine.startTickLoop: started (requestAnimationFrame chain)")
    }

    fun stopTickLoop()
    {
        window.cancelAnimationFrame(animationFrameId)
        log.info(LogCategory.SYSTEM, "AudioEngine.stopTickLoop: animation frame cancelled")
    }

    // ─── Playback API ───────────────────────────────────────────────────────

    fun play(
        obj: AudioObject,
        startWhenSeconds: Double = 0.0,
        /**
         * Optional stable id for the music family this [obj] belongs
         * to. When [obj] is a fresh loop-tail successor of a
         * previously-scheduled audio object, the successor inherits
         * the parent's familyId so [stop] / [pause] / [resume] calls
         * targeting the original id still resolve to the current
         * active player. When null, [playFromCache] uses [obj.id] as
         * the familyId (so the player becomes the root of its own
         * family). See [AudioObjectPlayer.familyId].
         */
        familyId: String? = null
    )
    {
        // [Plan A] Replace the silent drop with a WARN. The pre-fix
        // behaviour returned immediately when the engine was not yet
        // initialised, which was the root cause of the "main menu
        // music is silent" bug (MainMenu launching MenuMusicPlayer.start
        // before LoginWidgets's GlobalScope.launch { initChannels() }
        // had a chance to complete). Callers that want a guarantee
        // should `await AudioEngine.awaitReady()` before invoking
        // [play]; this WARN is the safety net for any caller that
        // forgets.
        if (!isInitialized)
        {
            // Self-heal: rather than drop the call, kick off initChannels
            // on the engine's own scope and queue the play. The play will
            // be re-driven when initChannels completes (via engineReady)
            // because the queue is re-checked on every transition. This
            // is the safety net the WARN used to advertise; the previous
            // behaviour silently dropped menu-music on the skipLogin path
            // because LoginWidgets never ran to launch initChannels.
            if (::ctx.isInitialized)
            {
                log.warn(
                    LogCategory.SYSTEM,
                    "AudioEngine.play: engine not yet initialized (isInitialized=false), " +
                    "auto-initialising for id=${obj.id} resource='${obj.resourceName}'"
                )
                pendingPlays.add(obj)
                engineScope.launch { initChannels() }
            }
            else
            {
                log.warn(
                    LogCategory.SYSTEM,
                    "AudioEngine.play: AudioContext not yet created, dropping id=${obj.id} " +
                    "resource='${obj.resourceName}' — call AudioEngine.initContext() first"
                )
            }
            return
        }
        val resourceName = obj.resourceName

        if (!bufferCache.containsKey(resourceName))
        {
            // Buffer not cached — preload then play. This branch is the
            // single most likely place for the menu-music bug to hide
            // (the call returns immediately and the audio fetch can
            // take hundreds of ms or hang entirely on a broken dev
            // server), so we log every transition: launch, cache-hit
            // after preload, cache-miss, and the timeout safeguard in
            // AudioResourceLoader. Without these logs, a wedged
            // preload looks identical to a successful play() from the
            // caller's perspective — see the 2026-06-16 12:50 session
            // log where MusicRunner logged "started menu track" but
            // the audio never actually played.
            log.info(
                LogCategory.SYSTEM,
                "AudioEngine.play: BUFFER NOT CACHED for id=${obj.id} resource='$resourceName' — launching preload coroutine"
            )
            engineScope.launch {
                val preloadStart = js("Date.now()").unsafeCast<Double>()
                log.debug(
                    LogCategory.SYSTEM,
                    "AudioEngine.play: preload coroutine ENTER for id=${obj.id} resource='$resourceName'"
                )
                preloadBuffer(resourceName)
                val preloadMs = js("Date.now()").unsafeCast<Double>() - preloadStart
                if (bufferCache.containsKey(resourceName)) {
                    log.info(
                        LogCategory.SYSTEM,
                        "AudioEngine.play: preload SUCCEEDED for id=${obj.id} resource='$resourceName' (took ${preloadMs.toInt()}ms) — scheduling AudioObjectPlayer"
                    )
                    playFromCache(obj, 0.0, familyId = familyId)
                } else {
                    log.warn(
                        LogCategory.SYSTEM,
                        "AudioEngine.play: preload FAILED for id=${obj.id} resource='$resourceName' (took ${preloadMs.toInt()}ms) — bufferCache still empty, no audio will play (loadBuffer returned null; check AudioResourceLoader logs for the underlying reason)"
                    )
                }
            }
            return
        }

        // [Bug fix — music transitions] The cached branch MUST thread
        // [familyId] through to [playFromCache] just like the
        // preload-success branch on line ~726 does. Without this, the
        // first loop-tail successor of a track whose buffer is already
        // cached (i.e. EVERY successor after the first one for any
        // track) gets `familyId = obj.id` (its own fresh uuid) instead
        // of inheriting the original's `familyId`. The engine's
        // `familyToPlayer` map then keys on the successor's own id,
        // so when [MusicRunner.playSchedule] later calls `stop(originalId)`
        // the lookup misses (the original's entry was already cleared
        // by [AudioObjectPlayer.cleanup] firing on the original's
        // onended) and the fade-out silently no-ops with
        // `AudioEngine.stop: no active player for objectId=... — no-op`.
        // The fix is the one missing argument on this call. The
        // preload-success branch already passes it correctly; this
        // cached branch is the one the prior diff missed.
        playFromCache(obj, startWhenSeconds, familyId = familyId)
    }

    private fun playFromCache(obj: AudioObject, startWhenSeconds: Double, familyId: String? = null)
    {
        val buffer = bufferCache[obj.resourceName]
        if (buffer == null) {
            log.warn(
                LogCategory.SYSTEM,
                "AudioEngine.playFromCache: buffer unexpectedly missing for resource='${obj.resourceName}' " +
                "(id=${obj.id}) — skipping (should not happen because play() gates on bufferCache)"
            )
            return
        }
        // Resolve the familyId. The constructor's default `familyId =
        // audioObj.id` covers the null case (player becomes the root
        // of its own family), but we pass through explicitly so the
        // engine can log both sides of the family-id handoff.
        val resolvedFamilyId = familyId ?: obj.id
        val player = AudioObjectPlayer(obj, buffer, ctx, this, familyId = resolvedFamilyId)
        activePlayers[obj.id] = player
        // Register the family map too. The AudioObjectPlayer
        // constructor already does this, but writing it here as well
        // makes the wiring visible at the call site that creates the
        // player (a unit-test entry point that bypasses the engine's
        // own play() would still work without it).
        familyToPlayer[resolvedFamilyId] = player
        log.info(
            LogCategory.SYSTEM,
            "AudioEngine.playFromCache: id=${obj.id} resource='${obj.resourceName}' " +
            "channel='${obj.channelId}' startWhenSeconds=$startWhenSeconds " +
            "familyId='$resolvedFamilyId' activePlayers now=${activePlayers.size} " +
            "familyToPlayer now=${familyToPlayer.size}"
        )
        player.play(startWhenSeconds)
    }

    fun pause(objectId: String)
    {
        val player = familyToPlayer[objectId] ?: activePlayers[objectId]
        if (player == null) {
            log.warn(
                LogCategory.SYSTEM,
                "AudioEngine.pause: no active player for objectId='$objectId' (familyToPlayer and activePlayers both empty)"
            )
            return
        }
        player.pause()
    }

    fun resume(objectId: String)
    {
        val player = familyToPlayer[objectId] ?: activePlayers[objectId]
        if (player == null) {
            log.warn(
                LogCategory.SYSTEM,
                "AudioEngine.resume: no active player for objectId='$objectId' (familyToPlayer and activePlayers both empty)"
            )
            return
        }
        player.resume()
    }

    fun stop(objectId: String, fadeOutDurationMs: Long = 0)
    {
        // [Bug fix — music transitions] Consult [familyToPlayer] FIRST
        // so a stop() targeting the *original* id of a loop-with-tail
        // chain reaches the *current* active successor (whose own id
        // is a fresh uuid generated by
        // [AudioObjectPlayer.scheduleLoopContinuation]). The
        // pre-fix code only looked at [activePlayers] and silently
        // no-op'd once the first loop iteration had replaced the
        // original id — leaving the running music playing through
        // every subsequent `playSchedule` (see the
        // `AudioEngine.stop: no active player ... — no-op` log storm
        // around 17:21:26 in browser-2026-06-16-130503.log).
        val familyPlayer = familyToPlayer.remove(objectId)
        val directPlayer = activePlayers.remove(objectId)
        val player = familyPlayer ?: directPlayer
        if (player == null) {
            log.warn(
                LogCategory.SYSTEM,
                "AudioEngine.stop: no active player for objectId='$objectId' " +
                "(familyToPlayer and activePlayers both empty, fadeOutMs=$fadeOutDurationMs) — no-op"
            )
            return
        }
        // If the family lookup resolved to a different id than the
        // caller's, also drop the direct [activePlayers] entry so
        // cleanup's `activePlayers.remove(audioObj.id)` doesn't
        // double-count.
        if (familyPlayer != null && directPlayer == null && player.audioObject.id != objectId)
        {
            activePlayers.remove(player.audioObject.id)
        }
        // The cleanup path inside player.stop() will unregister
        // familyToPlayer via the unregister call we just made above;
        // we already removed the entry here so the cleanup's
        // compare-and-set will be a no-op (returns false), which is
        // the correct behavior since we are the ones tearing it down.
        log.info(
            LogCategory.SYSTEM,
            "AudioEngine.stop: id='$objectId' resolved to player id='${player.audioObject.id}' " +
            "familyId='${player.familyId}' fadeOutMs=$fadeOutDurationMs " +
            "(activePlayers now=${activePlayers.size}, familyToPlayer now=${familyToPlayer.size})"
        )
        player.stop(fadeOutDurationMs)
    }

    // ─── Parameter changes via AudioParam automation ────────────────────────
    //
    // Each method below consults [familyToPlayer] first so a server-issued
    // param update by the *original* id of a loop-with-tail chain reaches
    // the actual current successor player. The pre-fix code only checked
    // [activePlayers], which silently no-op'd for any id whose successor
    // had been spawned.

    fun setVolume(objectId: String, volume: Float, fadeMs: Long = 0)
    {
        val player = familyToPlayer[objectId] ?: activePlayers[objectId]
        if (player == null) {
            log.warn(LogCategory.SYSTEM, "AudioEngine.setVolume: no active player for objectId='$objectId'")
            return
        }
        if (player.familyId != objectId)
        {
            log.debug(
                LogCategory.SYSTEM,
                "AudioEngine.setVolume: objectId='$objectId' resolved to successor id='${player.audioObject.id}' " +
                "familyId='${player.familyId}'"
            )
        }
        player.setVolume(volume, fadeMs)
    }

    fun setPanning(objectId: String, panning: Float, fadeMs: Long = 0)
    {
        val player = familyToPlayer[objectId] ?: activePlayers[objectId]
        if (player == null) {
            log.warn(LogCategory.SYSTEM, "AudioEngine.setPanning: no active player for objectId='$objectId'")
            return
        }
        if (player.familyId != objectId)
        {
            log.debug(
                LogCategory.SYSTEM,
                "AudioEngine.setPanning: objectId='$objectId' resolved to successor id='${player.audioObject.id}' " +
                "familyId='${player.familyId}'"
            )
        }
        player.setPanning(panning, fadeMs)
    }

    fun setSpeed(objectId: String, speed: Float, fadeMs: Long = 0)
    {
        val player = familyToPlayer[objectId] ?: activePlayers[objectId]
        if (player == null) {
            log.warn(LogCategory.SYSTEM, "AudioEngine.setSpeed: no active player for objectId='$objectId'")
            return
        }
        if (player.familyId != objectId)
        {
            log.debug(
                LogCategory.SYSTEM,
                "AudioEngine.setSpeed: objectId='$objectId' resolved to successor id='${player.audioObject.id}' " +
                "familyId='${player.familyId}'"
            )
        }
        player.setSpeed(speed, fadeMs)
    }

    override fun setChannelVolume(channelId: String, volume: Float, muted: Boolean)
    {
        val master = channels[channelId]
        if (master == null) {
            log.warn(
                LogCategory.SYSTEM,
                "AudioEngine.setChannelVolume: unknown channelId='$channelId' (known=${channels.keys})"
            )
            return
        }
        log.info(
            LogCategory.SYSTEM,
            "AudioEngine.setChannelVolume: channelId='$channelId' volume=$volume muted=$muted"
        )
        master.applyState(volume, muted)
    }

    fun setGlobalVolume(volume: Float)
    {
        val previous = globalVolume
        globalVolume = volume
        val now = ctx.currentTime
        globalGainNode.gain.setTargetAtTime(volume, now, 0.01)
        log.info(
            LogCategory.SYSTEM,
            "AudioEngine.setGlobalVolume: $previous → $volume (gain ramped with timeConstant=0.01s)"
        )
    }

    // ─── Server sync ──────────────────────────────────────────────────────

    fun scheduleFromServer(
        objects: List<AudioObject>,
        serverTimestampMs: Long,
        currentServerFrame: Long,
        serverSampleRate: Float,
        serverFrameSize: Int = 128
    )
    {
        if (!isInitialized) {
            log.warn(
                LogCategory.SYSTEM,
                "AudioEngine.scheduleFromServer: engine not initialized, dropping ${objects.size} objects"
            )
            return
        }
        val nowMs = js("Date.now()").unsafeCast<Double>().toLong()
        val clockOffsetMs = serverTimestampMs - nowMs
        val audioSampleRate = ctx.sampleRate.toFloat()

        log.info(
            LogCategory.SYSTEM,
            "AudioEngine.scheduleFromServer: ENTER objects.size=${objects.size} " +
            "serverTimestampMs=$serverTimestampMs nowMs=$nowMs clockOffsetMs=$clockOffsetMs " +
            "serverFrame=$currentServerFrame serverSampleRate=$serverSampleRate " +
            "audioSampleRate=$audioSampleRate"
        )

        for (obj in objects)
        {
            val startWhenSeconds = computeStartWhenSeconds(
                obj = obj,
                nowMs = nowMs,
                serverTimestampMs = serverTimestampMs,
                clockOffsetMs = clockOffsetMs,
                currentServerFrame = currentServerFrame,
                serverSampleRate = serverSampleRate,
                audioSampleRate = audioSampleRate,
                serverFrameSize = serverFrameSize
            )

            if (startWhenSeconds <= 0)
            {
                log.debug(
                    LogCategory.SYSTEM,
                    "AudioEngine.scheduleFromServer: id=${obj.id} resource='${obj.resourceName}' " +
                    "→ play() immediately (startWhenSeconds=$startWhenSeconds)"
                )
                play(obj, 0.0)
            }
            else
            {
                log.debug(
                    LogCategory.SYSTEM,
                    "AudioEngine.scheduleFromServer: id=${obj.id} resource='${obj.resourceName}' " +
                    "→ play() deferred by ${startWhenSeconds}s"
                )
                play(obj, startWhenSeconds)
            }
        }
    }

    /**
     * Compute the audio-clock-relative start time for a server-scheduled object.
     *
     * ## Math (correct, post-audit fix)
     *
     * Three scheduling modes are supported, all returning seconds-from-now
     * (where "now" is the audio context's clock at the moment the function
     * is called):
     *
     * 1. **startSample** set: the absolute sample index in the buffer where
     *    playback should start. We compute how many samples have elapsed
     *    since [serverTimestampMs] and subtract from the target sample.
     *
     * 2. **startFrame** set: the absolute server frame where playback should
     *    start, where frames are counted at the server's [serverFrameSize]
     *    samples per frame. Convert frames to seconds via
     *    `frames * serverFrameSize / serverSampleRate`, then subtract the
     *    elapsed wall-clock seconds so we schedule further in the future
     *    to compensate for time already passed.
     *
     * 3. **startTimeMs** set: wall-clock millisecond timestamp. Subtract
     *    the server-clock offset (in ms) to get ms-from-now, then divide
     *    by 1000.
     *
     * ## Bug this fixes
     *
     * The pre-fix code in the `startFrame` branch did
     * `(startFrame - currentServerFrame - elapsedSamples/128.0) * 128.0 / serverSampleRate`,
     * which (a) hard-coded `128` instead of using [serverFrameSize] and
     * (b) divided `elapsedSamples` by 128 (giving some non-unit number)
     * before multiplying the whole expression by 128. The unit conversions
     * canceled only by accident. The fix is the cleaner form below.
     *
     * @param obj the audio object with startSample / startFrame / startTimeMs
     * @param nowMs wall-clock time when the schedule is being computed
     * @param serverTimestampMs wall-clock time the server sent the schedule
     * @param clockOffsetMs `serverTimestampMs - nowMs` (precomputed for testability)
     * @param currentServerFrame the server's current frame at send time
     * @param serverSampleRate the server's sample rate
     * @param audioSampleRate the audio context's sample rate
     * @param serverFrameSize the server's frame size in samples
     * @return seconds from now (audio-clock-relative) at which playback should start
     */
    internal fun computeStartWhenSeconds(
        obj: AudioObject,
        nowMs: Long,
        serverTimestampMs: Long,
        clockOffsetMs: Long,
        currentServerFrame: Long,
        serverSampleRate: Float,
        audioSampleRate: Float,
        serverFrameSize: Int
    ): Double
    {
        return when
        {
            obj.startSample != null ->
            {
                val elapsedSamples = (nowMs - serverTimestampMs) * audioSampleRate / 1000.0
                (obj.startSample!!.toDouble() - elapsedSamples) / audioSampleRate
            }
            obj.startFrame != null ->
            {
                // Correct unit conversion:
                //   target_seconds  = (targetFrame - currentFrame) * frameSize / sampleRate
                //   elapsed_seconds = (nowMs - serverTimestampMs) / 1000.0
                //   startWhen       = target_seconds - elapsed_seconds
                val targetSeconds = (obj.startFrame!!.toDouble() - currentServerFrame.toDouble()) * serverFrameSize / serverSampleRate
                val elapsedSeconds = (nowMs - serverTimestampMs) / 1000.0
                targetSeconds - elapsedSeconds
            }
            obj.startTimeMs > 0 ->
            {
                // Use only wall-clock offset: compute seconds from now until startTimeMs using clock offset.
                // Do NOT mix in ctx.currentTime (which is audio-context time, not wall-clock) — that would double-count.
                (obj.startTimeMs - clockOffsetMs) / 1000.0
            }
            else -> 0.0
        }
    }

    fun reconcileWithSnapshot(snapshot: AudioSyncState): List<AudioObject>
    {
        log.info(
            LogCategory.SYSTEM,
            "AudioEngine.reconcileWithSnapshot: ENTER globalVolume=${snapshot.globalVolume} " +
            "channels.size=${snapshot.channels.size} scheduledObjects.size=${snapshot.scheduledObjects.size}"
        )
        setGlobalVolume(snapshot.globalVolume)
        for (ch in snapshot.channels)
        {
            channels[ch.id]?.applyState(ch.volume, ch.muted)
        }
        // Return objects that need to be started
        val toStart = snapshot.scheduledObjects.filter { obj ->
            activePlayers[obj.id] == null
        }
        log.info(
            LogCategory.SYSTEM,
            "AudioEngine.reconcileWithSnapshot: result — toStart.size=${toStart.size} " +
            "(alreadyActive=${snapshot.scheduledObjects.size - toStart.size})"
        )
        return toStart
    }

    fun getObjectView(id: String): AudioObjectView? = activePlayers[id]

    // ─── Buffer management ─────────────────────────────────────────────────

    suspend fun preloadBuffer(resourceName: String)
    {
        if (bufferCache.containsKey(resourceName)) {
            return
        }
        val buffer = resourceLoader.loadBuffer(resourceName, ctx)
        if (buffer == null) {
            log.warn(
                LogCategory.NETWORK,
                "AudioEngine.preloadBuffer: loadBuffer returned null for '$resourceName' — " +
                "draining pending plays for this resource so the queue doesn't stall"
            )
            // Preload failed — drain pending plays for this resource so the queue never stalls
            val ready = pendingPlays.filter { it.resourceName == resourceName }
            pendingPlays.removeAll(ready.toSet())
            return
        }
        bufferCache[resourceName] = buffer
        log.info(
            LogCategory.NETWORK,
            "AudioEngine.preloadBuffer: cached resourceName='$resourceName' " +
            "(bufferCache.size=${bufferCache.size})"
        )
    }

    fun getChannelMaster(channelId: String): AudioChannelMaster? = channels[channelId]
}