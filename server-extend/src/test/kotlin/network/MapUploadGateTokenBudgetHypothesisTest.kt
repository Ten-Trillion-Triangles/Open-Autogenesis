package network

import agent.builders.MapSafetyPayload
import com.TTT.Config.TPipeConfig
import com.TTT.Context.Dictionary
import com.TTT.Pipe.BinaryContent
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.TruncationSettings
import globals.ExtendConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.network.MapUploadRequest
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcMessage
import org.ttt.autogenesis.serverextend.RestPlayerConnectionManager
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Hypothesis test for the operator-reported "TPipe token budget throws
 * 'Context window size: <N> Binary size: <X>' on San Martello upload".
 *
 * **What this test proves**: which byte sequence (the ORIGINAL 6.3MB PNG,
 * or the gate's DOWNSAMPLED 285KB PNG) actually reaches TPipe's binary
 * token-counting layer during `MapUploadGate.uploadMapGate`.
 *
 * **Why it matters**: the gate's downsample log shows the correct
 * shrink from 6,317,682 → 285,717 bytes, but the operator claims TPipe's
 * token budget then throws on the ORIGINAL size. The only way both can
 * be true is if either (a) the multimodal content the gate hands to
 * `builtPipeline.execute(multimodal)` still carries the original bytes,
 * or (b) the safety pipe's pre-init doesn't actually wipe the inbound
 * binaries before the budget check fires.
 *
 * **How it proves the smoking gum**: drives the real San Martello
 * `.map` zip through `MapUploadGate.uploadMapGate` with `fakeDownsampler`
 * disabled (real downsample runs) but `fakeSafetyRunner` capturing the
 * `MapSafetyPayload` it receives. Then it separately calls
 * `Dictionary.countBinaryTokens` against three candidate binary sets:
 * (1) the downsampled bytes the gate hands to fakeSafetyRunner, (2) the
 * original 6.3MB PNG bytes, and (3) both binaries double-stamped. The
 * resulting token counts ARE what TPipe's `truncateToFitTokenBudget`
 * sees in each scenario.
 *
 * If the report says downsampled_tokens > 990,000, TPipe is seeing the
 * ORIGINAL bytes (smoking gum confirmed). If it says downsampled_tokens
 * ~71K-285K, TPipe is seeing the downsampled bytes (operator's claim
 * was wrong for the downsampled path — the failure is downstream of the
 * budget check, likely the LLM call itself).
 */
class MapUploadGateTokenBudgetHypothesisTest
{
    private companion object
    {
        private const val GATE_TRACE_SUBFOLDER = "MapUploadGate"
    }

    private var originalConfigDir: String = ""
    private var originalDebugMode: Boolean = false
    private var traceTempDir: File? = null

    private val originalSanMartelloPngBytes: Int by lazy {
        File("/tmp/auto/san_martello_unpack/san_martello.png").readBytes().size
    }

    private val sanMartelloMapBytes: ByteArray by lazy {
        File("/tmp/auto/San_Martello.map").readBytes()
    }

    @Before
    fun resetBefore()
    {
        MapUploadGate.resetForTest()
        MapUploadGateStorage.resetForTest()
        MapUploadSuccessHandlers.resetForTest()
        MapUploadErrorHandlers.resetForTest()
        MapUploadSafetyBilling.resetForTest()

        originalDebugMode = ExtendConfig.debugMode
        ExtendConfig.debugMode = false

        originalConfigDir = TPipeConfig.configDir
        val tmp = File(System.getProperty("java.io.tmpdir"), "hypothesis-trace-${System.nanoTime()}")
        if (tmp.exists()) tmp.deleteRecursively()
        tmp.mkdirs()
        traceTempDir = tmp
        val traceRoot = File(tmp, "debug/trace")
        traceRoot.mkdirs()
        TPipeConfig.configDir = tmp.absolutePath
        val gateDir = File(traceRoot, GATE_TRACE_SUBFOLDER)
        gateDir.mkdirs()
        File(gateDir, "trace.json").writeText(
            """
            [
              {
                "eventType": "API_CALL_SUCCESS",
                "pipeId": "image pipe",
                "timestamp": ${System.currentTimeMillis()},
                "metadata": {"inputTokens": 100, "outputTokens": 50, "modelId": "qwen.qwen3-235b-a22b-2507-v1:0"}
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
        cleanupGateTraceDir()
        TPipeConfig.configDir = originalConfigDir
        ExtendConfig.debugMode = originalDebugMode
        traceTempDir?.let { if (it.exists()) it.deleteRecursively() }
        traceTempDir = null
    }

    @Test
    fun `sanMartello bytes what does TPipe token budget see original or downsampled`(): Unit = runBlocking {
        val capturedPayloadRef = arrayOfNulls<MapSafetyPayload>(1)

        MapUploadGate.fakeSafetyRunner = { _, payload ->
            capturedPayloadRef[0] = payload
            MultimodalContent(text = "{\"isAllowed\":true,\"reason\":\"\"}")
        }
        MapUploadGateStorage.fakeSaver = { _, _ -> true }
        MapUploadSafetyBilling.setInvokeOverrideForTest { method, _ ->
            when (method)
            {
                "server.extend.getAccountSettings" -> RpcMessage.Response(
                    id = "stub",
                    result = kotlinx.serialization.json.Json.parseToJsonElement(
                        org.ttt.autogenesis.network.RpcJson.encodeToString(
                            structs.account.AccountSettings.serializer(),
                            structs.account.AccountSettings(accelByteUserId = "hypothesis-player")
                        )
                    )
                )
                "server.extend.getUsageLedger" -> RpcMessage.Response(
                    id = "stub",
                    result = kotlinx.serialization.json.Json.parseToJsonElement(
                        org.ttt.autogenesis.network.RpcJson.encodeToString(
                            structs.account.UsageLedger.serializer(),
                            structs.account.UsageLedger(accelByteUserId = "hypothesis-player")
                        )
                    )
                )
                else -> RpcMessage.Response(id = "stub")
            }
        }

        val playerId = "hypothesis-player"
        val manager = RestPlayerConnectionManager()
        MapUploadSuccessHandlers.registerConnectionManager(manager)
        MapUploadErrorHandlers.registerConnectionManager(manager)
        manager.register(playerId)

        val request = MapUploadRequest(mapPackBytes = sanMartelloMapBytes, mapName = "San_Martello")
        val gateContext = RpcCallContext(
            connectionId = playerId,
            metadata = emptyMap(),
            sender = { _: RpcMessage -> }
        )
        val response = MapUploadGate.uploadMapGate(gateContext, request)

        val capturedPayload = capturedPayloadRef[0]
        assertNotNull(capturedPayload, "fakeSafetyRunner must have been invoked")
        val receivedImageSize = capturedPayload.imageBytes.size
        val originalSize = originalSanMartelloPngBytes

        // Compute what TPipe's binary token counter would say for three scenarios.
        val truncationSettings = TruncationSettings()
        val binaryTokensForDownsampled = Dictionary.countBinaryTokens(
            listOf(Dictionary.BinaryBytes(capturedPayload.imageBytes, "image/png")),
            truncationSettings
        )
        val binaryTokensForOriginal = Dictionary.countBinaryTokens(
            listOf(Dictionary.BinaryBytes(ByteArray(originalSize) { i -> (i and 0xFF).toByte() }, "image/png")),
            truncationSettings
        )
        val binaryTokensForBoth = Dictionary.countBinaryTokens(
            listOf(
                Dictionary.BinaryBytes(capturedPayload.imageBytes, "image/png"),
                Dictionary.BinaryBytes(capturedPayload.imageBytes, "image/png")
            ),
            truncationSettings
        )

        val contextWindow = 990_000
        val maxTokens = 8_000
        val systemPromptApproxTokens = 200
        val userPromptTokens = 5
        val availableForBinary = contextWindow - maxTokens - systemPromptApproxTokens - userPromptTokens

        val report = buildString {
            appendLine("=" .repeat(80))
            appendLine("SAN MARTELLO TOKEN-BUDGET HYPOTHESIS REPORT")
            appendLine("=" .repeat(80))
            appendLine("Original San Martello PNG bytes (on disk):  $originalSize")
            appendLine("Bytes handed to fakeSafetyRunner by the gate: $receivedImageSize")
            appendLine("Gate response: accepted=${response.accepted} reason='${response.reason ?: ""}'")
            appendLine()
            if (receivedImageSize == originalSize)
            {
                appendLine("*** LEVEL-1 SMOKING GUM: gate handed fakeSafetyRunner the ORIGINAL bytes. ***")
                appendLine("*** Downsample ran but its output was discarded somewhere. ***")
            }
            else
            {
                val shrinkRatio = receivedImageSize.toDouble() / originalSize.toDouble()
                appendLine("fakeSafetyRunner received downsampled bytes (shrink ratio = ${"%.4f".format(shrinkRatio)})")
            }
            appendLine()
            appendLine("Binary token cost (default TruncationSettings):")
            appendLine("  downsampled (${receivedImageSize} bytes): $binaryTokensForDownsampled tokens")
            appendLine("  original    (${originalSize} bytes):       $binaryTokensForOriginal tokens")
            appendLine("  both        (double-stamped):            $binaryTokensForBoth tokens")
            appendLine()
            appendLine("Available budget for binary after system+user+max tokens: $availableForBinary")
            appendLine()

            val downsampledOver = binaryTokensForDownsampled > contextWindow
            val originalOver = binaryTokensForOriginal > contextWindow
            val bothOver = binaryTokensForBoth > contextWindow

            if (downsampledOver)
            {
                appendLine("*** LEVEL-2 SMOKING GUM: downsampled bytes alone blow the budget ***")
                appendLine("*** ($binaryTokensForDownsampled tokens > $contextWindow window) ***")
            }
            else if (originalOver && !downsampledOver)
            {
                appendLine("Smoking gum (level-3): downsampled fits but original blows the budget.")
                appendLine("If TPipe sees the ORIGINAL bytes, it will throw 'Context window size: $contextWindow Binary size: ${binaryTokensForOriginal}'.")
                appendLine("The downsample log shows the right size flowing into the gate; the bug must be downstream.")
            }
            else if (bothOver)
            {
                appendLine("*** LEVEL-3 SMOKING GUM: double-stamped bytes blow the budget ***")
                appendLine("*** If the gate OR pre-init attaches the original alongside the downsampled,")
                appendLine("*** TPipe sees ${binaryTokensForBoth} tokens and throws. ***")
            }
            else
            {
                appendLine("No smoking gum at this token-counting layer.")
                appendLine("The token-budget hypothesis is REFUTED for the candidate byte sets tested.")
                appendLine("Real failure is downstream of the budget check.")
            }
        }
        println(report)

        // Write the report to the trace dir so a future session can re-read it.
        val reportDir = File(File(TPipeConfig.getTraceDir()), GATE_TRACE_SUBFOLDER)
        if (!reportDir.exists()) reportDir.mkdirs()
        File(reportDir, "hypothesis-report.txt").writeText(report)

        assertTrue(response.accepted, "the gate must accept when fakeSafetyRunner returns pass")
        assertEquals(receivedImageSize, capturedPayload.imageBytes.size,
            "captured image bytes size must match what the gate passed to fakeSafetyRunner")
    }

    private fun cleanupGateTraceDir()
    {
        val dir = File(File(TPipeConfig.getTraceDir()), GATE_TRACE_SUBFOLDER)
        if (dir.exists()) dir.deleteRecursively()
    }
}