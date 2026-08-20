package org.ttt.autogenesis.kvisionapp.mapUpload

import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.MapUploadSuccessData
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcDirection
import org.ttt.autogenesis.network.RpcMethod

/**
 * Client-side handler for the `Map.Upload.Success` notification.
 *
 * Server-extend's `MapUploadSuccessHandlers` singleton pushes this notification
 * to the originating client after the safety-agent passes AND the pack has
 * been persisted as a Player Binary Record via `MapUploadGate`. The handler
 * is auto-registered via the rpc-ksp-generated
 * `registerMapUploadSuccessClientRpcHandlers(this, MapUploadSuccessClientHandlers)`
 * function — do NOT call `RpcRegistry.register("Map.Upload.Success", ...)`
 * manually here, the auto-registration contract handles wiring.
 *
 * Stage 1 behavior: log the success and surface the new mapId. Stage 2 will
 * replace the body with a `MessageBox` and trigger a map-list refresh using
 * the new mapId. The wiring contract (method name, payload shape,
 * RpcDirection) does not change between stages.
 */
object MapUploadSuccessClientHandlers
{
    @RpcMethod(name = "Map.Upload.Success", direction = RpcDirection.CLIENT)
    suspend fun handleMapUploadSuccess(_ctx: RpcCallContext, data: MapUploadSuccessData)
    {
        Logger.info(
            LogCategory.NETWORK,
            "Map.Upload.Success received: mapId='${data.mapId}', mapName='${data.mapName}'"
        )
        // Route through the static hook registered at CollectionOverlay
        // construction time. The hook is responsible for dismissing the
        // modal, surfacing the success MessageBox, and re-fetching the
        // catalogue. Null-safe: if no overlay is mounted, this is a no-op.
        ui.MapUploadModal.handleUploadSuccessNotification(data.mapName)
    }
}