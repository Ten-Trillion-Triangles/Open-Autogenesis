package org.ttt.autogenesis.kvisionapp.mapUpload

import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.MapDeleteErrorData
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcDirection
import org.ttt.autogenesis.network.RpcMethod

/**
 * Client-side handler for the `Map.Delete.Error` notification.
 *
 * Server-extend's `MapDeleteErrorHandlers` singleton pushes this notification
 * to the originating client when the AGS binary record delete or the catalogue
 * remove fails (or the auth check rejects the request). The handler is
 * auto-registered via the rpc-ksp-generated
 * `registerMapDeleteErrorClientRpcHandlers(this, ...)` function — do NOT call
 * `RpcRegistry.register("Map.Delete.Error", ...)` manually here, the
 * auto-registration contract handles wiring.
 *
 * Routes through the static hook registered at `MapUploadModal` construction
 * time. The hook surfaces the failure MessageBox so the player sees the
 * reason.
 */
object MapDeleteErrorClientHandlers
{
    @RpcMethod(name = "Map.Delete.Error", direction = RpcDirection.CLIENT)
    suspend fun handleMapDeleteError(_ctx: RpcCallContext, data: MapDeleteErrorData)
    {
        Logger.warn(LogCategory.NETWORK, "Map.Delete.Error received: reason='${data.reason}'")
        ui.MapUploadModal.handleDeleteErrorNotification(data.reason)
    }
}