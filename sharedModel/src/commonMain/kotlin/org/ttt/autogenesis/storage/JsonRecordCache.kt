package org.ttt.autogenesis.storage

import kotlinx.serialization.json.JsonElement

/**
 * Targets supported by the JSON record cache.
 */
enum class JsonRecordStorageTarget
{
    /** Uses the browser's `localStorage` or a durable JVM fallback. */
    LOCAL_STORAGE,

    /** Uses the browser's `sessionStorage` or an in-memory JVM fallback. */
    SESSION_STORAGE
}

/**
 * Cross-platform helper that stores arbitrary [JsonElement] payloads keyed by string names.
 *
 * Each platform implements the underlying storage (browser APIs or a JVM map) and exposes only
 * the targets it can satisfy.
 */
expect object JsonRecordCache
{
    /**
     * Returns the available targets on the current platform.
     */
    fun supportedTargets() : List<JsonRecordStorageTarget>

    /**
     * Indicates whether the specified target is accessible right now.
     */
    fun hasTarget(target : JsonRecordStorageTarget) : Boolean

    /**
     * Stores [value] under [key] within the selected [target].
     */
    fun store(target : JsonRecordStorageTarget, key : String, value : JsonElement)

    /**
     * Returns the saved [JsonElement], or `null` if the [key] is absent.
     */
    fun load(target : JsonRecordStorageTarget, key : String) : JsonElement?

    /**
     * Removes a single record from [target]. If the key is missing this is a no-op.
     */
    fun remove(target : JsonRecordStorageTarget, key : String)

    /**
     * Clears either every entry in [target] or only the ones whose keys start with [prefix].
     */
    fun clear(target : JsonRecordStorageTarget, prefix : String? = null)

    /**
     * Lists stored keys in [target], optionally filtering by [prefix].
     */
    fun keys(target : JsonRecordStorageTarget, prefix : String? = null) : List<String>
}
