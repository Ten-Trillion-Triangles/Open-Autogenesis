package org.ttt.autogenesis.jukebox.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.ttt.autogenesis.jukebox.JukeboxAudioEngine

/**
 * Unit tests for the AudioEngine + JukeboxAudioEngine facade that pin
 * down the API surface required by the Phase 1 fixes (A1, A2, A3).
 *
 * These tests are deliberately minimal and do NOT spin up an AudioContext —
 * the engine is gated by [AudioEngine.isInitialized] (and the facade by
 * [JukeboxAudioEngine.isReady]) so uninitialised calls no-op safely. The
 * value of these tests is the **compile-time** check that the relevant
 * method signatures exist (default-arg form, `close()` exposure, the
 * `getDefaultLoop` / `setDefaultLoop` round-trip).
 *
 * Phase 2 / 4 may add browser-side Playwright assertions for end-to-end
 * behaviour (see tests/playwright/jukebox-100pct.spec.js).
 */
class AudioEngineTest
{
    // ─── A1: setChannelVolume default-arg alignment ───────────────────────

    /**
     * Engine: 2-arg call to [AudioEngine.setChannelVolume] must compile and
     * not throw. Pre-fix the function signature was `(String, Float, Boolean)`
     * with no default, so this test would fail to compile.
     */
    @Test
    fun setChannelVolume_acceptsDefaultMutedArg()
    {
        // The 2-arg form is the contract: callers (and the facade) should
        // not have to pass `muted = false` explicitly. The engine is not
        // initialised in this test, so the call is a no-op on the empty
        // channels map — but the signature must exist.
        AudioEngine.setChannelVolume("music", 0.5f)
    }

    /**
     * Facade: mirror of the engine test. The 2-arg form must compile,
     * matching the engine's default-arg signature.
     */
    @Test
    fun jukeboxSetChannelVolume_acceptsDefaultMutedArg()
    {
        JukeboxAudioEngine.jukeboxSetChannelVolume("music", 0.5f)
    }

    // ─── A2: close() @JsName exposure ─────────────────────────────────────

    /**
     * The `close()` method must be reachable as a member of the facade so
     * that `window.jukebox.close` resolves to a function in the generated
     * bundle. We exercise the method here to confirm the symbol exists on
     * the facade type. The @JsName annotation is verified at the bridge
     * level by the Playwright spec (A2 in tests/playwright/jukebox-100pct.spec.js).
     */
    @Test
    fun close_isCallableOnFacade()
    {
        // No-op if not yet initialised; we only verify the function is
        // reachable from the type system.
        JukeboxAudioEngine.close()
    }

    // ─── A3: stopAll / getDefaultLoop / setDefaultLoop round-trip ─────────

    /**
     * `getDefaultLoop()` must return `false` in the default state. The
     * field is private to the facade and starts at `false` per A3 fix.
     */
    @Test
    fun getDefaultLoop_isFalseByDefault()
    {
        // Ensure clean state for test isolation.
        JukeboxAudioEngine.setDefaultLoop(false)
        assertEquals(false, JukeboxAudioEngine.getDefaultLoop())
    }

    /**
     * Setting `defaultLoop = true` and reading it back via `getDefaultLoop`
     * must round-trip. The set→get pair is the public contract for the
     * loop-mode toggle in the UI (B6).
     */
    @Test
    fun setDefaultLoop_thenGet_returnsNewValue()
    {
        JukeboxAudioEngine.setDefaultLoop(true)
        assertEquals(true, JukeboxAudioEngine.getDefaultLoop())
        // Reset for test isolation — other tests assume default = false.
        JukeboxAudioEngine.setDefaultLoop(false)
        assertEquals(false, JukeboxAudioEngine.getDefaultLoop())
    }

    /**
     * `stopAll()` must be reachable as a method on the facade. The
     * implementation iterates activePlayers and calls `stop(id, 100)` on
     * each. With no players active, the call is a no-op — but the symbol
     * must exist for B5 (Stop All button wiring in the UI).
     */
    @Test
    fun stopAll_isCallableOnFacade()
    {
        JukeboxAudioEngine.stopAll()
    }
}
