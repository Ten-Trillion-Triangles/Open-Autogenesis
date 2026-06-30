package ui.gameplay

import kotlinx.browser.localStorage
import org.ttt.autogenesis.audio.AudioChannelIds
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Contract for [AudioSettings].
 *
 * The widget is the user-facing side of the audio-settings feature,
 * but [AudioSettings] is the actual data layer (localStorage keys,
 * defaults, and the engine-push helper). The contract for
 * `applyPersistedToEngine` is the part most likely to silently
 * regress — if the volume is no longer pushed to the engine on
 * startup, the first menu note is at default volume and the user
 * has to open the settings panel to fix it. These tests pin down:
 *
 *  1. The "engine not ready" path is a no-op (the [ui.MainMenu] init
 *     site is gated on [org.ttt.autogenesis.kvisionapp.audio.AudioEngine.awaitReady]
 *     in practice, but the helper itself must be defensive).
 *  2. The "engine ready" path calls `setChannelVolume` exactly once
 *     per channel with the persisted value normalised to 0.0..1.0.
 *  3. The localStorage defaults documented in the AudioSettings KDoc
 *     are honoured when nothing is persisted yet.
 *  4. The function is safe to call repeatedly (idempotent at the
 *     helper level — the engine itself deduplicates via the
 *     `currentVolume` field in [org.ttt.autogenesis.kvisionapp.audio.AudioChannelMaster]).
 *  5. The read API round-trips through localStorage.
 *
 * The helper uses an [AudioEngineLike] injection point so the tests
 * don't need a real AudioContext — a recording fake is enough to
 * assert the call shape.
 */
class AudioSettingsTest
{
    /**
     * Recording stand-in for [AudioEngineLike]. Captures every
     * `setChannelVolume` call so the tests can assert against them
     * without spinning up a real AudioContext.
     *
     * The fake's [isReady] is configurable per test so the same
     * recording works for both branches.
     */
    private class FakeEngine(
        override val isReady: Boolean
    ) : AudioEngineLike
    {
        data class VolumeCall(val channelId: String, val volume: Float, val muted: Boolean)

        val calls: MutableList<VolumeCall> = mutableListOf()

        override fun setChannelVolume(channelId: String, volume: Float, muted: Boolean)
        {
            calls.add(VolumeCall(channelId, volume, muted))
        }
    }

    @BeforeTest
    fun clearLocalStorage()
    {
        // The jsTest runtime is karma + Chrome — localStorage is the
        // same shared global the production code reads, so we must
        // scrub it between tests to keep the assertions hermetic.
        localStorage.removeItem(AudioSettings.MUSIC_VOLUME_KEY)
        localStorage.removeItem(AudioSettings.SFX_VOLUME_KEY)
    }

    @AfterTest
    fun clearLocalStorageAfter()
    {
        localStorage.removeItem(AudioSettings.MUSIC_VOLUME_KEY)
        localStorage.removeItem(AudioSettings.SFX_VOLUME_KEY)
    }

    // ─── applyPersistedToEngine — engine not ready ───────────────────────

    @Test
    fun applyPersistedToEngine_whenEngineNotReady_isNoOp()
    {
        // Persist values that WOULD have been pushed if the engine
        // were ready — the test is asserting that the gate fires
        // before the volume normalisation, not that it gates on
        // the localStorage being empty.
        localStorage.setItem(AudioSettings.MUSIC_VOLUME_KEY, "30")
        localStorage.setItem(AudioSettings.SFX_VOLUME_KEY, "60")
        val engine = FakeEngine(isReady = false)

        AudioSettings.applyTo(engine)

        assertEquals(
            0,
            engine.calls.size,
            "applyTo must not call setChannelVolume when the engine reports isReady=false"
        )
    }

    // ─── applyPersistedToEngine — engine ready, both channels pushed ─────

    @Test
    fun applyPersistedToEngine_whenEngineReady_callsSetChannelVolumeForBothChannels()
    {
        localStorage.setItem(AudioSettings.MUSIC_VOLUME_KEY, "30")
        localStorage.setItem(AudioSettings.SFX_VOLUME_KEY, "60")
        val engine = FakeEngine(isReady = true)

        AudioSettings.applyTo(engine)

        // Exactly two calls — one for Music, one for Sfx, in that order.
        assertEquals(2, engine.calls.size, "expected one call per channel (Music + Sfx)")
        val musicCall = engine.calls[0]
        assertEquals(AudioChannelIds.MUSIC_MASTER_ID, musicCall.channelId)
        assertEquals(0.30f, musicCall.volume, 0.0001f, "30% must normalise to 0.30")
        assertEquals(false, musicCall.muted, "the slider does not own a mute toggle — always pass false")
        val sfxCall = engine.calls[1]
        assertEquals(AudioChannelIds.SFX_CHANNEL_ID, sfxCall.channelId)
        assertEquals(0.60f, sfxCall.volume, 0.0001f, "60% must normalise to 0.60")
        assertEquals(false, sfxCall.muted)
    }

    // ─── applyPersistedToEngine — empty localStorage falls back to defaults

    @Test
    fun applyPersistedToEngine_whenLocalStorageEmpty_usesDocumentedDefaults()
    {
        // The BeforeTest cleared the keys, so this is the "fresh
        // user" case: nothing has been persisted, defaults must
        // apply (Music=50 → 0.50, Sfx=75 → 0.75).
        val engine = FakeEngine(isReady = true)

        AudioSettings.applyTo(engine)

        assertEquals(2, engine.calls.size)
        val musicCall = engine.calls.first { it.channelId == AudioChannelIds.MUSIC_MASTER_ID }
        assertEquals(
            AudioSettings.DEFAULT_MUSIC / 100f,
            musicCall.volume,
            0.0001f,
            "Music default of 50% must normalise to ${AudioSettings.DEFAULT_MUSIC / 100f}"
        )
        val sfxCall = engine.calls.first { it.channelId == AudioChannelIds.SFX_CHANNEL_ID }
        assertEquals(
            AudioSettings.DEFAULT_SFX / 100f,
            sfxCall.volume,
            0.0001f,
            "Sfx default of 75% must normalise to ${AudioSettings.DEFAULT_SFX / 100f}"
        )
    }

    // ─── applyPersistedToEngine — idempotent ─────────────────────────────

    @Test
    fun applyPersistedToEngine_isIdempotent()
    {
        localStorage.setItem(AudioSettings.MUSIC_VOLUME_KEY, "42")
        localStorage.setItem(AudioSettings.SFX_VOLUME_KEY, "99")
        val engine = FakeEngine(isReady = true)

        AudioSettings.applyTo(engine)
        val firstCallCount = engine.calls.size
        val firstMusic = engine.calls.first { it.channelId == AudioChannelIds.MUSIC_MASTER_ID }
        val firstSfx = engine.calls.first { it.channelId == AudioChannelIds.SFX_CHANNEL_ID }

        // The settings widget's show() re-calls applyPersistedToEngine
        // as a belt-and-suspenders push; verify the helper itself is
        // safe to call repeatedly (the engine's AudioChannelMaster
        // dedups at the gain level, but the helper must always push
        // both channels regardless).
        AudioSettings.applyTo(engine)

        assertEquals(
            firstCallCount * 2,
            engine.calls.size,
            "a second applyTo must push both channels again (no dedup at the helper layer)"
        )
        val secondMusic = engine.calls
            .drop(firstCallCount)
            .first { it.channelId == AudioChannelIds.MUSIC_MASTER_ID }
        val secondSfx = engine.calls
            .drop(firstCallCount)
            .first { it.channelId == AudioChannelIds.SFX_CHANNEL_ID }
        assertEquals(
            firstMusic.volume,
            secondMusic.volume,
            0.0001f,
            "second Music call must push the same volume (idempotent value)"
        )
        assertEquals(
            firstSfx.volume,
            secondSfx.volume,
            0.0001f,
            "second Sfx call must push the same volume (idempotent value)"
        )
    }

    // ─── musicPercent / sfxPercent round-trip through localStorage ───────

    @Test
    fun musicPercent_and_sfxPercent_roundTripThroughLocalStorage()
    {
        localStorage.setItem(AudioSettings.MUSIC_VOLUME_KEY, "42")
        localStorage.setItem(AudioSettings.SFX_VOLUME_KEY, "99")

        assertEquals(42, AudioSettings.musicPercent())
        assertEquals(99, AudioSettings.sfxPercent())
    }

    // ─── default fall-through (bonus: unparseable values) ────────────────

    @Test
    fun musicPercent_unparseableValue_fallsBackToDefault()
    {
        // A future bug (or a malformed write from a different build)
        // could leave a non-integer in the key. The reader must not
        // throw and must fall back to the documented default.
        localStorage.setItem(AudioSettings.MUSIC_VOLUME_KEY, "not-a-number")
        assertEquals(AudioSettings.DEFAULT_MUSIC, AudioSettings.musicPercent())
    }

    @Test
    fun sfxPercent_unparseableValue_fallsBackToDefault()
    {
        localStorage.setItem(AudioSettings.SFX_VOLUME_KEY, "")
        assertEquals(AudioSettings.DEFAULT_SFX, AudioSettings.sfxPercent())
    }

    // ─── save* round-trip ────────────────────────────────────────────────

    @Test
    fun saveMusicPercent_then_musicPercent_returnsSavedValue()
    {
        AudioSettings.saveMusicPercent(73)
        assertEquals(73, AudioSettings.musicPercent())
        assertNotNull(localStorage.getItem(AudioSettings.MUSIC_VOLUME_KEY))
        assertTrue(
            localStorage.getItem(AudioSettings.MUSIC_VOLUME_KEY)!!.toInt() == 73,
            "saveMusicPercent must store the integer verbatim under the documented key"
        )
    }

    @Test
    fun saveSfxPercent_then_sfxPercent_returnsSavedValue()
    {
        AudioSettings.saveSfxPercent(11)
        assertEquals(11, AudioSettings.sfxPercent())
    }
}
