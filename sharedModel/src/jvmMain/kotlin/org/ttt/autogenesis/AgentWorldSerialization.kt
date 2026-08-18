package org.ttt.autogenesis

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import structs.World
import structs.audio.AudioTracks

/**
 * Serializes [World] for injection into an LLM agent's prompt context.
 *
 * The full [World.audioTracks] payload (drone, melody, rhythm, harmony,
 * menu, start, nemesis, end, channels) is expensive — 45+ KB of JSON on
 * a fully-populated catalog — and no current agent reads it. Stripping
 * it before serialization keeps the prompt payload small without
 * affecting the runtime music picker, which reads [World.audioTracks]
 * directly off the live object at `WorldManager.world.audioTracks`.
 *
 * Implementation: copy the world with `audioTracks` substituted by a
 * default-empty [AudioTracks] and run the standard kotlinx.serialization
 * path on the copy. The input world is not mutated.
 *
 * Mirrors the configuration of `com.TTT.Util.serialize` in TPipe
 * (ignoreUnknownKeys, isLenient, encodeDefaults=false) so the output
 * shape matches what every other agent call site already produces.
 *
 * Lives in jvmMain because the agents that consume this live on the
 * server (no JS equivalent is needed).
 *
 * @param world the live world to serialize.
 * @return JSON string with the audioTracks field omitted.
 */
@OptIn(ExperimentalSerializationApi::class)
private val agentPromptJson: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = false
    explicitNulls = false
    coerceInputValues = true
    allowSpecialFloatingPointValues = true
    allowStructuredMapKeys = true
    allowComments = true
    useArrayPolymorphism = false
    decodeEnumsCaseInsensitive = true
    useAlternativeNames = true
}

@OptIn(ExperimentalSerializationApi::class)
fun serializeWorldForAgentPrompt(world: World): String
{
    val stripped = world.copy(audioTracks = AudioTracks())
    return try
    {
        agentPromptJson.encodeToString(stripped)
    }
    catch(e: Exception)
    {
        ""
    }
}
