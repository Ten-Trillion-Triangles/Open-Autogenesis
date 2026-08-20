package org.ttt.autogenesis.network

import gameState.WorldManager
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import kotlinx.serialization.Serializable
import org.ttt.autogenesis.server.maps.MapResourceDescriptor
import org.ttt.autogenesis.server.maps.MapResourceSource
import org.ttt.autogenesis.server.maps.MapResourceRegistry
import org.ttt.autogenesis.server.maps.UploadedMapRepository

/**
 * Handles map-related RPC calls.
 */
@Serializable
data class MapResourceListResponse(val resources: List<MapResourceDescriptor>)

object MapRpcHandlers
{
    /**
     * upload map pack
     */
    @RpcMethod("server.uploadMapPack", RpcDirection.SERVER)
    suspend fun uploadMapPack(context: RpcCallContext, request: MapUploadRequest): Boolean
    {
        Logger.info(LogCategory.NETWORK, "Received map upload request (${request.mapPackBytes.size} bytes)")
        
        try
        {
            val metadata = UploadedMapRepository.registerMap(request.mapName, context.connectionId, request.mapPackBytes)
            Logger.info(LogCategory.NETWORK, "Stored uploaded map ${metadata.mapId} (${metadata.mapName}, ${metadata.sizeBytes} bytes) for ${context.connectionId}")
            WorldManager.loadMapFromPack(request.mapPackBytes, "upload:${context.connectionId}")
            
            // [Bug fix] Broadcast the new map pack to every connected client so
            // the multiplayer view stays in sync. Without this step the uploader
            // sees the new map but every other client stays on the stale pack
            // until they manually reconnect. broadcastMapLoad() gracefully
            // no-ops when the connection manager has not been initialised
            // (early boot, headless TurnHarness runs, etc.) thanks to the
            // null-manager guard in UiSignalRpcHandlers.broadcastNotification.
            org.ttt.autogenesis.server.UiSignalRpcHandlers.broadcastMapLoad(request.mapPackBytes)
            
            return true
        }
        catch (e: Exception)
        {
            Logger.error(LogCategory.NETWORK, "Failed to handle map upload: ${e.message}")
            return false
        }
    }

    /**
     * Returns every known map resource, including packaged assets and live uploads.
     *
     * Clients rely on this registry to populate their map browser and to decide when to call
     * [UploadedMapRepository.getMapBytes] for downloaded data packs.
     */
    @RpcMethod("server.listMapResources", RpcDirection.SERVER)
    suspend fun listMapResources(ctx: RpcCallContext): MapResourceListResponse
    {
        val packaged = MapResourceRegistry.listPackagedMaps().map { path ->
            MapResourceDescriptor(
                path = path,
                source = MapResourceSource.PACKAGED
            )
        }

        val uploadedMetadata = UploadedMapRepository.listUploadedMaps().map { metadata ->
            MapResourceDescriptor(
                path = metadata.mapId,
                source = MapResourceSource.UPLOADED,
                metadata = metadata
            )
        }

        return MapResourceListResponse(packaged + uploadedMetadata)
    }
}