package proxy

import accelbyte.cloudsave.BinaryRecord
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcDirection
import org.ttt.autogenesis.network.RpcMethod
import structs.accelbyte.cloudsave.BulkGameBinaryRecordResponse
import structs.accelbyte.cloudsave.BulkPlayerBinaryRecordResponse
import structs.accelbyte.cloudsave.GameBinaryRecordAdminListResponse
import structs.accelbyte.cloudsave.GameBinaryRecordAdminResponse
import structs.accelbyte.cloudsave.GameBinaryRecordCreateRequest
import structs.accelbyte.cloudsave.GameBinaryRecordListResponse
import structs.accelbyte.cloudsave.GameBinaryRecordMetadata
import structs.accelbyte.cloudsave.GameBinaryRecordResponse
import structs.accelbyte.cloudsave.PlayerBinaryRecordListResponse
import structs.accelbyte.cloudsave.PlayerBinaryRecordResponse
import structs.accelbyte.cloudsave.UploadBinaryRecordRequest
import structs.accelbyte.cloudsave.UploadBinaryRecordResponse
import structs.rpcRequests.AdminCreateGameBinaryRequest
import structs.rpcRequests.AdminCreatePlayerBinaryRequest
import structs.rpcRequests.AdminDeleteGameBinaryRequest
import structs.rpcRequests.AdminDeletePlayerBinaryRequest
import structs.rpcRequests.AdminGameBinaryMetadataRequest
import structs.rpcRequests.AdminGameBinaryPresignedRequest
import structs.rpcRequests.AdminGetGameBinaryRequest
import structs.rpcRequests.AdminGetPlayerBinaryRequest
import structs.rpcRequests.AdminListGameBinaryRequest
import structs.rpcRequests.AdminListPlayerBinaryRequest
import structs.rpcRequests.AdminPlayerBinaryMetadataRequest
import structs.rpcRequests.AdminPlayerBinaryPresignedRequest
import structs.rpcRequests.AdminReplaceGameBinaryRequest
import structs.rpcRequests.AdminReplacePlayerBinaryRequest
import structs.rpcRequests.BulkGetGameBinaryRequest
import structs.rpcRequests.BulkGetMyBinaryRequest
import structs.rpcRequests.CreateGameBinaryRequest
import structs.rpcRequests.CreateMyBinaryRequest
import structs.rpcRequests.DeleteGameBinaryRequest
import structs.rpcRequests.DeleteMyBinaryRequest
import structs.rpcRequests.GameBinaryPresignedRequest
import structs.rpcRequests.GetGameBinaryRequest
import structs.rpcRequests.GetMyBinaryRequest
import structs.rpcRequests.ListGameBinaryRequest
import structs.rpcRequests.ListMyBinaryRequest
import structs.rpcRequests.MyBinaryPresignedRequest
import structs.rpcRequests.ReplaceGameBinaryRequest
import structs.rpcRequests.ReplaceMyBinaryRequest

/**
 * Server-extend CORS-bypass proxy for AccelByte Cloud Save binary records.
 *
 * Mirrors the JSON-record pattern from [CloudSaveProxy] but routes binary
 * metadata calls through [accelbyte.cloudsave.BinaryRecord] instead of the
 * local VirtualFileSystemManager. The web client calls these RPCs to reach
 * AGS without tripping CORS preflight failures.
 *
 * **Important:** Byte uploads/downloads are NOT proxied. The client uses
 * the `UploadBinaryRecordResponse.url` returned by the presigned-URL RPCs
 * to PUT bytes directly to AGS/S3 (and to GET bytes directly back). This
 * proxy only mediates the metadata layer.
 *
 * IAM permission gap: the OAuth client used by server-extend must hold
 * `cloudsave:game:binary:read/write/admin` and `cloudsave:player:binary:*`
 * on the AGS namespace. See the binary-record runbook for details.
 */
object BinaryRecordProxy
{
    private fun BinaryRecordCreateRequestFrom(key: String, fileType: String, setBy: String?): GameBinaryRecordCreateRequest =
        GameBinaryRecordCreateRequest(key = key, fileType = fileType, setBy = setBy)

    private fun PresignedFrom(fileType: String): UploadBinaryRecordRequest =
        UploadBinaryRecordRequest(fileType = fileType)

    // ---- Public Game Binary --------------------------------------------------

    @RpcMethod("server.extend.listGameBinaries", RpcDirection.SERVER)
    suspend fun listGameBinaries(context: RpcCallContext, request: ListGameBinaryRequest): GameBinaryRecordListResponse
    {
        Logger.info(LogCategory.DATABASE, "BinaryRecordProxy: listGameBinaries limit=${request.limit} offset=${request.offset}")
        return BinaryRecord.listBinaries(
            limit = request.limit,
            offset = request.offset,
            query = request.query,
            tags = request.tags
        ).getOrElse { err ->
            Logger.error(LogCategory.DATABASE, "BinaryRecordProxy: listGameBinaries failed: ${err.message}")
            GameBinaryRecordListResponse(data = emptyList())
        }
    }

    @RpcMethod("server.extend.getGameBinary", RpcDirection.SERVER)
    suspend fun getGameBinary(context: RpcCallContext, request: GetGameBinaryRequest): GameBinaryRecordResponse
    {
        Logger.info(LogCategory.DATABASE, "BinaryRecordProxy: getGameBinary key='${request.key}'")
        return BinaryRecord.getBinary(request.key).getOrElse { err ->
            Logger.error(LogCategory.DATABASE, "BinaryRecordProxy: getGameBinary failed: ${err.message}")
            throw err
        }
    }

    @RpcMethod("server.extend.createGameBinary", RpcDirection.SERVER)
    suspend fun createGameBinary(context: RpcCallContext, request: CreateGameBinaryRequest): UploadBinaryRecordResponse
    {
        Logger.info(LogCategory.DATABASE, "BinaryRecordProxy: createGameBinary key='${request.key}' fileType='${request.fileType}'")
        return BinaryRecord.createBinary(
            BinaryRecordCreateRequestFrom(request.key, request.fileType, request.setBy)
        ).getOrElse { err ->
            Logger.error(LogCategory.DATABASE, "BinaryRecordProxy: createGameBinary failed: ${err.message}")
            throw err
        }
    }

    @RpcMethod("server.extend.replaceGameBinary", RpcDirection.SERVER)
    suspend fun replaceGameBinary(context: RpcCallContext, request: ReplaceGameBinaryRequest): GameBinaryRecordResponse
    {
        Logger.info(LogCategory.DATABASE, "BinaryRecordProxy: replaceGameBinary key='${request.key}'")
        return BinaryRecord.replaceBinary(
            key = request.key,
            request = structs.accelbyte.cloudsave.BinaryRecordRequest(
                contentType = request.contentType,
                fileLocation = request.fileLocation
            )
        ).getOrElse { err ->
            Logger.error(LogCategory.DATABASE, "BinaryRecordProxy: replaceGameBinary failed: ${err.message}")
            throw err
        }
    }

    @RpcMethod("server.extend.deleteGameBinary", RpcDirection.SERVER)
    suspend fun deleteGameBinary(context: RpcCallContext, request: DeleteGameBinaryRequest): Boolean
    {
        Logger.info(LogCategory.DATABASE, "BinaryRecordProxy: deleteGameBinary key='${request.key}'")
        return BinaryRecord.deleteBinary(request.key).isSuccess
    }

    @RpcMethod("server.extend.bulkGetGameBinaries", RpcDirection.SERVER)
    suspend fun bulkGetGameBinaries(context: RpcCallContext, request: BulkGetGameBinaryRequest): BulkGameBinaryRecordResponse
    {
        Logger.info(LogCategory.DATABASE, "BinaryRecordProxy: bulkGetGameBinaries count=${request.keys.size}")
        return BinaryRecord.bulkFetch(request.keys).getOrElse { err ->
            Logger.error(LogCategory.DATABASE, "BinaryRecordProxy: bulkGetGameBinaries failed: ${err.message}")
            BulkGameBinaryRecordResponse(records = emptyList())
        }
    }

    @RpcMethod("server.extend.gameBinaryPresignedUrl", RpcDirection.SERVER)
    suspend fun gameBinaryPresignedUrl(context: RpcCallContext, request: GameBinaryPresignedRequest): UploadBinaryRecordResponse
    {
        Logger.info(LogCategory.DATABASE, "BinaryRecordProxy: gameBinaryPresignedUrl key='${request.key}' fileType='${request.fileType}'")
        return BinaryRecord.requestPresignedUrl(
            key = request.key,
            uploadRequest = PresignedFrom(request.fileType)
        ).getOrElse { err ->
            Logger.error(LogCategory.DATABASE, "BinaryRecordProxy: gameBinaryPresignedUrl failed: ${err.message}")
            throw err
        }
    }

    // ---- Admin Game Binary ---------------------------------------------------

    @RpcMethod("server.extend.adminListGameBinaries", RpcDirection.SERVER)
    suspend fun adminListGameBinaries(context: RpcCallContext, request: AdminListGameBinaryRequest): GameBinaryRecordAdminListResponse
    {
        Logger.info(LogCategory.DATABASE, "BinaryRecordProxy: adminListGameBinaries limit=${request.limit} offset=${request.offset}")
        return BinaryRecord.listAdminRecords(
            limit = request.limit,
            offset = request.offset,
            query = request.query,
            tags = request.tags
        ).getOrElse { err ->
            Logger.error(LogCategory.DATABASE, "BinaryRecordProxy: adminListGameBinaries failed: ${err.message}")
            GameBinaryRecordAdminListResponse(records = emptyList())
        }
    }

    @RpcMethod("server.extend.adminGetGameBinary", RpcDirection.SERVER)
    suspend fun adminGetGameBinary(context: RpcCallContext, request: AdminGetGameBinaryRequest): GameBinaryRecordAdminResponse
    {
        Logger.info(LogCategory.DATABASE, "BinaryRecordProxy: adminGetGameBinary key='${request.key}'")
        return BinaryRecord.adminGetBinary(request.key).getOrElse { err ->
            Logger.error(LogCategory.DATABASE, "BinaryRecordProxy: adminGetGameBinary failed: ${err.message}")
            throw err
        }
    }

    @RpcMethod("server.extend.adminCreateGameBinary", RpcDirection.SERVER)
    suspend fun adminCreateGameBinary(context: RpcCallContext, request: AdminCreateGameBinaryRequest): UploadBinaryRecordResponse
    {
        Logger.info(LogCategory.DATABASE, "BinaryRecordProxy: adminCreateGameBinary key='${request.key}'")
        return BinaryRecord.adminCreateBinary(
            BinaryRecordCreateRequestFrom(request.key, request.fileType, request.setBy)
        ).getOrElse { err ->
            Logger.error(LogCategory.DATABASE, "BinaryRecordProxy: adminCreateGameBinary failed: ${err.message}")
            throw err
        }
    }

    @RpcMethod("server.extend.adminReplaceGameBinary", RpcDirection.SERVER)
    suspend fun adminReplaceGameBinary(context: RpcCallContext, request: AdminReplaceGameBinaryRequest): GameBinaryRecordAdminResponse
    {
        Logger.info(LogCategory.DATABASE, "BinaryRecordProxy: adminReplaceGameBinary key='${request.key}'")
        return BinaryRecord.adminReplaceBinary(
            key = request.key,
            request = structs.accelbyte.cloudsave.BinaryRecordRequest(
                contentType = request.contentType,
                fileLocation = request.fileLocation
            )
        ).getOrElse { err ->
            Logger.error(LogCategory.DATABASE, "BinaryRecordProxy: adminReplaceGameBinary failed: ${err.message}")
            throw err
        }
    }

    @RpcMethod("server.extend.adminDeleteGameBinary", RpcDirection.SERVER)
    suspend fun adminDeleteGameBinary(context: RpcCallContext, request: AdminDeleteGameBinaryRequest): Boolean
    {
        Logger.info(LogCategory.DATABASE, "BinaryRecordProxy: adminDeleteGameBinary key='${request.key}'")
        return BinaryRecord.adminDeleteBinary(request.key).isSuccess
    }

    @RpcMethod("server.extend.adminGameBinaryPresignedUrl", RpcDirection.SERVER)
    suspend fun adminGameBinaryPresignedUrl(context: RpcCallContext, request: AdminGameBinaryPresignedRequest): UploadBinaryRecordResponse
    {
        Logger.info(LogCategory.DATABASE, "BinaryRecordProxy: adminGameBinaryPresignedUrl key='${request.key}'")
        return BinaryRecord.adminRequestPresignedUrl(
            key = request.key,
            uploadRequest = PresignedFrom(request.fileType)
        ).getOrElse { err ->
            Logger.error(LogCategory.DATABASE, "BinaryRecordProxy: adminGameBinaryPresignedUrl failed: ${err.message}")
            throw err
        }
    }

    @RpcMethod("server.extend.adminUpdateGameBinaryMetadata", RpcDirection.SERVER)
    suspend fun adminUpdateGameBinaryMetadata(context: RpcCallContext, request: AdminGameBinaryMetadataRequest): GameBinaryRecordAdminResponse
    {
        Logger.info(LogCategory.DATABASE, "BinaryRecordProxy: adminUpdateGameBinaryMetadata key='${request.key}'")
        return BinaryRecord.adminUpdateMetadata(
            key = request.key,
            metadata = GameBinaryRecordMetadata(setBy = request.setBy, tags = request.tags)
        ).getOrElse { err ->
            Logger.error(LogCategory.DATABASE, "BinaryRecordProxy: adminUpdateGameBinaryMetadata failed: ${err.message}")
            throw err
        }
    }

    // ---- Public Player Binary (/users/me/) -----------------------------------

    @RpcMethod("server.extend.listMyBinaries", RpcDirection.SERVER)
    suspend fun listMyBinaries(context: RpcCallContext, request: ListMyBinaryRequest): PlayerBinaryRecordListResponse
    {
        Logger.info(LogCategory.DATABASE, "BinaryRecordProxy: listMyBinaries limit=${request.limit}")
        return BinaryRecord.listMyBinaries(
            limit = request.limit,
            offset = request.offset,
            query = request.query,
            tags = request.tags
        ).getOrElse { err ->
            Logger.error(LogCategory.DATABASE, "BinaryRecordProxy: listMyBinaries failed: ${err.message}")
            PlayerBinaryRecordListResponse(data = emptyList())
        }
    }

    @RpcMethod("server.extend.getMyBinary", RpcDirection.SERVER)
    suspend fun getMyBinary(context: RpcCallContext, request: GetMyBinaryRequest): PlayerBinaryRecordResponse
    {
        Logger.info(LogCategory.DATABASE, "BinaryRecordProxy: getMyBinary key='${request.key}'")
        return BinaryRecord.getMyBinary(request.key).getOrElse { err ->
            Logger.error(LogCategory.DATABASE, "BinaryRecordProxy: getMyBinary failed: ${err.message}")
            throw err
        }
    }

    @RpcMethod("server.extend.createMyBinary", RpcDirection.SERVER)
    suspend fun createMyBinary(context: RpcCallContext, request: CreateMyBinaryRequest): UploadBinaryRecordResponse
    {
        Logger.info(LogCategory.DATABASE, "BinaryRecordProxy: createMyBinary key='${request.key}'")
        return BinaryRecord.createMyBinary(
            BinaryRecordCreateRequestFrom(request.key, request.fileType, request.setBy)
        ).getOrElse { err ->
            Logger.error(LogCategory.DATABASE, "BinaryRecordProxy: createMyBinary failed: ${err.message}")
            throw err
        }
    }

    @RpcMethod("server.extend.replaceMyBinary", RpcDirection.SERVER)
    suspend fun replaceMyBinary(context: RpcCallContext, request: ReplaceMyBinaryRequest): PlayerBinaryRecordResponse
    {
        Logger.info(LogCategory.DATABASE, "BinaryRecordProxy: replaceMyBinary key='${request.key}'")
        return BinaryRecord.replaceMyBinary(
            key = request.key,
            request = structs.accelbyte.cloudsave.BinaryRecordRequest(
                contentType = request.contentType,
                fileLocation = request.fileLocation
            )
        ).getOrElse { err ->
            Logger.error(LogCategory.DATABASE, "BinaryRecordProxy: replaceMyBinary failed: ${err.message}")
            throw err
        }
    }

    @RpcMethod("server.extend.deleteMyBinary", RpcDirection.SERVER)
    suspend fun deleteMyBinary(context: RpcCallContext, request: DeleteMyBinaryRequest): Boolean
    {
        Logger.info(LogCategory.DATABASE, "BinaryRecordProxy: deleteMyBinary key='${request.key}'")
        return BinaryRecord.deleteMyBinary(request.key).isSuccess
    }

    @RpcMethod("server.extend.bulkGetMyBinaries", RpcDirection.SERVER)
    suspend fun bulkGetMyBinaries(context: RpcCallContext, request: BulkGetMyBinaryRequest): BulkPlayerBinaryRecordResponse
    {
        Logger.info(LogCategory.DATABASE, "BinaryRecordProxy: bulkGetMyBinaries count=${request.keys.size}")
        return BinaryRecord.bulkFetchMy(request.keys).getOrElse { err ->
            Logger.error(LogCategory.DATABASE, "BinaryRecordProxy: bulkGetMyBinaries failed: ${err.message}")
            BulkPlayerBinaryRecordResponse(records = emptyList())
        }
    }

    @RpcMethod("server.extend.myBinaryPresignedUrl", RpcDirection.SERVER)
    suspend fun myBinaryPresignedUrl(context: RpcCallContext, request: MyBinaryPresignedRequest): UploadBinaryRecordResponse
    {
        Logger.info(LogCategory.DATABASE, "BinaryRecordProxy: myBinaryPresignedUrl key='${request.key}'")
        return BinaryRecord.requestMyPresignedUrl(
            key = request.key,
            uploadRequest = PresignedFrom(request.fileType)
        ).getOrElse { err ->
            Logger.error(LogCategory.DATABASE, "BinaryRecordProxy: myBinaryPresignedUrl failed: ${err.message}")
            throw err
        }
    }

    // ---- Admin Player Binary -------------------------------------------------

    @RpcMethod("server.extend.adminListPlayerBinaries", RpcDirection.SERVER)
    suspend fun adminListPlayerBinaries(context: RpcCallContext, request: AdminListPlayerBinaryRequest): PlayerBinaryRecordListResponse
    {
        Logger.info(LogCategory.DATABASE, "BinaryRecordProxy: adminListPlayerBinaries userId='${request.userId}'")
        return BinaryRecord.adminListPlayerBinaries(
            userId = request.userId,
            limit = request.limit,
            offset = request.offset,
            query = request.query,
            tags = request.tags
        ).getOrElse { err ->
            Logger.error(LogCategory.DATABASE, "BinaryRecordProxy: adminListPlayerBinaries failed: ${err.message}")
            PlayerBinaryRecordListResponse(data = emptyList())
        }
    }

    @RpcMethod("server.extend.adminGetPlayerBinary", RpcDirection.SERVER)
    suspend fun adminGetPlayerBinary(context: RpcCallContext, request: AdminGetPlayerBinaryRequest): PlayerBinaryRecordResponse
    {
        Logger.info(LogCategory.DATABASE, "BinaryRecordProxy: adminGetPlayerBinary userId='${request.userId}' key='${request.key}'")
        return BinaryRecord.adminGetPlayerBinary(request.userId, request.key).getOrElse { err ->
            Logger.error(LogCategory.DATABASE, "BinaryRecordProxy: adminGetPlayerBinary failed: ${err.message}")
            throw err
        }
    }

    @RpcMethod("server.extend.adminCreatePlayerBinary", RpcDirection.SERVER)
    suspend fun adminCreatePlayerBinary(context: RpcCallContext, request: AdminCreatePlayerBinaryRequest): UploadBinaryRecordResponse
    {
        Logger.info(LogCategory.DATABASE, "BinaryRecordProxy: adminCreatePlayerBinary userId='${request.userId}' key='${request.key}'")
        return BinaryRecord.adminCreatePlayerBinary(
            userId = request.userId,
            request = BinaryRecordCreateRequestFrom(request.key, request.fileType, request.setBy)
        ).getOrElse { err ->
            Logger.error(LogCategory.DATABASE, "BinaryRecordProxy: adminCreatePlayerBinary failed: ${err.message}")
            throw err
        }
    }

    @RpcMethod("server.extend.adminReplacePlayerBinary", RpcDirection.SERVER)
    suspend fun adminReplacePlayerBinary(context: RpcCallContext, request: AdminReplacePlayerBinaryRequest): PlayerBinaryRecordResponse
    {
        Logger.info(LogCategory.DATABASE, "BinaryRecordProxy: adminReplacePlayerBinary userId='${request.userId}' key='${request.key}'")
        return BinaryRecord.adminReplacePlayerBinary(
            userId = request.userId,
            key = request.key,
            request = structs.accelbyte.cloudsave.BinaryRecordRequest(
                contentType = request.contentType,
                fileLocation = request.fileLocation
            )
        ).getOrElse { err ->
            Logger.error(LogCategory.DATABASE, "BinaryRecordProxy: adminReplacePlayerBinary failed: ${err.message}")
            throw err
        }
    }

    @RpcMethod("server.extend.adminDeletePlayerBinary", RpcDirection.SERVER)
    suspend fun adminDeletePlayerBinary(context: RpcCallContext, request: AdminDeletePlayerBinaryRequest): Boolean
    {
        Logger.info(LogCategory.DATABASE, "BinaryRecordProxy: adminDeletePlayerBinary userId='${request.userId}' key='${request.key}'")
        return BinaryRecord.adminDeletePlayerBinary(request.userId, request.key).isSuccess
    }

    @RpcMethod("server.extend.adminPlayerBinaryPresignedUrl", RpcDirection.SERVER)
    suspend fun adminPlayerBinaryPresignedUrl(context: RpcCallContext, request: AdminPlayerBinaryPresignedRequest): UploadBinaryRecordResponse
    {
        Logger.info(LogCategory.DATABASE, "BinaryRecordProxy: adminPlayerBinaryPresignedUrl userId='${request.userId}' key='${request.key}'")
        return BinaryRecord.adminRequestPlayerPresignedUrl(
            userId = request.userId,
            key = request.key,
            uploadRequest = PresignedFrom(request.fileType)
        ).getOrElse { err ->
            Logger.error(LogCategory.DATABASE, "BinaryRecordProxy: adminPlayerBinaryPresignedUrl failed: ${err.message}")
            throw err
        }
    }

    @RpcMethod("server.extend.adminUpdatePlayerBinaryMetadata", RpcDirection.SERVER)
    suspend fun adminUpdatePlayerBinaryMetadata(context: RpcCallContext, request: AdminPlayerBinaryMetadataRequest): PlayerBinaryRecordResponse
    {
        Logger.info(LogCategory.DATABASE, "BinaryRecordProxy: adminUpdatePlayerBinaryMetadata userId='${request.userId}' key='${request.key}'")
        return BinaryRecord.adminUpdatePlayerMetadata(
            userId = request.userId,
            key = request.key,
            metadata = GameBinaryRecordMetadata(setBy = request.setBy, tags = request.tags)
        ).getOrElse { err ->
            Logger.error(LogCategory.DATABASE, "BinaryRecordProxy: adminUpdatePlayerBinaryMetadata failed: ${err.message}")
            throw err
        }
    }
}