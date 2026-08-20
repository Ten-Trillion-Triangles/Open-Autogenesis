package org.ttt.autogenesis.kvisionapp.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract for [AudioResourceLoader.resolvePath].
 *
 * The manifest is keyed by short names (`"music.menu"`,
 * `"sfx.click"`, …) and the value for each is the list of webpack
 * import paths the loader should try, in format-preference order
 * (mp3 > aac > ogg > wav). The music pipeline passes
 * `MusicResourceResolver`-resolved paths (e.g.
 * `"audio/music/Xilaron and Eleuryiyidict wet final.mp3"`) through
 * this function, so the resolver must recognise the path as a
 * value of some manifest entry, not just the manifest key.
 *
 * ## Bug this pins down
 *
 * The pre-fix [AudioResourceLoader.resolvePath] only matched the
 * `name` argument against manifest keys, falling back to
 * underscore-stripped, prefix-match, and parent-prefix lookups —
 * none of which can ever match a full webpack path. The result was
 * that the menu track and every other
 * `MusicResourceResolver`-resolved resource was dropped at
 * `loadBufferImpl` with the "no audio file matches" warning, and
 * the audio engine never cached a buffer for it.
 *
 * The contract tests below codify the post-fix expectations so
 * the regression cannot reappear silently.
 */
class AudioResourceLoaderTest
{
    private val loader = AudioResourceLoader()

    // ─── Manifest-key lookups (the original behaviour) ────────────────────

    @Test
    fun resolvePath_manifestKeyExact_returnsPathList()
    {
        // The canonical lookup: the music runner / engine should be
        // able to pass `"music.menu"` (the manifest key) and get the
        // same list the manifest defines.
        val paths = loader.resolvePath("music.menu")
        assertNotNull(paths, "manifest key 'music.menu' must resolve to a non-null list")
        assertEquals(4, paths.size, "music.menu should advertise 4 format fallbacks (mp3/aac/ogg/wav)")
        assertTrue(
            paths.first().endsWith(".mp3"),
            "first format preference must be mp3 (per the class doc); got '${paths.first()}'"
        )
    }

    @Test
    fun resolvePath_manifestKeyForSfx_returnsSfxPathList()
    {
        val paths = loader.resolvePath("sfx.click")
        assertNotNull(paths, "sfx.click must resolve")
        assertTrue(paths.any { it.contains("sfx") }, "sfx paths should be in the sfx folder")
    }

    // ─── Path-value lookups (the bug fix) ─────────────────────────────────

    @Test
    fun resolvePath_fullWebpackPath_returnsPathList()
    {
        // The MusicResourceResolver turns the bare catalog name
        // "Xilaron and Eleuryiyidict wet final" into the full webpack
        // path "audio/music/Xilaron and Eleuryiyidict wet final.mp3".
        // The audio engine then passes THAT string into
        // AudioResourceLoader.loadBuffer, which calls
        // AudioResourceLoader.resolvePath on it. The pre-fix
        // resolver returned null because it only ever looked at
        // manifest keys, not path values.
        val paths = loader.resolvePath("audio/music/Xilaron and Eleuryiyidict wet final.mp3")
        assertNotNull(
            paths,
            "full webpack path 'audio/music/Xilaron and Eleuryiyidict wet final.mp3' must resolve " +
            "(this is the menu-track path produced by MusicResourceResolver; the pre-fix resolver returned null)"
        )
        assertEquals(4, paths.size, "menu track should still have 4 format fallbacks")
    }

    @Test
    fun resolvePath_sfxFullPath_returnsPathList()
    {
        val paths = loader.resolvePath("audio/sfx/click.mp3")
        assertNotNull(paths, "full sfx path must resolve to a manifest value")
    }

    // ─── Negative cases ───────────────────────────────────────────────────

    @Test
    fun resolvePath_completelyUnknownName_returnsNull()
    {
        // Names that are neither manifest keys nor values must
        // still return null so the engine can surface a clear
        // "no audio file matches" warning.
        val paths = loader.resolvePath("this-resource-does-not-exist-anywhere.mp3")
        assertNull(paths, "a name that exists in neither the keyset nor the value-set must return null")
    }
}