package network

import com.TTT.Config.TPipeConfig
import com.TTT.Pipeline.Pipeline
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [MapUploadGate.captureAndSaveTrace].
 *
 * The trace path is wired into the production branch of `MapUploadGate.uploadMapGate`,
 * but the existing `MapUploadGateTest` uses a fake safety runner that bypasses
 * the real pipeline entirely — so the trace path is never exercised there. This
 * test class closes the gap by building a real, empty `Pipeline`, calling
 * `enableTracing` on it, and invoking `captureAndSaveTrace` directly to confirm
 * the trace files land on disk.
 *
 * The trace content itself is empty (no pipes were executed), but the
 * file-existence + writable-path contract is what matters: that `getTraceDir()`
 * is consulted, the subfolder is created if missing, and both `trace.json` and
 * `trace.html` are written. The grep-style receipt in the harness verifies
 * the same wire markers independently.
 */
class MapUploadGateTraceTest
{
    @Before
    fun resetBefore()
    {
        MapUploadGate.resetForTest()
    }

    @After
    fun resetAfter()
    {
        MapUploadGate.resetForTest()
        // Clean up the trace dir we created during the test so the test
        // is hermetic. The `MapUploadGate/` subfolder is reset across
        // cases by deleting the directory after the assertions.
        val dir = File(File(TPipeConfig.getTraceDir()), "MapUploadGate")
        if (dir.exists()) dir.deleteRecursively()
    }

    @Test
    fun `captureAndSaveTrace writes trace json and trace html under TPipeConfig getTraceDir MapUploadGate`() = runBlocking {
        // Build a real Pipeline. We don't execute it — the trace content
        // is empty for an empty pipeline, which is fine for verifying the
        // file-write contract. The wire shape (which subfolder, which
        // file names) is what the test pins.
        val pipeline = Pipeline()
        pipeline.enableTracing()

        // Sanity: confirm the file does not exist before the call.
        val traceDir = File(File(TPipeConfig.getTraceDir()), "MapUploadGate")
        val jsonFile = File(traceDir.absolutePath, "trace.json")
        val htmlFile = File(traceDir.absolutePath, "trace.html")
        if (jsonFile.exists()) jsonFile.delete()
        if (htmlFile.exists()) htmlFile.delete()

        MapUploadGate.captureAndSaveTrace(pipeline, playerId = "player-trace-test")

        // Pin the directory was created.
        assertTrue(traceDir.exists(), "MapUploadGate trace dir must exist after capture: ${traceDir.absolutePath}")
        assertTrue(traceDir.isDirectory, "MapUploadGate trace path must be a directory: ${traceDir.absolutePath}")

        // Pin both files exist (file content may be empty for an empty pipeline,
        // but the file must be written — that is the contract).
        assertTrue(jsonFile.exists(), "trace.json must exist at ${jsonFile.absolutePath}")
        assertTrue(htmlFile.exists(), "trace.html must exist at ${htmlFile.absolutePath}")

        // Both files must be the trace format the JSON and HTML TraceFormat
        // variants emit. Empty pipeline produces "''" for HTML (HTML root
        // document) and "[]" for JSON (empty trace event array). We assert
        // the file is non-empty rather than pinning exact content because
        // the trace emission may vary across TPipe versions.
        val jsonContent = jsonFile.readText()
        val htmlContent = htmlFile.readText()
        assertTrue(jsonContent.isNotEmpty(), "trace.json must be non-empty")
        assertTrue(htmlContent.isNotEmpty(), "trace.html must be non-empty")
    }

    @Test
    fun `captureAndSaveTrace swallows IO failures and does not propagate`() = runBlocking {
        // Build a pipeline whose trace dir we will force to fail by setting
        // TPipeConfig.configDir to a path that cannot be created. The test
        // asserts that the helper logs the error and returns gracefully —
        // it MUST NOT throw.
        val pipeline = Pipeline()
        pipeline.enableTracing()

        // Reset TPipeConfig so we know the starting state.
        val originalConfigDir = TPipeConfig.configDir
        try
        {
            // Point the config dir at a path that contains a non-directory
            // component so the trace subfolder cannot be created. The
            // helper's `dir.mkdirs()` will fail and the catch block will
            // log the error. We assert the call returns cleanly.
            val blocker = File("/tmp/hermes-trace-blocker-${System.nanoTime()}")
            blocker.createNewFile()
            TPipeConfig.configDir = blocker.absolutePath
            try
            {
                // The helper will hit either a creation failure or a
                // write failure inside the try block. Either way, the
                // call must return cleanly without throwing.
                MapUploadGate.captureAndSaveTrace(pipeline, playerId = "player-fail-test")
                // Reaching this line is the assertion: no exception
                // propagated to the call site.
            }
            finally
            {
                TPipeConfig.configDir = originalConfigDir
                blocker.delete()
            }
        }
        catch (e: Exception)
        {
            TPipeConfig.configDir = originalConfigDir
            throw AssertionError("captureAndSaveTrace must not propagate IO failures; got: ${e.message}", e)
        }
    }
}
