package org.ttt.autogenesis.jukebox.audio

import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * Resolves a resource path to an absolute URL by composing it with the
 * document's base URI.
 *
 * This is **plain URL resolution** (not webpack dynamic import). The
 * Kotlin/JS executable mode emits a single self-contained bundle at the
 * productionExecutable output root, and a Gradle Copy task places the audio
 * MP3 files at audio/music/<name>.mp3 and audio/sfx/<name>.mp3 next to that
 * bundle. So a relative path like audio/music/ambient_forest.mp3 resolves
 * to <bundle>/audio/music/ambient_forest.mp3 in the browser. The actual
 * audio bytes are then fetched at runtime by loadBuffer via window.fetch.
 *
 * Using document.baseURI ensures the URL works regardless of how the page
 * is served (file://, http://localhost, file server in a subdirectory).
 *
 * Note: an earlier version of this function used js("import(path)"), but
 * webpack treats that as a static module import — with a runtime variable it
 * cannot statically analyze which chunk to bundle, so the import failed at
 * runtime. Using new URL(path, base).href is recognized by webpack as a
 * static asset reference and produces the correct runtime URL.
 *
 * @param path the relative path within the bundle output (e.g. "./audio/music/ambient_forest.mp3")
 * @return the absolute URL of the audio resource, suitable for window.fetch
 */
private fun dynamicImport(path: String): String {
    val base: String = js("document.baseURI")
    val url: String = js("(new URL(path, base)).href")
    return url
}

/**
 * Loads audio files lazily via webpack chunks.
 * Maintains a manifest of resource name → list of webpack chunk paths in preferred format order.
 * Format preference: mp3 > aac > ogg > wav
 */
class AudioResourceLoader {
    private val manifest: Map<String, List<String>> = buildManifest()

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
                "audio/music/menu.mp3",
                "audio/music/menu.aac",
                "audio/music/menu.ogg",
                "audio/music/menu.wav"
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
            )
        )
    }

    // Aliases for bare UI button names → dotted manifest keys
    // Allows the UI to call jukeboxPlay("forest") instead of jukeboxPlay("music.ambient.forest")
    private val bareNameAliases: Map<String, String> = mapOf(
        "forest" to "music.ambient.forest",
        "battle" to "music.battle.theme",
        "menu" to "music.menu"
    )

    fun resolvePath(name: String): List<String>? {
        // Check bare-name alias map first (UI sends short names)
        val resolved = bareNameAliases[name] ?: name
        if (manifest.containsKey(resolved)) return manifest[resolved]
        val underscorized = resolved.replace(".", "_")
        manifest.entries.find { it.key.replace(".", "_") == underscorized }?.let { return it.value }
        manifest.entries.filter { resolved.startsWith(it.key) || it.key.startsWith(resolved) }
            .maxByOrNull { it.key.length }?.let { return it.value }
        val parts = resolved.split(".")
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
     * Note: dynamicImport is plain URL resolution (not webpack code-splitting);
     * see its KDoc for details. The MP3 files are placed next to the bundle by
     * a Gradle Copy task, and loadBuffer downloads them via window.fetch +
     * AudioContext.decodeAudioData.
     *
     * @param resourceName the logical resource name to resolve to a path
     * @param ctx the Web Audio API context used to decode the buffer
     * @return the decoded AudioBuffer, or null if resolution or all format fetches fail
     */
    suspend fun loadBuffer(resourceName: String, ctx: AudioContext): AudioBuffer? {
        val paths = resolvePath(resourceName)
        if (paths == null) {
            console.warn("AudioResourceLoader: No audio file matches resource name: $resourceName")
            return null
        }

        val attemptedFormats = mutableListOf<String>()

        for (path in paths) {
            attemptedFormats.add(path)
            val relativePath = "./$path"
            val url: String = dynamicImport(relativePath)

            val response: dynamic = window.fetch(url).await()
            if (!response.ok) {
                console.warn("AudioResourceLoader: Failed to fetch audio for '$resourceName' at path '$path' — status: ${response.status}")
                continue
            }

            // Cast to typed Promise so .await() unwraps correctly.
            val abPromise: Promise<dynamic> = response.arrayBuffer().unsafeCast<Promise<dynamic>>()
            val ab: dynamic = abPromise.await()
            try {
                // Cast decodeAudioData's returned native Promise to a typed
                // Promise<dynamic> before calling .await() — same pattern as
                // the arrayBuffer cast above.
                val decodePromise: Promise<dynamic> = ctx.decodeAudioData(ab).unsafeCast<Promise<dynamic>>()
                val buffer: AudioBuffer = decodePromise.await().unsafeCast<AudioBuffer>()
                return buffer
            } catch (e: dynamic) {
                console.warn("AudioResourceLoader: Failed to decode audio for '$resourceName' at path '$path' — ${e.message}")
                continue
            }
        }

        console.warn("AudioResourceLoader: All formats failed for '$resourceName'. Attempted paths: $attemptedFormats")
        return null
    }
}