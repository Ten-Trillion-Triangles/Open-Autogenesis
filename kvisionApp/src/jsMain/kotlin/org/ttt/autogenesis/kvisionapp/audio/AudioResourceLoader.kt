package org.ttt.autogenesis.kvisionapp.audio

import kotlinx.browser.window
import kotlinx.coroutines.await
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import kotlin.js.Promise
import org.w3c.fetch.Response

/**
 * Convert a webpack public-path relative resource path to a fully
 * qualified URL the dev server (or production CDN) will serve.
 *
 * The loader used to call `import(path)` and read `module.default` to
 * get the webpack-emitted chunk URL. That pattern only works for
 * files webpack sees at compile time, and the audio loader's paths
 * are runtime-discovered (the [MusicResourceResolver] and the
 * manifest both build paths from the designer's catalog strings at
 * runtime). webpack cannot bundle those, so every `import()` failed
 * with "Failed to fetch dynamically imported module" (the runtime
 * `import()` reached the dev server, but webpack had not produced a
 * chunk for that runtime path).
 *
 * The fix: assume the path is already a public-URL path. The
 * kotlin/JS multiplatform plugin copies every `resources/audio/`
 * file into `processedResources/js/main/`, the dev server serves
 * them under `/`, and the production webpack build emits them via
 * the `asset/resource` rule in `webpack.config.d/audio-chunks.js`
 * with the same `audio/[name][ext]` generator. We just prepend the
 * current origin so the request is absolute.
 *
 * @param path the public-URL path (e.g. "audio/music/Xilaron and Eleuryiyidict wet final.mp3")
 * @return the absolute URL the dev server / CDN will serve
 */
internal fun publicUrl(path: String): String {
    // Resolve against the document's base URI (NOT just the origin).
    // Electron loads the frontend via mainWindow.loadFile(...), which
    // makes window.location.origin literally "file://"; the audio
    // bundle lives under the document's directory, not under the
    // filesystem root, so origin + path would land on /audio/… and
    // every fetch would 404. document.baseURI gives the proper base
    // for both file:// and http(s):// origins. See the
    // [AudioResourceLoaderFileBaseUrlTest] regression guard.
    val base: String = js(
        "(typeof document !== 'undefined' && document.baseURI) || " +
        "(typeof window !== 'undefined' && window.location && window.location.origin) || ''"
    )
    return publicUrlForBase(base, path)
}

/**
 * Pure URL constructor used by [publicUrl] and exercised directly by
 * the jsTest suite. Takes the base URI (typically `document.baseURI`)
 * and the webpack public-path-relative audio path, and returns the
 * absolute URL the dev server / CDN / Electron renderer should fetch.
 *
 * Implemented with the browser's `new URL(path, base)` constructor so
 * every browser-handled case Just Works:
 *   - trailing-slash vs no-trailing-slash on the base
 *   - filename in the base (e.g. `/index.html`) — the constructor
 *     resolves the relative path against the *directory* of the base,
 *     so `new URL("audio/foo.mp3", "file:///…/frontend/index.html")`
 *     yields `file:///…/frontend/audio/foo.mp3` — exactly what Electron
 *     needs.
 *   - percent-encoding of path segments (spaces, etc.) so the
 *     manifest's literal-space filenames (e.g. "Xilaron and
 *     Eleuryiyidict wet final.mp3") produce well-formed URLs in
 *     every environment.
 *   - the `file://` case in Electron: there is no implicit "/"
 *     mount in `file://`, so naive `origin + "/" + path`
 *     concatenation lands on the filesystem root. The URL
 *     constructor handles this correctly.
 */
internal fun publicUrlForBase(base: String, path: String): String {
    // Graceful degradation: `new URL` throws TypeError on an empty
    // base. In practice `publicUrl` always supplies a non-empty base
    // (it falls back through document.baseURI → location.origin → ''),
    // but a defensive guard here keeps the function safe to call
    // directly (e.g. from tests) with no base at all.
    if (base.isEmpty()) return path
    val result: String = js(
        "new URL(path, base).href"
    )
    return result
}

/**
 * Loads audio files via the dev server's `processedResources` mount
 * (or the production webpack `asset/resource` emission). Maintains a
 * manifest of resource name → list of public-URL paths in preferred
 * format order. Format preference: mp3 > aac > ogg > wav.
 */
class AudioResourceLoader {
    private val log = Logger
    private val manifest: Map<String, List<String>> = buildManifest()

    init {
        log.info(
            LogCategory.SYSTEM,
            "AudioResourceLoader: initialized with ${manifest.size} resource entries in the manifest " +
            "(${manifest.keys.joinToString()})"
        )
    }

    private fun buildManifest(): Map<String, List<String>> {
        return mapOf(
            "music.ambient.forest" to listOf(
                "audio/music/ambient_forest.mp3",
                "audio/music/ambient_forest.aac",
                "audio/music/ambient_forest.ogg",
                "audio/music/ambient_forest.wav"
            ),
            "music.battle.theme" to listOf(
                "audio/music/battle_theme.mp3",
                "audio/music/battle_theme.aac",
                "audio/music/battle_theme.ogg",
                "audio/music/battle_theme.wav"
            ),
            "music.menu" to listOf(
                // The editor's main-menu track. `MenuMusicPlayer` builds
                // an AudioObject with `resourceName = "Xilaron and
                // Eleuryiyidict wet final"`, which is what the
                // `AudioResourceLoader` manifest here is keyed under.
                // Other format fallbacks are kept for portability but the
                // only file we ship today is the MP3.
                "audio/music/Xilaron and Eleuryiyidict wet final.mp3",
                "audio/music/Xilaron and Eleuryiyidict wet final.aac",
                "audio/music/Xilaron and Eleuryiyidict wet final.ogg",
                "audio/music/Xilaron and Eleuryiyidict wet final.wav"
            ),
            "sfx.click" to listOf(
                "audio/sfx/click.mp3",
                "audio/sfx/click.aac",
                "audio/sfx/click.ogg",
                "audio/sfx/click.wav"
            ),
            "sfx.explosion" to listOf(
                "audio/sfx/explosion.mp3",
                "audio/sfx/explosion.aac",
                "audio/sfx/explosion.ogg",
                "audio/sfx/explosion.wav"
            ),
            "sfx.gunshot" to listOf(
                "audio/sfx/gunshot.mp3",
                "audio/sfx/gunshot.aac",
                "audio/sfx/gunshot.ogg",
                "audio/sfx/gunshot.wav"
            ),
            "sfx.footstep" to listOf(
                "audio/sfx/footstep.mp3",
                "audio/sfx/footstep.aac",
                "audio/sfx/footstep.ogg",
                "audio/sfx/footstep.wav"
            ),
                        // ─── Gameplay music tracks ────────────────────────────────────────
            // The 4 scenario tracks (start/nemesis/end/menu) and the 75 layer
            // tracks (drone/melody/rhythm/harmony) shipped under
            // resources/audio/music/. The catalog identifies them by their bare
            // resource name (e.g. "Initial Conditions wet 1", "D-Track 1"),
            // and `MusicResourceResolver` translates that to a webpack public
            // path (e.g. "audio/music/Initial Conditions wet 1.mp3"). The
            // `AudioEngine` then calls `preloadBuffer(resolvedPath)` which lands
            // in `loadBufferImpl`'s REVERSE-VALUE lookup — that lookup needs
            // the resolved path to appear as a value in some manifest entry.
            //
            // The menu track is the only one that was already wired (via the
            // "music.menu" key above). The remaining 78 entries below were
            // generated from `find kvisionApp/src/jsMain/resources/audio
            // -name "*.mp3"`. The keys are the full relative paths (so
            // canonical lookup by path also works), and the values mirror the
            // mp3/aac/ogg/wav fallback chain every other entry uses. Only mp3
            // is shipped today, so the .aac/.ogg/.wav attempts will 404 and
            // the loader falls through to `ALL formats FAILED` — but the mp3
            // attempt on the first value will succeed. If we ever ship aac
            // or ogg variants, the fallback chain lights up automatically.
            "audio/music/D-Tracks/D-Track 1 Retrograde Inversion.mp3" to listOf(
                "audio/music/D-Tracks/D-Track 1 Retrograde Inversion.mp3",
                "audio/music/D-Tracks/D-Track 1 Retrograde Inversion.aac",
                "audio/music/D-Tracks/D-Track 1 Retrograde Inversion.ogg",
                "audio/music/D-Tracks/D-Track 1 Retrograde Inversion.wav"
            ),
            "audio/music/D-Tracks/D-Track 1 Retrograde.mp3" to listOf(
                "audio/music/D-Tracks/D-Track 1 Retrograde.mp3",
                "audio/music/D-Tracks/D-Track 1 Retrograde.aac",
                "audio/music/D-Tracks/D-Track 1 Retrograde.ogg",
                "audio/music/D-Tracks/D-Track 1 Retrograde.wav"
            ),
            "audio/music/D-Tracks/D-Track 1.mp3" to listOf(
                "audio/music/D-Tracks/D-Track 1.mp3",
                "audio/music/D-Tracks/D-Track 1.aac",
                "audio/music/D-Tracks/D-Track 1.ogg",
                "audio/music/D-Tracks/D-Track 1.wav"
            ),
            "audio/music/D-Tracks/D-Track 2 Retrograde Inversion.mp3" to listOf(
                "audio/music/D-Tracks/D-Track 2 Retrograde Inversion.mp3",
                "audio/music/D-Tracks/D-Track 2 Retrograde Inversion.aac",
                "audio/music/D-Tracks/D-Track 2 Retrograde Inversion.ogg",
                "audio/music/D-Tracks/D-Track 2 Retrograde Inversion.wav"
            ),
            "audio/music/D-Tracks/D-Track 2 Retrograde.mp3" to listOf(
                "audio/music/D-Tracks/D-Track 2 Retrograde.mp3",
                "audio/music/D-Tracks/D-Track 2 Retrograde.aac",
                "audio/music/D-Tracks/D-Track 2 Retrograde.ogg",
                "audio/music/D-Tracks/D-Track 2 Retrograde.wav"
            ),
            "audio/music/D-Tracks/D-Track 2.mp3" to listOf(
                "audio/music/D-Tracks/D-Track 2.mp3",
                "audio/music/D-Tracks/D-Track 2.aac",
                "audio/music/D-Tracks/D-Track 2.ogg",
                "audio/music/D-Tracks/D-Track 2.wav"
            ),
            "audio/music/D-Tracks/D-Track 3 Retrograde Inversion.mp3" to listOf(
                "audio/music/D-Tracks/D-Track 3 Retrograde Inversion.mp3",
                "audio/music/D-Tracks/D-Track 3 Retrograde Inversion.aac",
                "audio/music/D-Tracks/D-Track 3 Retrograde Inversion.ogg",
                "audio/music/D-Tracks/D-Track 3 Retrograde Inversion.wav"
            ),
            "audio/music/D-Tracks/D-Track 3 Retrograde.mp3" to listOf(
                "audio/music/D-Tracks/D-Track 3 Retrograde.mp3",
                "audio/music/D-Tracks/D-Track 3 Retrograde.aac",
                "audio/music/D-Tracks/D-Track 3 Retrograde.ogg",
                "audio/music/D-Tracks/D-Track 3 Retrograde.wav"
            ),
            "audio/music/D-Tracks/D-Track 3.mp3" to listOf(
                "audio/music/D-Tracks/D-Track 3.mp3",
                "audio/music/D-Tracks/D-Track 3.aac",
                "audio/music/D-Tracks/D-Track 3.ogg",
                "audio/music/D-Tracks/D-Track 3.wav"
            ),
            "audio/music/D-Tracks/D-Track 4 Retrograde Inversion.mp3" to listOf(
                "audio/music/D-Tracks/D-Track 4 Retrograde Inversion.mp3",
                "audio/music/D-Tracks/D-Track 4 Retrograde Inversion.aac",
                "audio/music/D-Tracks/D-Track 4 Retrograde Inversion.ogg",
                "audio/music/D-Tracks/D-Track 4 Retrograde Inversion.wav"
            ),
            "audio/music/D-Tracks/D-Track 4 Retrograde.mp3" to listOf(
                "audio/music/D-Tracks/D-Track 4 Retrograde.mp3",
                "audio/music/D-Tracks/D-Track 4 Retrograde.aac",
                "audio/music/D-Tracks/D-Track 4 Retrograde.ogg",
                "audio/music/D-Tracks/D-Track 4 Retrograde.wav"
            ),
            "audio/music/D-Tracks/D-Track 4.mp3" to listOf(
                "audio/music/D-Tracks/D-Track 4.mp3",
                "audio/music/D-Tracks/D-Track 4.aac",
                "audio/music/D-Tracks/D-Track 4.ogg",
                "audio/music/D-Tracks/D-Track 4.wav"
            ),
            "audio/music/D-Tracks/D-Track 5 Retrograde.mp3" to listOf(
                "audio/music/D-Tracks/D-Track 5 Retrograde.mp3",
                "audio/music/D-Tracks/D-Track 5 Retrograde.aac",
                "audio/music/D-Tracks/D-Track 5 Retrograde.ogg",
                "audio/music/D-Tracks/D-Track 5 Retrograde.wav"
            ),
            "audio/music/D-Tracks/D-Track 5 Variant Inversion Rotation.mp3" to listOf(
                "audio/music/D-Tracks/D-Track 5 Variant Inversion Rotation.mp3",
                "audio/music/D-Tracks/D-Track 5 Variant Inversion Rotation.aac",
                "audio/music/D-Tracks/D-Track 5 Variant Inversion Rotation.ogg",
                "audio/music/D-Tracks/D-Track 5 Variant Inversion Rotation.wav"
            ),
            "audio/music/D-Tracks/D-Track 5 Variant Inversion.mp3" to listOf(
                "audio/music/D-Tracks/D-Track 5 Variant Inversion.mp3",
                "audio/music/D-Tracks/D-Track 5 Variant Inversion.aac",
                "audio/music/D-Tracks/D-Track 5 Variant Inversion.ogg",
                "audio/music/D-Tracks/D-Track 5 Variant Inversion.wav"
            ),
            "audio/music/D-Tracks/D-Track 5 Variant.mp3" to listOf(
                "audio/music/D-Tracks/D-Track 5 Variant.mp3",
                "audio/music/D-Tracks/D-Track 5 Variant.aac",
                "audio/music/D-Tracks/D-Track 5 Variant.ogg",
                "audio/music/D-Tracks/D-Track 5 Variant.wav"
            ),
            "audio/music/D-Tracks/D-Track 5.mp3" to listOf(
                "audio/music/D-Tracks/D-Track 5.mp3",
                "audio/music/D-Tracks/D-Track 5.aac",
                "audio/music/D-Tracks/D-Track 5.ogg",
                "audio/music/D-Tracks/D-Track 5.wav"
            ),
            "audio/music/Harmony Tracks/Harmony-1 Retrograde Inversion.mp3" to listOf(
                "audio/music/Harmony Tracks/Harmony-1 Retrograde Inversion.mp3",
                "audio/music/Harmony Tracks/Harmony-1 Retrograde Inversion.aac",
                "audio/music/Harmony Tracks/Harmony-1 Retrograde Inversion.ogg",
                "audio/music/Harmony Tracks/Harmony-1 Retrograde Inversion.wav"
            ),
            "audio/music/Harmony Tracks/Harmony-1 Retrograde.mp3" to listOf(
                "audio/music/Harmony Tracks/Harmony-1 Retrograde.mp3",
                "audio/music/Harmony Tracks/Harmony-1 Retrograde.aac",
                "audio/music/Harmony Tracks/Harmony-1 Retrograde.ogg",
                "audio/music/Harmony Tracks/Harmony-1 Retrograde.wav"
            ),
            "audio/music/Harmony Tracks/Harmony-1.mp3" to listOf(
                "audio/music/Harmony Tracks/Harmony-1.mp3",
                "audio/music/Harmony Tracks/Harmony-1.aac",
                "audio/music/Harmony Tracks/Harmony-1.ogg",
                "audio/music/Harmony Tracks/Harmony-1.wav"
            ),
            "audio/music/Harmony Tracks/Harmony-2 Retrograde Inversion.mp3" to listOf(
                "audio/music/Harmony Tracks/Harmony-2 Retrograde Inversion.mp3",
                "audio/music/Harmony Tracks/Harmony-2 Retrograde Inversion.aac",
                "audio/music/Harmony Tracks/Harmony-2 Retrograde Inversion.ogg",
                "audio/music/Harmony Tracks/Harmony-2 Retrograde Inversion.wav"
            ),
            "audio/music/Harmony Tracks/Harmony-2 Retrograde.mp3" to listOf(
                "audio/music/Harmony Tracks/Harmony-2 Retrograde.mp3",
                "audio/music/Harmony Tracks/Harmony-2 Retrograde.aac",
                "audio/music/Harmony Tracks/Harmony-2 Retrograde.ogg",
                "audio/music/Harmony Tracks/Harmony-2 Retrograde.wav"
            ),
            "audio/music/Harmony Tracks/Harmony-2.mp3" to listOf(
                "audio/music/Harmony Tracks/Harmony-2.mp3",
                "audio/music/Harmony Tracks/Harmony-2.aac",
                "audio/music/Harmony Tracks/Harmony-2.ogg",
                "audio/music/Harmony Tracks/Harmony-2.wav"
            ),
            "audio/music/Harmony Tracks/Harmony-3 Retrograde Inversion.mp3" to listOf(
                "audio/music/Harmony Tracks/Harmony-3 Retrograde Inversion.mp3",
                "audio/music/Harmony Tracks/Harmony-3 Retrograde Inversion.aac",
                "audio/music/Harmony Tracks/Harmony-3 Retrograde Inversion.ogg",
                "audio/music/Harmony Tracks/Harmony-3 Retrograde Inversion.wav"
            ),
            "audio/music/Harmony Tracks/Harmony-3.mp3" to listOf(
                "audio/music/Harmony Tracks/Harmony-3.mp3",
                "audio/music/Harmony Tracks/Harmony-3.aac",
                "audio/music/Harmony Tracks/Harmony-3.ogg",
                "audio/music/Harmony Tracks/Harmony-3.wav"
            ),
            "audio/music/Harmony Tracks/Harmony-E Retrograde Inversion.mp3" to listOf(
                "audio/music/Harmony Tracks/Harmony-E Retrograde Inversion.mp3",
                "audio/music/Harmony Tracks/Harmony-E Retrograde Inversion.aac",
                "audio/music/Harmony Tracks/Harmony-E Retrograde Inversion.ogg",
                "audio/music/Harmony Tracks/Harmony-E Retrograde Inversion.wav"
            ),
            "audio/music/Harmony Tracks/Harmony-E Retrograde.mp3" to listOf(
                "audio/music/Harmony Tracks/Harmony-E Retrograde.mp3",
                "audio/music/Harmony Tracks/Harmony-E Retrograde.aac",
                "audio/music/Harmony Tracks/Harmony-E Retrograde.ogg",
                "audio/music/Harmony Tracks/Harmony-E Retrograde.wav"
            ),
            "audio/music/Harmony Tracks/Harmony-E.mp3" to listOf(
                "audio/music/Harmony Tracks/Harmony-E.mp3",
                "audio/music/Harmony Tracks/Harmony-E.aac",
                "audio/music/Harmony Tracks/Harmony-E.ogg",
                "audio/music/Harmony Tracks/Harmony-E.wav"
            ),
            "audio/music/Harmony Tracks/Harmony-F Retrograde Inversion.mp3" to listOf(
                "audio/music/Harmony Tracks/Harmony-F Retrograde Inversion.mp3",
                "audio/music/Harmony Tracks/Harmony-F Retrograde Inversion.aac",
                "audio/music/Harmony Tracks/Harmony-F Retrograde Inversion.ogg",
                "audio/music/Harmony Tracks/Harmony-F Retrograde Inversion.wav"
            ),
            "audio/music/Harmony Tracks/Harmony-F Retrograde.mp3" to listOf(
                "audio/music/Harmony Tracks/Harmony-F Retrograde.mp3",
                "audio/music/Harmony Tracks/Harmony-F Retrograde.aac",
                "audio/music/Harmony Tracks/Harmony-F Retrograde.ogg",
                "audio/music/Harmony Tracks/Harmony-F Retrograde.wav"
            ),
            "audio/music/Harmony Tracks/Harmony-F Variant.mp3" to listOf(
                "audio/music/Harmony Tracks/Harmony-F Variant.mp3",
                "audio/music/Harmony Tracks/Harmony-F Variant.aac",
                "audio/music/Harmony Tracks/Harmony-F Variant.ogg",
                "audio/music/Harmony Tracks/Harmony-F Variant.wav"
            ),
            "audio/music/Harmony Tracks/Harmony-F.mp3" to listOf(
                "audio/music/Harmony Tracks/Harmony-F.mp3",
                "audio/music/Harmony Tracks/Harmony-F.aac",
                "audio/music/Harmony Tracks/Harmony-F.ogg",
                "audio/music/Harmony Tracks/Harmony-F.wav"
            ),
            "audio/music/Harmony Tracks/Harmony-R Retrograde Inversion.mp3" to listOf(
                "audio/music/Harmony Tracks/Harmony-R Retrograde Inversion.mp3",
                "audio/music/Harmony Tracks/Harmony-R Retrograde Inversion.aac",
                "audio/music/Harmony Tracks/Harmony-R Retrograde Inversion.ogg",
                "audio/music/Harmony Tracks/Harmony-R Retrograde Inversion.wav"
            ),
            "audio/music/Harmony Tracks/Harmony-R Retrograde.mp3" to listOf(
                "audio/music/Harmony Tracks/Harmony-R Retrograde.mp3",
                "audio/music/Harmony Tracks/Harmony-R Retrograde.aac",
                "audio/music/Harmony Tracks/Harmony-R Retrograde.ogg",
                "audio/music/Harmony Tracks/Harmony-R Retrograde.wav"
            ),
            "audio/music/Harmony Tracks/Harmony-R.mp3" to listOf(
                "audio/music/Harmony Tracks/Harmony-R.mp3",
                "audio/music/Harmony Tracks/Harmony-R.aac",
                "audio/music/Harmony Tracks/Harmony-R.ogg",
                "audio/music/Harmony Tracks/Harmony-R.wav"
            ),
            "audio/music/Harmony Tracks/Harmony-Y Retrograde Inversion.mp3" to listOf(
                "audio/music/Harmony Tracks/Harmony-Y Retrograde Inversion.mp3",
                "audio/music/Harmony Tracks/Harmony-Y Retrograde Inversion.aac",
                "audio/music/Harmony Tracks/Harmony-Y Retrograde Inversion.ogg",
                "audio/music/Harmony Tracks/Harmony-Y Retrograde Inversion.wav"
            ),
            "audio/music/Harmony Tracks/Harmony-Y Retrograde.mp3" to listOf(
                "audio/music/Harmony Tracks/Harmony-Y Retrograde.mp3",
                "audio/music/Harmony Tracks/Harmony-Y Retrograde.aac",
                "audio/music/Harmony Tracks/Harmony-Y Retrograde.ogg",
                "audio/music/Harmony Tracks/Harmony-Y Retrograde.wav"
            ),
            "audio/music/Harmony Tracks/Harmony-Y.mp3" to listOf(
                "audio/music/Harmony Tracks/Harmony-Y.mp3",
                "audio/music/Harmony Tracks/Harmony-Y.aac",
                "audio/music/Harmony Tracks/Harmony-Y.ogg",
                "audio/music/Harmony Tracks/Harmony-Y.wav"
            ),
            "audio/music/Initial Conditions wet 1.mp3" to listOf(
                "audio/music/Initial Conditions wet 1.mp3",
                "audio/music/Initial Conditions wet 1.aac",
                "audio/music/Initial Conditions wet 1.ogg",
                "audio/music/Initial Conditions wet 1.wav"
            ),
            "audio/music/Melody Tracks/Melody-Etnahta Retrograde Inversion.mp3" to listOf(
                "audio/music/Melody Tracks/Melody-Etnahta Retrograde Inversion.mp3",
                "audio/music/Melody Tracks/Melody-Etnahta Retrograde Inversion.aac",
                "audio/music/Melody Tracks/Melody-Etnahta Retrograde Inversion.ogg",
                "audio/music/Melody Tracks/Melody-Etnahta Retrograde Inversion.wav"
            ),
            "audio/music/Melody Tracks/Melody-Etnahta Retrograde.mp3" to listOf(
                "audio/music/Melody Tracks/Melody-Etnahta Retrograde.mp3",
                "audio/music/Melody Tracks/Melody-Etnahta Retrograde.aac",
                "audio/music/Melody Tracks/Melody-Etnahta Retrograde.ogg",
                "audio/music/Melody Tracks/Melody-Etnahta Retrograde.wav"
            ),
            "audio/music/Melody Tracks/Melody-Etnahta.mp3" to listOf(
                "audio/music/Melody Tracks/Melody-Etnahta.mp3",
                "audio/music/Melody Tracks/Melody-Etnahta.aac",
                "audio/music/Melody Tracks/Melody-Etnahta.ogg",
                "audio/music/Melody Tracks/Melody-Etnahta.wav"
            ),
            "audio/music/Melody Tracks/Melody-Mayela and Khefulah Retrograde Inversion.mp3" to listOf(
                "audio/music/Melody Tracks/Melody-Mayela and Khefulah Retrograde Inversion.mp3",
                "audio/music/Melody Tracks/Melody-Mayela and Khefulah Retrograde Inversion.aac",
                "audio/music/Melody Tracks/Melody-Mayela and Khefulah Retrograde Inversion.ogg",
                "audio/music/Melody Tracks/Melody-Mayela and Khefulah Retrograde Inversion.wav"
            ),
            "audio/music/Melody Tracks/Melody-Mayela and Khefulah Retrograde.mp3" to listOf(
                "audio/music/Melody Tracks/Melody-Mayela and Khefulah Retrograde.mp3",
                "audio/music/Melody Tracks/Melody-Mayela and Khefulah Retrograde.aac",
                "audio/music/Melody Tracks/Melody-Mayela and Khefulah Retrograde.ogg",
                "audio/music/Melody Tracks/Melody-Mayela and Khefulah Retrograde.wav"
            ),
            "audio/music/Melody Tracks/Melody-Mayela and Khefulah.mp3" to listOf(
                "audio/music/Melody Tracks/Melody-Mayela and Khefulah.mp3",
                "audio/music/Melody Tracks/Melody-Mayela and Khefulah.aac",
                "audio/music/Melody Tracks/Melody-Mayela and Khefulah.ogg",
                "audio/music/Melody Tracks/Melody-Mayela and Khefulah.wav"
            ),
            "audio/music/Melody Tracks/Melody-Pashta Inversion.mp3" to listOf(
                "audio/music/Melody Tracks/Melody-Pashta Inversion.mp3",
                "audio/music/Melody Tracks/Melody-Pashta Inversion.aac",
                "audio/music/Melody Tracks/Melody-Pashta Inversion.ogg",
                "audio/music/Melody Tracks/Melody-Pashta Inversion.wav"
            ),
            "audio/music/Melody Tracks/Melody-Pashta.mp3" to listOf(
                "audio/music/Melody Tracks/Melody-Pashta.mp3",
                "audio/music/Melody Tracks/Melody-Pashta.aac",
                "audio/music/Melody Tracks/Melody-Pashta.ogg",
                "audio/music/Melody Tracks/Melody-Pashta.wav"
            ),
            "audio/music/Melody Tracks/Melody-Shalshelet Inversion.mp3" to listOf(
                "audio/music/Melody Tracks/Melody-Shalshelet Inversion.mp3",
                "audio/music/Melody Tracks/Melody-Shalshelet Inversion.aac",
                "audio/music/Melody Tracks/Melody-Shalshelet Inversion.ogg",
                "audio/music/Melody Tracks/Melody-Shalshelet Inversion.wav"
            ),
            "audio/music/Melody Tracks/Melody-Shalshelet.mp3" to listOf(
                "audio/music/Melody Tracks/Melody-Shalshelet.mp3",
                "audio/music/Melody Tracks/Melody-Shalshelet.aac",
                "audio/music/Melody Tracks/Melody-Shalshelet.ogg",
                "audio/music/Melody Tracks/Melody-Shalshelet.wav"
            ),
            "audio/music/Melody Tracks/Melody-Siluk Retrograde Inversion.mp3" to listOf(
                "audio/music/Melody Tracks/Melody-Siluk Retrograde Inversion.mp3",
                "audio/music/Melody Tracks/Melody-Siluk Retrograde Inversion.aac",
                "audio/music/Melody Tracks/Melody-Siluk Retrograde Inversion.ogg",
                "audio/music/Melody Tracks/Melody-Siluk Retrograde Inversion.wav"
            ),
            "audio/music/Melody Tracks/Melody-Siluk Retrograde.mp3" to listOf(
                "audio/music/Melody Tracks/Melody-Siluk Retrograde.mp3",
                "audio/music/Melody Tracks/Melody-Siluk Retrograde.aac",
                "audio/music/Melody Tracks/Melody-Siluk Retrograde.ogg",
                "audio/music/Melody Tracks/Melody-Siluk Retrograde.wav"
            ),
            "audio/music/Melody Tracks/Melody-Siluk.mp3" to listOf(
                "audio/music/Melody Tracks/Melody-Siluk.mp3",
                "audio/music/Melody Tracks/Melody-Siluk.aac",
                "audio/music/Melody Tracks/Melody-Siluk.ogg",
                "audio/music/Melody Tracks/Melody-Siluk.wav"
            ),
            "audio/music/Melody Tracks/Melody-Tevir Inversion.mp3" to listOf(
                "audio/music/Melody Tracks/Melody-Tevir Inversion.mp3",
                "audio/music/Melody Tracks/Melody-Tevir Inversion.aac",
                "audio/music/Melody Tracks/Melody-Tevir Inversion.ogg",
                "audio/music/Melody Tracks/Melody-Tevir Inversion.wav"
            ),
            "audio/music/Melody Tracks/Melody-Tevir.mp3" to listOf(
                "audio/music/Melody Tracks/Melody-Tevir.mp3",
                "audio/music/Melody Tracks/Melody-Tevir.aac",
                "audio/music/Melody Tracks/Melody-Tevir.ogg",
                "audio/music/Melody Tracks/Melody-Tevir.wav"
            ),
            "audio/music/Melody Tracks/Melody-Tippeha Retrograde Inversion.mp3" to listOf(
                "audio/music/Melody Tracks/Melody-Tippeha Retrograde Inversion.mp3",
                "audio/music/Melody Tracks/Melody-Tippeha Retrograde Inversion.aac",
                "audio/music/Melody Tracks/Melody-Tippeha Retrograde Inversion.ogg",
                "audio/music/Melody Tracks/Melody-Tippeha Retrograde Inversion.wav"
            ),
            "audio/music/Melody Tracks/Melody-Tippeha Retrograde.mp3" to listOf(
                "audio/music/Melody Tracks/Melody-Tippeha Retrograde.mp3",
                "audio/music/Melody Tracks/Melody-Tippeha Retrograde.aac",
                "audio/music/Melody Tracks/Melody-Tippeha Retrograde.ogg",
                "audio/music/Melody Tracks/Melody-Tippeha Retrograde.wav"
            ),
            "audio/music/Melody Tracks/Melody-Tippeha.mp3" to listOf(
                "audio/music/Melody Tracks/Melody-Tippeha.mp3",
                "audio/music/Melody Tracks/Melody-Tippeha.aac",
                "audio/music/Melody Tracks/Melody-Tippeha.ogg",
                "audio/music/Melody Tracks/Melody-Tippeha.wav"
            ),
            "audio/music/Melody Tracks/Melody-Zakef Retrograde Inversion.mp3" to listOf(
                "audio/music/Melody Tracks/Melody-Zakef Retrograde Inversion.mp3",
                "audio/music/Melody Tracks/Melody-Zakef Retrograde Inversion.aac",
                "audio/music/Melody Tracks/Melody-Zakef Retrograde Inversion.ogg",
                "audio/music/Melody Tracks/Melody-Zakef Retrograde Inversion.wav"
            ),
            "audio/music/Melody Tracks/Melody-Zakef Retrograde.mp3" to listOf(
                "audio/music/Melody Tracks/Melody-Zakef Retrograde.mp3",
                "audio/music/Melody Tracks/Melody-Zakef Retrograde.aac",
                "audio/music/Melody Tracks/Melody-Zakef Retrograde.ogg",
                "audio/music/Melody Tracks/Melody-Zakef Retrograde.wav"
            ),
            "audio/music/Melody Tracks/Melody-Zakef.mp3" to listOf(
                "audio/music/Melody Tracks/Melody-Zakef.mp3",
                "audio/music/Melody Tracks/Melody-Zakef.aac",
                "audio/music/Melody Tracks/Melody-Zakef.ogg",
                "audio/music/Melody Tracks/Melody-Zakef.wav"
            ),
            "audio/music/Nemesis wet 1.mp3" to listOf(
                "audio/music/Nemesis wet 1.mp3",
                "audio/music/Nemesis wet 1.aac",
                "audio/music/Nemesis wet 1.ogg",
                "audio/music/Nemesis wet 1.wav"
            ),
            "audio/music/R-Tracks/R-Track 1 Retrograde.mp3" to listOf(
                "audio/music/R-Tracks/R-Track 1 Retrograde.mp3",
                "audio/music/R-Tracks/R-Track 1 Retrograde.aac",
                "audio/music/R-Tracks/R-Track 1 Retrograde.ogg",
                "audio/music/R-Tracks/R-Track 1 Retrograde.wav"
            ),
            "audio/music/R-Tracks/R-Track 1.mp3" to listOf(
                "audio/music/R-Tracks/R-Track 1.mp3",
                "audio/music/R-Tracks/R-Track 1.aac",
                "audio/music/R-Tracks/R-Track 1.ogg",
                "audio/music/R-Tracks/R-Track 1.wav"
            ),
            "audio/music/R-Tracks/R-Track 2 Retrograde.mp3" to listOf(
                "audio/music/R-Tracks/R-Track 2 Retrograde.mp3",
                "audio/music/R-Tracks/R-Track 2 Retrograde.aac",
                "audio/music/R-Tracks/R-Track 2 Retrograde.ogg",
                "audio/music/R-Tracks/R-Track 2 Retrograde.wav"
            ),
            "audio/music/R-Tracks/R-Track 2 Rotated.mp3" to listOf(
                "audio/music/R-Tracks/R-Track 2 Rotated.mp3",
                "audio/music/R-Tracks/R-Track 2 Rotated.aac",
                "audio/music/R-Tracks/R-Track 2 Rotated.ogg",
                "audio/music/R-Tracks/R-Track 2 Rotated.wav"
            ),
            "audio/music/R-Tracks/R-Track 2.mp3" to listOf(
                "audio/music/R-Tracks/R-Track 2.mp3",
                "audio/music/R-Tracks/R-Track 2.aac",
                "audio/music/R-Tracks/R-Track 2.ogg",
                "audio/music/R-Tracks/R-Track 2.wav"
            ),
            "audio/music/R-Tracks/R-Track 3 Retrograde.mp3" to listOf(
                "audio/music/R-Tracks/R-Track 3 Retrograde.mp3",
                "audio/music/R-Tracks/R-Track 3 Retrograde.aac",
                "audio/music/R-Tracks/R-Track 3 Retrograde.ogg",
                "audio/music/R-Tracks/R-Track 3 Retrograde.wav"
            ),
            "audio/music/R-Tracks/R-Track 3 Slow Rotation.mp3" to listOf(
                "audio/music/R-Tracks/R-Track 3 Slow Rotation.mp3",
                "audio/music/R-Tracks/R-Track 3 Slow Rotation.aac",
                "audio/music/R-Tracks/R-Track 3 Slow Rotation.ogg",
                "audio/music/R-Tracks/R-Track 3 Slow Rotation.wav"
            ),
            "audio/music/R-Tracks/R-Track 3.mp3" to listOf(
                "audio/music/R-Tracks/R-Track 3.mp3",
                "audio/music/R-Tracks/R-Track 3.aac",
                "audio/music/R-Tracks/R-Track 3.ogg",
                "audio/music/R-Tracks/R-Track 3.wav"
            ),
            "audio/music/R-Tracks/R-Track 4 Retrograde.mp3" to listOf(
                "audio/music/R-Tracks/R-Track 4 Retrograde.mp3",
                "audio/music/R-Tracks/R-Track 4 Retrograde.aac",
                "audio/music/R-Tracks/R-Track 4 Retrograde.ogg",
                "audio/music/R-Tracks/R-Track 4 Retrograde.wav"
            ),
            "audio/music/R-Tracks/R-Track 4 Rotated.mp3" to listOf(
                "audio/music/R-Tracks/R-Track 4 Rotated.mp3",
                "audio/music/R-Tracks/R-Track 4 Rotated.aac",
                "audio/music/R-Tracks/R-Track 4 Rotated.ogg",
                "audio/music/R-Tracks/R-Track 4 Rotated.wav"
            ),
            "audio/music/R-Tracks/R-Track 4 centered.mp3" to listOf(
                "audio/music/R-Tracks/R-Track 4 centered.mp3",
                "audio/music/R-Tracks/R-Track 4 centered.aac",
                "audio/music/R-Tracks/R-Track 4 centered.ogg",
                "audio/music/R-Tracks/R-Track 4 centered.wav"
            ),
            "audio/music/R-Tracks/R-Track 5 Retrograde.mp3" to listOf(
                "audio/music/R-Tracks/R-Track 5 Retrograde.mp3",
                "audio/music/R-Tracks/R-Track 5 Retrograde.aac",
                "audio/music/R-Tracks/R-Track 5 Retrograde.ogg",
                "audio/music/R-Tracks/R-Track 5 Retrograde.wav"
            ),
            "audio/music/R-Tracks/R-Track 5.mp3" to listOf(
                "audio/music/R-Tracks/R-Track 5.mp3",
                "audio/music/R-Tracks/R-Track 5.aac",
                "audio/music/R-Tracks/R-Track 5.ogg",
                "audio/music/R-Tracks/R-Track 5.wav"
            ),
            "audio/music/R-Tracks/R-Track 6 Retrograde.mp3" to listOf(
                "audio/music/R-Tracks/R-Track 6 Retrograde.mp3",
                "audio/music/R-Tracks/R-Track 6 Retrograde.aac",
                "audio/music/R-Tracks/R-Track 6 Retrograde.ogg",
                "audio/music/R-Tracks/R-Track 6 Retrograde.wav"
            ),
            "audio/music/R-Tracks/R-Track 6 Rotated.mp3" to listOf(
                "audio/music/R-Tracks/R-Track 6 Rotated.mp3",
                "audio/music/R-Tracks/R-Track 6 Rotated.aac",
                "audio/music/R-Tracks/R-Track 6 Rotated.ogg",
                "audio/music/R-Tracks/R-Track 6 Rotated.wav"
            ),
            "audio/music/R-Tracks/R-Track 6.mp3" to listOf(
                "audio/music/R-Tracks/R-Track 6.mp3",
                "audio/music/R-Tracks/R-Track 6.aac",
                "audio/music/R-Tracks/R-Track 6.ogg",
                "audio/music/R-Tracks/R-Track 6.wav"
            ),
            "audio/music/Terminal Conditions wet 1.mp3" to listOf(
                "audio/music/Terminal Conditions wet 1.mp3",
                "audio/music/Terminal Conditions wet 1.aac",
                "audio/music/Terminal Conditions wet 1.ogg",
                "audio/music/Terminal Conditions wet 1.wav"
            ),
            "audio/music/Xilaron and Eleuryiyidict wet final.mp3" to listOf(
                "audio/music/Xilaron and Eleuryiyidict wet final.mp3",
                "audio/music/Xilaron and Eleuryiyidict wet final.aac",
                "audio/music/Xilaron and Eleuryiyidict wet final.ogg",
                "audio/music/Xilaron and Eleuryiyidict wet final.wav"
            ),
        )
    }

    fun resolvePath(name: String): List<String>? {
        // 1) Exact manifest-key match. The original path the music
        //    runner used to take: hand the loader the key directly
        //    (e.g. "music.menu") and get back the format-fallback
        //    list. Preserved for source compatibility.
        if (manifest.containsKey(name)) return manifest[name]

        // 2) Exact manifest-VALUE match. The current path: the
        //    `MusicResourceResolver` translates the designer's bare
        //    catalog name ("Xilaron and Eleuryiyidict wet final")
        //    into a webpack import path
        //    ("audio/music/Xilaron and Eleuryiyidict wet final.mp3")
        //    and the engine passes THAT string into loadBuffer. The
        //    pre-fix resolver only ever inspected manifest keys, so
        //    every MusicResourceResolver-resolved resource was
        //    dropped at loadBufferImpl with the "no audio file
        //    matches" warning and the buffer cache stayed empty.
        //
        //    The fix: search the value-space too. If any manifest
        //    entry's path list contains `name` verbatim, that entry
        //    is the canonical answer.
        val reverseHit = manifest.entries.firstOrNull { (_, paths) -> paths.contains(name) }
        if (reverseHit != null) {
            log.debug(
                LogCategory.NETWORK,
                "AudioResourceLoader.resolvePath: REVERSE-VALUE hit for '$name' " +
                    "→ manifest key '${reverseHit.key}'"
            )
            return reverseHit.value
        }

        // 2b) Basename-without-extension match. Tracks from
        //     `audio-tracks.json` carry their resourceName as the
        //     bare basename (e.g. "Initial Conditions wet 1"). The
        //     manifest values are full paths
        //     ("audio/music/Initial Conditions wet 1.mp3"). Compare
        //     the basename of every manifest value against `name`
        //     (case-insensitive). This was the missing fix for
        //     audio.syncState resume: the server sends the bare
        //     basename; the client must find the matching full path.
        val basenameHit = manifest.entries.firstOrNull { (_, paths) ->
            paths.any { path ->
                path.substringAfterLast('/').substringBeforeLast('.')
                    .equals(name, ignoreCase = true)
            }
        }
        if (basenameHit != null) {
            log.debug(
                LogCategory.NETWORK,
                "AudioResourceLoader.resolvePath: BASENAME hit for '$name' " +
                    "→ manifest key '${basenameHit.key}'"
            )
            return basenameHit.value
        }

        // 3) Underscore-tolerant key match. Lets callers that drop
        //    dots in their namespace ("sfx_click" vs "sfx.click")
        //    still find a key.
        val underscorized = name.replace(".", "_")
        manifest.entries.find { it.key.replace(".", "_") == underscorized }?.let { return it.value }

        // 4) Fuzzy prefix match against keys. Useful when a caller
        //    hands in a short prefix ("music") and wants the
        //    longest matching key.
        manifest.entries.filter { name.startsWith(it.key) || it.key.startsWith(name) }
            .maxByOrNull { it.key.length }?.let { return it.value }

        // 5) Last-resort parent-prefix match. Strips the final
        //    dotted segment off `name` ("foo.bar.baz" → "foo.bar")
        //    and looks for any key that starts with that parent.
        val parts = name.split(".")
        if (parts.size >= 2) {
            val parentKey = parts.dropLast(1).joinToString(".")
            manifest.entries.find { it.key.startsWith(parentKey) }?.let { return it.value }
        }
        return null
    }

    /**
     * Loads an audio file buffer, trying multiple format variants in preferred order
     * (mp3 > aac > ogg > wav) before failing.
     * Logs a warning rather than throwing to prevent cascade crashes when
     * server schedules audio objects for resources not yet in the webpack bundle.
     *
     * ## How audio loading works
     *
     * The resource path is the public-URL path the [kotlin/JS] build emits
     * the audio file under (e.g. `audio/music/Xilaron and Eleuryiyidict
     * wet final.mp3` — see [publicUrl]). The dev server serves it
     * verbatim from the `processedResources` folder; the production build
     * emits the same file via the webpack `asset/resource` rule in
     * `webpack.config.d/audio-chunks.js`. We `fetch` that URL, pull the
     * `ArrayBuffer`, and hand it to `ctx.decodeAudioData`.
     *
     * @param resourceName the logical resource name to resolve to a path
     * @param ctx the Web Audio API context used to decode the buffer
     * @return the decoded AudioBuffer, or null if resolution or all format fetches fail
     */
    suspend fun loadBuffer(resourceName: String, ctx: AudioContext): AudioBuffer? {
        
        return try {
            loadBufferImpl(resourceName, ctx)
        } catch (e: Throwable) {
            Logger.error(
                LogCategory.NETWORK,
                "AudioResourceLoader.loadBuffer: THREW exception for '$resourceName' — ${e.message} (${e::class.simpleName})"
            )
            null
        }
    }

    private suspend fun loadBufferImpl(resourceName: String, ctx: AudioContext): AudioBuffer? {
        val paths = resolvePath(resourceName)
        
        if (paths == null) {
            log.warn(
                LogCategory.NETWORK,
                "AudioResourceLoader.loadBuffer: no audio file matches resourceName='$resourceName' " +
                "(not in manifest, no fuzzy match) — returning null"
            )
            return null
        }

        val attemptedFormats = mutableListOf<String>()

        for (path in paths) {
            attemptedFormats.add(path)
            val relativePath = "./$path"
            val url: String = publicUrl(relativePath.removePrefix("./"))
            log.debug(
                LogCategory.NETWORK,
                "AudioResourceLoader.loadBuffer: fetching resourceName='$resourceName' at path='$path' (url='$url') with ${LOAD_TIMEOUT_MS}ms timeout"
            )

            val response: Response? = try {
                fetchWithTimeout(url, LOAD_TIMEOUT_MS)
            } catch (e: dynamic) {
                log.warn(
                    LogCategory.NETWORK,
                    "AudioResourceLoader.loadBuffer: fetch threw for resourceName='$resourceName' at path='$path' (url='$url') — ${e.message}"
                )
                continue
            }
            if (response == null) {
                log.warn(
                    LogCategory.NETWORK,
                    "AudioResourceLoader.loadBuffer: fetch TIMED OUT after ${LOAD_TIMEOUT_MS}ms for resourceName='$resourceName' at path='$path' (url='$url') — trying next format"
                )
                continue
            }
            if (!response.ok) {
                log.warn(
                    LogCategory.NETWORK,
                    "AudioResourceLoader.loadBuffer: HTTP ${response.status} fetching resourceName='$resourceName' at path='$path' — trying next format"
                )
                continue
            }

            val ab: dynamic? = try {
                arrayBufferWithTimeout(response, LOAD_TIMEOUT_MS)
            } catch (e: dynamic) {
                log.warn(
                    LogCategory.NETWORK,
                    "AudioResourceLoader.loadBuffer: arrayBuffer() threw for resourceName='$resourceName' at path='$path' — ${e.message}"
                )
                continue
            }
            if (ab == null) {
                log.warn(
                    LogCategory.NETWORK,
                    "AudioResourceLoader.loadBuffer: arrayBuffer() TIMED OUT after ${LOAD_TIMEOUT_MS}ms for resourceName='$resourceName' at path='$path' — trying next format"
                )
                continue
            }
            try {
                val buffer = decodeWithTimeout(ctx, ab, LOAD_TIMEOUT_MS)
                if (buffer == null) {
                    log.warn(
                        LogCategory.NETWORK,
                        "AudioResourceLoader.loadBuffer: decodeAudioData() TIMED OUT after ${LOAD_TIMEOUT_MS}ms for resourceName='$resourceName' at path='$path' — trying next format"
                    )
                    continue
                }
                log.info(
                    LogCategory.NETWORK,
                    "AudioResourceLoader.loadBuffer: OK resourceName='$resourceName' " +
                    "loaded from path='$path' (duration=${buffer.duration}s, " +
                    "channels=${buffer.numberOfChannels}, sampleRate=${buffer.asDynamic().sampleRate})"
                )
                return buffer
            } catch (e: dynamic) {
                log.warn(
                    LogCategory.NETWORK,
                    "AudioResourceLoader.loadBuffer: decode failed for resourceName='$resourceName' at path='$path' — ${e.message}"
                )
                continue
            }
        }

        log.warn(
            LogCategory.NETWORK,
            "AudioResourceLoader.loadBuffer: ALL formats FAILED for resourceName='$resourceName'. " +
            "Attempted paths: $attemptedFormats — returning null"
        )
        return null
    }


    /**
     * Default timeout (ms) for the three I/O steps in [loadBufferImpl]:
     * the fetch, the arrayBuffer read, and the decodeAudioData call.
     *
     * The menu-music file is ~13 MB and decodes in well under a second
     * on a healthy browser, so 15 s is generous — but bounded so a
     * wedged fetch (e.g. dev server crashed mid-response) cannot keep
     * the engine's preload coroutine suspended forever, which was the
     * root cause of the "menu music never starts" bug observed in
     * `browser-2026-06-16-084739.log` (REVERSE-VALUE hit at
     * 12:50:15.887Z followed by 10+ s of nothing).
     */
    private val LOAD_TIMEOUT_MS: Long = 15_000L

    /**
     * Fetch a URL with a hard timeout. Returns the [Response] on success,
     * `null` on timeout. Throws on synchronous error (network down,
     * CORS rejection, etc.) so the caller's catch can log it.
     */
    private suspend fun fetchWithTimeout(url: String, timeoutMs: Long): Response? {
        val timeoutPromise: Promise<Response> = Promise { _, reject ->
            js("setTimeout(function() { reject(new Error('AudioResourceLoader fetch timeout after ' + timeoutMs + 'ms for ' + url)); }, timeoutMs)")
        }
        val raced = Promise.race(arrayOf(window.fetch(url), timeoutPromise))
        return try { raced.await() } catch (e: dynamic) { null }
    }

    /**
     * Read the response body as an [ArrayBuffer] with a hard timeout.
     */
    private suspend fun arrayBufferWithTimeout(response: Response, timeoutMs: Long): dynamic? {
        val timeoutPromise: Promise<dynamic> = Promise { _, reject ->
            js("setTimeout(function() { reject(new Error('AudioResourceLoader arrayBuffer timeout after ' + timeoutMs + 'ms')); }, timeoutMs)")
        }
        val raced = Promise.race(arrayOf(response.arrayBuffer(), timeoutPromise))
        return try { raced.await() } catch (e: dynamic) { null }
    }

    /**
     * Decode a raw [ArrayBuffer] into an [AudioBuffer] with a hard timeout.
     */
    private suspend fun decodeWithTimeout(ctx: AudioContext, ab: dynamic, timeoutMs: Long): AudioBuffer? {
        val decodePromise: Promise<AudioBuffer> = ctx.decodeAudioData(ab.unsafeCast<org.khronos.webgl.ArrayBuffer>())
        val timeoutPromise: Promise<AudioBuffer> = Promise { _, reject ->
            js("setTimeout(function() { reject(new Error('AudioResourceLoader decodeAudioData timeout after ' + timeoutMs + 'ms')); }, timeoutMs)")
        }
        val raced = Promise.race(arrayOf(decodePromise, timeoutPromise))
        return try { raced.await() } catch (e: dynamic) { null }
    }
}
