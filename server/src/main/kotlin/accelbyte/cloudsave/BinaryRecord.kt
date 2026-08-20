package accelbyte.cloudsave

import accelbyte.AccelByteSdkProvider
import net.accelbyte.sdk.api.cloudsave.models.*
import net.accelbyte.sdk.api.cloudsave.operations.admin_game_binary_record.*
import net.accelbyte.sdk.api.cloudsave.operations.public_game_binary_record.*
import net.accelbyte.sdk.api.cloudsave.wrappers.AdminGameBinaryRecord
import net.accelbyte.sdk.api.cloudsave.wrappers.PublicGameBinaryRecord
import structs.accelbyte.cloudsave.*

/** Helpers for working with AccelByte Cloud Save binary records. */
object BinaryRecord
{
    private val sdk get() = AccelByteSdkProvider.sdk
    private val namespace get() = AccelByteSdkProvider.namespace
    private val publicWrapper by lazy { PublicGameBinaryRecord(sdk) }
    private val adminWrapper by lazy { AdminGameBinaryRecord(sdk) }

    /**
     * Lists binary records with optional filtering parameters.
     * 
     * @param limit Maximum number of records to return
     * @param offset Number of records to skip for pagination
     * @param query Search query string for filtering records
     * @param tags List of tags to filter records by
     * @return [Result] containing [GameBinaryRecordListResponse] with matching records
     */
    fun listBinaries(
        limit : Int? = null,
        offset : Int? = null,
        query : String? = null,
        tags : List<String>? = null
    ) : Result<GameBinaryRecordListResponse> = runCatching {
        val op = ListGameBinaryRecordsV1.builder()
            .namespace(namespace)
            .limit(limit)
            .offset(offset)
            .query(query)
            .tags(tags)
            .build()
        publicWrapper.listGameBinaryRecordsV1(op).toSharedList()
    }

    /**
     * Retrieves a specific binary record by its key.
     * 
     * @param key Unique identifier for the binary record
     * @return [Result] containing [GameBinaryRecordResponse] with record details
     */
    fun getBinary(key : String) : Result<GameBinaryRecordResponse> = runCatching {
        val op = GetGameBinaryRecordV1.builder()
            .namespace(namespace)
            .key(key)
            .build()
        publicWrapper.getGameBinaryRecordV1(op).toSharedRecord()
    }

    /**
     * Creates a new binary record with the provided request data.
     * 
     * @param request [GameBinaryRecordCreateRequest] containing record creation details
     * @return [Result] containing [UploadBinaryRecordResponse] with upload information
     */
    fun createBinary(request : GameBinaryRecordCreateRequest) : Result<UploadBinaryRecordResponse> =
        runCatching {
            val op = PostGameBinaryRecordV1.builder()
                .namespace(namespace)
                .body(request.toModelsPublicGameBinaryRecordCreate())
                .build()
            publicWrapper.postGameBinaryRecordV1(op).toUploadResponse()
        }

    /**
     * Replaces an existing binary record with new data.
     * 
     * @param key Unique identifier for the binary record to replace
     * @param request [BinaryRecordRequest] containing replacement data
     * @return [Result] containing updated [GameBinaryRecordResponse]
     */
    fun replaceBinary(key : String, request : BinaryRecordRequest) : Result<GameBinaryRecordResponse> =
        runCatching {
            val op = PutGameBinaryRecordV1.builder()
                .namespace(namespace)
                .key(key)
                .body(request.toModelsBinaryRecordRequest())
                .build()
            publicWrapper.putGameBinaryRecordV1(op).toSharedRecord()
        }

    /**
     * Deletes a binary record by its key.
     * 
     * @param key Unique identifier for the binary record to delete
     * @return [Result] indicating success or failure of deletion
     */
    fun deleteBinary(key : String) : Result<Unit> = runCatching {
        val op = DeleteGameBinaryRecordV1.builder()
            .namespace(namespace)
            .key(key)
            .build()
        publicWrapper.deleteGameBinaryRecordV1(op)
    }

    /**
     * Retrieves multiple binary records in a single bulk operation.
     * 
     * @param keys List of record keys to fetch
     * @return [Result] containing [BulkGameBinaryRecordResponse] with requested records
     */
    fun bulkFetch(keys : List<String>) : Result<BulkGameBinaryRecordResponse> = runCatching {
        val op = BulkGetGameBinaryRecordV1.builder()
            .namespace(namespace)
            .body(ModelsBulkGetGameRecordRequest.builder().keys(keys).build())
            .build()
        publicWrapper.bulkGetGameBinaryRecordV1(op).toSharedBulk()
    }

    /**
     * Requests a presigned URL for uploading binary data to a record.
     * 
     * @param key Unique identifier for the binary record
     * @param uploadRequest [UploadBinaryRecordRequest] containing upload parameters
     * @return [Result] containing [UploadBinaryRecordResponse] with presigned URL
     */
    fun requestPresignedUrl(
        key : String,
        uploadRequest : UploadBinaryRecordRequest
    ) : Result<UploadBinaryRecordResponse> = runCatching {
        val op = PostGameBinaryPresignedURLV1.builder()
            .namespace(namespace)
            .key(key)
            .body(uploadRequest.toModelsUploadBinaryRecordRequest())
            .build()
        publicWrapper.postGameBinaryPresignedURLV1(op).toUploadResponse()
    }

    /**
     * Lists binary records using admin privileges with extended filtering options.
     * 
     * @param limit Maximum number of records to return
     * @param offset Number of records to skip for pagination
     * @param query Search query string for filtering records
     * @param tags List of tags to filter records by
     * @return [Result] containing [GameBinaryRecordAdminListResponse] with admin record details
     */
    fun listAdminRecords(
        limit : Int,
        offset : Int,
        query : String? = null,
        tags : List<String>? = null
    ) : Result<GameBinaryRecordAdminListResponse> = runCatching {
        val op = AdminListGameBinaryRecordsV1.builder()
            .namespace(namespace)
            .limit(limit)
            .offset(offset)
            .query(query)
            .tags(tags)
            .build()
        adminWrapper.adminListGameBinaryRecordsV1(op).toAdminList()
    }

    /**
     * Retrieves a binary record using admin privileges for extended access.
     * 
     * @param key Unique identifier for the binary record
     * @return [Result] containing [GameBinaryRecordAdminResponse] with admin record details
     */
    fun adminGetBinary(key : String) : Result<GameBinaryRecordAdminResponse> = runCatching {
        val op = AdminGetGameBinaryRecordV1.builder()
            .namespace(namespace)
            .key(key)
            .build()
        adminWrapper.adminGetGameBinaryRecordV1(op).toAdminResponse()
    }

    /**
     * Creates a binary record using admin privileges with extended permissions.
     * 
     * @param request [GameBinaryRecordCreateRequest] containing record creation details
     * @return [Result] containing [UploadBinaryRecordResponse] with upload information
     */
    fun adminCreateBinary(request : GameBinaryRecordCreateRequest) : Result<UploadBinaryRecordResponse> =
        runCatching {
            val op = AdminPostGameBinaryRecordV1.builder()
                .namespace(namespace)
                .body(request.toModelsGameBinaryRecordCreate())
                .build()
            adminWrapper.adminPostGameBinaryRecordV1(op).toUploadResponse()
        }

    /**
     * Replaces a binary record using admin privileges.
     * 
     * @param key Unique identifier for the binary record to replace
     * @param request [BinaryRecordRequest] containing replacement data
     * @return [Result] containing updated [GameBinaryRecordAdminResponse]
     */
    fun adminReplaceBinary(
        key : String,
        request : BinaryRecordRequest
    ) : Result<GameBinaryRecordAdminResponse> = runCatching {
        val op = AdminPutGameBinaryRecordV1.builder()
            .namespace(namespace)
            .key(key)
            .body(request.toModelsBinaryRecordRequest())
            .build()
        adminWrapper.adminPutGameBinaryRecordV1(op).toAdminResponse()
    }

    /**
     * Deletes a binary record using admin privileges.
     * 
     * @param key Unique identifier for the binary record to delete
     * @return [Result] indicating success or failure of deletion
     */
    fun adminDeleteBinary(key : String) : Result<Unit> = runCatching {
        val op = AdminDeleteGameBinaryRecordV1.builder()
            .namespace(namespace)
            .key(key)
            .build()
        adminWrapper.adminDeleteGameBinaryRecordV1(op)
    }

    /**
     * Requests a presigned URL for uploading binary data using admin privileges.
     * 
     * @param key Unique identifier for the binary record
     * @param uploadRequest [UploadBinaryRecordRequest] containing upload parameters
     * @return [Result] containing [UploadBinaryRecordResponse] with presigned URL
     */
    fun adminRequestPresignedUrl(
        key : String,
        uploadRequest : UploadBinaryRecordRequest
    ) : Result<UploadBinaryRecordResponse> = runCatching {
        val op = AdminPostGameBinaryPresignedURLV1.builder()
            .namespace(namespace)
            .key(key)
            .body(uploadRequest.toModelsUploadBinaryRecordRequest())
            .build()
        adminWrapper.adminPostGameBinaryPresignedURLV1(op).toUploadResponse()
    }

    /**
     * Updates metadata for a binary record using admin privileges.
     * 
     * @param key Unique identifier for the binary record
     * @param metadata [GameBinaryRecordMetadata] containing new metadata
     * @return [Result] containing updated [GameBinaryRecordAdminResponse]
     */
    fun adminUpdateMetadata(
        key : String,
        metadata : GameBinaryRecordMetadata
    ) : Result<GameBinaryRecordAdminResponse> = runCatching {
        val op = AdminPutGameBinaryRecorMetadataV1.builder()
            .namespace(namespace)
            .key(key)
            .body(metadata.toModelsMetadataRequest())
            .build()
        adminWrapper.adminPutGameBinaryRecorMetadataV1(op).toAdminResponse()
    }

    private fun ModelsGameBinaryRecordResponse.toSharedRecord() : GameBinaryRecordResponse =
        GameBinaryRecordResponse(
            key = key,
            namespace = namespace,
            binaryInfo = binaryInfo?.toBinaryInfo(),
            tags = tags,
            setBy = setBy,
            createdAt = createdAt,
            updatedAt = updatedAt
        )

    private fun ModelsListGameBinaryRecordsResponse.toSharedList() : GameBinaryRecordListResponse =
        GameBinaryRecordListResponse(
            data = data?.map { it.toSharedRecord() } ?: emptyList(),
            paging = paging?.toPaging()
        )

    private fun ModelsBulkGetGameBinaryRecordResponse.toSharedBulk() : BulkGameBinaryRecordResponse =
        BulkGameBinaryRecordResponse(records = data?.map { it.toSharedRecord() } ?: emptyList())

    private fun ModelsUploadBinaryRecordResponse.toUploadResponse() : UploadBinaryRecordResponse =
        UploadBinaryRecordResponse(
            contentType = contentType,
            fileLocation = fileLocation,
            url = url,
            version = version
        )

    private fun ModelsGameBinaryRecordAdminResponse.toAdminResponse() : GameBinaryRecordAdminResponse =
        GameBinaryRecordAdminResponse(
            key = key,
            namespace = namespace,
            binaryInfo = binaryInfo?.toBinaryInfo(),
            tags = tags,
            setBy = setBy,
            ttlConfig = ttlConfig.toGameRecordTtlConfig(),
            createdAt = createdAt,
            updatedAt = updatedAt
        )

    private fun ModelsBinaryInfoResponse.toBinaryInfo() : BinaryInfo =
        BinaryInfo(
            contentType = contentType,
            createdAt = createdAt,
            fileLocation = fileLocation,
            updatedAt = updatedAt,
            url = url,
            version = version ?: 0
        )

    private fun ModelsPagination.toPaging() : GameRecordPagingInfo =
        GameRecordPagingInfo(first, last, next, previous)

    private fun ModelsListGameBinaryRecordsAdminResponse.toAdminList() : GameBinaryRecordAdminListResponse =
        GameBinaryRecordAdminListResponse(
            records = data?.map { it.toAdminResponse() } ?: emptyList(),
            paging = paging?.toPaging()
        )

}