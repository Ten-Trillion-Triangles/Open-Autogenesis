package proxy

import accelbyte.cloudsave.BinaryRecord
import accelbyte.cloudsave.BinaryRecordOperations
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.network.RpcCallContext
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
import structs.accelbyte.cloudsave.BinaryInfo
import structs.accelbyte.cloudsave.BinaryRecordRequest
import structs.accelbyte.cloudsave.GameRecordPagingInfo
import structs.accelbyte.cloudsave.GameRecordTtlConfig
import structs.accelbyte.cloudsave.UploadBinaryRecordRequest
import structs.accelbyte.cloudsave.UploadBinaryRecordResponse
import structs.rpcRequests.AdminCreateGameBinaryRequest
import structs.rpcRequests.AdminDeleteGameBinaryRequest
import structs.rpcRequests.AdminGameBinaryMetadataRequest
import structs.rpcRequests.AdminGetGameBinaryRequest
import structs.rpcRequests.AdminGetPlayerBinaryRequest
import structs.rpcRequests.AdminListGameBinaryRequest
import structs.rpcRequests.AdminListPlayerBinaryRequest
import structs.rpcRequests.AdminReplaceGameBinaryRequest
import structs.rpcRequests.BulkGetGameBinaryRequest
import structs.rpcRequests.CreateGameBinaryRequest
import structs.rpcRequests.CreateMyBinaryRequest
import structs.rpcRequests.DeleteGameBinaryRequest
import structs.rpcRequests.GameBinaryPresignedRequest
import structs.rpcRequests.GetGameBinaryRequest
import structs.rpcRequests.GetMyBinaryRequest
import structs.rpcRequests.ListGameBinaryRequest
import structs.rpcRequests.MyBinaryPresignedRequest
import structs.rpcRequests.ReplaceGameBinaryRequest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Unit tests for [BinaryRecordProxy] using a fake [BinaryRecordOperations]
 * implementation. These tests exercise the proxy's branching (success,
 * failure fallback to empty) without booting the AccelByte SDK or hitting a
 * live namespace.
 *
 * Each test sets [BinaryRecord.operationsFactory] to a recording fake, runs
 * the proxy method, and asserts the proxy translated the request correctly
 * and surfaced the result (or empty fallback on failure).
 */
class BinaryRecordProxyTest
{
    private val rpcContext: RpcCallContext = RpcCallContext(
        connectionId = "test-conn",
        metadata = mapOf("accelByteId" to "user-1"),
        sender = { /* noop in tests */ }
    )

    private lateinit var fake: RecordingFakeBinaryRecordOps

    @Before
    fun setUp()
    {
        fake = RecordingFakeBinaryRecordOps()
        BinaryRecord.operationsFactory = { fake }
    }

    @After
    fun tearDown()
    {
        BinaryRecord.operationsFactory = { accelbyte.cloudsave.RealBinaryRecordOperations() }
    }

    // ---- Public Game Binary --------------------------------------------------

    @Test
    fun `listGameBinaries delegates with limit offset query tags`(): Unit = runBlocking {
        val response = BinaryRecordProxy.listGameBinaries(
            rpcContext,
            ListGameBinaryRequest(limit = 10, offset = 5, query = "tag:test", tags = listOf("foo"))
        )
        assertNotNull(response)
        assertEquals(1, fake.listBinariesCalls.size)
        val call = fake.listBinariesCalls[0]
        assertEquals(10, call.limit)
        assertEquals(5, call.offset)
        assertEquals("tag:test", call.query)
        assertEquals(listOf("foo"), call.tags)
    }

    @Test
    fun `getGameBinary forwards key and surfaces admin response shape`(): Unit = runBlocking {
        val result = BinaryRecordProxy.getGameBinary(rpcContext, GetGameBinaryRequest(key = "splash"))
        assertNotNull(result)
        assertEquals("splash", fake.getBinaryCalls.single())
        assertEquals("splash", result.key)
    }

    @Test
    fun `createGameBinary returns presigned URL response`(): Unit = runBlocking {
        val presigned = BinaryRecordProxy.createGameBinary(
            rpcContext,
            CreateGameBinaryRequest(key = "logo", fileType = "image/png", setBy = "client")
        )
        assertEquals("https://s3.example.com/upload", presigned.url)
        assertEquals(1, presigned.version)
        assertEquals("logo", fake.createBinaryCalls.single().key)
    }

    @Test
    fun `replaceGameBinary returns updated record`(): Unit = runBlocking {
        val result = BinaryRecordProxy.replaceGameBinary(
            rpcContext,
            ReplaceGameBinaryRequest(key = "logo", contentType = "image/png", fileLocation = "s3://bucket/logo.png")
        )
        assertEquals("logo", result.key)
        assertEquals("image/png", fake.replaceBinaryCalls.single().contentType)
    }

    @Test
    fun `deleteGameBinary returns true on success`(): Unit = runBlocking {
        val success = BinaryRecordProxy.deleteGameBinary(rpcContext, DeleteGameBinaryRequest(key = "logo"))
        assertTrue(success)
        assertEquals("logo", fake.deleteBinaryCalls.single())
    }

    @Test
    fun `bulkGetGameBinaries forwards key list`(): Unit = runBlocking {
        val result = BinaryRecordProxy.bulkGetGameBinaries(
            rpcContext,
            BulkGetGameBinaryRequest(keys = listOf("a", "b", "c"))
        )
        assertEquals(3, result.records.size)
        assertEquals(listOf("a", "b", "c"), fake.bulkFetchCalls.single())
    }

    @Test
    fun `gameBinaryPresignedUrl returns presigned URL`(): Unit = runBlocking {
        val result = BinaryRecordProxy.gameBinaryPresignedUrl(
            rpcContext,
            GameBinaryPresignedRequest(key = "logo", fileType = "image/png")
        )
        assertEquals("https://s3.example.com/upload", result.url)
        assertEquals("image/png", fake.presignedCalls.single().fileType)
    }

    // ---- Admin Game Binary ---------------------------------------------------

    @Test
    fun `adminListGameBinaries delegates with admin list response`(): Unit = runBlocking {
        val result = BinaryRecordProxy.adminListGameBinaries(
            rpcContext,
            AdminListGameBinaryRequest(limit = 25, offset = 0, query = null, tags = null)
        )
        assertEquals(1, result.records.size)
        assertEquals(25, fake.listAdminCalls.single().limit)
    }

    @Test
    fun `adminGetGameBinary returns admin response`(): Unit = runBlocking {
        val result = BinaryRecordProxy.adminGetGameBinary(rpcContext, AdminGetGameBinaryRequest(key = "logo"))
        assertEquals("logo", result.key)
        assertNotNull(result.ttlConfig)
    }

    @Test
    fun `adminCreateGameBinary returns presigned URL`(): Unit = runBlocking {
        val result = BinaryRecordProxy.adminCreateGameBinary(
            rpcContext,
            AdminCreateGameBinaryRequest(key = "logo", fileType = "image/png")
        )
        assertEquals("https://s3.example.com/upload", result.url)
    }

    @Test
    fun `adminReplaceGameBinary returns admin response`(): Unit = runBlocking {
        val result = BinaryRecordProxy.adminReplaceGameBinary(
            rpcContext,
            AdminReplaceGameBinaryRequest(key = "logo", contentType = "image/png", fileLocation = "s3://x")
        )
        assertEquals("logo", result.key)
    }

    @Test
    fun `adminDeleteGameBinary returns true on success`(): Unit = runBlocking {
        val success = BinaryRecordProxy.adminDeleteGameBinary(rpcContext, AdminDeleteGameBinaryRequest(key = "logo"))
        assertTrue(success)
    }

    @Test
    fun `adminGameBinaryPresignedUrl returns presigned URL`(): Unit = runBlocking {
        val result = BinaryRecordProxy.adminGameBinaryPresignedUrl(
            rpcContext,
            structs.rpcRequests.AdminGameBinaryPresignedRequest(key = "logo", fileType = "image/png")
        )
        assertEquals("https://s3.example.com/upload", result.url)
    }

    @Test
    fun `adminUpdateGameBinaryMetadata forwards metadata fields`(): Unit = runBlocking {
        val result = BinaryRecordProxy.adminUpdateGameBinaryMetadata(
            rpcContext,
            AdminGameBinaryMetadataRequest(key = "logo", setBy = "admin", tags = listOf("event"))
        )
        assertEquals("logo", result.key)
        assertEquals(listOf("event"), fake.updateMetadataCalls.single().tags)
    }

    // ---- Public Player Binary (/users/me/) -----------------------------------

    @Test
    fun `listMyBinaries returns player list`(): Unit = runBlocking {
        val result = BinaryRecordProxy.listMyBinaries(rpcContext, structs.rpcRequests.ListMyBinaryRequest(limit = 5))
        assertEquals(1, result.data.size)
        assertEquals("user-1", result.data[0].userId)
    }

    @Test
    fun `getMyBinary returns player record`(): Unit = runBlocking {
        val result = BinaryRecordProxy.getMyBinary(rpcContext, GetMyBinaryRequest(key = "avatar"))
        assertEquals("avatar", result.key)
        assertEquals("user-1", result.userId)
    }

    @Test
    fun `createMyBinary returns presigned URL`(): Unit = runBlocking {
        val result = BinaryRecordProxy.createMyBinary(
            rpcContext,
            CreateMyBinaryRequest(key = "avatar", fileType = "image/png")
        )
        assertEquals("https://s3.example.com/upload", result.url)
    }

    @Test
    fun `replaceMyBinary returns updated player record`(): Unit = runBlocking {
        val result = BinaryRecordProxy.replaceMyBinary(
            rpcContext,
            structs.rpcRequests.ReplaceMyBinaryRequest(key = "avatar", contentType = "image/png", fileLocation = "s3://x")
        )
        assertEquals("avatar", result.key)
    }

    @Test
    fun `deleteMyBinary returns true on success`(): Unit = runBlocking {
        val success = BinaryRecordProxy.deleteMyBinary(rpcContext, structs.rpcRequests.DeleteMyBinaryRequest(key = "avatar"))
        assertTrue(success)
    }

    @Test
    fun `bulkGetMyBinaries returns bulk player response`(): Unit = runBlocking {
        val result = BinaryRecordProxy.bulkGetMyBinaries(
            rpcContext,
            structs.rpcRequests.BulkGetMyBinaryRequest(keys = listOf("a", "b"))
        )
        assertEquals(2, result.records.size)
    }

    @Test
    fun `myBinaryPresignedUrl returns presigned URL`(): Unit = runBlocking {
        val result = BinaryRecordProxy.myBinaryPresignedUrl(
            rpcContext,
            MyBinaryPresignedRequest(key = "avatar", fileType = "image/png")
        )
        assertEquals("https://s3.example.com/upload", result.url)
    }

    // ---- Admin Player Binary -------------------------------------------------

    @Test
    fun `adminListPlayerBinaries forwards userId`(): Unit = runBlocking {
        val result = BinaryRecordProxy.adminListPlayerBinaries(
            rpcContext,
            AdminListPlayerBinaryRequest(userId = "user-42", limit = 10)
        )
        assertEquals("user-42", fake.adminListPlayerCalls.single().userId)
        assertEquals(1, result.data.size)
    }

    @Test
    fun `adminGetPlayerBinary returns player record`(): Unit = runBlocking {
        val result = BinaryRecordProxy.adminGetPlayerBinary(
            rpcContext,
            AdminGetPlayerBinaryRequest(userId = "user-42", key = "avatar")
        )
        assertEquals("user-42", result.userId)
        assertEquals("avatar", result.key)
    }

    @Test
    fun `adminCreatePlayerBinary returns presigned URL`(): Unit = runBlocking {
        val result = BinaryRecordProxy.adminCreatePlayerBinary(
            rpcContext,
            structs.rpcRequests.AdminCreatePlayerBinaryRequest(userId = "user-42", key = "avatar", fileType = "image/png")
        )
        assertEquals("https://s3.example.com/upload", result.url)
    }

    @Test
    fun `adminReplacePlayerBinary returns player record`(): Unit = runBlocking {
        val result = BinaryRecordProxy.adminReplacePlayerBinary(
            rpcContext,
            structs.rpcRequests.AdminReplacePlayerBinaryRequest(
                userId = "user-42",
                key = "avatar",
                contentType = "image/png",
                fileLocation = "s3://x"
            )
        )
        assertEquals("avatar", result.key)
        assertEquals("user-42", result.userId)
    }

    @Test
    fun `adminDeletePlayerBinary returns true on success`(): Unit = runBlocking {
        val success = BinaryRecordProxy.adminDeletePlayerBinary(
            rpcContext,
            structs.rpcRequests.AdminDeletePlayerBinaryRequest(userId = "user-42", key = "avatar")
        )
        assertTrue(success)
    }

    @Test
    fun `adminPlayerBinaryPresignedUrl returns presigned URL`(): Unit = runBlocking {
        val result = BinaryRecordProxy.adminPlayerBinaryPresignedUrl(
            rpcContext,
            structs.rpcRequests.AdminPlayerBinaryPresignedRequest(userId = "user-42", key = "avatar", fileType = "image/png")
        )
        assertEquals("https://s3.example.com/upload", result.url)
    }

    @Test
    fun `adminUpdatePlayerBinaryMetadata forwards userId and metadata`(): Unit = runBlocking {
        val result = BinaryRecordProxy.adminUpdatePlayerBinaryMetadata(
            rpcContext,
            structs.rpcRequests.AdminPlayerBinaryMetadataRequest(userId = "user-42", key = "avatar", tags = listOf("flagged"))
        )
        assertEquals("avatar", result.key)
        assertEquals(listOf("flagged"), fake.adminUpdatePlayerMetadataCalls.single().tags)
    }

    // ---- Failure fallback (graceful empty response) --------------------------

    @Test
    fun `listGameBinaries returns empty list on SDK failure`(): Unit = runBlocking {
        fake.failListBinaries = true
        val result = BinaryRecordProxy.listGameBinaries(rpcContext, ListGameBinaryRequest())
        assertTrue(result.data.isEmpty(), "Empty fallback should yield no records")
    }

    @Test
    fun `deleteGameBinary returns false on SDK failure`(): Unit = runBlocking {
        fake.failDeleteBinary = true
        val success = BinaryRecordProxy.deleteGameBinary(rpcContext, DeleteGameBinaryRequest(key = "missing"))
        assertFalse(success, "Delete failure should propagate as false")
    }
}

// -----------------------------------------------------------------------------
// Fake BinaryRecordOperations for unit tests
// -----------------------------------------------------------------------------

private data class ListCall(val limit: Int?, val offset: Int?, val query: String?, val tags: List<String>?)
private data class PresignedCall(val key: String, val fileType: String)
private data class ReplaceCall(val contentType: String, val fileLocation: String)
private data class AdminListCall(val limit: Int, val offset: Int, val query: String?, val tags: List<String>?)
private data class AdminListPlayerCall(val userId: String, val limit: Int?, val offset: Int?)
private data class UpdateMetadataCall(val setBy: String?, val tags: List<String>?)

private class RecordingFakeBinaryRecordOps : BinaryRecordOperations
{
    var failListBinaries: Boolean = false
    var failDeleteBinary: Boolean = false

    val listBinariesCalls = mutableListOf<ListCall>()
    val getBinaryCalls = mutableListOf<String>()
    val createBinaryCalls = mutableListOf<GameBinaryRecordCreateRequest>()
    val replaceBinaryCalls = mutableListOf<ReplaceCall>()
    val deleteBinaryCalls = mutableListOf<String>()
    val bulkFetchCalls = mutableListOf<List<String>>()
    val presignedCalls = mutableListOf<PresignedCall>()
    val listAdminCalls = mutableListOf<AdminListCall>()
    val adminListPlayerCalls = mutableListOf<AdminListPlayerCall>()
    val updateMetadataCalls = mutableListOf<UpdateMetadataCall>()
    val adminUpdatePlayerMetadataCalls = mutableListOf<UpdateMetadataCall>()

    override fun listBinaries(namespace: String, limit: Int?, offset: Int?, query: String?, tags: List<String>?): Result<GameBinaryRecordListResponse>
    {
        listBinariesCalls += ListCall(limit, offset, query, tags)
        if (failListBinaries) return Result.failure(RuntimeException("simulated SDK failure"))
        return Result.success(GameBinaryRecordListResponse(data = emptyList()))
    }

    override fun getBinary(namespace: String, key: String): Result<GameBinaryRecordResponse>
    {
        getBinaryCalls += key
        return Result.success(GameBinaryRecordResponse(key = key, namespace = namespace))
    }

    override fun createBinary(namespace: String, request: GameBinaryRecordCreateRequest): Result<UploadBinaryRecordResponse>
    {
        createBinaryCalls += request
        return Result.success(UploadBinaryRecordResponse(
            contentType = request.fileType,
            fileLocation = "s3://bucket/${request.key}",
            url = "https://s3.example.com/upload",
            version = 1
        ))
    }

    override fun replaceBinary(namespace: String, key: String, request: BinaryRecordRequest): Result<GameBinaryRecordResponse>
    {
        replaceBinaryCalls += ReplaceCall(request.contentType, request.fileLocation)
        return Result.success(GameBinaryRecordResponse(key = key, namespace = namespace))
    }

    override fun deleteBinary(namespace: String, key: String): Result<Unit>
    {
        deleteBinaryCalls += key
        if (failDeleteBinary) return Result.failure(RuntimeException("simulated delete failure"))
        return Result.success(Unit)
    }

    override fun bulkFetch(namespace: String, keys: List<String>): Result<BulkGameBinaryRecordResponse>
    {
        bulkFetchCalls += keys
        return Result.success(BulkGameBinaryRecordResponse(records = keys.map { GameBinaryRecordResponse(key = it, namespace = namespace) }))
    }

    override fun requestPresignedUrl(namespace: String, key: String, uploadRequest: UploadBinaryRecordRequest): Result<UploadBinaryRecordResponse>
    {
        presignedCalls += PresignedCall(key, uploadRequest.fileType)
        return Result.success(UploadBinaryRecordResponse(
            contentType = uploadRequest.fileType,
            fileLocation = "s3://bucket/$key",
            url = "https://s3.example.com/upload",
            version = 2
        ))
    }

    override fun listAdminRecords(namespace: String, limit: Int, offset: Int, query: String?, tags: List<String>?): Result<GameBinaryRecordAdminListResponse>
    {
        listAdminCalls += AdminListCall(limit, offset, query, tags)
        return Result.success(GameBinaryRecordAdminListResponse(
            records = listOf(GameBinaryRecordAdminResponse(key = "admin-record", namespace = namespace, ttlConfig = GameRecordTtlConfig(action = "KEEP", expiresAt = "2099-01-01T00:00:00Z")))
        ))
    }

    override fun adminGetBinary(namespace: String, key: String): Result<GameBinaryRecordAdminResponse>
    {
        return Result.success(GameBinaryRecordAdminResponse(key = key, namespace = namespace, ttlConfig = GameRecordTtlConfig(action = "KEEP", expiresAt = "2099-01-01T00:00:00Z")))
    }

    override fun adminCreateBinary(namespace: String, request: GameBinaryRecordCreateRequest): Result<UploadBinaryRecordResponse>
    {
        return Result.success(UploadBinaryRecordResponse(
            contentType = request.fileType,
            fileLocation = "s3://bucket/${request.key}",
            url = "https://s3.example.com/upload",
            version = 1
        ))
    }

    override fun adminReplaceBinary(namespace: String, key: String, request: BinaryRecordRequest): Result<GameBinaryRecordAdminResponse>
    {
        return Result.success(GameBinaryRecordAdminResponse(key = key, namespace = namespace, ttlConfig = GameRecordTtlConfig(action = "KEEP", expiresAt = "2099-01-01T00:00:00Z")))
    }

    override fun adminDeleteBinary(namespace: String, key: String): Result<Unit>
    {
        return Result.success(Unit)
    }

    override fun adminRequestPresignedUrl(namespace: String, key: String, uploadRequest: UploadBinaryRecordRequest): Result<UploadBinaryRecordResponse>
    {
        return Result.success(UploadBinaryRecordResponse(
            contentType = uploadRequest.fileType,
            fileLocation = "s3://bucket/$key",
            url = "https://s3.example.com/upload",
            version = 1
        ))
    }

    override fun adminUpdateMetadata(namespace: String, key: String, metadata: GameBinaryRecordMetadata): Result<GameBinaryRecordAdminResponse>
    {
        updateMetadataCalls += UpdateMetadataCall(metadata.setBy, metadata.tags)
        return Result.success(GameBinaryRecordAdminResponse(key = key, namespace = namespace, ttlConfig = GameRecordTtlConfig(action = "KEEP", expiresAt = "2099-01-01T00:00:00Z")))
    }

    override fun listMyBinaries(namespace: String, limit: Int?, offset: Int?, query: String?, tags: List<String>?): Result<PlayerBinaryRecordListResponse>
    {
        return Result.success(PlayerBinaryRecordListResponse(
            data = listOf(PlayerBinaryRecordResponse(key = "avatar", namespace = namespace, userId = "user-1"))
        ))
    }

    override fun getMyBinary(namespace: String, key: String): Result<PlayerBinaryRecordResponse>
    {
        return Result.success(PlayerBinaryRecordResponse(key = key, namespace = namespace, userId = "user-1"))
    }

    override fun createMyBinary(namespace: String, request: GameBinaryRecordCreateRequest): Result<UploadBinaryRecordResponse>
    {
        return Result.success(UploadBinaryRecordResponse(
            contentType = request.fileType,
            fileLocation = "s3://bucket/${request.key}",
            url = "https://s3.example.com/upload",
            version = 1
        ))
    }

    override fun replaceMyBinary(namespace: String, key: String, request: BinaryRecordRequest): Result<PlayerBinaryRecordResponse>
    {
        return Result.success(PlayerBinaryRecordResponse(key = key, namespace = namespace, userId = "user-1"))
    }

    override fun deleteMyBinary(namespace: String, key: String): Result<Unit>
    {
        return Result.success(Unit)
    }

    override fun bulkFetchMy(namespace: String, keys: List<String>): Result<BulkPlayerBinaryRecordResponse>
    {
        return Result.success(BulkPlayerBinaryRecordResponse(
            records = keys.map { PlayerBinaryRecordResponse(key = it, namespace = namespace, userId = "user-1") }
        ))
    }

    override fun requestMyPresignedUrl(namespace: String, key: String, uploadRequest: UploadBinaryRecordRequest): Result<UploadBinaryRecordResponse>
    {
        return Result.success(UploadBinaryRecordResponse(
            contentType = uploadRequest.fileType,
            fileLocation = "s3://bucket/$key",
            url = "https://s3.example.com/upload",
            version = 1
        ))
    }

    override fun adminListPlayerBinaries(namespace: String, userId: String, limit: Int?, offset: Int?, query: String?, tags: List<String>?): Result<PlayerBinaryRecordListResponse>
    {
        adminListPlayerCalls += AdminListPlayerCall(userId, limit, offset)
        return Result.success(PlayerBinaryRecordListResponse(
            data = listOf(PlayerBinaryRecordResponse(key = "avatar", namespace = namespace, userId = userId))
        ))
    }

    override fun adminGetPlayerBinary(namespace: String, userId: String, key: String): Result<PlayerBinaryRecordResponse>
    {
        return Result.success(PlayerBinaryRecordResponse(key = key, namespace = namespace, userId = userId))
    }

    override fun adminCreatePlayerBinary(namespace: String, userId: String, request: GameBinaryRecordCreateRequest): Result<UploadBinaryRecordResponse>
    {
        return Result.success(UploadBinaryRecordResponse(
            contentType = request.fileType,
            fileLocation = "s3://bucket/${request.key}",
            url = "https://s3.example.com/upload",
            version = 1
        ))
    }

    override fun adminReplacePlayerBinary(namespace: String, userId: String, key: String, request: BinaryRecordRequest): Result<PlayerBinaryRecordResponse>
    {
        return Result.success(PlayerBinaryRecordResponse(key = key, namespace = namespace, userId = userId))
    }

    override fun adminDeletePlayerBinary(namespace: String, userId: String, key: String): Result<Unit>
    {
        return Result.success(Unit)
    }

    override fun adminRequestPlayerPresignedUrl(namespace: String, userId: String, key: String, uploadRequest: UploadBinaryRecordRequest): Result<UploadBinaryRecordResponse>
    {
        return Result.success(UploadBinaryRecordResponse(
            contentType = uploadRequest.fileType,
            fileLocation = "s3://bucket/$key",
            url = "https://s3.example.com/upload",
            version = 1
        ))
    }

    override fun adminUpdatePlayerMetadata(namespace: String, userId: String, key: String, metadata: GameBinaryRecordMetadata): Result<PlayerBinaryRecordResponse>
    {
        adminUpdatePlayerMetadataCalls += UpdateMetadataCall(metadata.setBy, metadata.tags)
        return Result.success(PlayerBinaryRecordResponse(key = key, namespace = namespace, userId = userId))
    }
}