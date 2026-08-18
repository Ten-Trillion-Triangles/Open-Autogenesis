package network

import kotlinx.serialization.serializer
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.MapDeleteErrorData
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcMessage
import org.ttt.autogenesis.serverextend.RestPlayerConnectionManager

/**
 * Server-extend → client notifications for failed map deletes.
 *
 * Owns the [RestPlayerConnectionManager] (injected at startup, mirroring
 * [MapUploadErrorHandlers]) and pushes typed notifications down through the
 * originating client's SSE session when the AGS binary record delete or the
 * catalogue remove fails inside [proxy.MapStorageProxy.deletePlayerMap].
 *
 * The `Map.Delete.Error` notification payload is the shared
 * [MapDeleteErrorData] DTO. The matching client-side handler is registered in
 * `kvisionApp/.../mapUpload/MapDeleteErrorClientHandlers.kt` against
 * `RpcDirection.CLIENT`.
 *
 * Mirrors [MapUploadErrorHandlers] exactly — composed on the same singleton
 * pattern with the same `registerConnectionManager` injection seam so the
 * ServerExtend bootstrap can wire both handlers in two adjacent lines.
 */
object MapDeleteErrorHandlers
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
     * at startup, mirroring the `MapUploadErrorHandlers.registerConnectionManager`
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
     * Pushes a `Map.Delete.Error` notification to the player whose map delete
     * request failed.
     *
     * Mirrors [MapUploadErrorHandlers.sendMapUploadError]'s shape: typed DTO
     * + `RpcJson.encodeToJsonElement` + `RpcMessage.Notification` +
     * `session.sendRpcMessage`. No-ops with a WARN log when the player has
     * no live SSE session (mirrors the "session not found" branches in
     * `MapUploadErrorHandlers`).
     *
     * @param playerId Originating player whose delete failed.
     * @param reason Human-readable rejection reason from the proxy or auth check.
     */
    suspend fun sendMapDeleteError(playerId: String, reason: String)
    {
        val manager = connectionManager
        if (manager == null)
        {
            Logger.warn(LogCategory.NETWORK, "MapDeleteErrorHandlers: sendMapDeleteError() called before registerConnectionManager() — dropping notification for playerId=$playerId")
            return
        }

        val session = manager.findSession(playerId)
        if (session == null)
        {
            Logger.warn(LogCategory.NETWORK, "MapDeleteErrorHandlers: Cannot send map delete error, session '$playerId' not found")
            return
        }

        val data = MapDeleteErrorData(reason = reason)
        val payload = RpcJson.encodeToJsonElement(MapDeleteErrorData.serializer(), data)
        val notification = RpcMessage.Notification("Map.Delete.Error", payload)

        Logger.info(LogCategory.NETWORK, "MapDeleteErrorHandlers: Dispatching 'Map.Delete.Error' to $playerId (reason='$reason')")
        session.sendRpcMessage(notification)
    }
}
