package proxy

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.server.vfs.VirtualFileSystemManager
import structs.accelbyte.cloudsave.PlayerRecordResponse
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression tests for BUG #5: CloudSaveProxy.getMasterRecord used to crash
 * with `Index -1 out of bounds for length 0` when AccelByte returned a
 * malformed or empty record. The fix null-safely resolves the inner value
 * and returns an empty [structs.storage.MasterRecord] rather than feeding
 * `JsonNull` into `decodeFromJsonElement`.
 *
 * The test exercises the production code path against a real local VFS,
 * saving records with edge-case payloads and reading them back through
 * [CloudSaveProxy.getMasterRecord].
 */
class CloudSaveProxyMasterRecordTest
{
    @Before
    fun setUp()
    {
        try
        {
            val tempDir = java.nio.file.Files.createTempDirectory("cloud-save-proxy-test")
            VirtualFileSystemManager.initialize(
                listOf("--mode=local", "--vfs-local-dir=${tempDir.toAbsolutePath()}")
            )
        }
        catch (e: Exception)
        {
            // ignore re-initialization
        }
    }

    @Test
    fun `getMasterRecord returns empty MasterRecord when response value is null`()
    {
        runBlocking {
            // Save an explicit null-value record into the local VFS.
            val userId = "test-user-null-${System.nanoTime()}"
            val vfs = VirtualFileSystemManager.forUser(userId)
            vfs.saveUserRecord(
                userId = userId,
                key = "master-record",
                payload = kotlinx.serialization.json.JsonNull
            )

            val ctx = RpcCallContext(connectionId = "test-conn", sender = { _ -> })
            val result = CloudSaveProxy.getMasterRecord(ctx, userId)

            assertNotNull(result)
            assertEquals(0, result.commanderKeys.size, "Empty master record should have no commander keys")
            assertEquals(0, result.storyKeys.size, "Empty master record should have no story keys")
        }
    }

    @Test
    fun `getMasterRecord returns empty MasterRecord when no record exists`()
    {
        runBlocking {
            val userId = "test-user-empty-${System.nanoTime()}"
            // No record saved — VFS returns failure/RecordNotFoundException.
            val ctx = RpcCallContext(connectionId = "test-conn", sender = { _ -> })
            val result = CloudSaveProxy.getMasterRecord(ctx, userId)

            assertNotNull(result)
            assertEquals(0, result.commanderKeys.size)
        }
    }

    @Test
    fun `getMasterRecord deserializes a valid MasterRecord payload`()
    {
        runBlocking {
            val userId = "test-user-valid-${System.nanoTime()}"
            val validPayload = buildJsonObject {
                put("commanderKeys", buildJsonObject {})
                put("storyKeys", buildJsonObject {})
            }
            val vfs = VirtualFileSystemManager.forUser(userId)
            vfs.saveUserRecord(
                userId = userId,
                key = "master-record",
                payload = validPayload
            )

            val ctx = RpcCallContext(connectionId = "test-conn", sender = { _ -> })
            val result = CloudSaveProxy.getMasterRecord(ctx, userId)

            assertNotNull(result)
            assertTrue(result.commanderKeys.isEmpty(), "Saved empty commanderKeys should deserialize as empty")
            assertTrue(result.storyKeys.isEmpty(), "Saved empty storyKeys should deserialize as empty")
        }
    }
}
