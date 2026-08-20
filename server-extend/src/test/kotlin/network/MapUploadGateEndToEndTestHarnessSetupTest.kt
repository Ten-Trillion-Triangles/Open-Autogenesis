package network

import agent.builders.MapSafetyPayload
import com.TTT.Config.TPipeConfig
import com.TTT.Pipe.MultimodalContent
import globals.ExtendConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.network.MapUploadRequest
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcMessage
import org.ttt.autogenesis.serverextend.RestPlayerConnectionManager
import java.io.File
import kotlin.test.assertTrue

/**
 * Pins the test-harness setup for [MapUploadGateEndToEndTest]'s
 * safety-billing assertions.
 *
 * Bug (2026-08-13, surfaced from this thread's two pre-existing
 * EndToEnd test failures):
 *   `MapUploadGateEndToEndTest`'s `full upload flow succeeds, client
 *   receives the success notification, and the safety-agent trace
 *   lands on disk` test asserts `billingCalls.contains("saveUsageLedger")`
 *   after running the real gate against the fake-seamed safety runner.
 *   With `ExtendConfig.debugMode=true` (the JVM default — see
 *   [globals.ExtendConfig.debugMode]), `MapUploadSafetyBilling.recordSafetyUsage`
 *   short-circuits with `SafetyBillingOutcome.Skipped("dev mode")` BEFORE
 *   the `setInvokeOverrideForTest` seam is consulted. The seam
 *   therefore records zero billing calls, the assertion fires RED,
 *   and the suite carries two latent pre-existing failures.
 *
 * Fix contract:
 *   The harness `@Before` must (a) flip `ExtendConfig.debugMode = false`,
 *   (b) reset `MapUploadSafetyBilling` so the dev-mode override + the
 *   per-test override don't leak across tests, and (c) drop a
 *   synthetic `MapUploadGate/trace.json` under the resolved
 *   [TPipeConfig.getTraceDir] so the billing singleton has trace tokens
 *   to parse on the safety-pass path. Without (c) the billing singleton
 *   returns `Skipped("trace.json missing")` even with the seam
 *   configured — the harness currently asserts neither Skipped nor
 *   Recorded so it would silently slip back into the broken state.
 *
 * This sentinel runs the simplest possible end-to-end upload (real
 * pack, fake safety, fake saver) and asserts the billing seam fires
 * with the right method names. Once it pins GREEN, the existing three
 * EndToEnd test cases that exercise the same seam get the same setup
 * for free.
 */
class MapUploadGateEndToEndTestHarnessSetupTest
{
    private var originalConfigDir: String = ""
    private var originalDebugMode: Boolean = false

    @Before
    fun resetBefore()
    {
        // Mirror the harness setup that the EndToEnd test class needs.
        // The `MapUploadGateEndToEndTest.@Before` already resets the gate
        // seams; we layer the billing seams on top because they are
        // billing-specific.
        MapUploadGate.resetForTest()
        MapUploadGateStorage.resetForTest()
        MapUploadSuccessHandlers.resetForTest()
        MapUploadErrorHandlers.resetForTest()
        MapUploadSafetyBilling.resetForTest()

        // Flip debugMode off so the billing singleton reaches its RPC
        // loop. Production code (ServerExtend.kt) sets debugMode true at
        // SSE /events connect for guest sessions — this test runs the
        // production-shaped gate path against fake seams, which is the
        // "live mode" shape that maps to ExtendConfig.debugMode = false.
        originalDebugMode = ExtendConfig.debugMode
        ExtendConfig.debugMode = false

        // Drop a synthetic trace.json into the path the billing singleton
        // reads. Without it `MapUploadSafetyBilling.recordSafetyUsage`
        // returns `Skipped("trace.json missing")` even with the seam
        // configured. TPipe's getTraceDir() resolves to
        // `${TPipeConfig.configDir}/debug/trace` — two levels below
        // configDir (mirrors `MapUploadSafetyBillingLedgerWriteTest`'s
        // TemporaryFolder + parentFile.parentFile pattern).
        originalConfigDir = TPipeConfig.configDir
        val tmp = File(System.getProperty("java.io.tmpdir"), "harness-trace-${System.nanoTime()}")
        if (tmp.exists()) tmp.deleteRecursively()
        tmp.mkdirs()
        val traceRoot = File(tmp, "debug/trace")
        traceRoot.mkdirs()
        TPipeConfig.configDir = tmp.absolutePath
        val gateDir = File(traceRoot, "MapUploadGate")
        gateDir.mkdirs()
        File(gateDir, "trace.json").writeText(
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
        )
    }

    @After
    fun resetAfter()
    {
        MapUploadGate.resetForTest()
        MapUploadGateStorage.resetForTest()
        MapUploadSuccessHandlers.resetForTest()
        MapUploadErrorHandlers.resetForTest()
        MapUploadSafetyBilling.resetForTest()

        TPipeConfig.configDir = originalConfigDir
        ExtendConfig.debugMode = originalDebugMode
    }

    @Test
    fun endToEndGateFiresBillingSeam_whenDebugModeFalse_andTraceJsonPresent(): Unit = runBlocking {
        // Same shape as the existing EndToEnd test: real pack, fake
        // safety runner that returns a pass, fake saver that returns
        // true. Capture every method the billing seam fires.
        val packBytes = MapUploadGateTestFixtures.buildPackBytes("Harness Sentinel Map")
        val preUnpacked = MapUploadGateTestFixtures.preUnpack(packBytes)

        MapUploadGate.fakeUnpacker = { _ ->
            MapSafetyPayload(imageBytes = preUnpacked.first, mapData = preUnpacked.second)
        }
        MapUploadGate.fakeSafetyRunner = { _, _ ->
            MultimodalContent(text = "{\"isAllowed\": true, \"reason\": \"\"}")
        }
        MapUploadGateStorage.fakeSaver = { _, _ -> true }

        val billingCalls = mutableListOf<String>()
        MapUploadSafetyBilling.setInvokeOverrideForTest { method, _ ->
            billingCalls.add(method)
            when (method)
            {
                "server.extend.getAccountSettings" -> RpcMessage.Response(
                    id = "stub",
                    result = RpcJson.encodeToJsonElement(
                        structs.account.AccountSettings.serializer(),
                        structs.account.AccountSettings(accelByteUserId = "harness-sentinel")
                    )
                )
                "server.extend.getUsageLedger" -> RpcMessage.Response(
                    id = "stub",
                    result = RpcJson.encodeToJsonElement(
                        structs.account.UsageLedger.serializer(),
                        structs.account.UsageLedger(accelByteUserId = "harness-sentinel")
                    )
                )
                else -> RpcMessage.Response(id = "stub", result = null)
            }
        }

        val manager = RestPlayerConnectionManager()
        MapUploadSuccessHandlers.registerConnectionManager(manager)
        manager.register("harness-sentinel")

        val request = MapUploadRequest(mapPackBytes = packBytes, mapName = "Harness Sentinel Map")
        val response = MapUploadGate.uploadMapGate(
            RpcCallContext(
                connectionId = "harness-sentinel",
                metadata = emptyMap(),
                sender = { _: RpcMessage -> }
            ),
            request
        )

        assertTrue(response.accepted, "safety-pass + save-success must return accepted=true")
        assertTrue(
            billingCalls.contains("server.extend.saveUsageLedger"),
            "billing seam MUST record saveUsageLedger when debugMode=false " +
                    "AND a synthetic trace.json is present. Got billingCalls=" +
                    "$billingCalls. If this fails the EndToEnd harness setup " +
                    "is incomplete and the billing-side assertions will silently " +
                    "slip back to failing. Fix: mirror MapUploadSafetyBillingLedgerWriteTest's " +
                    "@Before setup in MapUploadGateEndToEndTest."
        )
        assertTrue(
            billingCalls.contains("server.extend.saveAccountSettings"),
            "billing seam MUST record saveAccountSettings under the same " +
                    "conditions. Got billingCalls=$billingCalls."
        )
    }
}