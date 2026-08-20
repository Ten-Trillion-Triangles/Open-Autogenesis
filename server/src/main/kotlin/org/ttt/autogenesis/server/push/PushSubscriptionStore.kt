package org.ttt.autogenesis.server.push

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.server.vfs.VirtualFileSystem
import org.ttt.autogenesis.server.vfs.VirtualFileSystemManager
import structs.push.PushSubscriptionDto

/**
 * Stores Web Push subscriptions keyed by AccelByte user id, supporting
 * multi-device delivery (desktop + mobile, or multiple browser profiles).
 *
 * Storage layout: VFS key `"push-subscription"` per `accelByteId` holds a JSON
 * envelope of the shape:
 * ```
 * { "subscriptions": [ {endpoint, p256dh, auth}, ... ] }
 * ```
 * The envelope is wrapped under a `subscriptions` key (not stored as a bare
 * array) for forward compatibility — future revisions can add fields like
 * `lastUpdated` or `deviceLabel` without breaking already-stored envelopes.
 *
 * Per-subscription identity is the `endpoint` URL. If [put] is called with a
 * DTO whose endpoint already exists, the existing entry is replaced (the
 * browser rotated its p256dh/auth keys but kept the same push service
 * endpoint). New endpoints are appended. This matches the W3C Push spec's
 * `pushsubscriptionchange` lifecycle: when Firefox rotates a subscription the
 * page-side handler forwards the new keys to [put] with the same endpoint.
 *
 * In live mode this routes to AccelByte CloudSave via the cloud-save proxy;
 * in dev mode it routes to the local VFS at `~/.autogenesis/`. Construction
 * uses [VirtualFileSystemManager.forUser] for routing, so guest sessions stay
 * on the local VFS in both dev and live modes.
 *
 * Use the secondary constructor `PushSubscriptionStore()` to use the default
 * factory from [VirtualFileSystemManager.forUser]. Use the primary constructor
 * with an explicit factory for testability (test code can pass any
 * `VirtualFileSystem` instance without touching the global manager).
 */
class PushSubscriptionStore(private val vfsFactory: (String) -> VirtualFileSystem)
{
    companion object
    {
        const val STORAGE_KEY = "push-subscription"
        const val SUBSCRIPTIONS_FIELD = "subscriptions"
    }

    constructor() : this(VirtualFileSystemManager::forUser)

    /**
     * Adds (or replaces, by endpoint) a subscription for [accelByteId].
     * Returns silently on a blank id; logs and returns on VFS failure
     * (push is best-effort and must never crash the game server).
     */
    suspend fun put(accelByteId: String, subscription: PushSubscriptionDto)
    {
        if (accelByteId.isBlank()) return
        val vfs = vfsFactory(accelByteId)
        val existing: List<PushSubscriptionDto> = readAll(vfs, accelByteId)
        val deduped: List<PushSubscriptionDto> = upsertByEndpoint(existing, subscription)
        val payload: JsonElement = encodeEnvelope(deduped)
        val result = vfs.saveUserRecord(accelByteId, STORAGE_KEY, payload)
        result.fold(
            onSuccess = { Logger.debug(LogCategory.NETWORK, "PushSubscriptionStore: stored subscription for user=$accelByteId endpoint=${subscription.endpoint.take(60)} (total=${deduped.size})") },
            onFailure = { Logger.warn(LogCategory.NETWORK, "PushSubscriptionStore: store failed for user=$accelByteId: ${it.message}") }
        )
    }

    /**
     * Returns all stored subscriptions for [accelByteId]. Empty list when
     * the user has no record, the record is malformed, or [accelByteId]
     * is blank. Used by the push trigger to fan out across every device
     * the user has registered (desktop + mobile, etc.).
     */
    suspend fun getAll(accelByteId: String): List<PushSubscriptionDto>
    {
        if (accelByteId.isBlank()) return emptyList()
        val vfs = vfsFactory(accelByteId)
        return readAll(vfs, accelByteId)
    }

    /**
     * Returns the most recently stored subscription for [accelByteId], or
     * null if none. "Most recent" means the last entry in the stored
     * list (which is the most recent [put] call). Use [getAll] when
     * multi-device delivery is required.
     */
    suspend fun get(accelByteId: String): PushSubscriptionDto?
    {
        return getAll(accelByteId).lastOrNull()
    }

    /**
     * Removes a single subscription by [endpoint] for [accelByteId]. Used
     * by the push service when a delivery returns 404 Not Found or 410
     * Gone — the push service endpoint is dead and must be pruned
     * surgically without touching the user's other live devices. If the
     * last subscription for the user is removed, the VFS record is
     * deleted entirely.
     */
    suspend fun removeByEndpoint(accelByteId: String, endpoint: String)
    {
        if (accelByteId.isBlank() || endpoint.isBlank()) return
        val vfs = vfsFactory(accelByteId)
        val existing: List<PushSubscriptionDto> = readAll(vfs, accelByteId)
        val filtered: List<PushSubscriptionDto> = existing.filter { it.endpoint != endpoint }
        if (filtered.size == existing.size)
        {
            // No-op — endpoint was not in the store. Idempotent.
            Logger.debug(LogCategory.NETWORK, "PushSubscriptionStore: removeByEndpoint no-op for user=$accelByteId (endpoint not found)")
            return
        }
        if (filtered.isEmpty())
        {
            vfs.deleteUserRecord(accelByteId, STORAGE_KEY)
            Logger.debug(LogCategory.NETWORK, "PushSubscriptionStore: removeByEndpoint cleared all subscriptions for user=$accelByteId")
            return
        }
        val payload: JsonElement = encodeEnvelope(filtered)
        val result = vfs.saveUserRecord(accelByteId, STORAGE_KEY, payload)
        result.fold(
            onSuccess = { Logger.debug(LogCategory.NETWORK, "PushSubscriptionStore: removeByEndpoint pruned endpoint for user=$accelByteId (remaining=${filtered.size})") },
            onFailure = { Logger.warn(LogCategory.NETWORK, "PushSubscriptionStore: removeByEndpoint save failed for user=$accelByteId: ${it.message}") }
        )
    }

    /**
     * Removes ALL subscriptions for [accelByteId]. Used when the user
     * logs out or explicitly disables push — the VFS record is deleted
     * entirely. Idempotent.
     */
    suspend fun remove(accelByteId: String)
    {
        if (accelByteId.isBlank()) return
        val vfs = vfsFactory(accelByteId)
        vfs.deleteUserRecord(accelByteId, STORAGE_KEY)
        Logger.debug(LogCategory.NETWORK, "PushSubscriptionStore: removed all subscriptions for user=$accelByteId")
    }

    /**
     * Replaces a stored subscription list (by endpoint) with a new list
     * atomically. Used by the page-side `pushsubscriptionchange` handler
     * to re-register a rotated subscription in a single call: read the
     * existing list, swap in the new DTO for the matching endpoint, write
     * back. Equivalent to [put] when the new DTO is the only one, but
     * exposed as a distinct operation for clarity at the call site.
     */
    suspend fun replaceByEndpoint(accelByteId: String, subscription: PushSubscriptionDto)
    {
        if (accelByteId.isBlank()) return
        put(accelByteId, subscription)
    }

    /**
     * Encodes a list of subscriptions under the `subscriptions` envelope
     * key. Exposed for tests; production callers go through [put].
     */
    internal fun encodeEnvelope(subscriptions: List<PushSubscriptionDto>): JsonElement
    {
        val elements: List<JsonElement> = subscriptions.map { dto ->
            RpcJson.encodeToJsonElement(PushSubscriptionDto.serializer(), dto)
        }
        return buildJsonObject {
            put(SUBSCRIPTIONS_FIELD, JsonArray(elements))
        }
    }

    /**
     * Reads the raw list of subscriptions from the VFS. Tolerates:
     *  - missing record (returns empty list)
     *  - record stored as a single flat DTO (legacy single-device shape —
     *    wraps it into a one-element list)
     *  - record stored as the new envelope shape (returns the list)
     *  - record with the AccelByte CloudSave wrapper `{"value": ...}` (unwraps
     *    one level before applying the above heuristics)
     */
    private suspend fun readAll(vfs: VirtualFileSystem, accelByteId: String): List<PushSubscriptionDto>
    {
        val result = vfs.fetchUserRecord(accelByteId, STORAGE_KEY)
        return result.fold(
            onSuccess = { response ->
                val raw: JsonElement? = response.value
                if (raw == null || raw is kotlinx.serialization.json.JsonNull) return@fold emptyList()
                decodeAny(raw)
            },
            onFailure = { emptyList() }
        )
    }

    /**
     * Dispatches decode based on the raw element shape. Public for tests
     * that construct a `PlayerRecordResponse.value` directly.
     */
    internal fun decodeAny(raw: JsonElement): List<PushSubscriptionDto>
    {
        // 1) Try direct decode as a single DTO (legacy single-device shape).
        val asSingle: PushSubscriptionDto? = runCatching {
            RpcJson.decodeFromJsonElement(PushSubscriptionDto.serializer(), raw)
        }.getOrNull()
        if (asSingle != null) return listOf(asSingle)

        // 2) AccelByte CloudSave sometimes wraps the value one level deep:
        //    stored as {"value": {"subscriptions": [...]}}. Unwrap if so.
        val unwrapped: JsonElement = if (raw is JsonObject && raw["value"] is JsonObject)
        {
            raw["value"] as JsonElement
        }
        else raw

        // 3) Try as the new envelope shape: {"subscriptions": [...]}.
        if (unwrapped is JsonObject)
        {
            val subsElement: JsonElement? = unwrapped[SUBSCRIPTIONS_FIELD]
            if (subsElement is JsonArray)
            {
                return subsElement.mapNotNull { element ->
                    runCatching {
                        RpcJson.decodeFromJsonElement(PushSubscriptionDto.serializer(), element)
                    }.getOrNull()
                }
            }
        }
        return emptyList()
    }

    private fun upsertByEndpoint(existing: List<PushSubscriptionDto>, incoming: PushSubscriptionDto): List<PushSubscriptionDto>
    {
        val filtered: List<PushSubscriptionDto> = existing.filter { it.endpoint != incoming.endpoint }
        return filtered + incoming
    }
}