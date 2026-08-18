package network

import agent.builders.MapSafetyPayload
import agent.builders.buildMapSafetyAgent
import com.TTT.Config.TPipeConfig
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Pipe.MultimodalContent
import com.TTT.Util.extractJson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.LogPriority
import org.ttt.autogenesis.logging.Logger
import structs.MapPackManager
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live AGS + AWS Bedrock test for the map-upload safety agent.
 *
 * Exercises the safety agent's core steps end-to-end against the real
 * infrastructure:
 *   1. Load an existing map pack from the game server's resource folder
 *      (server/src/main/resources/maps/Laurasiagondwana.map).
 *   2. Unpack it via MapPackManager.unpack to get the image bytes + MapData.
 *   3. Build the safety pipeline via buildMapSafetyAgent(...).
 *   4. Enable DEBUG tracing on the pipeline.
 *   5. Execute the pipeline with the image bytes attached.
 *   6. Capture the trace via MapUploadGate.captureAndSaveTrace (writes
 *      JSON + HTML to TPipeConfig.getTraceDir()/MapUploadGate/).
 *   7. Read both files back from disk and verify:
 *        - Both files exist and are non-blank.
 *        - The JSON trace contains at least one pipe name.
 *   8. Print a human-readable report to stdout: paths, sizes, the safety
 *      verdict (approved/rejected), the pipe names found in the trace.
 *
 * AWS credentials come from the default provider chain (~/.aws/credentials).
 * The BedrockKey profile is the live-test profile; the test does NOT
 * explicitly set AWS_PROFILE — the AWS SDK reads the default profile
 * automatically. If the host has the default profile misconfigured the
 * test will fail with ProfileNotFound. Documented in the runbook.
 *
 * Opt-in via env var:
 *   BEDROCK_MANTLE_LIVE_TEST=true \
 *   ./gradlew :server-extend:test --tests 'network.MapUploadSafetyAgentLiveTest'
 *
 * Live tests cost Bedrock $$$ per run (two BedrockMultimodalPipe calls).
 * The test gates on env var BEDROCK_MANTLE_LIVE_TEST so CI doesn't accidentally
 * trigger it.
 */
class MapUploadSafetyAgentLiveTest
{
    private val playerId = "live-test-safety-agent"
    private val mapResourcePath = "maps/Laurasiagondwana.map"
    private val traceSubFolder = "MapUploadGate"

    @Before
    fun setUp()
    {
        Logger.configure(LogPriority.DEBUG, true, serverType = "safety-agent-live-test")
        MapUploadGate.resetForTest()
    }

    @After
    fun tearDown()
    {
        MapUploadGate.resetForTest()
        // Trace cleanup is intentionally disabled — the trace files at
        // ${'$'}{TPipeConfig.getTraceDir()}/MapUploadGate/ persist across
        // test runs so the operator can inspect them after the fact. The
        // next live test run will overwrite the trace files in place via
        // captureAndSaveTrace -> writeStringToFile, so the directory
        // contents always reflect the most recent run.
    }

    private fun liveTestEnabled(): Boolean =
        System.getenv("BEDROCK_MANTLE_LIVE_TEST") == "true"

    @Test
    fun runSafetyAgentEndToEndAgainstLiveAws(): Unit = runBlocking {
        assumeTrue(
            "set BEDROCK_MANTLE_LIVE_TEST=true to enable the live test",
            liveTestEnabled()
        )

        println("=== MapUpload Safety Agent Live Test ===")
        println("AWS credentials:        default provider chain (~/.aws/credentials)")
        println("Map resource:            " + mapResourcePath + " (classpath)")
        println("Trace output directory:  " + TPipeConfig.getTraceDir() + "/" + traceSubFolder + "/")

        // 1. Load the map pack from the classpath resource folder.
        val resourceStream = javaClass.classLoader.getResourceAsStream(mapResourcePath)
        assertNotNull(resourceStream, "map resource must exist on the classpath: " + mapResourcePath)
        val mapBytes = resourceStream.use { it.readBytes() }
        val mapBytesSize = mapBytes.size
        println("Map pack bytes:          " + mapBytesSize)
        assertTrue(mapBytesSize > 0, "map pack must be non-empty")

        // 2. Unpack the zip.
        val unpacked = MapPackManager.unpack(mapBytes)
        println("Unpacked image name:     " + unpacked.imageName)
        println("Unpacked image bytes:    " + unpacked.imageBytes.size)
        println("Unpacked mapData pins:   " + unpacked.mapData.pins.size)

        // 3. Pre-flight: if the image is too big for the safety classifier's
        //    context window, downsample to 1024x1024 once. This mirrors the
        //    gate's pre-flight so the live test exercises the same shape as
        //    the production gate.
        val maxSafeBytes = 3 * 1024 * 1024 // 3 MB
        val downsampledImageBytes = downsampleForLiveTest(unpacked.imageBytes, maxSafeBytes)
        if (downsampledImageBytes != null)
        {
            println("Pre-flight downsampled: " + unpacked.imageBytes.size + " bytes -> " + downsampledImageBytes.size + " bytes")
        }
        else
        {
            println("Pre-flight passed: image is under the cap (no downsample)")
        }
        val imageBytesForPipeline = downsampledImageBytes ?: unpacked.imageBytes

        // 4. Build the safety pipeline.
        println("Building safety pipeline...")
        val payload = MapSafetyPayload(imageBytes = imageBytesForPipeline, mapData = unpacked.mapData)
        val pipeline = buildMapSafetyAgent(playerId, payload)

        // 5. Enable DEBUG tracing.
        println("Enabling DEBUG tracing...")
        pipeline.enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))

        // 6. Execute the pipeline.
        println("Executing safety pipeline...")
        val multimodal = MultimodalContent(text = "Map upload safety check")
        multimodal.addBinary(imageBytesForPipeline, mimeType = "image/png", filename = unpacked.imageName)
        val result = pipeline.execute(multimodal)
        println("Execution complete.")
        val verdict = if (result.shouldTerminate()) "REJECTED" else "APPROVED"
        println("Safety verdict:          " + verdict)
        println("Result text length:      " + result.text.length)

        // 6. Capture the trace.
        println("Capturing trace to disk...")
        MapUploadGate.captureAndSaveTrace(pipeline, playerId)

        // 7. Read the trace files back from disk.
        val traceDir = File(File(TPipeConfig.getTraceDir()), traceSubFolder)
        val jsonFile = File(traceDir.absolutePath, "trace.json")
        val htmlFile = File(traceDir.absolutePath, "trace.html")

        assertTrue(jsonFile.exists(), "trace.json must exist at " + jsonFile.absolutePath)
        assertTrue(htmlFile.exists(), "trace.html must exist at " + htmlFile.absolutePath)

        val traceJson = jsonFile.readText()
        val traceHtml = htmlFile.readText()

        println("Trace JSON path:         " + jsonFile.absolutePath + " (" + traceJson.length + " bytes)")
        println("Trace HTML path:         " + htmlFile.absolutePath + " (" + traceHtml.length + " bytes)")

        assertTrue(traceJson.isNotBlank(), "trace.json must be non-blank")
        assertTrue(traceHtml.isNotBlank(), "trace.html must be non-blank")

        // 8. Verify the trace contains references to the two safety pipes.
        // The pipeline is composed of two pipes from buildMapSafetyAgent — the
        // image checker and the content checker. The TPipe trace events JSON
        // emits a "name" field per pipe (the pipe's display name). We extract
        // those names and assert at least one is present.
        val pipeNames = extractPipeNamesFromTraceJson(traceJson)
        println("Pipe names found:        " + pipeNames)

        assertTrue(pipeNames.isNotEmpty(), "trace JSON must contain at least one pipe name")

        // 9. Try to extract the safety result from the result text. The
        // pipeline emits a JSON object with isAllowed and reason fields.
        // Log the parsed result for the report (do not gate the test on
        // the verdict — the test pins the trace shape, not the verdict).
        val safetyResult = extractJson<MapSafetyResult>(result.text)
        if (safetyResult != null)
        {
            println("Safety isAllowed:        " + safetyResult.isAllowed)
            println("Safety reason:           " + safetyResult.reason)
        }
        else
        {
            println("Safety result not parseable as MapSafetyResult JSON (may be a streaming chunk)")
        }

        println("=== VERDICT: PASS — trace is working as expected ===")
    }

    /**
     * Mirrors the gate's pre-flight downsample. If the image bytes are over
     * the cap, downsamples to 1024x1024 once via JDK ImageIO + BufferedImage.
     * Returns null if the image is already under the cap (no downsample
     * needed). Returns null if the image is small enough that the downsample
     * path is bypassed entirely.
     *
     * Used by the live test to mirror the gate's pre-flight behavior so the
     * safety classifier sees a downsampled image when the source is too big.
     */
    private fun downsampleForLiveTest(bytes: ByteArray, maxBytes: Int): ByteArray?
    {
        if (bytes.size <= maxBytes) return null
        try
        {
            val src = ImageIO.read(ByteArrayInputStream(bytes))
                ?: return null
            val srcW = src.width
            val srcH = src.height
            val maxDim = 1024
            val longestEdge = maxOf(srcW, srcH)
            if (longestEdge <= maxDim) return null
            val scale = maxDim.toDouble() / longestEdge
            val dstW = (srcW * scale).toInt().coerceAtLeast(1)
            val dstH = (srcH * scale).toInt().coerceAtLeast(1)
            val dst = BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_ARGB)
            val g = dst.createGraphics()
            try
            {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.drawImage(src, 0, 0, dstW, dstH, null)
            }
            finally
            {
                g.dispose()
            }
            val out = ByteArrayOutputStream()
            ImageIO.write(dst, "png", out)
            return out.toByteArray()
        }
        catch (e: Exception)
        {
            Logger.warn(LogCategory.SYSTEM, "Live test downsample failed: " + e.message)
            return null
        }
    }

    /**
     * Best-effort extraction of pipe names from the trace JSON. The trace
     * format is a JSON array of events; each event has a "name" field. We
     * scan for distinct names that look like pipe names.
     */
    private fun extractPipeNamesFromTraceJson(json: String): List<String>
    {
        val candidates = mutableSetOf<String>()
        val nameRegex = Regex("\"pipeName\"\\s*:\\s*\"([^\"]+)\"")
        for (match in nameRegex.findAll(json))
        {
            val name = match.groupValues[1]
            if (name.contains("Checker", ignoreCase = true) ||
                name.contains("Multimodal", ignoreCase = true) ||
                name.contains("Bedrock", ignoreCase = true) ||
                name.contains("Pipe", ignoreCase = true))
            {
                candidates.add(name)
            }
        }
        return candidates.toList()
    }

    /**
     * Pair of {isAllowed, reason} used to deserialize the safety classifier's
     * JSON output. Mirrors the MapSafetyCheck data class in
     * agent.builders.mapSafetyBuilder but lives in the test so the test
     * doesn't pull a private type from the production code.
     */
    @kotlinx.serialization.Serializable
    private data class MapSafetyResult(
        val isAllowed: Boolean = false,
        val reason: String = ""
    )
}
