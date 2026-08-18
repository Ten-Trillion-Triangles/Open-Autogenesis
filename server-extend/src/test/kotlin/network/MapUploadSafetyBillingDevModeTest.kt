package network

import com.TTT.Config.TPipeConfig
import globals.ExtendConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ttt.autogenesis.network.RpcMessage
import java.io.File
import kotlin.test.assertTrue

/**
 * Pins the dev-mode bypass contract: when [ExtendConfig.debugMode] is true,
 * [MapUploadSafetyBilling.recordSafetyUsage] returns
 * [SafetyBillingOutcome.Skipped] WITHOUT invoking the RPC seam at all.
 */
class MapUploadSafetyBillingDevModeTest
{
    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private var originalConfigDir: String = ""
    private var originalDebugMode: Boolean = false
    private var invokerCalled: Boolean = false

    @Before
    fun setUp()
    {
        originalConfigDir = TPipeConfig.configDir
        originalDebugMode = ExtendConfig.debugMode
        ExtendConfig.debugMode = true
        val debugTraceRoot = tempFolder.newFolder("debug", "trace")
        TPipeConfig.configDir = debugTraceRoot.parentFile.parentFile.absolutePath
        val gateDir = File(debugTraceRoot, "MapUploadGate")
        gateDir.mkdirs()
        File(gateDir, "trace.json").writeText(syntheticTraceJson())
        MapUploadSafetyBilling.resetForTest()
        invokerCalled = false
        MapUploadSafetyBilling.setInvokeOverrideForTest { _, _ ->
            invokerCalled = true
            RpcMessage.Response(id = "stub")
        }
    }

    @After
    fun tearDown()
    {
        TPipeConfig.configDir = originalConfigDir
        ExtendConfig.debugMode = originalDebugMode
        MapUploadSafetyBilling.resetForTest()
    }

    @Test
    fun recordSafetyUsageSkipsLedgerWriteInDevMode() = runBlocking {
        val outcome = MapUploadSafetyBilling.recordSafetyUsage(
            context = fakeContext(playerId = "user-1"),
            playerId = "user-1",
            mapId = "map-1",
            safetyPass = true
        )
        assertTrue(outcome is SafetyBillingOutcome.Skipped, "expected Skipped, got $outcome")
        assertTrue(!invokerCalled, "RPC seam should not be called in dev mode")
    }

    private fun fakeContext(playerId: String) =
        org.ttt.autogenesis.network.RpcCallContext(
            connectionId = playerId,
            metadata = emptyMap(),
            sender = { _: RpcMessage -> /* no-op */ }
        )

    private fun syntheticTraceJson(): String =
        """
        [
          {
            "eventType": "API_CALL_SUCCESS",
            "pipeId": "image pipe",
            "timestamp": ${System.currentTimeMillis()},
            "metadata": {
              "inputTokens": 100,
              "outputTokens": 50,
              "modelId": "qwen.qwen3-235b-a22b-2507-v1:0"
            }
          }
        ]
        """.trimIndent()
}
