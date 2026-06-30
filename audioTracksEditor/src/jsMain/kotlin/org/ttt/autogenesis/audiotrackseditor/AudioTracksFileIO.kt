package org.ttt.autogenesis.audiotrackseditor

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import structs.audio.AudioTracks

/**
 * Encode/decode wrapper for [AudioTracks] JSON serialization.
 *
 * Encoding is pretty-printed with 2-space indent for human readability.
 * Decoding is strict: unknown keys cause errors and missing required fields
 * cause errors. The strictness is deliberate — the editor surfaces these
 * errors to the user via the error banner so a malformed file does not
 * silently corrupt the in-memory state.
 */
@OptIn(ExperimentalSerializationApi::class)
object AudioTracksFileIO
{
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        ignoreUnknownKeys = false
        isLenient = false
        encodeDefaults = true
    }

    /**
     * Serialize [tracks] to a JSON string with pretty-printing enabled.
     *
     * @param tracks The audio tracks to serialize
     * @return A multi-line JSON string with 2-space indent
     */
    fun encode(tracks: AudioTracks): String
    {
        val pretty = json.encodeToString(tracks)
        return pretty.replace(": [", ":[")
    }

    /**
     * Parse a JSON string into [AudioTracks]. Strict: throws
     * [SerializationException] on malformed input, unknown keys, or
     * missing required fields.
     *
     * @param text The JSON text to parse
     * @return The parsed [AudioTracks]
     * @throws SerializationException if the text is not valid JSON or does
     *   not match the [AudioTracks] schema
     */
    fun decode(text: String): AudioTracks
    {
        val element = json.parseToJsonElement(text)
        val obj = element as? JsonObject
            ?: throw SerializationException("Expected JSON object, got: $element")
        // The four original layer fields are still required — a JSON
        // object missing any of them is the wrong file type, even if
        // it has the new scenario fields. The new fields (`menu`,
        // `start`, `nemesis`, `end`) are optional and default to
        // empty so JSON written by the previous editor version still
        // loads cleanly.
        val requiredKeys = setOf("drone", "melody", "rhythm", "harmony")
        val missingKeys = requiredKeys - obj.keys
        if (missingKeys.isNotEmpty())
        {
            throw SerializationException("Missing required fields: $missingKeys")
        }
        return json.decodeFromJsonElement(element)
    }
}
