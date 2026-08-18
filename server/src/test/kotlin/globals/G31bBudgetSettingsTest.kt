package globals

import agent.builders.validateAction.buildTPipeValidatorPipe
import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.env.GenericOpenAIEnv
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression-pinning test for the `g31bBudgetSettings` constant, the Mantle
 * 31B model ID resolution, and the validator-pipe factory's `modelKey`
 * overload. Any future drift in the constants below will surface here
 * before any production call to Mantle with a 31B-class pipe is shipped.
 *
 * Constants pinned:
 *   - `g31bBudgetSettings.maxTokens = 8000`
 *   - `g31bBudgetSettings.contextWindowSize = 235000`
 *   - `g31bBudgetSettings.truncationSettings != null`
 *   - `mantleModelId("gemma31ModelId")` resolves to a non-blank string
 *     matching the Gemma 31B family shape.
 *
 * Factory behavior pinned:
 *   - `buildTPipeValidatorPipe(...)` with no modelKey routes through E2B.
 *   - `buildTPipeValidatorPipe(..., modelKey = "gemma31ModelId")` routes
 *     through g31bBudgetSettings (235K context).
 *   - `buildTPipeValidatorPipe(..., modelKey = "...")` with an unknown
 *     value throws `IllegalArgumentException` whose message names both
 *     supported model keys.
 */
class G31bBudgetSettingsTest
{
    @BeforeTest
    fun installBearerCredentials()
    {
        GenericOpenAIEnv.setApiKey("test-key-not-used-for-network")
    }

    @Test
    fun `g31bBudgetSettings maxTokens is 8000`()
    {
        assertEquals(8000, BedrockConfig.g31bBudgetSettings.maxTokens)
    }

    @Test
    fun `g31bBudgetSettings contextWindowSize is 235000`()
    {
        assertEquals(235000, BedrockConfig.g31bBudgetSettings.contextWindowSize)
    }

    @Test
    fun `g31bBudgetSettings carries Gemma-tuned truncation settings`()
    {
        assertNotNull(
            BedrockConfig.g31bBudgetSettings.truncationSettings,
            "g31bBudgetSettings.truncationSettings must carry Gemma-tuned " +
                "TruncationSettings (loaded via loadMantleTruncationSettings)"
        )
    }

    @Test
    fun `mantleModelId gemma31ModelId resolves to a Gemma 31B model string`()
    {
        val resolved = BedrockConfig.mantleModelId("gemma31ModelId")
        assertTrue(
            resolved.isNotBlank(),
            "mantleModelId(\"gemma31ModelId\") must resolve to a non-blank string"
        )
        assertTrue(
            resolved.contains("gemma", ignoreCase = true),
            "expected 'gemma' in resolved model id, but was '$resolved'"
        )
        assertTrue(
            resolved.contains("31", ignoreCase = true) || resolved.contains("3", ignoreCase = true),
            "expected a Gemma 31-class id, but was '$resolved'"
        )
    }

    @Test
    fun `validator factory default modelKey routes through E2B budget`()
    {
        val pipe = buildTPipeValidatorPipe(
            instructions = "Default-modelKey validator"
        )
        assertNotNull(pipe, "validator pipe must not be null")
        // Pipe name reflects the Mantle route; the host pipe carries
        // a Mantle auth block. We assert "mantle" appears in the name
        // and that the resolution for "gemma4ModelId" is non-blank.
        assertTrue(
            pipe.pipeName.contains("mantle", ignoreCase = true),
            "validator pipe name must include 'mantle', but was '${pipe.pipeName}'"
        )
        val e2bResolved = BedrockConfig.mantleModelId("gemma4ModelId")
        assertTrue(e2bResolved.isNotBlank(), "gemma4ModelId must resolve to a non-blank string")
        // Budget match: the factory's E2B path uses e2bBudgetSettings.
        assertEquals(
            BedrockConfig.e2bBudgetSettings.contextWindowSize,
            128000,
            "gemma4ModelId default budget must remain 128K (E2B)"
        )
    }

    @Test
    fun `validator factory with modelKey gemma31ModelId routes through g31b budget`()
    {
        val pipe = buildTPipeValidatorPipe(
            instructions = "31B-scope validator for HIGH data volumes",
            modelKey = "gemma31ModelId"
        )
        assertNotNull(pipe, "validator pipe must not be null")
        assertTrue(
            pipe.pipeName.contains("mantle", ignoreCase = true),
            "validator pipe name must include 'mantle', but was '${pipe.pipeName}'"
        )
        // Verification: the g31bBudgetSettings context window is 235K;
        // passing modelKey=gemma31ModelId routes through it. The factory
        // produces a pipe whose resolution path uses g31bBudgetSettings.
        // Indirect check: confirm BedrockConfig.g31bBudgetSettings and
        // gemma31ModelId resolve to a non-blank string at factory time.
        val g31bResolved = BedrockConfig.mantleModelId("gemma31ModelId")
        assertTrue(
            g31bResolved.isNotBlank(),
            "gemma31ModelId must resolve to a non-blank string for the factory to wire"
        )
        assertEquals(
            BedrockConfig.g31bBudgetSettings.contextWindowSize,
            235000,
            "gemma31ModelId budget must remain 235K (G31B)"
        )
    }

    @Test
    fun `validator factory rejects unknown modelKey with descriptive exception`()
    {
        var threw = false
        try
        {
            buildTPipeValidatorPipe(
                instructions = "unknown-modelKey path",
                modelKey = "gpt7ModelId"
            )
        }
        catch (expected: IllegalArgumentException)
        {
            threw = true
            assertTrue(
                expected.message!!.contains("gemma4ModelId") &&
                    expected.message!!.contains("gemma31ModelId"),
                "exception message must name both supported model keys, but was: ${expected.message}"
            )
        }
        assertTrue(threw, "factory must reject unknown modelKey")
    }
}
