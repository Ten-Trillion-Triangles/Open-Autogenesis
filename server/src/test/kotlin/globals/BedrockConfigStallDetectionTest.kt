package globals

import com.TTT.Pipe.DummyPipe
import com.TTT.Pipe.Pipe
import com.TTT.Pipeline.Pipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BedrockConfigStallDetectionTest
{
    @Test
    fun `configures every reachable child pipe once and preserves runtime settings`()
    {
        val root = DummyPipe().apply {
            setPipeName("root")
            enablePipeTimeout(duration = 1234L, autoRetry = true, retryLimit = 7)
        }
        val validator = DummyPipe().apply { setPipeName("validator") }
        val transformation = DummyPipe().apply { setPipeName("transformation") }
        val branch = DummyPipe().apply { setPipeName("branch") }
        val reasoning = DummyPipe().apply { setPipeName("reasoning") }
        val nested = DummyPipe().apply { setPipeName("nested") }
        val shared = DummyPipe().apply { setPipeName("shared") }

        root.validatorPipe = validator
        root.transformationPipe = transformation
        root.branchPipe = branch
        root.reasoningPipe = reasoning
        validator.reasoningPipe = nested
        transformation.branchPipe = shared
        branch.validatorPipe = shared
        reasoning.transformationPipe = nested
        nested.branchPipe = root

        val pipeline = Pipeline().add(root)
        val callback: suspend (com.TTT.Pipe.StallEvent) -> Unit = { }

        assertSame(pipeline, BedrockConfig.configureGameplayStallDetection(pipeline, callback))

        val expected = BedrockConfig.gameplayStallDetectorConfig
        listOf(root, validator, transformation, branch, reasoning, nested, shared).forEach { pipe ->
            assertTrue(pipe.enableStallDetector, pipe.pipeName)
            assertEquals(expected, pipe.stallDetectorConfig, pipe.pipeName)
            assertNotNull(pipe.stallCallback, pipe.pipeName)
            assertFalse(pipe.streamingEnabled, pipe.pipeName)
        }
        assertEquals(1234L, root.pipeTimeout)
        assertEquals(7, root.maxRetryAttempts)
    }
}
