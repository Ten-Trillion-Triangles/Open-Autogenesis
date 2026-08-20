package org.ttt.autogenesis.kvisionapp.audio

import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import org.ttt.autogenesis.audio.AudioObject
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.w3c.fetch.Response
import structs.audio.AudioTracks

/**
 * Plays the editor's main-menu track on the Music channel.
 *
 * The server stores the menu track in `World.audioTracks.menu` and
 * the runtime music pipeline ([org.ttt.autogenesis.kvisionapp.audio.MusicRunner])
 * runs only during gameplay turns, so there is no server-pushed
 * notification for the menu. This helper loads the designer's
 * menu-track entry from the bundled audio-tracks catalog (the same
 * JSON the server's [org.ttt.autogenesis.audio.MusicSelector] reads
 * from), hands it to the [MusicRunner] on [start], and lets the
 * runner's own tracking-set take it from there.
 *
 * The fields here are read from the editor's `World.audioTracks.menu[0]`
 * payload — specifically the entry shipped in
 * `sharedModel/src/commonMain/resources/audio/audio-tracks.json`
 * (the designer-authored catalog that the server's `MusicSelector`
 * also reads from). The resolver and the engine both read the
 * catalog name verbatim, so a change to the menu entry in the
 * editor export will be picked up here on the next build with no
 * further code change.
 */
object MenuMusicPlayer
{
    private val log = Logger

    /** Channel id the menu track lives on; mirrors the server's `Music` channel. */
    const val CHANNEL: String = "Music"

    /**
     * Fade-out duration used when [stop] is called explicitly (for
     * example, when the user navigates away from the main menu back
     * to the login screen). On normal game start the music schedule
     * uses its own fade-out duration instead.
     */
    const val STOP_FADE_MS: Long = 1000L

    /**
     * Webpack-bundled URL of the designer-authored audio-tracks
     * catalog (also lives at
     * `sharedModel/src/commonMain/resources/audio/audio-tracks.json`,
     * the same VCS file the server reads). The webpack rule in
     * `kvisionApp/webpack.config.d/audio-tracks-json.js` publishes
     * the JSON under the `audio/` output folder so this URL is
     * stable.
     */
    const val BUNDLED_CATALOG_URL: String = "audio/audio-tracks.json"

    /**
     * JSON codec for the audio-tracks file. Mirrors the lenient
     * settings of the server's [org.ttt.autogenesis.server.audio.AudioTracksResourceLoader]
     * (ignore-unknown-keys, encode defaults) so an editor export
     * with extra fields or missing scenario tabs decodes cleanly.
     */
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        encodeDefaults = true
    }

    init {
        log.info(
            LogCategory.SYSTEM,
            "MenuMusicPlayer: singleton initialized — channel='$CHANNEL', " +
            "bundled catalog url='$BUNDLED_CATALOG_URL', stopFadeMs=$STOP_FADE_MS"
        )
    }

    /**
     * Pull the menu-track entry out of the bundled audio-tracks
     * catalog. Returns null if the catalog is missing, malformed,
     * or has no menu entry (the editor always emits exactly one
     * `menu[0]`, so null is a configuration error).
     *
     * @return the menu-track [AudioObject] from `audioTracks.menu[0]`,
     *   or null if the menu list is empty.
     */
    internal fun extractMenuTrack(audioTracks: AudioTracks): AudioObject? = audioTracks.menu.firstOrNull()

    /**
     * Decode the bundled audio-tracks JSON text into an
     * [AudioTracks] payload. Pure function; isolated from
     * network/file IO so it is unit-testable with a fixture string.
     */
    internal fun parseAudioTracks(jsonText: String): AudioTracks = json.decodeFromString(AudioTracks.serializer(), jsonText)

    /**
     * Fetch the bundled audio-tracks catalog from webpack's public
     * path. The fetch happens once per [start] call today; if
     * menu-music load becomes hot, this can be hoisted into a
     * lazy-initialised cache.
     */
    private suspend fun loadBundledAudioTracks(): AudioTracks
    {
        log.info(
            LogCategory.NETWORK,
            "MenuMusicPlayer.loadBundledAudioTracks: fetching catalog from '$BUNDLED_CATALOG_URL'"
        )
        // Fetch + text() pattern. The `Response.text()` is a properly
        // typed `Promise<String>` (kotlinx.browser.Response facade),
        // so `await()` resolves to a `String` here. We previously
        // typed the response as `dynamic`; that compiled to a raw
        // `r.text().await` JavaScript call, and the Kotlin/JS
        // coroutine's `await()` extension could not find a Promise
        // receiver in that path — the run-time surfaced
        // `response.text(...).await is not a function` even though
        // `text` exists. Using the typed `Response` from
        // `kotlinx.browser.window.fetch(...).await()` mirrors the
        // working pattern in `DebugSignalBridge.pollOnce` and
        // `AudioResourceLoader.loadBuffer` and keeps the call site
        // on the typed Promise path.
        val response: Response = window.fetch(BUNDLED_CATALOG_URL).await()
        if (!response.ok) {
            throw IllegalStateException(
                "MenuMusicPlayer: failed to fetch audio-tracks catalog at '$BUNDLED_CATALOG_URL' " +
                "— HTTP ${response.status}"
            )
        }
        val text: String = response.text().await()
        log.debug(
            LogCategory.NETWORK,
            "MenuMusicPlayer.loadBundledAudioTracks: received ${text.length} bytes of JSON"
        )
        return parseAudioTracks(text)
    }

    /**
     * Start the menu track. Safe to call from a user-gesture call
     * stack (e.g. inside the MainMenu mount path that is reached
     * after the loading screen's user click) — this is the
     * AudioContext-resumed call stack the engine expects.
     *
     * @return the id of the scheduled menu track, or `null` if the
     *   runner rejected it (e.g. resolver miss, or the catalog has
     *   no `menu` entry).
     */
    suspend fun start(): String?
    {
        log.info(LogCategory.SYSTEM, "MenuMusicPlayer.start: ENTER")
        val obj: AudioObject
        try
        {
            val audioTracks = loadBundledAudioTracks()
            log.info(
                LogCategory.NETWORK,
                "MenuMusicPlayer.start: catalog decoded — " +
                "menu.size=${audioTracks.menu.size} start.size=${audioTracks.start.size} " +
                "nemesis.size=${audioTracks.nemesis.size} end.size=${audioTracks.end.size}"
            )
            obj = extractMenuTrack(audioTracks) ?: run {
                log.warn(
                    LogCategory.NETWORK,
                    "MenuMusicPlayer.start: bundled audio-tracks catalog has no 'menu' entry — skipping"
                )
                return null
            }
            log.info(
                LogCategory.NETWORK,
                "MenuMusicPlayer.start: extracted menu track id=${obj.id} " +
                "resource='${obj.resourceName}' channel='${obj.channelId}' " +
                "volume=${obj.volume} loop=${obj.loop} fadeInMs=${obj.fadeInDurationMs}"
            )
        }
        catch (e: Throwable)
        {
            log.warn(
                LogCategory.NETWORK,
                "MenuMusicPlayer.start: failed to load bundled audio-tracks catalog (non-fatal): ${e.message}"
            )
            return null
        }
        val id = MusicRunner.instance.playMenu(obj)
        if (id == null)
        {
            log.warn(
                LogCategory.NETWORK,
                "MenuMusicPlayer.start: MusicRunner rejected the menu track (resource='${obj.resourceName}')"
            )
        } else {
            log.info(
                LogCategory.SYSTEM,
                "MenuMusicPlayer.start: EXIT — menu track scheduled with id='$id'"
            )
        }
        return id
    }

    /**
     * Stop a previously-started menu track with a short fade-out.
     * No-op if [start] was not called or the runner is not tracking
     * the supplied id.
     */
    fun stop(id: String)
    {
        log.info(
            LogCategory.SYSTEM,
            "MenuMusicPlayer.stop: ENTER id='$id' fadeOutMs=$STOP_FADE_MS"
        )
        MusicRunner.instance.stopMenu(id, fadeOutMs = STOP_FADE_MS)
    }
}