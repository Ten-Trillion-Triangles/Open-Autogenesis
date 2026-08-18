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
import org.ttt.autogenesis.network.RpcInvoker
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcMessage
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the ledger-write contract: the singleton calls
 * `server.extend.saveUsageLedger` and `server.extend.saveAccountSettings`
 * exactly once each, with JSON payloads that carry the right `accelByteUserId`,
 * `model` short ID, and `inputTokens`/`outputTokens`.
 *
 * Uses [MapUploadSafetyBilling.setInvokeOverrideForTest] for the RPC seam —
 * [RpcInvoker] is final in sharedModel and cannot be subclassed from tests.
 */
class MapUploadSafetyBillingLedgerWriteTest
{
    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private var originalConfigDir: String = ""
    private var originalDebugMode: Boolean = false
    private val recordedCalls: MutableList<Pair<String, kotlinx.serialization.json.JsonElement?>> = mutableListOf()

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
    fun recordSafetyUsageInvokesSaveUsageLedgerAndSaveAccountSettings() = runBlocking {
        MapUploadSafetyBilling.setInvokeOverrideForTest { method, params ->
            recordedCalls.add(method to params)
            canned(method)
        }

        val outcome = MapUploadSafetyBilling.recordSafetyUsage(
            context = fakeContext(playerId = "user-1"),
            playerId = "user-1",
            mapId = "map-1",
            safetyPass = true
        )
        assertTrue(outcome is SafetyBillingOutcome.Recorded, "expected Recorded, got $outcome")

        val saveLedgerCalls = recordedCalls.filter { it.first == "server.extend.saveUsageLedger" }
        val saveSettingsCalls = recordedCalls.filter { it.first == "server.extend.saveAccountSettings" }
        assertEquals(1, saveLedgerCalls.size, "expected exactly 1 saveUsageLedger call, got ${saveLedgerCalls.size}")
        assertEquals(1, saveSettingsCalls.size, "expected exactly 1 saveAccountSettings call, got ${saveSettingsCalls.size}")
    }

    @Test
    fun recordSafetyUsageFreeUserEntryCarriesNegativeCreditsDelta() = runBlocking {
        MapUploadSafetyBilling.setInvokeOverrideForTest { method, params ->
            recordedCalls.add(method to params)
            canned(method)
        }

        MapUploadSafetyBilling.recordSafetyUsage(
            context = fakeContext(playerId = "user-1"),
            playerId = "user-1",
            mapId = "map-1",
            safetyPass = true
        )

        // Capture the saveUsageLedger call's JSON params; parse the
        // ledgerJson embedded inside to verify creditsDelta is negative.
        val saveLedgerCall = recordedCalls.first { it.first == "server.extend.saveUsageLedger" }
        val saveRequest = RpcJson.decodeFromJsonElement(
            structs.rpcRequests.SaveUsageLedgerRequest.serializer(),
            saveLedgerCall.second!!
        )
        val ledgerJsonElement = Json.parseToJsonElement(saveRequest.ledgerJson)
        val ledger = RpcJson.decodeFromJsonElement(
            structs.account.UsageLedger.serializer(),
            ledgerJsonElement
        )
        assertEquals(1, ledger.entries.size, "expected exactly one ledger entry")
        val entry = ledger.entries.first()
        assertTrue(
            entry.creditsDelta < 0.0,
            "FREE user creditsDelta should be negative; got ${entry.creditsDelta}"
        )
        assertEquals("Map upload safety check", entry.sourceLabel)
        assertEquals("agent-run", entry.sourceIconKey)
    }

    private fun canned(method: String): RpcMessage.Response
    {
        val blankSettings = structs.account.AccountSettings(accelByteUserId = "user-1")
        val blankLedger = structs.account.UsageLedger(accelByteUserId = "user-1")
        return when (method)
        {
            "server.extend.getAccountSettings" -> RpcMessage.Response(
                id = "stub",
                result = Json.parseToJsonElement(
                    RpcJson.encodeToString(structs.account.AccountSettings.serializer(), blankSettings)
                )
            )
            "server.extend.getUsageLedger" -> RpcMessage.Response(
                id = "stub",
                result = Json.parseToJsonElement(
                    RpcJson.encodeToString(structs.account.UsageLedger.serializer(), blankLedger)
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
