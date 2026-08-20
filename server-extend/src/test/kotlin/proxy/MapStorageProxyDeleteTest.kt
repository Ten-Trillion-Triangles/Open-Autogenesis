package proxy

import accelbyte.cloudsave.BinaryRecord
import accelbyte.cloudsave.RealBinaryRecordOperations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import maps.PlayerMapRepository
import network.MapDeleteErrorHandlers
import network.MapDeleteSuccessHandlers
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.serverextend.RestPlayerConnectionManager
import structs.rpcRequests.DeletePlayerMapRequest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for the augmented [MapStorageProxy.deletePlayerMap] handler.
 *
 * The base proxy behaviour (AGS delete + catalogue remove + return
 * DeletePlayerMapResult) is already covered by `deletePlayerMapRemovesCatalogueEntry`
 * in [MapStorageProxyTest]. This file layers the new ownership check +
 * async notification dispatch on top:
 *
 *  - happy path: SSE session accelbyteId matches request.userId -> AGS delete +
 *    catalogue remove + `Map.Delete.Success` notification dispatched.
 *  - auth mismatch: SSE session accelbyteId does NOT match request.userId ->
 *    reject with `deleted=false` + `Map.Delete.Error` notification; the AGS
 *    delete is NOT called and the catalogue entry is preserved.
 *  - skipLogin path: SSE session has no accelbyteId (default empty string)
 *    -> trust the request body, behave like the happy path.
 *  - back-compat: no connection manager registered -> AGS delete still
 *    runs, notifications are dropped (direct-tooling callers).
 */
class MapStorageProxyDeleteTest
{
    @Before
    fun setUp()
    {
        PlayerMapRepository.clearForTests()
        MapDeleteSuccessHandlers.resetForTest()
        MapDeleteErrorHandlers.resetForTest()
        BinaryRecord.operationsFactory = { RealBinaryRecordOperations() }
    }

    @After
    fun tearDown()
    {
        MapDeleteSuccessHandlers.resetForTest()
        MapDeleteErrorHandlers.resetForTest()
        PlayerMapRepository.clearForTests()
    }

    @Test
    fun deletePlayerMapEmitsMapDeleteSuccessWhenAuthAndDeleteSucceed(): Unit = runBlocking {
        val manager = RestPlayerConnectionManager()
        manager.register("conn-1", accelbyteId = "user-A")
        val session = manager.findSession("conn-1")!!
        MapDeleteSuccessHandlers.registerConnectionManager(manager)
        MapDeleteErrorHandlers.registerConnectionManager(manager)
        PlayerMapRepository.addEntry("user-A", "map-1", "Map One", 100, uploadedAt = 1000L)

        val captured = Channel<String>(capacity = 1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val collector: Job = scope.launch {
            try { session.outgoingFlow().collect { captured.send(it) } }
            catch (_: Throwable) { /* channel closed */ }
        }
        try
        {
            val result = MapStorageProxy.deletePlayerMap(
                context = RpcCallContext(connectionId = "conn-1", sender = { }),
                request = DeletePlayerMapRequest(userId = "user-A", mapId = "map-1")
            )
            assertTrue(result.deleted, "expected deleted=true on happy path")
            assertEquals(null, PlayerMapRepository.getEntry("user-A", "map-1"), "catalogue entry should be removed")

            val json = captured.receive()
            assertNotNull(json, "session should have received a JSON-encoded notification")
            assertTrue(
                json.contains("\"method\":\"Map.Delete.Success\""),
                "expected Map.Delete.Success notification method; got: $json"
            )
            assertTrue(json.contains("\"mapId\":\"map-1\""), "expected mapId in payload; got: $json")
            assertTrue(json.contains("\"mapName\":\"Map One\""), "expected mapName 'Map One' in payload; got: $json")
        }
        finally
        {
            collector.cancel()
            scope.cancel()
        }
    }

    @Test
    fun deletePlayerMapRejectsWhenSessionAccelbyteIdDoesNotMatchRequestUserId(): Unit = runBlocking {
        val manager = RestPlayerConnectionManager()
        manager.register("conn-1", accelbyteId = "user-A")
        val session = manager.findSession("conn-1")!!
        MapDeleteSuccessHandlers.registerConnectionManager(manager)
        MapDeleteErrorHandlers.registerConnectionManager(manager)
        PlayerMapRepository.addEntry("user-A", "map-1", "Map One", 100, uploadedAt = 1000L)

        val captured = Channel<String>(capacity = 1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val collector: Job = scope.launch {
            try { session.outgoingFlow().collect { captured.send(it) } }
            catch (_: Throwable) { /* channel closed */ }
        }
        try
        {
            // request.userId = "USER-B" does not match the session accelbyteId "user-A".
            val result = MapStorageProxy.deletePlayerMap(
                context = RpcCallContext(connectionId = "conn-1", sender = { }),
                request = DeletePlayerMapRequest(userId = "USER-B", mapId = "map-1")
            )

            assertFalse(result.deleted, "expected deleted=false on auth mismatch")
            assertNotNull(
                PlayerMapRepository.getEntry("user-A", "map-1"),
                "catalogue entry must be preserved on auth mismatch"
            )

            val json = captured.receive()
            assertTrue(
                json.contains("\"method\":\"Map.Delete.Error\""),
                "expected Map.Delete.Error notification on auth mismatch; got: $json"
            )
            assertTrue(
                json.contains("\"reason\":\"Delete denied"),
                "expected denial reason in payload; got: $json"
            )
        }
        finally
        {
            collector.cancel()
            scope.cancel()
        }
    }

    @Test
    fun deletePlayerMapAllowsSkipLoginPathWhenSessionHasNoAccelbyteId(): Unit = runBlocking {
        // Default accelbyteId = "" (empty string) per the RestPlayerSession contract.
        val manager = RestPlayerConnectionManager()
        manager.register("conn-1")
        val session = manager.findSession("conn-1")!!
        MapDeleteSuccessHandlers.registerConnectionManager(manager)
        MapDeleteErrorHandlers.registerConnectionManager(manager)
        PlayerMapRepository.addEntry("user-A", "map-1", "Map One", 100, uploadedAt = 1000L)

        val captured = Channel<String>(capacity = 1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val collector: Job = scope.launch {
            try { session.outgoingFlow().collect { captured.send(it) } }
            catch (_: Throwable) { /* channel closed */ }
        }
        try
        {
            val result = MapStorageProxy.deletePlayerMap(
                context = RpcCallContext(connectionId = "conn-1", sender = { }),
                request = DeletePlayerMapRequest(userId = "user-A", mapId = "map-1")
            )
            assertTrue(result.deleted, "expected deleted=true on skipLogin path (empty accelbyteId)")
            assertEquals(null, PlayerMapRepository.getEntry("user-A", "map-1"))

            val json = captured.receive()
            assertTrue(
                json.contains("\"method\":\"Map.Delete.Success\""),
                "expected Map.Delete.Success on skipLogin path; got: $json"
            )
        }
        finally
        {
            collector.cancel()
            scope.cancel()
        }
    }

    @Test
    fun deletePlayerMapExistingBehaviourPreservedNoAuthManagerRegistered(): Unit = runBlocking {
        // No connection manager registered -> the proxy still does the AGS
        // delete + catalogue remove for source-compat. The notifications are
        // dropped (no manager to findSession against). This pins the
        // back-compat surface for direct callers.
        PlayerMapRepository.addEntry("user-A", "map-1", "Map One", 100, uploadedAt = 1000L)
        val result = MapStorageProxy.deletePlayerMap(
            RpcCallContext(connectionId = "test", sender = { }),
            request = DeletePlayerMapRequest(userId = "user-A", mapId = "map-1")
        )
        assertTrue(result.deleted, "no-manager path should still delete")
        assertEquals(null, PlayerMapRepository.getEntry("user-A", "map-1"))
    }
}