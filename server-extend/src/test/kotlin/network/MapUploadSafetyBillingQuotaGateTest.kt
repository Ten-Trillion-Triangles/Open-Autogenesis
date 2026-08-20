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
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcMessage
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the cap-rejection contract: when the user's
 * [AccountSettings.billingStatus.tokensUsedThisCycle] + the additional
 * safety-agent tokens would exceed `tokensCapPerCycle`, the singleton
 * returns [SafetyBillingOutcome.CapExceeded] WITHOUT invoking
 * `server.extend.saveUsageLedger` or `saveAccountSettings`.
 */
class MapUploadSafetyBillingQuotaGateTest
{
    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private var originalConfigDir: String = ""
    private var originalDebugMode: Boolean = false
    private val recordedCalls: MutableList<String> = mutableListOf()

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
        File(gateDir, "trace.json").writeText(syntheticTraceJson(inputTokens = 100, outputTokens = 50))
        MapUploadSafetyBilling.resetForTest()
        recordedCalls.clear()
    }

    @After
    fun tearDown()
    {
        TPipeConfig.configDir = originalConfigDir
        ExtendConfig.debugMode = originalDebugMode
        MapUploadSafetyBilling.resetForTest()
    }

    @Test
    fun recordSafetyUsageReturnsCapExceededWhenOverCap() = runBlocking {
        // User has used 1_999_950 of 2_000_000 cap. Additional 150 tokens
        // (100 in + 50 out) would push them to 2_000_100, over the cap.
        val settings = structs.account.AccountSettings(
            accelByteUserId = "user-over",
            billingStatus = structs.account.BillingStatus(
                credits = 0.0,
                tokensUsedThisCycle = 1_999_950L,
                tokensCapPerCycle = 2_000_000L
            )
        )
        MapUploadSafetyBilling.setInvokeOverrideForTest { method, _ ->
            recordedCalls.add(method)
            cannedForSettings(method, settings)
        }

        val outcome = MapUploadSafetyBilling.recordSafetyUsage(
            context = fakeContext(playerId = "user-over"),
            playerId = "user-over",
            mapId = "map-1",
            safetyPass = true
        )
        assertTrue(outcome is SafetyBillingOutcome.CapExceeded, "expected CapExceeded, got $outcome")
        val cap = outcome as SafetyBillingOutcome.CapExceeded
        assertEquals(1_999_950L, cap.tokensUsedThisCycle, "tokensUsedThisCycle mismatch")
        assertEquals(2_000_000L, cap.tokensCapPerCycle, "tokensCapPerCycle mismatch")

        val saveLedgerCalls = recordedCalls.filter { it == "server.extend.saveUsageLedger" }
        val saveSettingsCalls = recordedCalls.filter { it == "server.extend.saveAccountSettings" }
        assertEquals(0, saveLedgerCalls.size, "saveUsageLedger should NOT be called when over cap")
        assertEquals(0, saveSettingsCalls.size, "saveAccountSettings should NOT be called when over cap")
    }

    @Test
    fun recordSafetyUsageProceedsWhenUnderCap() = runBlocking {
        // User has used 100 of 2_000_000 cap. 150 additional tokens leave
        // plenty of room.
        val settings = structs.account.AccountSettings(
            accelByteUserId = "user-under",
            billingStatus = structs.account.BillingStatus(
                credits = 100.0,
                tokensUsedThisCycle = 100L,
                tokensCapPerCycle = 2_000_000L
            )
        )
        MapUploadSafetyBilling.setInvokeOverrideForTest { method, _ ->
            recordedCalls.add(method)
            cannedForSettings(method, settings)
        }

        val outcome = MapUploadSafetyBilling.recordSafetyUsage(
            context = fakeContext(playerId = "user-under"),
            playerId = "user-under",
            mapId = "map-1",
            safetyPass = true
        )
        assertTrue(outcome is SafetyBillingOutcome.Recorded, "expected Recorded, got $outcome")

        val saveLedgerCalls = recordedCalls.filter { it == "server.extend.saveUsageLedger" }
        val saveSettingsCalls = recordedCalls.filter { it == "server.extend.saveAccountSettings" }
        assertEquals(1, saveLedgerCalls.size, "expected exactly 1 saveUsageLedger call")
        assertEquals(1, saveSettingsCalls.size, "expected exactly 1 saveAccountSettings call")
    }

    private fun cannedForSettings(method: String, settings: structs.account.AccountSettings): RpcMessage.Response
    {
        return when (method)
        {
            "server.extend.getAccountSettings" -> RpcMessage.Response(
                id = "stub",
                result = Json.parseToJsonElement(
                    RpcJson.encodeToString(structs.account.AccountSettings.serializer(), settings)
                )
            )
            "server.extend.getUsageLedger" -> RpcMessage.Response(
                id = "stub",
                result = Json.parseToJsonElement(
                    RpcJson.encodeToString(structs.account.UsageLedger.serializer(), structs.account.UsageLedger(accelByteUserId = settings.accelByteUserId))
                )
            )
            else -> RpcMessage.Response(id = "stub", result = null)
        }
    }

    private fun fakeContext(playerId: String) =
        org.ttt.autogenesis.network.RpcCallContext(
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