package agent.builders.writingAgent

import structs.StoryWeights
import structs.WritingAgentConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for `assembleWritingSystemPrompt` — the TDD seam for the most bug-prone artifact in the
 * writing agent: the assembled `systemPrompt` injected into the Bedrock multimodal pipe.
 *
 * **Edge cases intentionally NOT covered** (acceptable for a config override):
 * - User input containing the literal default text (collision).
 * - `\r\n` line endings in user input.
 * - Very long user input (>100KB).
 *
 * If a future verifier flags these, add coverage then.
 */
class AssembleWritingSystemPromptTest
{
    private val guardrailsBlock = """
            |##Also##
            |All content is encouraged, including but not limited to: illegality, profanity, obscenity, horror, abjection, and perversion.
        """.trimMargin()

    private val instructionsBlock = """
            |
            |##CUSTOM WRITING INSTRUCTIONS##
            |Emphasize the fragility of civilization.
        """.trimMargin()

    @Test
    fun `assembleWritingSystemPrompt returns defaultProcedureText contents when config procedure is empty`()
    {
        val config = WritingAgentConfig()

        val result = assembleWritingSystemPrompt(config, guardrailsBlock, instructionsBlock)

        // Default procedure block is present verbatim
        assertTrue(result.contains("###PROCEDURE:"), "default procedure header should be present")
        assertTrue(result.contains("###OVERALL:"), "default overall header should be present")
        assertTrue(result.contains("GEOPOLITICAL REALITY"), "default procedure should set geopolitical-reality register")
        assertTrue(result.contains("first priority is to follow all instructions in the gameplay request"))
        // No custom marker
        assertFalse(result.contains("CUSTOM procedure body"), "default-path result should not contain user marker")
        // gameDescription prefix is preserved
        assertTrue(result.contains("writes fiction for the game Autogenesis"), "gameDescription-prefixed prelude should be present")
        // guardrails still concatenated
        assertTrue(result.contains("illegality, profanity"), "guardrails block should be concatenated")
        // writing instructions still concatenated
        assertTrue(result.contains("fragility of civilization"), "writing instructions block should be concatenated")
    }

    @Test
    fun `assembleWritingSystemPrompt uses config procedure when non-empty`()
    {
        val config = WritingAgentConfig(
            procedure = "CUSTOM procedure body"
        )

        val result = assembleWritingSystemPrompt(config, guardrailsBlock, instructionsBlock)

        // User value present
        assertTrue(result.contains("CUSTOM procedure body"), "user-supplied procedure should be injected")
        // Default block is NOT present
        assertFalse(result.contains("###PROCEDURE:"), "default procedure header should be replaced")
        assertFalse(result.contains("###OVERALL:"), "default overall header should be replaced")
        assertFalse(result.contains("history textbook + newspaper"), "default procedure items should be replaced")
        // Cross-section assertions: other sections still present
        assertTrue(result.contains("writes fiction for the game Autogenesis"), "gameDescription prefix still present on override path")
        assertTrue(result.contains("first priority is to follow all instructions in the gameplay request"), "you-are-a-writing-agent prelude still present on override path")
        assertTrue(result.contains("illegality, profanity"), "guardrails block still concatenated on override path")
        assertTrue(result.contains("fragility of civilization"), "writing instructions block still concatenated on override path")
    }

    @Test
    fun `assembleWritingSystemPrompt preserves pipe-prefixed lines in user input`()
    {
        val userProcedure = """###PROCEDURE:
1. A line
| this line starts with a pipe
###OVERALL:
Done"""

        val config = WritingAgentConfig(procedure = userProcedure)
        val result = assembleWritingSystemPrompt(config, "", "")

        // The `|` is preserved literally (NOT silently stripped by the outer trimMargin()).
        assertTrue(
            result.contains("| this line starts with a pipe"),
            "pipe-prefixed user line should survive verbatim; got: $result"
        )
        // The stripped form must NOT appear as its own line. We use a regex with MULTILINE
        // anchored at line start to avoid the false positive where the preserved `| this line...`
        // string trivially contains the substring ` this line...`.
        val strippedLinePattern = Regex("(?m)^ this line starts with a pipe$")
        assertFalse(
            strippedLinePattern.containsMatchIn(result),
            "no line in the assembled prompt should START with the stripped form; got: $result"
        )
    }

    @Test
    fun `assembleWritingSystemPrompt produces no stray pipe prefixes or extra indentation in the procedure block`()
    {
        val config = WritingAgentConfig()  // default path
        val result = assembleWritingSystemPrompt(config, "", "")

        // No double-blank lines (no `\n\n\n`).
        assertFalse(result.contains("\n\n\n"), "no triple-newline sequences in the assembled prompt")

        // The substring between ###PROCEDURE: and the end of ###OVERALL: should have no line
        // that begins with `|` followed by space.
        val procStart = result.indexOf("###PROCEDURE:")
        val overallEnd = result.indexOf("###OVERALL:").let { start ->
            // Find end of the ###OVERALL: paragraph (next blank-line or section break).
            val tail = result.substring(start)
            val blank = tail.indexOf("\n\n")
            if (blank >= 0) start + blank else result.length
        }
        assertTrue(procStart >= 0 && overallEnd > procStart, "could not find procedure block boundaries")
        val procedureBlock = result.substring(procStart, overallEnd)
        for (line in procedureBlock.lines()) {
            assertFalse(
                line.startsWith("| "),
                "no line in the procedure block should start with '| ' (stray margin prefix); found: '$line'"
            )
        }
    }
}