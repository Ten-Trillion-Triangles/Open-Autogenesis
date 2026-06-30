package org.ttt.autogenesis.kvisionapp.assets

import kotlinx.coroutines.await
import kotlinx.coroutines.withTimeoutOrNull
import org.ttt.autogenesis.kvisionapp.audio.AudioEngine
import org.ttt.autogenesis.kvisionapp.audio.AudioResourceLoader
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.w3c.dom.HTMLAudioElement
import kotlinx.browser.document
import kotlinx.browser.window
import kotlin.js.Promise

/**
 * Loads an audio asset by verifying the browser can decode the bytes via
 * [HTMLAudioElement] (with `preload="auto"`).
 *
 * Webpack bundles audio resources from `resources/audio/` as separate chunks
 * via the config in `kvisionApp/webpack.config.d/audio-chunks.js`, so by the
 * time this loader runs the audio file is already on disk at the manifest
 * path. We use a hidden `<audio>` element to force the browser to fetch
 * + decode the bytes so the first user-triggered play is instant.
 *
 * ## Failure handling
 *
 * The browser is given up to 30 seconds per loader before the loader reports
 * [LoadResult.Failed]. The asset pipeline then continues with the next loader;
 * the loading screen never traps the user on a failure.
 *
 * @param id kebab-case identifier surfaced in logs and CSS data-testids.
 * @param humanLabel user-facing label (e.g. `Main menu music`).
 * @param resourceName AudioResourceLoader manifest key, e.g. `music.menu`.
 * @param fallbackPath raw resource path used when manifest lookup fails, e.g.
 *   `audio/music/Initial Conditions wet 1.mp3`.
 */
class Mp3AssetLoader(
    override val id: String,
    override val humanLabel: String,
    private val resourceName: String,
    private val fallbackPath: String
) : AssetLoader
{
    private val resourceLoader = AudioResourceLoader()

    override suspend fun load(progress: (LoadProgress) -> Unit): LoadResult
    {
        Logger.info(LogCategory.NETWORK, "Mp3AssetLoader[$id]: load START resourceName='$resourceName' fallbackPath='$fallbackPath'")
        progress(LoadProgress(0.0, "Resolving..."))
        val paths = resourceLoader.resolvePath(resourceName)
        // Prefer the first manifest path (browser format negotiation), but if
        // the manifest lookup misses (returns null) or the list is empty,
        // fall back to the caller-supplied path.
        val targetPath = paths?.firstOrNull() ?: fallbackPath
        Logger.info(LogCategory.NETWORK, "Mp3AssetLoader[$id]: resolved targetPath='$targetPath' (manifest=${if (paths == null) "MISS" else "HIT"})")

        progress(LoadProgress(0.1, "Decoding audio..."))
        val result = tryLoadViaHtmlAudio(targetPath, progress)
        Logger.info(LogCategory.NETWORK, "Mp3AssetLoader[$id]: load EXIT result=$result")
        return result
    }

    /**
     * Create a hidden `<audio preload="auto">` element pointing at the path
     * and wait for `canplaythrough` (or 30 s timeout). On success the browser
     * has the bytes in cache and can play instantly; on timeout or error we
     * return [LoadResult.Failed] but do not throw.
     */
    private suspend fun tryLoadViaHtmlAudio(
        path: String,
        progress: (LoadProgress) -> Unit
    ): LoadResult
    {
        // The dev server expects URL-encoded paths ("Initial%20Conditions%20wet%201.mp3").
        // Browsers do this for `<audio src>` natively, but logging the encoded path
        // here makes the 404 vs 200 status more obvious if the path is malformed.
        val encodedPath = encodeURI(path)
        Logger.debug(LogCategory.NETWORK, "Mp3AssetLoader[$id]: creating hidden HTMLAudioElement src='$encodedPath' (raw='$path')")
        val audio = document.createElement("audio") as HTMLAudioElement
        audio.preload = "auto"
        audio.src = encodedPath
        audio.style.display = "none"
        document.body?.appendChild(audio)
        // Shared mutable flag so the teardown path can suppress the phantom
        // `error` event that the browser fires when we reset `audio.src = ""`
        // to release the webpack-dev-server's per-URL connection slot. Without
        // this guard, every successful audio load logs a false-positive WARN:
        //   "Mp3AssetLoader: audio element error networkState=3 readyState=0
        //    err=[object MediaError] src='http://localhost:8080/'"
        // The flag is set in BOTH the success path (before its teardown) and
        // the finally block (before its teardown) so any error event that
        // fires as a result of our teardown is silently dropped. Real errors
        // — those that fire BEFORE we set the flag — still log and reject.
        val tornDown = booleanArrayOf(false)
        try
        {
            val result = withTimeoutOrNull(30_000)
            {
                awaitCanPlayThrough(audio, progress, tornDown)
            }
            return if(result == true)
            {
                progress(LoadProgress(1.0, "Ready"))
                Logger.info(LogCategory.NETWORK, "Mp3AssetLoader[$id]: canplaythrough reached for '$encodedPath'")
                // ─── ORDER MATTERS HERE ─────────────────────────────────────
                // Tear the HTMLAudioElement down BEFORE calling
                // AudioEngine.preloadBuffer. The webpack-dev-server keeps
                // the media element's response stream open for the lifetime
                // of the element (so the browser can re-buffer on demand),
                // and on the first load of a music file it serves the bytes
                // to that long-lived connection immediately. If we leave
                // the element in the DOM and then issue a parallel fetch
                // for the same URL, the dev server — which is single-
                // threaded for a given file path — queues the second
                // request behind the first and the fetch times out after
                // 15 s. Removing the element first releases the dev-server
                // slot, so the AudioEngine's fetch goes straight to
                // compilation-and-serve. Confirmed against
                // `browser-2026-06-16-114319.log`: prewarm succeeded on
                // page reload (file already in webpack's memory) but
                // timed out on first load (file not yet in memory, dev
                // server tied up by the media element's open stream).
                //
                // Set tornDown BEFORE the teardown so the `error` event the
                // browser fires as a side-effect of `audio.src = ""` is
                // suppressed by the listener in awaitCanPlayThrough.
                tornDown[0] = true
                try { document.body?.removeChild(audio) } catch(_: Throwable) {}
                audio.src = ""
                Logger.debug(
                    LogCategory.NETWORK,
                    "Mp3AssetLoader[$id]: HTMLAudioElement torn down before prewarm " +
                    "(released dev-server connection slot)"
                )
                // Pre-warm AudioEngine's AudioBuffer cache so the first
                // AudioEngine.play(...) for this resource is instant. The
                // HTMLAudioElement pre-cache above put the bytes in the
                // browser's HTTP cache, but the Web Audio API needs a
                // decoded AudioBuffer (a separate in-memory copy) before
                // AudioBufferSourceNode can be scheduled. With the media
                // element torn down the dev server is now free to serve
                // the same URL to the fetch() call below.
                try
                {
                    AudioEngine.preloadBuffer(path)
                    Logger.info(
                        LogCategory.NETWORK,
                        "Mp3AssetLoader[$id]: AudioEngine buffer prewarm complete for '$path' " +
                        "(bufferCache.size=${AudioEngine.bufferCache.size})"
                    )
                }
                catch(err: Throwable)
                {
                    Logger.warn(
                        LogCategory.NETWORK,
                        "Mp3AssetLoader[$id]: AudioEngine.preloadBuffer threw for '$path' (non-fatal): ${err.message}"
                    )
                }
                LoadResult.Done
            }
            else
            {
                Logger.warn(LogCategory.NETWORK, "Mp3AssetLoader[$id]: canplaythrough timed out (>30s) for '$encodedPath'")
                LoadResult.Failed("audio preload timed out or errored for '$encodedPath'")
            }
        }
        catch(err: Throwable)
        {
            Logger.warn(
                LogCategory.NETWORK,
                "Mp3AssetLoader[$id]: HTMLAudioElement preload threw for '$encodedPath': ${err.message}"
            )
            return LoadResult.Failed(err.message ?: "HTMLAudioElement preload error")
        }
        finally
        {
            // The success path tears the element down before prewarm
            // (so the dev server is freed for the parallel fetch). The
            // failure / timeout paths land here instead, so make sure
            // the element is still removed if the success branch
            // never ran. Mark tornDown first so the resulting
            // `audio.src = ""` does not log a phantom error.
            tornDown[0] = true
            try { document.body?.removeChild(audio) } catch(_: Throwable) {}
            try { audio.src = "" } catch(_: Throwable) {}
        }
    }

    /**
     * Await the audio element's `canplaythrough` event, reporting coarse
     * progress along the way. Returns true on success, false on error.
     */
    private suspend fun awaitCanPlayThrough(
        audio: HTMLAudioElement,
        progress: (LoadProgress) -> Unit,
        tornDown: BooleanArray
    ): Boolean
    {
        val readyPromise: Promise<Unit> = Promise { resolve, _ ->
            audio.addEventListener("canplaythrough", { _ -> resolve(Unit) })
            audio.addEventListener("canplay", { _ -> progress(LoadProgress(0.7, "Buffered...")) })
            audio.addEventListener("progress", { _ ->
                if(audio.readyState < 3)
                {
                    // HAVE_FUTURE_DATA = 3; we report 0.75 while waiting
                    progress(LoadProgress(0.75, "Buffering..."))
                }
            })
            audio.load()
        }
        val errorPromise: Promise<Unit> = Promise { _, reject ->
            audio.addEventListener("error", { _ ->
                if(tornDown[0])
                {
                    // Phantom error: the success / finally block intentionally
                    // reset `audio.src = ""` to release the dev-server
                    // connection slot, which makes the browser fire a
                    // synthetic `error` with networkState=3 / readyState=0
                    // and an empty src. Do not log or reject — the audio
                    // actually loaded (canplaythrough reached) and the
                    // prewarm succeeded.
                    return@addEventListener
                }
                val networkState = audio.asDynamic().networkState
                val readyState = audio.asDynamic().readyState
                val err = audio.asDynamic().error
                Logger.warn(
                    LogCategory.NETWORK,
                    "Mp3AssetLoader: audio element error networkState=$networkState readyState=$readyState err=$err src='${audio.src}'"
                )
                reject(Error("audio error networkState=$networkState src='${audio.src}'"))
            })
        }
        return try
        {
            readyPromise.await()
            true
        }
        catch(_: Throwable)
        {
            try { errorPromise.await() } catch(_: Throwable) {}
            false
        }
    }
}

// Bridge to JS's encodeURI. Kotlin/JS does not expose this on String directly
// in all targets, so wrap it.
private fun encodeURI(s: String): String
{
    return js("encodeURI(s)") as String
}
