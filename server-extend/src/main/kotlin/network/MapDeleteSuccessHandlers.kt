package network

import kotlinx.serialization.serializer
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.MapDeleteSuccessData
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcMessage
import org.ttt.autogenesis.serverextend.RestPlayerConnectionManager

/**
 * Server-extend → client notifications for successful map deletes.
 *
 * Owns the [RestPlayerConnectionManager] (injected at startup, mirroring
 * [MapUploadSuccessHandlers]) and pushes typed notifications down through the
 * originating client's SSE session when the AGS binary record has been removed
 * AND the catalogue entry has been stripped by [proxy.MapStorageProxy.deletePlayerMap].
 *
 * The `Map.Delete.Success` notification payload is the shared
 * [MapDeleteSuccessData] DTO. The matching client-side handler is registered in
 * `kvisionApp/.../mapUpload/MapDeleteSuccessClientHandlers.kt` against
 * `RpcDirection.CLIENT`.
 *
 * Mirrors [MapUploadSuccessHandlers] exactly — composed on the same singleton
 * pattern with the same `registerConnectionManager` injection seam so the
 * ServerExtend bootstrap can wire both handlers in two adjacent lines.
 */
object MapDeleteSuccessHandlers
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
     * [org.ttt.autogenesis.serverextend.ServerExtendKt.Application.serverExtendModule]
     * at startup, mirroring the `MapUploadSuccessHandlers.registerConnectionManager`
     * call.
     */
    internal fun registerConnectionManager(manager: RestPlayerConnectionManager)
    {
        connectionManager = manager
    }

    /**
     * Internal accessor so peer singletons (e.g.
     * [org.ttt.autogenesis.proxy.MapStorageProxy.deletePlayerMap]) can resolve
     * the session without a parallel registration seam. Not for production
     * routing — production callers should use [RestPlayerConnectionManager.findSession]
     * directly.
     */
    internal fun currentConnectionManager(): RestPlayerConnectionManager? = connectionManager

    /**
     * Pushes a `Map.Delete.Success` notification to the player whose map
     * has been deleted.
     *
     * Mirrors [MapUploadSuccessHandlers.sendMapUploadSuccess]'s shape: typed DTO
     * + `RpcJson.encodeToJsonElement` + `RpcMessage.Notification` +
     * `session.sendRpcMessage`. No-ops with a WARN log when the player has
     * no live SSE session (mirrors the "session not found" branches in
     * `MapUploadSuccessHandlers`).
     *
     * @param playerId Originating player whose map was deleted.
     * @param mapId The just-deleted map's identifier.
     * @param mapName Human-readable display name echoed at delete time.
     */
    suspend fun sendMapDeleteSuccess(playerId: String, mapId: String, mapName: String)
    {
        val manager = connectionManager
        if (manager == null)
        {
            Logger.warn(LogCategory.NETWORK, "MapDeleteSuccessHandlers: sendMapDeleteSuccess() called before registerConnectionManager() — dropping notification for playerId=$playerId")
            return
        }

        val session = manager.findSession(playerId)
        if (session == null)
        {
            Logger.warn(LogCategory.NETWORK, "MapDeleteSuccessHandlers: Cannot send map delete success, session '$playerId' not found")
            return
        }

        val data = MapDeleteSuccessData(mapId = mapId, mapName = mapName)
        val payload = RpcJson.encodeToJsonElement(MapDeleteSuccessData.serializer(), data)
        val notification = RpcMessage.Notification("Map.Delete.Success", payload)

        Logger.info(LogCategory.NETWORK, "MapDeleteSuccessHandlers: Dispatching 'Map.Delete.Success' to $playerId (mapId='$mapId', mapName='$mapName')")
        session.sendRpcMessage(notification)
    }
}