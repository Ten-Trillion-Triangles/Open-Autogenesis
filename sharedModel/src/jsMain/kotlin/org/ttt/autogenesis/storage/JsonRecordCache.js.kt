package org.ttt.autogenesis.storage

import kotlinx.browser.window
import kotlinx.serialization.json.JsonElement
import org.w3c.dom.Storage
import structs.accelbyte.common.AccelByteJson

actual object JsonRecordCache
{
    private val jsonSerializer = JsonElement.serializer()

    private fun resolveStorage(target : JsonRecordStorageTarget) : Storage?
    {
        return runCatching<Storage>
        {
            when(target)
            {
                JsonRecordStorageTarget.LOCAL_STORAGE -> window.localStorage
                JsonRecordStorageTarget.SESSION_STORAGE -> window.sessionStorage
            }
        }.getOrNull()
    }

    private fun encode(value : JsonElement) : String
    {
        return AccelByteJson.encodeToString(jsonSerializer, value)
    }

    private fun decode(text : String) : JsonElement
    {
        return AccelByteJson.decodeFromString(jsonSerializer, text)
    }

    actual fun supportedTargets() : List<JsonRecordStorageTarget>
    {
        return JsonRecordStorageTarget.values().filter { resolveStorage(it) != null }
    }

    actual fun hasTarget(target : JsonRecordStorageTarget) : Boolean
    {
        return resolveStorage(target) != null
    }

    actual fun store(target : JsonRecordStorageTarget, key : String, value : JsonElement)
    {
        resolveStorage(target)?.let { storage ->
            storage.setItem(key, encode(value))
        }
    }

    actual fun load(target : JsonRecordStorageTarget, key : String) : JsonElement?
    {
        val storage = resolveStorage(target) ?: return null
        val raw = storage.getItem(key) ?: return null
        return try
        {
            decode(raw)
        }
        catch(err : Throwable)
        {
            console.error("JsonRecordCache: failed to decode '$key'", err)
            null
        }
    }

    actual fun remove(target : JsonRecordStorageTarget, key : String)
    {
        resolveStorage(target)?.removeItem(key)
    }

    actual fun clear(target : JsonRecordStorageTarget, prefix : String?)
    {
        val storage = resolveStorage(target) ?: return
        if(prefix == null)
        {
            storage.clear()
            return
        }
        keys(target, prefix).forEach { storage.removeItem(it) }
    }

    actual fun keys(target : JsonRecordStorageTarget, prefix : String?) : List<String>
    {
        val storage = resolveStorage(target) ?: return emptyList()
        val limit = storage.length
        val result = mutableListOf<String>()
        for(index in 0 until limit)
        {
            val entry = storage.key(index) ?: continue
            if(prefix == null || entry.startsWith(prefix))
            {
                result.add(entry)
            }
        }
        return result
    }
}