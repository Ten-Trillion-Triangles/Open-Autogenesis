package org.ttt.autogenesis.kvisionapp.mapUpload

import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.MapUploadErrorData
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcDirection
import org.ttt.autogenesis.network.RpcMethod

/**
 * Client-side handler for the `Map.Upload.Error` notification.
 *
 * Server-extend's `MapUploadErrorHandlers` singleton pushes this notification
 * to the originating client when the map safety classifier rejects an
 * upload. The handler is auto-registered via the rpc-ksp-generated
 * `registerMapUploadErrorClientRpcHandlers(this, MapUploadErrorClientHandlers)`
 * function — do NOT call `RpcRegistry.register("Map.Upload.Error", ...)`
 * manually here, the auto-registration contract handles wiring.
 *
 * Stage 1 behavior: log the rejection. Stage 2 will replace the body with a
 * `MessageBox` so the rejection reason surfaces in the standard UI dialog.
 * The wiring contract (method name, payload shape, RpcDirection) does not
 * change between stages.
 */
object MapUploadErrorClientHandlers
{
    @RpcMethod(name = "Map.Upload.Error", direction = RpcDirection.CLIENT)
    suspend fun handleMapUploadError(_ctx: RpcCallContext, data: MapUploadErrorData)
    {
        Logger.warn(LogCategory.NETWORK, "Map.Upload.Error received: reason='${data.reason}'")
        // Route through the static hook registered at CollectionOverlay
        // construction time. The hook dismisses the modal and surfaces
        // the failure MessageBox. Null-safe: if no overlay is mounted,
        // this is a no-op.
        ui.MapUploadModal.handleUploadErrorNotification(data.reason)
    }
}
