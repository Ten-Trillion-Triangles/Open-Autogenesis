package org.ttt.autogenesis.storage

import kotlinx.serialization.json.JsonElement
import structs.accelbyte.common.AccelByteJson
import java.util.concurrent.ConcurrentHashMap

actual object JsonRecordCache
{
    private val jsonSerializer = JsonElement.serializer()
    private val store = ConcurrentHashMap<JsonRecordStorageTarget, MutableMap<String, String>>()

    private fun bucket(target : JsonRecordStorageTarget) : MutableMap<String, String>
    {
        return store.computeIfAbsent(target) { ConcurrentHashMap() }
    }

    private fun encode(value : JsonElement) : String
    {
        return AccelByteJson.encodeToString(jsonSerializer, value)
    }

    private fun decode(value : String) : JsonElement
    {
        return AccelByteJson.decodeFromString(jsonSerializer, value)
    }

    actual fun supportedTargets() : List<JsonRecordStorageTarget>
    {
        return JsonRecordStorageTarget.values().toList()
    }

    actual fun hasTarget(target : JsonRecordStorageTarget) : Boolean
    {
        return true
    }

    actual fun store(target : JsonRecordStorageTarget, key : String, value : JsonElement)
    {
        bucket(target)[key] = encode(value)
    }

    actual fun load(target : JsonRecordStorageTarget, key : String) : JsonElement?
    {
        return bucket(target)[key]?.let { decode(it) }
    }

    actual fun remove(target : JsonRecordStorageTarget, key : String)
    {
        bucket(target).remove(key)
    }

    actual fun clear(target : JsonRecordStorageTarget, prefix : String?)
    {
        val map = bucket(target)
        if(prefix == null)
        {
            map.clear()
            return
        }
        map.keys.filter { it.startsWith(prefix) }.forEach { map.remove(it) }
    }

    actual fun keys(target : JsonRecordStorageTarget, prefix : String?) : List<String>
    {
        val map = bucket(target)
        return map.keys.filter { prefix == null || it.startsWith(prefix) }
    }
}
