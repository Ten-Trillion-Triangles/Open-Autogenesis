package network

import com.TTT.Config.TPipeConfig
import globals.ExtendConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcMessage
import java.io.File
import kotlin.test.assertTrue

/**
 * Pins the trace-parse contract for [MapUploadSafetyBilling.recordSafetyUsage]:
 *
 *  - Reads `${TPipeConfig.getTraceDir()}/MapUploadGate/trace.json`.
 *  - Builds a [TurnBillingRecord] with `turnKey = "MapUploadSafety_<mapId>_<ts>"`.
 *  - Returns [SafetyBillingOutcome.Recorded] with `costUsd > 0.0` for a non-empty trace.
 *
 * RED step (T3): the singleton returns `Skipped("not implemented")`, so the
 * Recorded assertion fails until T4 implements the trace-parse body.
 */
class MapUploadSafetyBillingTraceParseTest
{
    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private lateinit var traceDir: File
    private var originalConfigDir: String = ""
    private var originalDebugMode: Boolean = false

    @Before
    fun setUp()
    {
        // Route TPipeConfig.getTraceDir() to our temp dir via the canonical
        // configDir setter (used by MapUploadGateTraceTest). The gate's
        // captureAndSaveTrace writes {traceDir}/MapUploadGate/trace.json; we
        // mirror that contract by writing the fixture directly.
        originalConfigDir = TPipeConfig.configDir
        originalDebugMode = ExtendConfig.debugMode
        // Force non-dev so the dev-mode bypass in the production singleton
        // does not short-circuit the test. Tests that exercise the dev-mode
        // bypass use a dedicated test class (T11).
        ExtendConfig.debugMode = false
        traceDir = tempFolder.newFolder()
        // TPipeConfig.getTraceDir() = "${configDir}/debug/trace", so set
        // configDir to traceDir's parent and write the fixture under
        // traceDir/MapUploadGate/trace.json (which is the path the production
        // code reads).
        val debugTraceRoot = tempFolder.newFolder("debug", "trace")
        TPipeConfig.configDir = debugTraceRoot.parentFile.parentFile.absolutePath
        val gateDir = File(debugTraceRoot, "MapUploadGate")
        gateDir.mkdirs()
        File(gateDir, "trace.json").writeText(syntheticTraceJson(inputTokens = 800, outputTokens = 200))
        MapUploadSafetyBilling.resetForTest()
    }

    @After
    fun tearDown()
    {
        TPipeConfig.configDir = originalConfigDir
        ExtendConfig.debugMode = originalDebugMode
        MapUploadSafetyBilling.resetForTest()
    }

    @Test
    fun recordSafetyUsageParsesTraceJsonAndReturnsRecordedWithCorrectTurnKeyAndCostUsd() = runBlocking {
        // Inject the RPC seam so the test is hermetic — we don't want
        // production connection-manager state from other tests in the
        // same JVM run to leak into this test's cap check.
        MapUploadSafetyBilling.setInvokeOverrideForTest { method, _ ->
            when (method)
            {
                "server.extend.getAccountSettings" -> RpcMessage.Response(
                    id = "stub",
                    result = kotlinx.serialization.json.Json.parseToJsonElement(
                        RpcJson.encodeToString(
                            structs.account.AccountSettings.serializer(),
                            structs.account.AccountSettings(accelByteUserId = "player-1")
                        )
                    )
                )
                "server.extend.getUsageLedger" -> RpcMessage.Response(
                    id = "stub",
                    result = kotlinx.serialization.json.Json.parseToJsonElement(
                        RpcJson.encodeToString(
                            structs.account.UsageLedger.serializer(),
                            structs.account.UsageLedger(accelByteUserId = "player-1")
                        )
                    )
                )
                else -> RpcMessage.Response(id = "stub")
            }
        }

        val outcome = MapUploadSafetyBilling.recordSafetyUsage(
            context = fakeContext(playerId = "player-1"),
            playerId = "player-1",
            mapId = "map-abc-123",
            safetyPass = true
        )
        assertTrue(outcome is SafetyBillingOutcome.Recorded, "expected Recorded, got $outcome")
        val recorded = outcome as SafetyBillingOutcome.Recorded
        assertTrue(
            recorded.turnKey.startsWith("MapUploadSafety_map-abc-123_"),
            "turnKey '${recorded.turnKey}' should start with 'MapUploadSafety_map-abc-123_'"
        )
        assertTrue(recorded.costUsd > 0.0, "costUsd should be > 0 for a non-empty trace")
    }

    private fun fakeContext(playerId: String) =
        RpcCallContext(
            connectionId = playerId,
            metadata = emptyMap(),
            sender = { _: RpcMessage -> /* no-op */ }
        )

    private fun syntheticTraceJson(inputTokens: Int, outputTokens: Int): String =
        """
        [
          {
            "eventType": "API_CALL_SUCCESS",
            "pipeId": "image pipe",
            "timestamp": ${System.currentTimeMillis()},
            "metadata": {
              "inputTokens": $inputTokens,
              "outputTokens": $outputTokens,
              "modelId": "qwen.qwen3-235b-a22b-2507-v1:0"
            }
          }
        ]
        """.trimIndent()
}
