package proxy

import accelbyte.cloudsave.BinaryRecord
import accelbyte.cloudsave.BinaryRecordOperations
import accelbyte.cloudsave.RealBinaryRecordOperations
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import maps.PlayerMapRepository
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.network.RpcCallContext
import structs.accelbyte.cloudsave.BinaryInfo
import structs.accelbyte.cloudsave.GameBinaryRecordCreateRequest
import structs.accelbyte.cloudsave.PlayerBinaryRecordResponse
import structs.accelbyte.cloudsave.UploadBinaryRecordResponse
import structs.rpcRequests.DeletePlayerMapRequest
import structs.rpcRequests.GetPlayerMapRequest
import structs.rpcRequests.ListPlayerMapsRequest
import structs.rpcRequests.SavePlayerMapRequest
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [MapStorageProxy].
 *
 * These run the proxy against an embedded HTTP server (`com.sun.net.httpserver.HttpServer`)
 * that simulates the AGS presigned-URL endpoints. The fake SDK wrapper records every
 * admin* call so the test can assert on metadata + bytes flow.
 *
 * The fake SDK implements [BinaryRecordOperations] by delegating to a recording shim
 * and overriding the methods the proxy exercises. Methods the proxy doesn't call are
 * inherited (the interface is large; pattern matches the recording fake in
 * [BinaryRecordProxyTest]).
 */
class MapStorageProxyTest
{
    private val ctx = RpcCallContext(connectionId = "test", sender = { })
    private var server: HttpServer? = null
    private var port: Int = 0
    private lateinit var fakeOps: RecordingFakeMapOps

    @Before
    fun setUp()
    {
        PlayerMapRepository.clearForTests()
        // Bind ephemeral port for the embedded upload/download mock.
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server!!.address.port
        server!!.start()

        // Default upload/download mock behaviour: capture the body, return 200.
        server!!.createContext("/upload") { exchange: HttpExchange ->
            val bytes = exchange.requestBody.readBytes()
            capturedUploadBytes = bytes
            exchange.sendResponseHeaders(200, -1L)
            exchange.close()
        }
        server!!.createContext("/download") { exchange: HttpExchange ->
            exchange.sendResponseHeaders(200, capturedDownloadBytes.size.toLong())
            exchange.responseBody.use { it.write(capturedDownloadBytes) }
            exchange.close()
        }

        fakeOps = RecordingFakeMapOps(
            baseUrl = "http://127.0.0.1:$port",
            onCreate = { createReq ->
                UploadBinaryRecordResponse(
                    contentType = "bin",
                    fileLocation = "s3://fake-bucket/${createReq.key}",
                    url = "http://127.0.0.1:$port/upload",
                    version = 1
                )
            },
            onGet = { key ->
                // Ownership is encoded in the key prefix `player-<userId>-`.
                // The tag-based backstop was attempted but AGS rejects the
                // metadata-update payload (SDK serializes `set_by: null`),
                // so the key prefix is the authoritative ownership channel.
                structs.accelbyte.cloudsave.GameBinaryRecordAdminResponse(
                    key = key,
                    namespace = "fake-ns",
                    binaryInfo = BinaryInfo(
                        contentType = "bin",
                        createdAt = "2026-08-09T00:00:00Z",
                        fileLocation = "s3://fake-bucket/$key",
                        updatedAt = "2026-08-09T00:00:00Z",
                        url = "http://127.0.0.1:$port/download",
                        version = 1
                    )
                )
            }
        )
        BinaryRecord.operationsFactory = { fakeOps }
    }

    @After
    fun tearDown()
    {
        server?.stop(0)
        server = null
        BinaryRecord.operationsFactory = { RealBinaryRecordOperations() }
        PlayerMapRepository.clearForTests()
    }

    @Test
    fun savePlayerMapRejectsOversizePayload(): Unit = runBlocking {
        val huge = ByteArray(60 * 1024 * 1024)
        val result = MapStorageProxy.savePlayerMap(ctx, SavePlayerMapRequest(
            userId = "user-1", mapId = "abc", mapName = "big", mapPackBytes = huge
        ))
        assertFalse(result, "savePlayerMap must reject payloads over 50 MB")
        assertTrue(fakeOps.createCalls.isEmpty(), "no AGS call should be made for oversized payloads")
    }

    @Test
    fun savePlayerMapRejectsBlankMapId(): Unit = runBlocking {
        val result = MapStorageProxy.savePlayerMap(ctx, SavePlayerMapRequest(
            userId = "user-1", mapId = "", mapName = "name", mapPackBytes = ByteArray(100)
        ))
        assertFalse(result)
        assertTrue(fakeOps.createCalls.isEmpty())
    }

    @Test
    fun savePlayerMapRejectsOverlongName(): Unit = runBlocking {
        val longName = "x".repeat(200)
        val result = MapStorageProxy.savePlayerMap(ctx, SavePlayerMapRequest(
            userId = "user-1", mapId = "abc", mapName = longName, mapPackBytes = ByteArray(100)
        ))
        assertFalse(result)
    }

    @Test
    fun savePlayerMapPutsBytesAndUpdatesCatalogue(): Unit = runBlocking {
        val pack = "FAKE_MAP_PACK_v1".toByteArray()
        val result = MapStorageProxy.savePlayerMap(ctx, SavePlayerMapRequest(
            userId = "user-1", mapId = "abc", mapName = "level1", mapPackBytes = pack
        ))
        assertTrue(result, "savePlayerMap should succeed with a valid payload")
        assertEquals(1, fakeOps.createCalls.size)
        // Game Binary key encodes ownership: `player-<userId>-<mapId>`
        assertEquals("player-user-1-abc", fakeOps.createCalls.single().key)
        assertEquals("SERVER", fakeOps.createCalls.single().setBy)
        // The embedded upload server captured the bytes.
        assertEquals(pack.size, capturedUploadBytes.size)
        assertTrue(capturedUploadBytes.contentEquals(pack))
        // The catalogue reflects the save.
        val entry = PlayerMapRepository.getEntry("user-1", "abc")
        assertNotNull(entry)
        assertEquals("level1", entry.mapName)
        assertEquals(pack.size, entry.sizeBytes)
    }

    @Test
    fun savePlayerMapReturnsFalseWhenAgsCreateFails(): Unit = runBlocking {
        // Override the create hook to fail.
        fakeOps.overrideCreate { Result.failure(RuntimeException("simulated AGS 503")) }
        val result = MapStorageProxy.savePlayerMap(ctx, SavePlayerMapRequest(
            userId = "user-1", mapId = "abc", mapName = "x", mapPackBytes = ByteArray(100)
        ))
        assertFalse(result)
        assertEquals(null, PlayerMapRepository.getEntry("user-1", "abc"))
    }

    @Test
    fun listPlayerMapsReturnsSnapshotFromCatalogue(): Unit = runBlocking {
        MapStorageProxy.savePlayerMap(ctx, SavePlayerMapRequest("u", "a", "alpha", ByteArray(100)))
        MapStorageProxy.savePlayerMap(ctx, SavePlayerMapRequest("u", "b", "beta", ByteArray(200)))
        val snap = MapStorageProxy.listPlayerMaps(ctx, ListPlayerMapsRequest("u"))
        assertEquals(2, snap.maps.size)
        assertEquals("alpha", snap.maps[0].mapName)
        assertEquals("beta", snap.maps[1].mapName)
    }

    @Test
    fun getPlayerMapFetchesBytesFromPresignedUrl(): Unit = runBlocking {
        capturedDownloadBytes = "DOWNLOADED_PACK_v1".toByteArray()
        val response = MapStorageProxy.getPlayerMap(ctx, GetPlayerMapRequest(userId = "u", mapId = "abc"))
        assertTrue(response.mapPackBytes.contentEquals("DOWNLOADED_PACK_v1".toByteArray()))
        assertEquals(1, fakeOps.getCalls.size)
        // Key reconstruction: `player-<userId>-<mapId>`
        assertEquals("player-u-abc", fakeOps.getCalls.single())
    }

    @Test
    fun deletePlayerMapRemovesCatalogueEntry(): Unit = runBlocking {
        MapStorageProxy.savePlayerMap(ctx, SavePlayerMapRequest("u", "abc", "alpha", ByteArray(100)))
        val res = MapStorageProxy.deletePlayerMap(ctx, DeletePlayerMapRequest(userId = "u", mapId = "abc"))
        assertTrue(res.deleted)
        assertEquals(null, PlayerMapRepository.getEntry("u", "abc"))
        assertEquals(1, fakeOps.deleteCalls.size)
    }

    @Test
    fun savePlayerMapReplacesExistingEntryByNameCaseInsensitive(): Unit = runBlocking {
        // Pre-populate the catalogue with an entry named "Arctica" under mapId="old-id".
        PlayerMapRepository.addEntry("user-1", "old-id", "Arctica", 100)

        val newBytes = "new-pack-bytes".toByteArray()
        val result = MapStorageProxy.savePlayerMap(
            ctx,
            SavePlayerMapRequest(
                userId = "user-1",
                mapId = "new-id",
                mapName = "arctica",
                mapPackBytes = newBytes,
                contentType = "application/zip"
            )
        )

        assertTrue(result, "savePlayerMap should succeed on replace")
        assertNull(PlayerMapRepository.getEntry("user-1", "old-id"))
        val newEntry = PlayerMapRepository.getEntry("user-1", "new-id")
        assertNotNull(newEntry)
        assertEquals("arctica", newEntry.mapName)
        assertEquals(newBytes.size, newEntry.sizeBytes)
        assertTrue(
            fakeOps.deleteCalls.contains("player-user-1-old-id"),
            "adminDeleteBinary should have been called for the old mapId; saw ${fakeOps.deleteCalls}"
        )
        assertTrue(
            fakeOps.createCalls.any { it.key == "player-user-1-new-id" },
            "adminCreateGameBinary should have been called for the new mapId"
        )
    }

    @Test
    fun savePlayerMapDoesNotDeleteWhenNoNameCollision(): Unit = runBlocking {
        val result = MapStorageProxy.savePlayerMap(
            ctx,
            SavePlayerMapRequest(
                userId = "user-1",
                mapId = "id-1",
                mapName = "Greenland",
                mapPackBytes = "bytes".toByteArray(),
                contentType = "application/zip"
            )
        )

        assertTrue(result)
        assertTrue(fakeOps.deleteCalls.isEmpty(), "No delete should fire on first upload")
        assertTrue(fakeOps.createCalls.any { it.key == "player-user-1-id-1" })
    }

    @Test
    fun savePlayerMapHandlesStaleCatalogueEntryWithMissingAGSRecord(): Unit = runBlocking {
        // adminDeleteBinary on the fake SDK returns success-on-missing-key by default;
        // that mirrors the production AGS soft-no-op behavior for missing records.
        PlayerMapRepository.addEntry("user-1", "stale-id", "Arctica", 100)

        val result = MapStorageProxy.savePlayerMap(
            ctx,
            SavePlayerMapRequest(
                userId = "user-1",
                mapId = "fresh-id",
                mapName = "Arctica",
                mapPackBytes = "bytes".toByteArray(),
                contentType = "application/zip"
            )
        )

        assertTrue(result, "Stale-catalogue case should still succeed")
        assertNull(PlayerMapRepository.getEntry("user-1", "stale-id"))
        assertNotNull(PlayerMapRepository.getEntry("user-1", "fresh-id"))
    }

    @Test
    fun savePlayerMapTrimsNameWhenMatching(): Unit = runBlocking {
        PlayerMapRepository.addEntry("user-1", "old-id", "Arctica", 100)

        val result = MapStorageProxy.savePlayerMap(
            ctx,
            SavePlayerMapRequest(
                userId = "user-1",
                mapId = "new-id",
                mapName = "  ARCTICA  ",
                mapPackBytes = "bytes".toByteArray(),
                contentType = "application/zip"
            )
        )

        assertTrue(result)
        assertTrue(fakeOps.deleteCalls.contains("player-user-1-old-id"))
    }

    companion object
    {
        // Captured bytes for the embedded upload/download server.
        @JvmStatic var capturedUploadBytes: ByteArray = ByteArray(0)
        @JvmStatic var capturedDownloadBytes: ByteArray = ByteArray(0)
    }
}

/**
 * Recording fake for the [BinaryRecordOperations] interface used by the proxy.
 *
 * Delegates to a [RealBinaryRecordOperations] for the 30+ methods the proxy
 * doesn't exercise, and overrides only the 3 the proxy actually calls. Mirrors
 * the recording-fake pattern in [BinaryRecordProxyTest.kt].
 */
private class RecordingFakeMapOps(
    private val baseUrl: String,
    private val onCreate: (GameBinaryRecordCreateRequest) -> UploadBinaryRecordResponse,
    private val onGet: (key: String) -> structs.accelbyte.cloudsave.GameBinaryRecordAdminResponse
) : BinaryRecordOperations by RealBinaryRecordOperations()
{
    val createCalls = mutableListOf<GameBinaryRecordCreateRequest>()
    val getCalls = mutableListOf<String>()  // Game Binary keys (no userId in key)
    val deleteCalls = mutableListOf<String>()  // Game Binary keys (no userId in key)

    @Volatile
    private var createOverride: ((GameBinaryRecordCreateRequest) -> Result<UploadBinaryRecordResponse>)? = null

    fun overrideCreate(fn: (GameBinaryRecordCreateRequest) -> Result<UploadBinaryRecordResponse>)
    {
        createOverride = fn
    }

    override fun adminCreateBinary(
        namespace: String, request: GameBinaryRecordCreateRequest
    ): Result<UploadBinaryRecordResponse>
    {
        createCalls += request
        val override = createOverride
        return if (override != null) override(request) else Result.success(onCreate(request))
    }

    override fun adminGetBinary(
        namespace: String, key: String
    ): Result<structs.accelbyte.cloudsave.GameBinaryRecordAdminResponse>
    {
        getCalls += key
        return Result.success(onGet(key))
    }

    override fun adminDeleteBinary(namespace: String, key: String): Result<Unit>
    {
        deleteCalls += key
        return Result.success(Unit)
    }
}
