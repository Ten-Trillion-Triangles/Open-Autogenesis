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
import org.ttt.autogenesis.serverextend.config.AccelByteConfig
import structs.rpcRequests.DeletePlayerMapRequest
import structs.rpcRequests.GetPlayerMapRequest
import structs.rpcRequests.ListPlayerMapsRequest
import structs.rpcRequests.SavePlayerMapRequest
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live AGS smoke test for [MapStorageProxy].
 *
 * Proves the full upload + list + download + delete chain against the real
 * AGS namespace, using the Game Binary Record path. The original Player Binary
 * path was observed returning HTTP 500 `l5d-proxy-error` from AccelByte's
 * Linkerd service mesh in 2026-08; the refactor moved storage to namespace-
 * scoped Game Binary Records (`player-<userId>-<mapId>` keys, with a
 * `ownerUserId:<userId>` tag for defense-in-depth ownership).
 *
 *   1. adminCreateGameBinary -> PUT bytes to AGS-issued URL -> ownership tag -> catalogue add
 *   2. listPlayerMaps returns the just-saved entry (from in-memory cache)
 *   3. getPlayerMap fetches the bytes via the presigned download URL, verifying ownership tag
 *   4. deletePlayerMap removes the record
 *
 * Opt-in via AGS_LIVE_TEST=true + env vars (AB_NAMESPACE, AB_BASE_URL,
 * AB_CLIENT_ID, AB_CLIENT_SECRET) exported before gradle invocation.
 *
 * Bypasses AccelByteConfig's stale-lazy capture (verified pattern from
 * BinaryRecordProxyLiveTest @Before hook) by reading AB_NAMESPACE from
 * System.getenv directly.
 */
class MapStorageProxyLiveTest
{
    private val ctx = RpcCallContext(connectionId = "live-test", sender = { })

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

    private fun namespaceFromEnv(): String?
    {
        val ns = System.getenv("AB_NAMESPACE")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("AB_NAMESPACE")?.takeIf { it.isNotBlank() }
        return ns
    }

    @Before
    fun setUp()
    {
        PlayerMapRepository.clearForTests()

        val envNamespace = namespaceFromEnv() ?: return

        // Install a per-call ops factory that resolves namespace freshly each
        // time — defeats the AccelByteConfig singleton's stale-lazy capture
        // when earlier tests have already touched it. Same pattern as
        // BinaryRecordProxyLiveTest.
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

        Logger.info(
            LogCategory.SYSTEM,
            "MapStorageProxyLiveTest: namespace=$envNamespace"
        )
    }

    @After
    fun tearDown()
    {
        BinaryRecord.operationsFactory = { RealBinaryRecordOperations() }
        PlayerMapRepository.clearForTests()
    }

    @Test
    fun saveAndListAndGetAndDeleteEndToEnd(): Unit = runBlocking {
        assumeTrue("set AGS_LIVE_TEST=true", liveTestEnabled())
        assumeTrue("no credentials file", credentialsAvailable())
        val ns = namespaceFromEnv()
        assumeTrue(
            "AB_NAMESPACE must be exported; " +
            "export \$(grep -v '^#' server-extend/accelbyte.local.properties | xargs) before running",
            !ns.isNullOrBlank()
        )
        val namespace = ns!!

        // Use a REAL user from the namespace — adminCreateGameBinary requires
        // a user that exists in AGS's user database (random UUIDs surface 500).
        // Found via: `ags iam users search --query "test"` against echoofmaridia-autogenesis.
        val userId = System.getenv("AB_LIVE_TEST_USER")
            ?: "25a70be88881466286bc03154f5d7492"  // KingCandy1 — pre-verified against live AGS
        val mapId = UUID.randomUUID().toString()
        val pack = "live-e2e-game-binary-${System.currentTimeMillis()}".toByteArray()

        // 1. Upload (Game Binary path — adminCreateGameBinary returns a presigned URL)
        val saved = MapStorageProxy.savePlayerMap(ctx, SavePlayerMapRequest(
            userId = userId, mapId = mapId, mapName = "e2e-game-binary",
            mapPackBytes = pack
        ))
        assertTrue(saved, "savePlayerMap must succeed against live AGS via Game Binary (namespace=$namespace)")

        // 2. List
        val snap = MapStorageProxy.listPlayerMaps(ctx, ListPlayerMapsRequest(userId))
        assertTrue(
            snap.maps.any { it.mapId == mapId },
            "list should include the freshly saved map; got ${snap.maps}"
        )

        // 3. Get — confirmed working as of 2026-08-11 once the three-step save flow
        //    (create → S3 PUT → commit via adminPutGameBinaryRecordV1) was
        //    added to MapStorageProxy.savePlayerMap. AGS now populates
        //    `binary_info.url` on the GET response. See depot's C++ Unreal
        //    SDK at AccelCore.cpp::CommitBinaryServerRecord for the pattern.
        val response = MapStorageProxy.getPlayerMap(ctx, GetPlayerMapRequest(userId, mapId))
        assertNotNull(response.mapPackBytes)
        assertTrue(
            response.mapPackBytes.contentEquals(pack),
            "downloaded bytes must match what we uploaded"
        )

        // 4. Delete
        MapStorageProxy.deletePlayerMap(ctx, DeletePlayerMapRequest(userId, mapId))
        val afterDelete = MapStorageProxy.listPlayerMaps(ctx, ListPlayerMapsRequest(userId))
        assertEquals(0, afterDelete.maps.size, "catalogue must be empty after delete")
    }

    /**
     * Diagnostic test that exercises the SAME Game Binary path that the
     * end-to-end test above uses. Fails loudly when the path is broken so CI
     * surfaces the breakage instead of silently skipping.
     */
    @Test
    fun diagnosticSaveGameBinaryAgainstLiveAgs(): Unit = runBlocking {
        assumeTrue("set AGS_LIVE_TEST=true", liveTestEnabled())
        assumeTrue("no credentials file", credentialsAvailable())
        val ns = namespaceFromEnv()
        assumeTrue("AB_NAMESPACE must be exported", !ns.isNullOrBlank())
        val namespace = ns!!

        val userId = System.getenv("AB_LIVE_TEST_USER")
            ?: "25a70be88881466286bc03154f5d7492"
        val mapId = UUID.randomUUID().toString()
        val pack = "diagnostic-game-binary-${System.currentTimeMillis()}".toByteArray()

        val saved = MapStorageProxy.savePlayerMap(ctx, SavePlayerMapRequest(
            userId = userId, mapId = mapId, mapName = "diagnostic-game-binary",
            mapPackBytes = pack
        ))
        assertTrue(
            saved,
            "savePlayerMap against live AGS FAILED — Game Binary endpoint adminCreateGameBinary " +
            "is unreachable for namespace=$namespace userId=$userId. Verify AGS Game Binary " +
            "service health via AccelByte Admin Portal or support ticket."
        )
    }
}
