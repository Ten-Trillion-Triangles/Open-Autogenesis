package org.ttt.autogenesis.kvisionapp.mapUpload

import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.MapDeleteSuccessData
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcDirection
import org.ttt.autogenesis.network.RpcMethod

/**
 * Client-side handler for the `Map.Delete.Success` notification.
 *
 * Server-extend's `MapDeleteSuccessHandlers` singleton pushes this notification
 * to the originating client after the AGS binary record has been removed AND
 * the catalogue entry has been stripped. The handler is auto-registered via
 * the rpc-ksp-generated `registerMapDeleteSuccessClientRpcHandlers(this, ...)`
 * function — do NOT call `RpcRegistry.register("Map.Delete.Success", ...)`
 * manually here, the auto-registration contract handles wiring.
 *
 * Routes through the static hook registered at `MapUploadModal` construction
 * time. The hook dismisses the active detail window, surfaces the success
 * MessageBox, and re-fetches the catalogue so the deleted row vanishes.
 */
object MapDeleteSuccessClientHandlers
{
    @RpcMethod(name = "Map.Delete.Success", direction = RpcDirection.CLIENT)
    suspend fun handleMapDeleteSuccess(_ctx: RpcCallContext, data: MapDeleteSuccessData)
    {
        Logger.info(
            LogCategory.NETWORK,
            "Map.Delete.Success received: mapId='${data.mapId}', mapName='${data.mapName}'"
        )
        ui.MapUploadModal.handleDeleteSuccessNotification(data.mapId, data.mapName)
    }
}
