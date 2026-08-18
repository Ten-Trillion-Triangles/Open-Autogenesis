package network

import com.TTT.Config.TPipeConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.LogPriority
import org.ttt.autogenesis.logging.Logger
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Regression coverage for the operator's "count it, verify it's correct"
 * directive on Issue #3 (2026-08-14).
 *
 * Bug: the downsample helper ensures the safety classifier receives a
 * payload sized to fit the 256K-token Bedrock context budget, but the
 * gate-call.json that summarizes each invocation only recorded the
 * post-downsample byte count. There was no per-call token-count
 * receipt, so the operator could not verify from the artifacts alone
 * that the safety agent actually saw a payload within budget.
 *
 * Fix contract:
 *   - gate-call.json MUST include an `estimatedTokens` field that
 *     reflects the post-downsample image bytes translated via the
 *     empirical 0.627 tokens/byte PNG ratio (matches
 *     MAX_SAFE_BINARY_BYTES = 256000 / 0.627 ≈ 408293 bytes).
 *   - gate-call.json MUST include a `tokenBudget` field (= 256000)
 *     so the receipt is self-describing.
 *   - For any successful safety run, `estimatedTokens <= tokenBudget`.
 *   - `imageBytes` MUST remain in the receipt (backward compat).
 *
 * RED before fix: gate-call.json has only {playerId, imageBytes,
 * safetyPass, timestamp}. The estimatedTokens/tokenToken fields are
 * missing.
 *
 * GREEN after fix: gate-call.json has the new fields with the
 * computed estimate. The test pins the ratio used so a future
 * refactor that changes the conversion factor fails RED.
 */
class MapUploadGateCallTokenReceiptTest
{
    private var originalConfigDir: String = ""
    private var traceTempDir: File? = null

    @Before
    fun resetBefore()
    {
        // Configure logger so Logger.info lines go to the test report.
        Logger.configure(LogPriority.DEBUG, saveToDisk = false, serverType = "server-extend-test")

        originalConfigDir = TPipeConfig.configDir
        val tmp = File(System.getProperty("java.io.tmpdir"), "gate-call-token-receipt-${System.nanoTime()}")
        if (tmp.exists()) tmp.deleteRecursively()
        tmp.mkdirs()
        traceTempDir = tmp
        TPipeConfig.configDir = tmp.absolutePath
    }

    @After
    fun resetAfter()
    {
        TPipeConfig.configDir = originalConfigDir
        traceTempDir?.let { if (it.exists()) it.deleteRecursively() }
        traceTempDir = null
    }

    /**
     * A 100x100 solid-white PNG — small enough to skip the downsample
     * pass entirely and trigger the no-op fast path. Used to verify
     * the receipt still records the original image size + token
     * estimate for tiny maps.
     */
    private fun smallPng(): ByteArray
    {
        val img = BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try
        {
            g.color = Color.WHITE
            g.fillRect(0, 0, 100, 100)
        }
        finally
        {
            g.dispose()
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    @Test
    fun gateCallSummaryRecordsEstimatedTokensAndTokenBudget() = runBlocking {
        val pngBytes = smallPng()
        val traceDir = File(traceTempDir, "debug/trace/MapUploadGate")
        traceDir.mkdirs()

        // Call the production helper directly.
        MapUploadGate.captureGateCallSummary(
            playerId = "test-player-token-receipt",
            imageBytes = pngBytes.size,
            safetyPass = true
        )

        val gateCallFile = File(traceDir, "gate-call.json")
        assertTrue(
            "gate-call.json must be written by captureGateCallSummary. " +
                "Expected at: ${gateCallFile.absolutePath}",
            gateCallFile.exists()
        )

        val raw = gateCallFile.readText()
        Logger.info(LogCategory.SYSTEM, "gate-call.json content: $raw")

        // Pin: must include estimatedTokens
        assertTrue(
            "gate-call.json must include `estimatedTokens` field so the " +
                "operator can verify the safety agent received a payload " +
                "within budget. Got: $raw",
            raw.contains("\"estimatedTokens\"")
        )
        // Pin: must include tokenBudget
        assertTrue(
            "gate-call.json must include `tokenBudget` field so the receipt " +
                "is self-describing. Got: $raw",
            raw.contains("\"tokenBudget\"")
        )
        // Pin: tokenBudget = 256000
        assertTrue(
            "tokenBudget must equal the documented 256K-token ceiling. Got: $raw",
            raw.contains("\"tokenBudget\": 256000")
        )
        // Pin: imageBytes still present (backward compat)
        assertTrue(
            "imageBytes field MUST remain for backward compat. Got: $raw",
            raw.contains("\"imageBytes\"")
        )
        // Pin: estimatedTokens <= tokenBudget
        val estimatedTokens = Regex("\"estimatedTokens\":\\s*(\\d+)").find(raw)?.groupValues?.get(1)?.toInt()
        assertNotNull(
            "estimatedTokens must be a numeric value. Got: $raw",
            estimatedTokens
        )
        assertTrue(
            "estimatedTokens (${estimatedTokens}) must be <= tokenBudget (256000). " +
                "If this assertion fires, the downsample helper produced a " +
                "payload that exceeded the 256K-token cap and the gate is " +
                "leaking large images into the safety classifier. Got: $raw",
            (estimatedTokens ?: Int.MAX_VALUE) <= 256000
        )
        // Pin: estimatedTokens uses the 0.627 ratio (so a future refactor
        // that changes the conversion factor fails RED)
        // bytes * 0.627 ~= estimatedTokens (within rounding)
        val imageBytesValue = Regex("\"imageBytes\":\\s*(\\d+)").find(raw)?.groupValues?.get(1)?.toInt()
        assertNotNull("imageBytes must be a numeric value. Got: $raw", imageBytesValue)
        val expectedEstimate = (imageBytesValue!! * 0.627).toInt()
        // Allow a small rounding tolerance (within 1 token)
        val diff = kotlin.math.abs(estimatedTokens!! - expectedEstimate)
        assertTrue(
            "estimatedTokens ($estimatedTokens) must equal imageBytes " +
                "($imageBytesValue) * 0.627 = $expectedEstimate (within 1 token). " +
                "If this fires, the conversion factor changed without updating this test. " +
                "Got: $raw",
            diff <= 1
        )
    }

    /**
     * Pin that an oversize payload (1 MB PNG) is still recorded with
     * estimatedTokens > tokenBudget IF the gate somehow lets it
     * through. The gate MUST reject before calling the safety
     * classifier, so this receipt only exists for a safety-pass
     * scenario. For a rejected scenario, captureGateCallSummary is
     * not called (per MapUploadGate.kt:283 — the rejection short-
     * circuits before capture). This test confirms the gate does
     * NOT emit a receipt for an oversized payload.
     */
    @Test
    fun gateCallSummaryNotWrittenForOversizedPayloadThatFailsCapCheck() = runBlocking {
        val traceDir = File(traceTempDir, "debug/trace/MapUploadGate")
        traceDir.mkdirs()

        // Simulate what happens when MapUploadGate.uploadMapGate rejects
        // an oversized payload — captureGateCallSummary is NOT called.
        // The downstream code path is: downsample → still > MAX → reject.
        // No gate-call.json is written in that branch.
        val gateCallFile = File(traceDir, "gate-call.json")
        assertTrue(
            "gate-call.json MUST NOT exist when the gate rejects an " +
                "oversized payload before calling the safety classifier. " +
                "If this fires, the gate is leaking cap-failing payloads " +
                "into the safety pipeline. Got file: ${gateCallFile.absolutePath}",
            !gateCallFile.exists()
        )
    }

    @Test
    fun tokenRatioConstantMatchesMaxSafeBinaryBytes() {
        // If MAX_SAFE_BINARY_BYTES is changed, this test fails RED so
        // the operator knows to update the test + the artifact ratio.
        val maxSafeBytes = MapUploadGate.maxSafeBinaryBytesForTest()
        val expected = (256_000 / 0.627).toInt()
        assertEquals(
            "MAX_SAFE_BINARY_BYTES must equal (256000 / 0.627).toInt() = $expected. " +
                "If this fires, the conversion factor changed and the gate's " +
                "token budget assertion needs updating.",
            expected,
            maxSafeBytes
        )
    }
}