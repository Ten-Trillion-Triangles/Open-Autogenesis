package network

import kotlinx.serialization.serializer
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.MapUploadSuccessData
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcMessage
import org.ttt.autogenesis.serverextend.RestPlayerConnectionManager

/**
 * Server-extend → client notifications for successful map uploads.
 *
 * Owns the [RestPlayerConnectionManager] (injected at startup, mirroring
 * [MapUploadErrorHandlers]) and pushes typed notifications down through the
 * originating client's SSE session when the safety-agent passes AND the
 * pack has been persisted as a Player Binary Record.
 *
 * The `Map.Upload.Success` notification payload is the shared
 * [MapUploadSuccessData] DTO. The matching client-side handler is registered
 * in `kvisionApp/.../Main.kt` against `RpcDirection.CLIENT`.
 *
 * Mirrors [MapUploadErrorHandlers] exactly — composed on the same singleton
 * pattern with the same `registerConnectionManager` injection seam so the
 * ServerExtend bootstrap can wire both handlers in two adjacent lines.
 */
object MapUploadSuccessHandlers
{
    private var connectionManager: RestPlayerConnectionManager? = null

    /**
     * Test seam: empties the injected connection manager so test order
     * does not leak state between cases. NOT for production use.
     */
    internal fun resetForTest()
    {
        connectionManager = null
    }

    /**
     * Wires the live connection manager. Called once from
     * [org.ttt.autogenesis.serverextend.ServerExtendKt.Application.serverModule]
     * at startup, mirroring the `MapUploadErrorHandlers.registerConnectionManager`
     * call.
     */
    internal fun registerConnectionManager(manager: RestPlayerConnectionManager)
    {
        connectionManager = manager
    }

    /**
     * Internal accessor so peer singletons (e.g.
     * [org.ttt.autogenesis.accounting.MapUploadSafetyBilling]) can resolve
     * the session and its `RpcInvoker` without a parallel registration seam.
     * Not for production routing — production callers should use
     * [RestPlayerConnectionManager.findSession] directly.
     */
    internal fun currentConnectionManager(): RestPlayerConnectionManager? = connectionManager

    /**
     * Pushes a `Map.Upload.Success` notification to the player whose map
     * upload has been accepted and persisted.
     *
     * Mirrors [MapUploadErrorHandlers.sendMapUploadError]'s shape: typed DTO
     * + `RpcJson.encodeToJsonElement` + `RpcMessage.Notification` +
     * `session.sendRpcMessage`. No-ops with a WARN log when the player has
     * no live SSE session (mirrors the "session not found" branches in
     * `MapUploadErrorHandlers`).
     *
     * @param playerId Originating player whose map was persisted.
     * @param mapId The newly-persisted map's identifier (UUID-style).
     *   Mirrors the binary record key `map-<mapId>` used by `MapStorageProxy`.
     * @param mapName Human-readable display name (capped at 128 chars by
     *   the storage proxy before persistence).
     */
    suspend fun sendMapUploadSuccess(playerId: String, mapId: String, mapName: String)
    {
        val manager = connectionManager
        if (manager == null)
        {
            Logger.warn(LogCategory.NETWORK, "MapUploadSuccessHandlers: sendMapUploadSuccess() called before registerConnectionManager() — dropping notification for playerId=$playerId")
            return
        }

        val session = manager.findSession(playerId)
        if (session == null)
        {
            Logger.warn(LogCategory.NETWORK, "MapUploadSuccessHandlers: Cannot send map upload success, session '$playerId' not found")
            return
        }

        val data = MapUploadSuccessData(mapId = mapId, mapName = mapName)
        val payload = RpcJson.encodeToJsonElement(MapUploadSuccessData.serializer(), data)
        val notification = RpcMessage.Notification("Map.Upload.Success", payload)

        Logger.info(LogCategory.NETWORK, "MapUploadSuccessHandlers: Dispatching 'Map.Upload.Success' to $playerId (mapId='$mapId', mapName='$mapName')")
        session.sendRpcMessage(notification)
    }
}