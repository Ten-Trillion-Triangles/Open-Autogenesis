package network

import kotlinx.serialization.serializer
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.MapUploadErrorData
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcMessage
import org.ttt.autogenesis.serverextend.RestPlayerConnectionManager

/**
 * Server-extend → client notifications for map upload outcomes.
 *
 * Owns the [RestPlayerConnectionManager] (injected at startup, mirroring
 * [org.ttt.autogenesis.server.UiSignalRpcHandlers]) and pushes typed
 * notifications down through the originating client's SSE session.
 *
 * The `Map.Upload.Error` notification payload is the shared
 * [MapUploadErrorData] DTO. The matching client-side handler is
 * registered in `kvisionApp/.../Main.kt` against
 * `RpcDirection.CLIENT`.
 */
object MapUploadErrorHandlers
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
     * [ServerExtendKt.Application.serverExtendModule] at startup,
     * mirroring the `UiSignalRpcHandlers.connectionManager = ...` injection
     * site in `server/src/main/kotlin/org/ttt/autogenesis/server/Server.kt`.
     */
    internal fun registerConnectionManager(manager: RestPlayerConnectionManager)
    {
        connectionManager = manager
    }

    /**
     * Pushes a `Map.Upload.Error` notification to the player whose map
     * upload was rejected by the safety classifier.
     *
     * Mirrors [org.ttt.autogenesis.server.UiSignalRpcHandlers.sendTurnTimerUpdate]'s
     * shape: typed DTO + `RpcJson.encodeToJsonElement` + `RpcMessage.Notification`
     * + `session.sendRpcMessage`. No-ops with a WARN log when the player
     * has no live SSE session (mirrors the "session not found" branches
     * in `UiSignalRpcHandlers`).
     *
     * @param playerId Originating player whose map was rejected.
     * @param reason Human-readable rejection reason from the safety classifier.
     */
    suspend fun sendMapUploadError(playerId: String, reason: String)
    {
        val manager = connectionManager
        if (manager == null)
        {
            Logger.warn(LogCategory.NETWORK, "MapUploadErrorHandlers: sendMapUploadError() called before registerConnectionManager() — dropping notification for playerId=$playerId")
            return
        }

        val session = manager.findSession(playerId)
        if (session == null)
        {
            Logger.warn(LogCategory.NETWORK, "MapUploadErrorHandlers: Cannot send map upload error, session '$playerId' not found")
            return
        }

        val data = MapUploadErrorData(reason = reason)
        val payload = RpcJson.encodeToJsonElement(MapUploadErrorData.serializer(), data)
        val notification = RpcMessage.Notification("Map.Upload.Error", payload)

        Logger.info(LogCategory.NETWORK, "MapUploadErrorHandlers: Dispatching 'Map.Upload.Error' to $playerId (reason='$reason')")
        session.sendRpcMessage(notification)
    }
}
