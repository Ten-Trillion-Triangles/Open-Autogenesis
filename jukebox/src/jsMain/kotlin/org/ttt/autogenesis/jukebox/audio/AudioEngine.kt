package org.ttt.autogenesis.jukebox.audio

import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.ttt.autogenesis.audio.AudioChannel
import org.ttt.autogenesis.audio.AudioObject
import org.ttt.autogenesis.audio.AudioObjectView

/**
 * Main Web Audio API engine for the browser client.
 * Manages AudioContext, global/channel/object gain nodes,
 * playback state, and the tick loop.
 */
object AudioEngine
{
    // AudioContext is `internal` (not `private`) so JukeboxAudioEngine can
    // call resume() and close() on it directly when needed for user-gesture
    // init (B1) and teardown (B4). Marked internal to keep it inside the
    // jukebox module — not exposed across modules.
    internal lateinit var ctx: AudioContext
        private set
    lateinit var globalGainNode: GainNode
        private set

    val channels: MutableMap<String, AudioChannelMaster> = mutableMapOf()
    val activePlayers: MutableMap<String, AudioObjectPlayer> = mutableMapOf()
    val bufferCache: MutableMap<String, AudioBuffer> = mutableMapOf()

    var globalVolume: Float = 1.0f
        private set

    private var animationFrameId: Int = 0
    private var isInitialized: Boolean = false
    private val resourceLoader = AudioResourceLoader()
    // Queue of objects waiting for their buffer to finish loading before playback
    private val pendingPlays: MutableList<AudioObject> = mutableListOf()
    // F10: own scope for preload coroutines. [JukeboxAudioEngine.close]
    // cancels this so a still-in-flight preload doesn't leak a coroutine
    // after teardown. The previous code used GlobalScope.launch which
    // survives close().
    private val engineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Initialize AudioContext on first user gesture.
     *
     * ## Valid Call Sites
     * This function **must** be called from a user gesture context (click/touch handler)
     * to satisfy browser autoplay policy. Valid call sites:
     * - [JukeboxAudioEngine] jukeboxPlay() entry point (existing)
     * - Any user gesture handler in the jukebox UI
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
     * still on the call stack, otherwise strict autoplay policies (Safari iOS,
     * some embedded WebViews) leave the context `suspended` and audio is
     * silent.
     *
     * After this returns, [initChannels] can run in a coroutine — those calls
     * (createGain, connect, AudioChannelMaster construction) are not
     * user-gesture-gated.
     */
    fun initContext()
    {
        if (isInitialized) return
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
    }

    /**
     * Asynchronous half of init. Safe to call from a coroutine — runs after
     * [initContext] has created the AudioContext within a user gesture.
     * Sets up the default channel hierarchy and starts the tick loop.
     */
    suspend fun initChannels()
    {
        if (isInitialized) return
        // F11: defensive guard. If a future caller invokes initChannels()
        // without first calling initContext(), [ctx] is uninitialized and
        // the calls below would throw UninitializedPropertyAccessException.
        // We log and return rather than crashing.
        if (!::ctx.isInitialized)
        {
            console.warn("AudioEngine.initChannels: ctx not initialized; call initContext() first")
            return
        }
        // parentId field wires the AudioChannel hierarchy used by effectiveVolume calculations.
        // Channels must be registered before we can resolve parent references.
        val musicChannel = AudioChannel(id = "Music", name = "Music", parentId = null)
        val sfxChannel = AudioChannel(id = "Sfx", name = "SFX", parentId = "Music")
        channels["Music"] = AudioChannelMaster(musicChannel, ctx.createGain(), null, this)
        channels["Sfx"] = AudioChannelMaster(sfxChannel, ctx.createGain(), channels["Music"], this)

        // Chain channel gains to global gain
        channels["Music"]!!.gainNode.connect(globalGainNode.unsafeCast<AudioNode>())
        channels["Sfx"]!!.gainNode.connect(globalGainNode.unsafeCast<AudioNode>())

        startTickLoop()
        isInitialized = true
    }

    /**
     * @deprecated Kept for source-compatibility; new callers should use
     * [initContext] (synchronous, in user gesture) + [initChannels]
     * (suspend, in coroutine). This wraps both for any caller that
     * pre-dates the split.
     */
    suspend fun init()
    {
        if (isInitialized) return
        initContext()
        initChannels()
    }

    private fun startTickLoop()
    {
        fun tick(time: Double)
        {
            for (player in activePlayers.values)
            {
                player.tick()
            }
            animationFrameId = window.requestAnimationFrame { timestamp -> tick(timestamp) }.toInt()
        }
        animationFrameId = window.requestAnimationFrame { timestamp -> tick(timestamp) }.toInt()
    }

    fun stopTickLoop()
    {
        window.cancelAnimationFrame(animationFrameId)
    }

    /**
     * Cancel the engine's preload scope. Called by
     * [org.ttt.autogenesis.jukebox.JukeboxAudioEngine.close] so a
     * still-in-flight preload coroutine launched by [play] doesn't run
     * against a closed AudioContext. (F10 follow-up: needed because
     * the engine's preload scope is internal and cannot be reached
     * directly from JukeboxAudioEngine.)
     */
    fun cancelEngineScope()
    {
        engineScope.cancel()
    }

    /**
     * Reset the engine to its un-initialized state. Called by
     * [org.ttt.autogenesis.jukebox.JukeboxAudioEngine.close] so that
     * `close()` followed by `initJukebox()` rebuilds from scratch.
     *
     * Does NOT touch the AudioContext, the rAF loop, channels, or
     * active players — the caller is responsible for those (so that
     * the teardown order can be controlled).
     */
    fun reset() {
        isInitialized = false
    }

    // ─── Playback API ───────────────────────────────────────────────────────

    fun play(obj: AudioObject, startWhenSeconds: Double = 0.0)
    {
        if (!isInitialized) return
        val resourceName = obj.resourceName

        if (!bufferCache.containsKey(resourceName))
        {
            // Buffer not cached — queue for playback once preload completes
            pendingPlays.add(obj)
            // F10: use the engine's own scope (cancellable by close()) instead
            // of GlobalScope.launch which would survive close().
            engineScope.launch {
                preloadBuffer(resourceName)
                val ready = pendingPlays.filter { it.resourceName == resourceName }
                pendingPlays.removeAll(ready.toSet())
                for (o in ready) {
                    playFromCache(o, 0.0)
                }
            }
            return
        }

        playFromCache(obj, startWhenSeconds)
    }

    private fun playFromCache(obj: AudioObject, startWhenSeconds: Double)
    {
        val buffer = bufferCache[obj.resourceName] ?: return
        val player = AudioObjectPlayer(obj, buffer, ctx, this)
        activePlayers[obj.id] = player
        player.play(startWhenSeconds)
    }

    fun pause(objectId: String)
    {
        activePlayers[objectId]?.pause()
    }

    fun resume(objectId: String)
    {
        activePlayers[objectId]?.resume()
    }

    fun stop(objectId: String, fadeOutDurationMs: Long = 0)
    {
        // Keep the player in [activePlayers] during a fade-out so the
        // renderer can show the FADING OUT badge. The player removes itself
        // from [activePlayers] in its own [AudioObjectPlayer.cleanup] once
        // the fade-out finishes (or immediately for fadeOutDurationMs=0). For
        // the no-fade case we still want to evict synchronously so the
        // renderer drops the row right away.
        val player = activePlayers[objectId] ?: return
        if (fadeOutDurationMs <= 0) {
            activePlayers.remove(objectId)?.stop(0)
        } else {
            player.stop(fadeOutDurationMs)
        }
    }

    // ─── Parameter changes via AudioParam automation ────────────────────────

    fun setVolume(objectId: String, volume: Float, fadeMs: Long = 0)
    {
        activePlayers[objectId]?.setVolume(volume, fadeMs)
    }

    fun setPanning(objectId: String, panning: Float, fadeMs: Long = 0)
    {
        activePlayers[objectId]?.setPanning(panning, fadeMs)
    }

    fun setSpeed(objectId: String, speed: Float, fadeMs: Long = 0)
    {
        activePlayers[objectId]?.setSpeed(speed, fadeMs)
    }

    // A1 fix: `muted` now defaults to false. The facade method
    // `JukeboxAudioEngine.jukeboxSetChannelVolume` already had a default
    // for this arg, but the engine method did not, so a 2-arg call site
    // compiled against the facade but failed to compile if it reached
    // the engine directly. Align the signatures.
    fun setChannelVolume(channelId: String, volume: Float, muted: Boolean = false)
    {
        channels[channelId]?.applyState(volume, muted)
    }

    fun setChannelMuted(channelId: String, muted: Boolean)
    {
        channels[channelId]?.setMuted(muted)
    }

    fun setGlobalVolume(volume: Float)
    {
        globalVolume = volume
        val now = ctx.currentTime
        globalGainNode.gain.setTargetAtTime(volume, now, 0.01)
    }

    // ─── Server sync ──────────────────────────────────────────────────────
    //
    // Server-sync methods (scheduleFromServer, reconcileWithSnapshot,
    // computeStartWhenSeconds) were removed in F4 — the standalone jukebox
    // does not talk to a game server. The shared `AudioSyncState.serverFrameSize`
    // field in `sharedModel/.../audio/AudioRpc.kt` stays, since it is part of
    // the wire format used by kvisionApp's RPC layer.

    fun getObjectView(id: String): AudioObjectView? = activePlayers[id]

    // ─── Buffer management ─────────────────────────────────────────────────

    suspend fun preloadBuffer(resourceName: String)
    {
        if (bufferCache.containsKey(resourceName)) return
        val buffer = resourceLoader.loadBuffer(resourceName, ctx) ?: run {
            // Preload failed — drain pending plays for this resource so the queue never stalls
            val ready = pendingPlays.filter { it.resourceName == resourceName }
            pendingPlays.removeAll(ready.toSet())
            return
        }
        bufferCache[resourceName] = buffer
    }

    fun getChannelMaster(channelId: String): AudioChannelMaster? = channels[channelId]
}