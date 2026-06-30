package ui.gameplay

import kotlinx.browser.localStorage
import org.ttt.autogenesis.audio.AudioChannelIds
import org.ttt.autogenesis.kvisionapp.audio.AudioEngine
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

/**
 * Narrow test-friendly view of the audio engine.
 *
 * [AudioSettings.applyPersistedToEngine] only needs `isReady` and
 * `setChannelVolume`, so the production code depends on this
 * interface rather than the full [AudioEngine] object. The unit
 * tests in [AudioSettingsTest] pass a recording fake that implements
 * this interface, sidestepping the need to spin up a real
 * AudioContext in a headless browser.
 */
internal interface AudioEngineLike
{
    val isReady: Boolean
    fun setChannelVolume(channelId: String, volume: Float, muted: Boolean)
}

/**
 * Single source of truth for the persisted audio settings read by
 * the options-menu sliders ([SettingsWidget]) and pushed into the
 * audio engine on first play.
 *
 * ## Why this lives outside [SettingsWidget]
 *
 * The MainMenu init path needs to push the same persisted volumes
 * into the engine before the first menu note plays — the options
 * menu does not exist yet at that point. Pulling the localStorage
 * key strings, defaults, and read/write helpers into their own
 * object lets MainMenu read the same values the widget writes,
 * without depending on the widget class itself.
 *
 * ## Channel cascade
 *
 * The persisted value is a 0..100 integer that maps linearly to
 * the engine's 0.0..1.0 range. The engine's channel tree
 * (Music → Drone/Melody/Rhythm/Harmony/Menu/Start/Nemesis/End;
 * Sfx as an independent root) multiplies the change down via
 * [org.ttt.autogenesis.kvisionapp.audio.AudioChannelMaster.effectiveVolume],
 * so 50% on the Music slider scales every active music player to
 * half volume. There is no third "master" slider — the
 * [AudioEngine.globalVolume] stays at `1.0f`.
 *
 * ## Persistence contract
 *
 * - Key names are stable across releases so users who set a
 *   preference before this refactor keep it afterwards.
 * - Missing / unparseable values fall back to the documented
 *   defaults ([DEFAULT_MUSIC]=50, [DEFAULT_SFX]=75). The defaults
 *   are intentionally the same values [SettingsWidget] used before
 *   this refactor.
 */
object AudioSettings
{
    /**
     * The localStorage key under which the Music volume (0..100)
     * is persisted. Stable; do not rename without a migration.
     */
    const val MUSIC_VOLUME_KEY: String = "gameSettings_musicVolume"

    /**
     * The localStorage key under which the SFX volume (0..100)
     * is persisted. Stable; do not rename without a migration.
     */
    const val SFX_VOLUME_KEY: String = "gameSettings_sfxVolume"

    /** Default Music volume (percent) used when nothing is persisted yet. */
    const val DEFAULT_MUSIC: Int = 50

    /** Default SFX volume (percent) used when nothing is persisted yet. */
    const val DEFAULT_SFX: Int = 75

    /**
     * @return the persisted Music volume as a 0..100 integer, or
     *   [DEFAULT_MUSIC] when the key is missing or unparseable.
     */
    fun musicPercent(): Int
    {
        return localStorage.getItem(MUSIC_VOLUME_KEY)?.toIntOrNull() ?: DEFAULT_MUSIC
    }

    /**
     * @return the persisted SFX volume as a 0..100 integer, or
     *   [DEFAULT_SFX] when the key is missing or unparseable.
     */
    fun sfxPercent(): Int
    {
        return localStorage.getItem(SFX_VOLUME_KEY)?.toIntOrNull() ?: DEFAULT_SFX
    }

    /**
     * Persist a new Music volume. The value is stored verbatim
     * without range-clamping so the caller (a slider with `min=0
     * max=100 step=1`) controls bounds; out-of-range values would
     * still round to a valid engine input (the engine clamps
     * negative or >1 inputs via `setTargetAtTime`).
     */
    fun saveMusicPercent(percent: Int)
    {
        localStorage.setItem(MUSIC_VOLUME_KEY, percent.toString())
    }

    /**
     * Persist a new SFX volume. See [saveMusicPercent] for the
     * range-clamping contract.
     */
    fun saveSfxPercent(percent: Int)
    {
        localStorage.setItem(SFX_VOLUME_KEY, percent.toString())
    }

    /**
     * Push the persisted Music + SFX volumes to the live audio
     * engine. No-op when the engine isn't ready (the engine's own
     * [AudioEngine.setChannelVolume] logs a WARN and returns for
     * unknown channel ids, and the channel tree isn't built until
     * [AudioEngine.initChannels] completes).
     *
     * Call sites:
     *  - [MainMenu][ui.MainMenu] init, after [AudioEngine.awaitReady]
     *    and before [org.ttt.autogenesis.kvisionapp.audio.MenuMusicPlayer.start],
     *    so the first menu note plays at the user's chosen volume.
     *  - [SettingsWidget.show], as a belt-and-suspenders re-apply
     *    for any code path that constructs the widget after the
     *    engine is already live.
     */
    fun applyPersistedToEngine()
    {
        applyTo(AudioEngine)
    }

    /**
     * Test-friendly overload: push the persisted values via the
     * provided [engine] instead of the global [AudioEngine]. Used
     * by the unit tests so they can assert the calls without
     * having to spin up a real AudioContext.
     *
     * @param engine the engine-like target to push volumes into.
     *   The production code uses [AudioEngine]; tests use a
     *   recording fake.
     */
    internal fun applyTo(engine: AudioEngineLike)
    {
        if (!engine.isReady)
        {
            Logger.debug(
                LogCategory.UI,
                "AudioSettings.applyTo: engine not ready, skipping push " +
                "(music=${musicPercent()}%, sfx=${sfxPercent()}%)"
            )
            return
        }
        val musicVolumeFloat = musicPercent() / 100f
        val sfxVolumeFloat = sfxPercent() / 100f
        Logger.debug(
            LogCategory.UI,
            "AudioSettings.applyTo: pushing music=${musicVolumeFloat} " +
            "sfx=${sfxVolumeFloat} to engine"
        )
        engine.setChannelVolume(AudioChannelIds.MUSIC_MASTER_ID, musicVolumeFloat, false)
        engine.setChannelVolume(AudioChannelIds.SFX_CHANNEL_ID, sfxVolumeFloat, false)
    }
}
