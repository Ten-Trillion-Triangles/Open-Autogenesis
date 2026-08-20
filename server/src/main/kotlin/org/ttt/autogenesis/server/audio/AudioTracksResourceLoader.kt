package org.ttt.autogenesis.server.audio

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import structs.audio.AudioTracks
import java.io.InputStream

/**
 * Server-side loader for the audio-tracks catalog.
 *
 * The audio-tracks editor (in `audioTracksEditor/`) is the canonical
 * author of the JSON file. This loader is the server-side consumer
 * that runs once at startup, after the game map has been unpacked in
 * [gameInit.GameInit.defineGameRules], and stashes the parsed payload
 * onto [structs.World.audioTracks] for the music-selector and audio
 * runner to read.
 *
 * The wire format is the same JSON the editor writes (see
 * [structs.audio.AudioTracks] and the editor's `AudioTracksFileIO`).
 * To keep the two sides honest, this loader mirrors the editor's
 * `requiredKeys` rule: the four original layer fields (`drone`,
 * `melody`, `rhythm`, `harmony`) must either all be present or all
 * be absent. The four new scenario fields (`menu`, `start`, `nemesis`,
 * `end`) are optional and default to empty so a JSON file written by
 * the previous editor version still loads cleanly.
 *
 * All three entry points ([loadFromBytes], [loadFromString],
 * [loadFromResource]) go through the same private [decode] helper so
 * the contract is consistent regardless of where the bytes come from.
 */
object AudioTracksResourceLoader
{
    /**
     * The four original layer fields that the editor requires. The
     * server-side loader mirrors that requirement for compatibility —
     * a JSON object claiming to be a tracks file MUST identify which
     * layer fields it carries.
     */
    private val requiredLayerKeys: Set<String> = setOf("drone", "melody", "rhythm", "harmony")

    private val json: Json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        isLenient = false
        encodeDefaults = true
    }

    /**
     * Decode raw JSON bytes into an [AudioTracks] payload.
     *
     * @param bytes The raw UTF-8 (or other charset-decoded by caller) JSON bytes.
     * @return The parsed [AudioTracks].
     * @throws SerializationException on malformed JSON, missing required
     *   layer fields, or schema mismatch.
     */
    fun loadFromBytes(bytes: ByteArray): AudioTracks
    {
        Logger.debug(
            LogCategory.SYSTEM,
            "AudioTracksResourceLoader.loadFromBytes: ENTER bytes.size=${bytes.size}"
        )
        if (bytes.isEmpty())
        {
            Logger.error(
                LogCategory.SYSTEM,
                "AudioTracksResourceLoader.loadFromBytes: bytes are empty (0 bytes) — throwing SerializationException"
            )
            throw SerializationException("AudioTracks JSON is empty (0 bytes)")
        }
        val text = String(bytes, Charsets.UTF_8)
        return decode(text)
    }

    /**
     * Convenience wrapper for callers that already have a `String`.
     * Delegates to [loadFromBytes].
     */
    fun loadFromString(text: String): AudioTracks
    {
        Logger.debug(
            LogCategory.SYSTEM,
            "AudioTracksResourceLoader.loadFromString: ENTER text.length=${text.length}"
        )
        if (text.isBlank())
        {
            Logger.error(
                LogCategory.SYSTEM,
                "AudioTracksResourceLoader.loadFromString: text is blank — throwing SerializationException"
            )
            throw SerializationException("AudioTracks JSON is blank")
        }
        return decode(text)
    }

    /**
     * Read the audio-tracks JSON from the server's bundled resources.
     *
     * The lookup uses the current thread's context class loader so the
     * same code path works for the dev server, the production jar, and
     * the Beryx runtime image (all of which put resources on the
     * classpath at `audio/audio-tracks.json`).
     *
     * @param resourcePath Classpath-relative path, e.g. `"audio/audio-tracks.json"`.
     * @return The parsed [AudioTracks].
     * @throws IllegalArgumentException if no resource is registered at [resourcePath].
     * @throws SerializationException on malformed JSON or schema mismatch.
     */
    fun loadFromResource(resourcePath: String): AudioTracks
    {
        Logger.info(
            LogCategory.SYSTEM,
            "AudioTracksResourceLoader.loadFromResource: ENTER resourcePath='$resourcePath'"
        )
        val classLoader = Thread.currentThread().contextClassLoader
        val stream: InputStream = classLoader.getResourceAsStream(resourcePath)
            ?: run {
                Logger.error(
                    LogCategory.SYSTEM,
                    "AudioTracksResourceLoader.loadFromResource: resource NOT found on classpath " +
                    "— classLoader=$classLoader, requestedPath='$resourcePath'. " +
                    "Make sure the file lives under src/main/resources/$resourcePath."
                )
                throw IllegalArgumentException(
                    "AudioTracks resource not found on classpath: $resourcePath " +
                    "(classLoader=$classLoader). " +
                    "Make sure the file lives under src/main/resources/$resourcePath."
                )
            }
        Logger.debug(
            LogCategory.SYSTEM,
            "AudioTracksResourceLoader.loadFromResource: resource stream opened for '$resourcePath'"
        )
        return stream.use { input ->
            val bytes = input.readBytes()
            Logger.debug(
                LogCategory.SYSTEM,
                "AudioTracksResourceLoader.loadFromResource: read ${bytes.size} bytes from '$resourcePath'"
            )
            loadFromBytes(bytes)
        }
    }

    /**
     * Validate the [AudioTracks] payload has the four required layer
     * fields, then hand the text to `kotlinx.serialization` for the
     * final decode. The validation is the same as the editor's
     * `AudioTracksFileIO.requiredKeys` so a JSON file written by the
     * editor decodes on the server and vice versa.
     */
    private fun decode(text: String): AudioTracks
    {
        Logger.debug(
            LogCategory.SYSTEM,
            "AudioTracksResourceLoader.decode: ENTER text.length=${text.length}"
        )
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            Logger.error(
                LogCategory.SYSTEM,
                "AudioTracksResourceLoader.decode: JSON parse failed — ${e.message}"
            )
            throw e
        }
        val obj = element as? JsonObject
            ?: run {
                Logger.error(
                    LogCategory.SYSTEM,
                    "AudioTracksResourceLoader.decode: root element is not a JSON object " +
                    "(got ${element::class.simpleName}) — throwing"
                )
                throw SerializationException(
                    "AudioTracks JSON must be an object at the root, got: ${element::class.simpleName}"
                )
            }

        // "If any of the four required keys is present, all must be
        //  present." An empty `{}` is allowed and decodes to a fully
        //  defaulted AudioTracks — the file is well-formed, it just
        //  carries no tracks. A file claiming to carry tracks but
        //  missing one of the four layers is malformed.
        val presentRequired = requiredLayerKeys.intersect(obj.keys)
        if (presentRequired.isNotEmpty() && presentRequired.size != requiredLayerKeys.size)
        {
            val missing = requiredLayerKeys - presentRequired
            Logger.error(
                LogCategory.SYSTEM,
                "AudioTracksResourceLoader.decode: missing required layer fields: $missing " +
                "(present=$presentRequired, expected=$requiredLayerKeys) — throwing"
            )
            throw SerializationException(
                "AudioTracks JSON is missing required layer fields: $missing. " +
                "Either supply all four (drone, melody, rhythm, harmony) or none."
            )
        }

        val tracks = try {
            json.decodeFromJsonElement<AudioTracks>(element)
        } catch (e: SerializationException) {
            Logger.error(
                LogCategory.SYSTEM,
                "AudioTracksResourceLoader.decode: schema decode failed — ${e.message}"
            )
            throw e
        }
        Logger.info(
            LogCategory.SYSTEM,
            "AudioTracksResourceLoader: decoded payload " +
                "(drone=${tracks.drone.size}, melody=${tracks.melody.size}, " +
                "rhythm=${tracks.rhythm.size}, harmony=${tracks.harmony.size}, " +
                "menu=${tracks.menu.size}, start=${tracks.start.size}, " +
                "nemesis=${tracks.nemesis.size}, end=${tracks.end.size})"
        )
        return tracks
    }
}