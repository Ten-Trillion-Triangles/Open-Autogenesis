package network

import com.TTT.Config.TPipeConfig
import globals.ExtendConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcMessage
import java.io.File
import kotlin.test.assertEquals

/**
 * Pins the connectionId → accelbyteId attribution contract.
 *
 * Given two SSE sessions registered in the connection manager with distinct
 * `accelbyteId` values, calls to [MapUploadSafetyBilling.resolveAccelByteId]
 * must translate the SSE playerId to the registered canonical accelbyteId —
 * NOT echo the SSE playerId back as the user identifier.
 */
class MapUploadSafetyBillingAttributionTest
{
    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private lateinit var traceDir: File
    private var originalConfigDir: String = ""
    private var originalDebugMode: Boolean = false
    private lateinit var manager: org.ttt.autogenesis.serverextend.RestPlayerConnectionManager

    @Before
    fun setUp()
    {
        originalConfigDir = TPipeConfig.configDir
        originalDebugMode = ExtendConfig.debugMode
        ExtendConfig.debugMode = false
        val debugTraceRoot = tempFolder.newFolder("debug", "trace")
        TPipeConfig.configDir = debugTraceRoot.parentFile.parentFile.absolutePath
        val gateDir = File(debugTraceRoot, "MapUploadGate")
        gateDir.mkdirs()
        File(gateDir, "trace.json").writeText(syntheticTraceJson())
        MapUploadSafetyBilling.resetForTest()
        manager = org.ttt.autogenesis.serverextend.RestPlayerConnectionManager()
    }

    @After
    fun tearDown()
    {
        TPipeConfig.configDir = originalConfigDir
        ExtendConfig.debugMode = originalDebugMode
        MapUploadSafetyBilling.resetForTest()
    }

    @Test
    fun resolveAccelByteIdTranslatesSsePlayerIdToRegisteredAccelbyteId() = runBlocking {
        MapUploadSafetyBilling.setFakeConnectionManagerForTest(manager)

        // Register two sessions with distinct accelbyteIds via the real
        // register API (which builds the per-session RpcInvoker internally).
        manager.register(playerId = "sse-player-A", accelbyteId = "user-A")
        manager.register(playerId = "sse-player-B", accelbyteId = "user-B")

        assertEquals("user-A", MapUploadSafetyBilling.resolveAccelByteId("sse-player-A"))
        assertEquals("user-B", MapUploadSafetyBilling.resolveAccelByteId("sse-player-B"))
    }

    @Test
    fun resolveAccelByteIdFallsBackToPlayerIdWhenSessionHasNoAccelbyteId() = runBlocking {
        MapUploadSafetyBilling.setFakeConnectionManagerForTest(manager)
        // Empty manager — no sessions registered.
        assertEquals("unknown-sse-id", MapUploadSafetyBilling.resolveAccelByteId("unknown-sse-id"))
    }

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
