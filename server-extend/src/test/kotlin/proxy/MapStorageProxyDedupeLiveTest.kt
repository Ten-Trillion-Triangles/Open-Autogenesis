package proxy

import accelbyte.cloudsave.BinaryRecord
import accelbyte.cloudsave.BinaryRecordOperations
import accelbyte.cloudsave.RealBinaryRecordOperations
import kotlinx.coroutines.runBlocking
import maps.PlayerMapRepository
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.RpcCallContext
import structs.rpcRequests.DeletePlayerMapRequest
import structs.rpcRequests.SavePlayerMapRequest
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live AGS integration test for the dedupe-and-replace-by-name flow added to
 * [MapStorageProxy.savePlayerMap].
 *
 * Skips the safety agent entirely — the test calls [MapStorageProxy.savePlayerMap]
 * directly with pre-packed bytes, mimicking what the gate layer would do AFTER
 * the safety check passes. This isolates the dedupe preflight from the safety
 * pipeline so a failure here points at the storage path, not at the agent.
 *
 * Flow exercised per test:
 *  1. Save initial map with name X, capture its AGS key (`player-<userId>-<mapId>`)
 *  2. Save a SECOND map with a case-insensitive variant of X (e.g. "x" vs "X")
 *     — the dedupe preflight should detect the collision and orphan the old record
 *  3. Probe AGS via `BinaryRecord.adminGetBinary(oldKey)` — expect `record not found`
 *  4. Probe AGS via `BinaryRecord.adminGetBinary(newKey)` — expect success
 *  5. Confirm the catalogue contains only the new mapId
 *  6. Clean up by deleting the new record (the old one is already orphaned)
 *
 * Opt-in via AGS_LIVE_TEST=true + env vars (AB_NAMESPACE, AB_BASE_URL,
 * AB_CLIENT_ID, AB_CLIENT_SECRET) exported before gradle invocation, OR the
 * secrets file at `~/.autogenesis/config/accelbyte.properties` (the standard
 * dev-machine path). Both shapes are accepted.
 *
 * Run with:
 *   AGS_LIVE_TEST=true \\
 *   export AB_NAMESPACE=$(grep '^AB_NAMESPACE=' ~/.autogenesis/config/accelbyte.properties | cut -d= -f2) && \\
 *   export AB_BASE_URL=$(grep '^AB_BASE_URL=' ~/.autogenesis/config/accelbyte.properties | cut -d= -f2) && \\
 *   export AB_CLIENT_ID=$(grep '^AB_CLIENT_ID=' ~/.autogenesis/config/accelbyte.properties | cut -d= -f2) && \\
 *   export AB_CLIENT_SECRET=$(grep '^AB_CLIENT_SECRET=' ~/.autogenesis/config/accelbyte.properties | cut -d= -f2) && \\
 *   ./gradlew :server-extend:test \\
 *     --tests 'proxy.MapStorageProxyDedupeLiveTest' \\
 *     --no-daemon --rerun-tasks -i
 */
class MapStorageProxyDedupeLiveTest
{
    private val ctx = RpcCallContext(connectionId = "live-dedupe-test", sender = { })

    private fun liveTestEnabled(): Boolean = System.getenv("AGS_LIVE_TEST") == "true"

    private fun credentialsAvailable(): Boolean
    {
        val candidates = listOf(
            File("accelbyte.local.properties"),
            File("accelbyte.properties"),
            File(System.getProperty("user.home"), ".autogenesis/config/accelbyte.local.properties"),
            File(System.getProperty("user.home"), ".autogenesis/config/accelbyte.properties")
        )
        return candidates.any { it.exists() }
    }

    private fun namespaceFromEnv(): String? =
        System.getenv("AB_NAMESPACE")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("AB_NAMESPACE")?.takeIf { it.isNotBlank() }

    /**
     * Same per-call ops factory pattern as [MapStorageProxyLiveTest] — bypasses
     * AccelByteConfig's stale-lazy capture so a fresh namespace is read on every call.
     */
    private fun installOpsFactory(envNamespace: String)
    {
        BinaryRecord.operationsFactory = {
            object : BinaryRecordOperations by RealBinaryRecordOperations()
            {
                override fun adminCreateBinary(
                    namespace: String,
                    request: structs.accelbyte.cloudsave.GameBinaryRecordCreateRequest
                ) = RealBinaryRecordOperations().adminCreateBinary(envNamespace, request)

                override fun adminGetBinary(
                    namespace: String, key: String
                ) = RealBinaryRecordOperations().adminGetBinary(envNamespace, key)

                override fun adminDeleteBinary(
                    namespace: String, key: String
                ) = RealBinaryRecordOperations().adminDeleteBinary(envNamespace, key)

                override fun adminUpdateMetadata(
                    namespace: String, key: String,
                    metadata: structs.accelbyte.cloudsave.GameBinaryRecordMetadata
                ) = RealBinaryRecordOperations().adminUpdateMetadata(envNamespace, key, metadata)
            }
        }
    }

    /**
     * The verified AGS userId from [MapStorageProxyLiveTest.saveAndListAndGetAndDeleteEndToEnd].
     * `adminCreateGameBinary` requires the userId to exist in AGS's user database;
     * random UUIDs surface 500. Found via `ags iam users search --query "test"` against
     * the echoofmaridia-autogenesis namespace.
     */
    private val userId: String =
        System.getenv("AB_LIVE_TEST_USER") ?: "25a70be88881466286bc03154f5d7492"

    /**
     * Records created during a test, for cleanup in @After so the namespace
     * doesn't accumulate orphaned records across re-runs.
     */
    private val cleanupKeys: MutableList<String> = mutableListOf()

    @Before
    fun setUp()
    {
        PlayerMapRepository.clearForTests()
        val envNamespace = namespaceFromEnv() ?: return
        installOpsFactory(envNamespace)
        cleanupKeys.clear()
        Logger.info(
            LogCategory.SYSTEM,
            "MapStorageProxyDedupeLiveTest: namespace=$envNamespace userId=$userId"
        )
    }

    @After
    fun tearDown()
    {
        // Best-effort cleanup — if any record still exists, delete it so the
        // namespace doesn't accumulate orphaned records across test runs.
        runBlocking {
            for (key in cleanupKeys)
            {
                try
                {
                    BinaryRecord.adminDeleteBinary(key)
                    Logger.info(LogCategory.SYSTEM, "MapStorageProxyDedupeLiveTest: cleaned up key=$key")
                }
                catch (e: Exception)
                {
                    Logger.warn(
                        LogCategory.SYSTEM,
                        "MapStorageProxyDedupeLiveTest: cleanup failed for key=$key: ${e.message}"
                    )
                }
            }
        }
        BinaryRecord.operationsFactory = { RealBinaryRecordOperations() }
        PlayerMapRepository.clearForTests()
    }

    /**
     * Build the AGS record key for a (userId, mapId) pair. Mirrors
     * [MapStorageProxy.recordKey] without pulling in the private method.
     */
    private fun recordKey(mapId: String): String = "player-$userId-$mapId"

    @Test
    fun dedupeReplacesOldRecordOnCaseInsensitiveNameCollision(): Unit = runBlocking {
        assumeTrue("set AGS_LIVE_TEST=true", liveTestEnabled())
        assumeTrue("no credentials file", credentialsAvailable())
        val ns = namespaceFromEnv()
        assumeTrue(
            "AB_NAMESPACE must be exported; " +
            "source ~/.autogenesis/config/accelbyte.properties or pass AB_NAMESPACE explicitly",
            !ns.isNullOrBlank()
        )

        // Unique test marker so re-runs don't collide with leftover records
        // from a prior session under the same name.
        val marker = UUID.randomUUID().toString().take(8)
        val baseName = "LiveDedupe-$marker"
        val oldMapId = UUID.randomUUID().toString()
        val newMapId = UUID.randomUUID().toString()
        val oldKey = recordKey(oldMapId)
        val newKey = recordKey(newMapId)
        val oldPack = "old-pack-bytes-$marker".toByteArray()
        val newPack = "new-pack-bytes-$marker".toByteArray()

        // Register both keys for cleanup — oldKey might or might not exist by
        // the time @After runs (the dedupe flow may have already deleted it),
        // but `adminDeleteBinary` is a soft no-op on missing keys so duplicate
        // cleanup is safe.
        cleanupKeys += oldKey
        cleanupKeys += newKey

        // -- 1. First upload: name "LiveDedupe-<marker>" with mapId=oldMapId --
        val firstOk = MapStorageProxy.savePlayerMap(
            ctx,
            SavePlayerMapRequest(
                userId = userId,
                mapId = oldMapId,
                mapName = baseName,
                mapPackBytes = oldPack,
                contentType = "application/zip"
            )
        )
        assertTrue(firstOk, "first save must succeed against live AGS")

        // Confirm the first record exists in AGS via a direct get.
        val firstGet = BinaryRecord.adminGetBinary(oldKey).getOrNull()
        assertNotNull(firstGet, "first record must exist in AGS after savePlayerMap (key=$oldKey)")
        Logger.info(LogCategory.SYSTEM, "Step 1 OK: oldKey=$oldKey visible in AGS")

        // -- 2. Second upload: case-insensitive collision "livededupe-<marker>" --
        //     The dedupe preflight inside savePlayerMap should detect the name
        //     collision, delete the old AGS record, remove the catalogue entry,
        //     then proceed with the normal new-upload flow.
        val secondOk = MapStorageProxy.savePlayerMap(
            ctx,
            SavePlayerMapRequest(
                userId = userId,
                mapId = newMapId,
                mapName = baseName.lowercase(),  // "livededupe-<marker>" — case collision
                mapPackBytes = newPack,
                contentType = "application/zip"
            )
        )
        assertTrue(secondOk, "second save must succeed; the dedupe preflight should not block normal save")
        Logger.info(LogCategory.SYSTEM, "Step 2 OK: second save succeeded with newKey=$newKey")

        // -- 3. Probe AGS: old record must be GONE --
        //     adminGetBinary on a missing record returns Result.failure (AGS
        //     error 18303 "record not found"). The preflight's adminDeleteBinary
        //     call should have removed the old key from AGS.
        val oldGetAfter = BinaryRecord.adminGetBinary(oldKey)
        assertTrue(
            oldGetAfter.isFailure,
            "old record must be deleted by the dedupe preflight; " +
            "got success=${oldGetAfter.getOrNull()}"
        )
        Logger.info(LogCategory.SYSTEM, "Step 3 OK: oldKey=$oldKey is gone from AGS (failure=${oldGetAfter.exceptionOrNull()?.message})")

        // -- 4. Probe AGS: new record must exist --
        //     After the dedupe preflight runs, the normal new-upload flow
        //     creates a fresh AGS record at newKey with the new bytes.
        val newGetAfter = BinaryRecord.adminGetBinary(newKey).getOrNull()
        assertNotNull(newGetAfter, "new record must exist in AGS after second save (key=$newKey)")
        Logger.info(LogCategory.SYSTEM, "Step 4 OK: newKey=$newKey is present in AGS")

        // -- 5. Catalogue: only the new mapId, not the old --
        val oldEntry = PlayerMapRepository.getEntry(userId, oldMapId)
        val newEntry = PlayerMapRepository.getEntry(userId, newMapId)
        assertEquals(null, oldEntry, "old mapId must be removed from the catalogue")
        assertNotNull(newEntry, "new mapId must be present in the catalogue")
        assertEquals(baseName.lowercase(), newEntry.mapName, "catalogue should store the new name verbatim")
        Logger.info(LogCategory.SYSTEM, "Step 5 OK: catalogue has new mapId only; old mapId is gone")

        // -- 6. Cleanup: delete the new record so the namespace stays tidy --
        val cleanupOk = BinaryRecord.adminDeleteBinary(newKey)
        assertTrue(cleanupOk.isSuccess, "cleanup delete must succeed; got ${cleanupOk.exceptionOrNull()?.message}")
        // Mark both keys as cleaned so @After skips them.
        cleanupKeys.remove(oldKey)
        cleanupKeys.remove(newKey)
    }

    @Test
    fun dedupeDoesNotDeleteAnythingWhenNoNameCollision(): Unit = runBlocking {
        // Negative-control companion to the replace test. Proves the dedupe
        // preflight is correctly scoped: a first upload with a unique name
        // must leave AGS untouched (no orphan delete fired).
        assumeTrue("set AGS_LIVE_TEST=true", liveTestEnabled())
        assumeTrue("no credentials file", credentialsAvailable())
        val ns = namespaceFromEnv()
        assumeTrue("AB_NAMESPACE must be exported", !ns.isNullOrBlank())

        val marker = UUID.randomUUID().toString().take(8)
        val uniqueName = "LiveNoCollision-$marker-${System.currentTimeMillis()}"
        val onlyMapId = UUID.randomUUID().toString()
        val onlyKey = recordKey(onlyMapId)
        cleanupKeys += onlyKey

        val ok = MapStorageProxy.savePlayerMap(
            ctx,
            SavePlayerMapRequest(
                userId = userId,
                mapId = onlyMapId,
                mapName = uniqueName,
                mapPackBytes = "first-only-bytes-$marker".toByteArray(),
                contentType = "application/zip"
            )
        )
        assertTrue(ok, "save must succeed against live AGS")

        val get = BinaryRecord.adminGetBinary(onlyKey).getOrNull()
        assertNotNull(get, "the only upload must produce a visible AGS record")

        // Re-upload with a DIFFERENT name to ensure no false-positive dedupe.
        val secondName = "LiveNoCollision-$marker-different"
        val secondMapId = UUID.randomUUID().toString()
        val secondKey = recordKey(secondMapId)
        cleanupKeys += secondKey

        val secondOk = MapStorageProxy.savePlayerMap(
            ctx,
            SavePlayerMapRequest(
                userId = userId,
                mapId = secondMapId,
                mapName = secondName,
                mapPackBytes = "second-bytes-$marker".toByteArray(),
                contentType = "application/zip"
            )
        )
        assertTrue(secondOk, "second save must succeed")

        // Both records must still exist in AGS — different names, no collision.
        assertNotNull(BinaryRecord.adminGetBinary(onlyKey).getOrNull(), "first record must still exist")
        assertNotNull(BinaryRecord.adminGetBinary(secondKey).getOrNull(), "second record must still exist")

        // Clean up both before @After runs so the namespace stays tidy.
        BinaryRecord.adminDeleteBinary(onlyKey)
        BinaryRecord.adminDeleteBinary(secondKey)
        cleanupKeys.remove(onlyKey)
        cleanupKeys.remove(secondKey)
    }
}
