package network

import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.RpcCallContext
import proxy.MapStorageProxy
import structs.rpcRequests.SavePlayerMapRequest

/**
 * Thin wrapper around `MapStorageProxy.savePlayerMap` that returns a
 * `Result<Unit>` so the gate layer can branch on a save failure without
 * catching exceptions.
 *
 * The wrapper exists for two reasons:
 *  1. `_testHook` injection of a fake so the gate can be tested without
 *     hitting AGS.
 *  2. Converting the `Boolean` success flag from `MapStorageProxy.savePlayerMap`
 *     into a `Result<Unit>` so the gate's `runCatching`/pattern-match flow
 *     stays uniform with the unpack step.
 *
 * Callers that want the raw `Boolean` (e.g. diagnostic tools) should call
 * `MapStorageProxy.savePlayerMap` directly.
 */
object MapUploadGateStorage
{
    /**
     * Test seam: when non-null, overrides the real `MapStorageProxy.savePlayerMap`
     * call. Defaults to null so production calls go through to the AGS proxy.
     */
    @Volatile
    internal var fakeSaver: ((context: RpcCallContext, request: SavePlayerMapRequest) -> Boolean)? = null

    internal fun resetForTest()
    {
        fakeSaver = null
    }

    /**
     * Persists the pack bytes as a Player Binary Record + catalogue entry.
     *
     * @param context RPC call context. Forwarded to `MapStorageProxy.savePlayerMap`.
     * @param userId AccelByte user ID whose namespace the record lives under.
     * @param mapId Client-generated UUID-style identifier (binary record key = `map-<mapId>`).
     * @param mapName Human-friendly display name (≤128 chars enforced by the proxy).
     * @param mapPackBytes Raw pack bytes (typically the same bytes the client
     *   sent in the `MapUploadRequest`).
     * @return `Result.success(Unit)` when the upload succeeded; `Result.failure`
     *   with the underlying exception when the save failed.
     */
    suspend fun savePack(
        context: RpcCallContext,
        userId: String,
        mapId: String,
        mapName: String,
        mapPackBytes: ByteArray
    ): Result<Unit>
    {
        val saver = fakeSaver
        val request = SavePlayerMapRequest(
            userId = userId,
            mapId = mapId,
            mapName = mapName,
            mapPackBytes = mapPackBytes,
            contentType = "application/zip"
        )

        return runCatching {
            val ok = if (saver != null)
            {
                saver(context, request)
            }
            else
            {
                MapStorageProxy.savePlayerMap(context, request)
            }
            if (!ok)
            {
                Logger.error(LogCategory.DATABASE, "MapUploadGateStorage: savePack failed for $userId/$mapId")
                throw RuntimeException("MapStorageProxy.savePlayerMap returned false for $userId/$mapId")
            }
        }
    }
}
