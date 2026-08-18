package proxy

import accelbyte.cloudsave.BinaryRecord
import accelbyte.cloudsave.BinaryRecordOperations
import accelbyte.cloudsave.RealBinaryRecordOperations
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.serverextend.config.AccelByteConfig
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import structs.accelbyte.cloudsave.GameBinaryRecordCreateRequest
import structs.accelbyte.cloudsave.UploadBinaryRecordRequest
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live AGS smoke test for the BinaryRecord proxy + helper. Opt-in via:
 *
 *     AGS_LIVE_TEST=true ./gradlew :server-extend:test --tests "proxy.BinaryRecordProxyLiveTest"
 *
 * Reads AccelByte credentials from `./accelbyte.local.properties` (relative
 * to the working directory of the test JVM) and verifies that a real call
 * to `requestPresignedUrl` returns a usable presigned URL from AGS. This
 * proves the SDK wiring on server-extend is correct end-to-end.
 *
 * Cleanup: deletes the test record after a successful run to keep the
 * namespace tidy. Test record keys are timestamped to avoid collisions
 * with other live runs.
 */
class BinaryRecordProxyLiveTest
{
    private val rpcContext: RpcCallContext = RpcCallContext(
        connectionId = "live-test",
        sender = { /* noop */ }
    )

    private fun liveTestEnabled(): Boolean =
        System.getenv("AGS_LIVE_TEST") == "true"

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

    /**
     * Guard before every live test: load env vars into System properties if
     * not already set, and validate that AB_NAMESPACE is non-empty. The
     * AccelByteConfig singleton was initialised once (likely by an earlier
     * test class) and cached whatever it saw first — re-applying the env
     * values here ensures the live tests see the operator-supplied namespace
     * regardless of test execution order.
     *
     * Also installs a [BinaryRecordOperations] factory that resolves the
     * namespace at call-time rather than at lazy-init time, so per-test
     * namespace changes are honoured even when [RealBinaryRecordOperations]
     * captures state earlier.
     */
    @Before
    fun ensureNamespaceAndOpsLoaded()
    {
        val envNamespace = System.getenv("AB_NAMESPACE") ?: ""
        val envBaseUrl = System.getenv("AB_BASE_URL") ?: ""
        val envClientId = System.getenv("AB_CLIENT_ID") ?: ""
        val envClientSecret = System.getenv("AB_CLIENT_SECRET") ?: ""

        val sysPropsNamespace = System.getProperty("AB_NAMESPACE") ?: ""
        val sysPropsBaseUrl = System.getProperty("AB_BASE_URL") ?: ""

        // Diagnostic for CI debugging — full picture of what this JVM sees.
        Logger.info(
            LogCategory.SYSTEM,
            "BinaryRecordProxyLiveTest: env AB_NAMESPACE='$envNamespace' " +
            "AB_BASE_URL='$envBaseUrl' " +
            "AB_CLIENT_ID='$envClientId' " +
            "AB_CLIENT_SECRET='${envClientSecret.take(8)}...' " +
            "sysProp AB_NAMESPACE='$sysPropsNamespace' " +
            "sysProp AB_BASE_URL='$sysPropsBaseUrl' " +
            "userDir='${System.getProperty("user.dir")}' " +
            "cwd files: ${File(".").listFiles()?.joinToString { it.name } ?: "(unreadable)"}"
        )

        if (envNamespace.isNotBlank()) System.setProperty("AB_NAMESPACE", envNamespace)
        if (envBaseUrl.isNotBlank()) System.setProperty("AB_BASE_URL", envBaseUrl)
        if (envClientId.isNotBlank()) System.setProperty("AB_CLIENT_ID", envClientId)
        if (envClientSecret.isNotBlank()) System.setProperty("AB_CLIENT_SECRET", envClientSecret)

        // Force AccelByteConfig's properties to refresh by re-loading.
        // AccelByteConfig is a JVM singleton with a final Properties field
        // initialised in <clinit>. If a prior test class triggered init while
        // AB_NAMESPACE was missing, the cached Properties has the wrong value
        // and getProperty() returns empty (not null), blocking the env fallback.
        // We can't reset the singleton without reflection, but we CAN ensure
        // the test JVM's view is correct by reading the resolved namespace
        // through AccelByteSdkProvider (which reads AB_NAMESPACE from env) —
        // and use that as the authoritative source in the per-call factory.
        val authoritativeNamespace = System.getenv("AB_NAMESPACE")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("AB_NAMESPACE")?.takeIf { it.isNotBlank() }
            ?: "echoofmaridia-autogenesis"  // Hard fallback so live tests fail loudly, not silently

        Logger.info(
            LogCategory.SYSTEM,
            "BinaryRecordProxyLiveTest: authoritative namespace for SDK calls = '$authoritativeNamespace'"
        )

        // Log the resolved namespace for diagnostics in CI logs.
        Logger.info(
            LogCategory.SYSTEM,
            "BinaryRecordProxyLiveTest: resolved AB_NAMESPACE='${AccelByteConfig.getNamespace()}' " +
            "baseUrl='${envBaseUrl}'"
        )

        // Install a per-call ops factory that resolves the namespace freshly
        // each time, defeating any stale lazy capture from earlier tests.
        // Uses the authoritative namespace (from env/system property) rather
        // than AccelByteConfig.getNamespace() which returns a stale cached
        // value if the singleton was initialised by an earlier test class.
        BinaryRecord.operationsFactory = {
            object : BinaryRecordOperations
            {
                private val delegate = RealBinaryRecordOperations()
                override fun listBinaries(namespace: String, limit: Int?, offset: Int?, query: String?, tags: List<String>?) =
                    delegate.listBinaries(authoritativeNamespace, limit, offset, query, tags)
                override fun getBinary(namespace: String, key: String) =
                    delegate.getBinary(authoritativeNamespace, key)
                override fun createBinary(namespace: String, request: GameBinaryRecordCreateRequest) =
                    delegate.createBinary(authoritativeNamespace, request)
                override fun replaceBinary(namespace: String, key: String, request: structs.accelbyte.cloudsave.BinaryRecordRequest) =
                    delegate.replaceBinary(authoritativeNamespace, key, request)
                override fun deleteBinary(namespace: String, key: String) =
                    delegate.deleteBinary(authoritativeNamespace, key)
                override fun bulkFetch(namespace: String, keys: List<String>) =
                    delegate.bulkFetch(authoritativeNamespace, keys)
                override fun requestPresignedUrl(namespace: String, key: String, uploadRequest: UploadBinaryRecordRequest) =
                    delegate.requestPresignedUrl(authoritativeNamespace, key, uploadRequest)

                override fun listAdminRecords(namespace: String, limit: Int, offset: Int, query: String?, tags: List<String>?) =
                    delegate.listAdminRecords(authoritativeNamespace, limit, offset, query, tags)
                override fun adminGetBinary(namespace: String, key: String) =
                    delegate.adminGetBinary(authoritativeNamespace, key)
                override fun adminCreateBinary(namespace: String, request: GameBinaryRecordCreateRequest) =
                    delegate.adminCreateBinary(authoritativeNamespace, request)
                override fun adminReplaceBinary(namespace: String, key: String, request: structs.accelbyte.cloudsave.BinaryRecordRequest) =
                    delegate.adminReplaceBinary(authoritativeNamespace, key, request)
                override fun adminDeleteBinary(namespace: String, key: String) =
                    delegate.adminDeleteBinary(authoritativeNamespace, key)
                override fun adminRequestPresignedUrl(namespace: String, key: String, uploadRequest: UploadBinaryRecordRequest) =
                    delegate.adminRequestPresignedUrl(authoritativeNamespace, key, uploadRequest)
                override fun adminUpdateMetadata(namespace: String, key: String, metadata: structs.accelbyte.cloudsave.GameBinaryRecordMetadata) =
                    delegate.adminUpdateMetadata(authoritativeNamespace, key, metadata)

                override fun listMyBinaries(namespace: String, limit: Int?, offset: Int?, query: String?, tags: List<String>?) =
                    delegate.listMyBinaries(authoritativeNamespace, limit, offset, query, tags)
                override fun getMyBinary(namespace: String, key: String) =
                    delegate.getMyBinary(authoritativeNamespace, key)
                override fun createMyBinary(namespace: String, request: GameBinaryRecordCreateRequest) =
                    delegate.createMyBinary(authoritativeNamespace, request)
                override fun replaceMyBinary(namespace: String, key: String, request: structs.accelbyte.cloudsave.BinaryRecordRequest) =
                    delegate.replaceMyBinary(authoritativeNamespace, key, request)
                override fun deleteMyBinary(namespace: String, key: String) =
                    delegate.deleteMyBinary(authoritativeNamespace, key)
                override fun bulkFetchMy(namespace: String, keys: List<String>) =
                    delegate.bulkFetchMy(authoritativeNamespace, keys)
                override fun requestMyPresignedUrl(namespace: String, key: String, uploadRequest: UploadBinaryRecordRequest) =
                    delegate.requestMyPresignedUrl(authoritativeNamespace, key, uploadRequest)

                override fun adminListPlayerBinaries(namespace: String, userId: String, limit: Int?, offset: Int?, query: String?, tags: List<String>?) =
                    delegate.adminListPlayerBinaries(authoritativeNamespace, userId, limit, offset, query, tags)
                override fun adminGetPlayerBinary(namespace: String, userId: String, key: String) =
                    delegate.adminGetPlayerBinary(authoritativeNamespace, userId, key)
                override fun adminCreatePlayerBinary(namespace: String, userId: String, request: GameBinaryRecordCreateRequest) =
                    delegate.adminCreatePlayerBinary(authoritativeNamespace, userId, request)
                override fun adminReplacePlayerBinary(namespace: String, userId: String, key: String, request: structs.accelbyte.cloudsave.BinaryRecordRequest) =
                    delegate.adminReplacePlayerBinary(authoritativeNamespace, userId, key, request)
                override fun adminDeletePlayerBinary(namespace: String, userId: String, key: String) =
                    delegate.adminDeletePlayerBinary(authoritativeNamespace, userId, key)
                override fun adminRequestPlayerPresignedUrl(namespace: String, userId: String, key: String, uploadRequest: UploadBinaryRecordRequest) =
                    delegate.adminRequestPlayerPresignedUrl(authoritativeNamespace, userId, key, uploadRequest)
                override fun adminUpdatePlayerMetadata(namespace: String, userId: String, key: String, metadata: structs.accelbyte.cloudsave.GameBinaryRecordMetadata) =
                    delegate.adminUpdatePlayerMetadata(authoritativeNamespace, userId, key, metadata)
            }
        }
    }

    @Test
    fun `adminCreateBinary returns presigned URL against live AGS`(): Unit = runBlocking {
        assumeTrue("Skipping live AGS test (set AGS_LIVE_TEST=true to enable)", liveTestEnabled())
        assumeTrue("Skipping live AGS test (no accelbyte.local.properties found)", credentialsAvailable())

        // AccelByteConfig is a singleton — its <clinit> ran once when another
        // test class first touched it. If that class ran without the env vars
        // exported (AB_NAMESPACE, etc.), the namespace was empty at SDK init.
        // We re-read the namespace here so the test reflects the actual env
        // state, not whatever was cached by an earlier test.
        val namespace = System.getenv("AB_NAMESPACE") ?: System.getProperty("AB_NAMESPACE") ?: ""
        assumeTrue(
            "Skipping live AGS test (AB_NAMESPACE not set in test JVM env; " +
            "export $(grep -v '^#' server-extend/accelbyte.local.properties | xargs) before running)",
            namespace.isNotBlank()
        )

        val testKey = "lord-maple-tree-admin-create-${System.currentTimeMillis()}"

        // AGS binary record pattern: create returns a presigned upload URL.
        // The record metadata is finalized only AFTER bytes are uploaded.
        // Note: AGS validates that set_by is non-empty (error 18305) AND must be a
        // recognized value — "SERVER" is the magic value that matches
        // CloudSaveProxy's existing JSON save path.
        // AGS file types: jpeg, jpg, png, bmp, gif, mp3, webp, bin (NOT "image/png").
        val createResult = BinaryRecord.adminCreateBinary(
            GameBinaryRecordCreateRequest(key = testKey, fileType = "png", setBy = "SERVER")
        )

        val response = createResult.getOrNull()
        if (response == null)
        {
            val error = createResult.exceptionOrNull()?.message ?: "unknown"
            System.err.println("[BinaryRecordProxyLiveTest] adminCreateBinary failed: $error")
            assertTrue(
                false,
                "adminCreateBinary failed with $error — this is the canary endpoint for diagnosing token/permission gaps"
            )
            return@runBlocking
        }

        assertNotNull(response.url, "Admin createBinary response must include a presigned upload URL")
        assertTrue(
            response.url.startsWith("https://"),
            "Presigned URL must be HTTPS, got: ${response.url}"
        )

        BinaryRecord.adminDeleteBinary(testKey)
    }

    @Test
    fun `adminRequestPresignedUrl returns presigned URL against live AGS`(): Unit = runBlocking {
        assumeTrue("Skipping live AGS test (set AGS_LIVE_TEST=true to enable)", liveTestEnabled())
        assumeTrue("Skipping live AGS test (no accelbyte.local.properties found)", credentialsAvailable())

        // See note in adminCreateBinary test about AB_NAMESPACE env propagation.
        val namespace = System.getenv("AB_NAMESPACE") ?: System.getProperty("AB_NAMESPACE") ?: ""
        assumeTrue(
            "Skipping live AGS test (AB_NAMESPACE not set in test JVM env; " +
            "export $(grep -v '^#' server-extend/accelbyte.local.properties | xargs) before running)",
            namespace.isNotBlank()
        )

        val testKey = "lord-maple-tree-admin-presigned-${System.currentTimeMillis()}"

        // Create the record first (with set_by=SERVER) so the subsequent
        // presigned-URL request has something to operate on.
        BinaryRecord.adminCreateBinary(
            GameBinaryRecordCreateRequest(key = testKey, fileType = "png", setBy = "SERVER")
        )

        val presigned = BinaryRecord.adminRequestPresignedUrl(
            key = testKey,
            // AGS accepts: jpeg, jpg, png, bmp, gif, mp3, webp, bin
            uploadRequest = UploadBinaryRecordRequest(fileType = "png")
        )

        val response = presigned.getOrNull()
        if (response == null)
        {
            val error = presigned.exceptionOrNull()?.message ?: "unknown"
            System.err.println("[BinaryRecordProxyLiveTest] adminRequestPresignedUrl failed: $error")
            BinaryRecord.adminDeleteBinary(testKey)
            assertTrue(
                false,
                "adminRequestPresignedUrl failed with $error — this is the canary endpoint for diagnosing token/permission gaps"
            )
            return@runBlocking
        }

        assertNotNull(response.url, "Admin presigned URL response must contain a non-empty url")
        assertTrue(
            response.url.startsWith("https://"),
            "Admin presigned URL should be HTTPS, got: ${response.url}"
        )

        BinaryRecord.adminDeleteBinary(testKey)
    }
}