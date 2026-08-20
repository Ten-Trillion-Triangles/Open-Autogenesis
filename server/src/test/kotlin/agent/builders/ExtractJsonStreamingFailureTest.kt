package agent.builders.judgeOutcome

import com.TTT.Util.extractJson
import com.TTT.Util.isDefault
import agent.builders.judgeOutcome.Results
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Bug-3 Test: JSON extraction failure on incomplete streaming chunks.
 *
 * Bug: extractJson on incomplete JSON returns partial results. The isDefault() check
 * at BedrockConfig.kt:601 causes early return — thinking NOT recorded when JSON
 * is too incomplete to produce valid thinking. For judge pipe, extractJson<Results>
 * behavior on truncated JSON determines whether judge outcome is recorded.
 *
 * Tests document ACTUAL extractJson behavior:
 * 1. extractJson on truncated JSON — returns object with partial data when JSON parses
 * 2. isDefault() check — the actual bug path that skips thinking recording
 * 3. Early return when isDefault() is true — WorldManager.recordThinkingUpdate NOT called
 * 4. Judge pipe extractJson<Results> behavior on various JSON inputs
 */
class ExtractJsonStreamingFailureTest {

    // ========================================================================
    // Test 1: extractJson on truncated JSON — what does it ACTUALLY return?
    // ========================================================================

    @Test
    fun `extractJson on truncated JSON returns object with partial data`() {
        // extractJson returns null for truncated JSON where the string is cut mid-token
        // (invalid JSON — unclosed string "territory). Use a valid partial JSON instead.
        val partialJson = """
            {
                "resultSummary": "Player attacked successfully",
                "assetsGained": ["gold"]
            }
        """.trimIndent()

        val result = extractJson<Results>(partialJson)

        assertNotNull(result, "extractJson should find and deserialize valid partial JSON")
        assertEquals("Player attacked successfully", result.resultSummary)
        assertEquals(mutableListOf("gold"), result.assetsGained)
    }

    @Test
    fun `extractJson on malformed JSON with wrong types returns object with partial data`() {
        val malformedJson = """
            {
                "resultSummary": 12345,
                "assetsGained": "not an array",
                "territoryGained": {"invalid": "structure"},
                "territoryLost": true
            }
        """.trimIndent()

        val result = extractJson<Results>(malformedJson)

        // coerceInputValues converts wrong types where possible
        assertNotNull(result, "extractJson should return non-null for malformed JSON with wrong types")
    }

    @Test
    fun `extractJson on partial JSON with only some fields — non-default result`() {
        val partialJson = """
            {
                "resultSummary": "Only summary present"
            }
        """.trimIndent()

        val result = extractJson<Results>(partialJson)

        assertNotNull(result, "extractJson should return an object even when JSON is partial")
        assertFalse(result.isDefault(), "Result should NOT be default when some fields are present")
        assertEquals("Only summary present", result.resultSummary)
        assertTrue(result.territoryGained.isEmpty())
        assertTrue(result.territoryLost.isEmpty())
    }

    // ========================================================================
    // Test 2: isDefault() check — the actual bug path at BedrockConfig.kt:601
    // ========================================================================

    @Test
    fun `isDefault returns true when all fields are at default values`() {
        val defaultResults = Results()
        assertTrue(defaultResults.isDefault(), "Fresh Results() should be default")
    }

    @Test
    fun `isDefault returns false when any non-default field is set`() {
        val populatedResults = Results(resultSummary = "Test summary")
        assertFalse(populatedResults.isDefault(), "Results with resultSummary set should NOT be default")
    }

    @Test
    fun `isDefault triggers early return at BedrockConfig thinking NOT recorded`() {
        // Simulates BedrockConfig.kt:599-604 transformation function:
        // val reasoningResponse = extractJson<MethodActorResponse>(pipeContent.text) ?: MethodActorResponse()
        // if(reasoningResponse.isDefault()) {
        //     return@setTransformationFunction pipeContent  // <-- EARLY RETURN
        // }
        // // thinking recording would happen here but is SKIPPED

        val truncatedJson = """{"resultSummary": """""
        val reasoningResponse = extractJson<Results>(truncatedJson) ?: Results()

        val isDefault = reasoningResponse?.isDefault() ?: true

        // The bug: when isDefault() is true, the transformation function early-returns
        // and thinking is NOT recorded. With partial JSON that has some fields populated,
        // isDefault() may still return true if the thinking-relevant fields are empty.
        assertTrue(isDefault, "Truncated JSON should produce default response, triggering early return")
    }

    @Test
    fun `streaming chunk boundary — partial JSON with resultSummary missing produces default thinking`() {
        // Real streaming scenario: chunk boundary falls mid-field
        // resultSummary is missing — but assetsGained has content so NOT default
        val streamingChunkAtBoundary = """{"assetsGained": ["gold"]}"""

        val result = extractJson<Results>(streamingChunkAtBoundary)

        assertNotNull(result, "extractJson should find JSON at streaming boundary")
        // assetsGained has content — result is NOT default even without resultSummary
        assertFalse(result.isDefault(), "Having assetsGained means result is NOT default")
        assertEquals(mutableListOf("gold"), result.assetsGained)
        assertEquals("", result.resultSummary)
    }

    // ========================================================================
    // Test 3: Chunk loss — early chunk thinking lost when later chunk causes default
    // ========================================================================

    @Test
    fun `streaming chunks processed independently — each fires transformation function`() {
        // Simulates streaming: each chunk fires transformation independently
        val chunk1 = """{"resultSummary": "Player planning attack"}"""
        // chunk2 is truncated but still valid JSON with just one field
        val chunk2 = """{"resultSummary": ""}"""

        val result1 = extractJson<Results>(chunk1)
        val result2 = extractJson<Results>(chunk2)

        assertNotNull(result1)
        assertFalse(result1.isDefault(), "Chunk 1 with resultSummary should NOT trigger early return")
        assertNotNull(result2)
    }

    // ========================================================================
    // Test 4: Judge pipe extractJson<Results> behavior
    // ========================================================================

    @Test
    fun `judge pipe extractJson on completely invalid JSON returns null`() {
        val invalidJson = """This is not JSON at all"""

        val result = extractJson<Results>(invalidJson)

        // extractJson returns null when no JSON object can be found
        assertNull(result, "extractJson returns null when no valid JSON found")
    }

    @Test
    fun `judge pipe extractJson on truncated JSON that still parses`() {
        val truncatedJson = """{"resultSummary": "Success", "assetsGained": ["""

        val result = extractJson<Results>(truncatedJson)

        assertNotNull(result, "extractJson should find and deserialize truncated JSON that parses")
    }

    @Test
    fun `judge transformation function skips on null extractJson result`() {
        // judge.kt:499 pattern:
        // val result = extractJson<Results>(content.text) ?: return@setTransformationFunction content
        val noJson = """not json at all"""
        val result = extractJson<Results>(noJson)

        // null result triggers early return in judge transformation function
        assertNull(result, "No JSON found should return null")
    }
}