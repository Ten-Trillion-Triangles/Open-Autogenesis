package org.ttt.autogenesis.kvisionapp

import kotlinx.serialization.serializer
import org.ttt.autogenesis.storage.JsonRecordCache
import org.ttt.autogenesis.storage.JsonRecordStorageTarget
import structs.accelbyte.common.AccelByteJson
import structs.Commander

private const val STORAGE_PREFIX = "commander-record:"

/**
 * Minimal cache around commander definitions saved through server-extend.
 *
 * This prevents repeated reads when the client has already captured a record locally.
 */
object CommanderCache
{
    private fun normalizeName(name : String) : String
    {
        val cleaned = name.trim().lowercase().replace("\\s+".toRegex(), "-")
        return cleaned.ifBlank { "unnamed" }
    }

    private fun storageKey(name : String) : String
    {
        return "$STORAGE_PREFIX${normalizeName(name)}"
    }

    /**
     * Stores the serialized commander definition under [name].
     */
    fun cacheCommanderRecord(
        name : String,
        commander : Commander,
        target : JsonRecordStorageTarget = JsonRecordStorageTarget.LOCAL_STORAGE
    )
    {
        if(name.isBlank()) return
        if(!JsonRecordCache.hasTarget(target)) return
        val jsonElement = AccelByteJson.encodeToJsonElement(Commander.serializer(), commander)
        JsonRecordCache.store(target, storageKey(name), jsonElement)
    }

    /**
     * Loads a cached commander definition if it exists locally.
     */
    fun loadCommanderRecord(
        name : String,
        target : JsonRecordStorageTarget = JsonRecordStorageTarget.LOCAL_STORAGE
    ) : Commander?
    {
        if(name.isBlank()) return null
        if(!JsonRecordCache.hasTarget(target)) return null
        return JsonRecordCache.load(target, storageKey(name))?.let {
            AccelByteJson.decodeFromJsonElement(Commander.serializer(), it)
        }
    }

    /**
     * Returns cached commander names available in [target].
     */
    fun cachedCommanderNames(
        target : JsonRecordStorageTarget = JsonRecordStorageTarget.LOCAL_STORAGE
    ) : List<String>
    {
        if(!JsonRecordCache.hasTarget(target)) return emptyList()
        return JsonRecordCache.keys(target, STORAGE_PREFIX)
            .map { it.removePrefix(STORAGE_PREFIX) }
            .map { it.replace('-', ' ') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Removes the cached commander definition for [name].
     */
    fun removeCommanderRecord(
        name : String,
        target : JsonRecordStorageTarget = JsonRecordStorageTarget.LOCAL_STORAGE
    )
    {
        if(name.isBlank()) return
        if(!JsonRecordCache.hasTarget(target)) return
        JsonRecordCache.remove(target, storageKey(name))
    }
}