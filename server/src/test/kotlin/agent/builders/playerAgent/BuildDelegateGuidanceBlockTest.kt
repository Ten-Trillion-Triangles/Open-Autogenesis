package agent.builders.playerAgent

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [buildDelegateGuidanceBlock], the pure function that renders
 * `Player.delegateInstructions` into the `##PLAYER DELEGATE GUIDANCE##` context
 * slot consumed by the player agent's planning stage.
 *
 * The slot shape is what the planning pipe's `preValidationMiniBankFunction` writes
 * into `context.contextMap["delegate_guidance"]`, and the LLM is told to read it
 * alongside `player_stats`. These tests pin:
 *  - the marker header
 *  - the "(none provided)" fallback so the LLM is never silently steered
 *  - the verbatim inclusion of the player's text when present
 *  - the trim semantics (whitespace-only input counts as "no guidance")
 */
class BuildDelegateGuidanceBlockTest
{
    @Test
    fun nullRendersExplicitNoneProvidedMarker()
    {
        val out = buildDelegateGuidanceBlock(null)
        assertTrue(out.contains("##PLAYER DELEGATE GUIDANCE##"))
        assertTrue(out.contains("(none provided"))
    }

    @Test
    fun blankRendersNoneProvidedMarker()
    {
        val out = buildDelegateGuidanceBlock("")
        assertTrue(out.contains("(none provided"))
    }

    @Test
    fun whitespaceOnlyRendersNoneProvidedMarker()
    {
        val out = buildDelegateGuidanceBlock("   \n\t  ")
        assertTrue(out.contains("(none provided"))
    }

    @Test
    fun textIncludesPlayerInstructionsVerbatim()
    {
        val guidance = "Prioritize the north front. Avoid attacking Qos. Trade with Kara for resources."
        val out = buildDelegateGuidanceBlock(guidance)
        assertTrue(out.contains("##PLAYER DELEGATE GUIDANCE##"))
        assertTrue(out.contains(guidance))
        assertFalse(out.contains("(none provided"))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed()
    {
        val out = buildDelegateGuidanceBlock("  Hold the line.  \n")
        assertTrue(out.contains("Hold the line."))
        assertFalse(out.contains("  Hold the line."))
    }

    @Test
    fun textAlwaysStartsWithTheMarkerHeader()
    {
        // The planning pipe looks for the marker first; ensure the marker is at the
        // beginning of the block (not buried after a stray newline).
        assertTrue(buildDelegateGuidanceBlock(null).startsWith("##PLAYER DELEGATE GUIDANCE##"))
        assertTrue(buildDelegateGuidanceBlock("anything").startsWith("##PLAYER DELEGATE GUIDANCE##"))
    }

    @Test
    fun nonEmptyAndEmptyOutputAreDistinct()
    {
        // Guard: if the empty fallback and the populated block ever start to overlap,
        // the agent's "no override" reasoning would be wrong.
        val empty = buildDelegateGuidanceBlock(null)
        val populated = buildDelegateGuidanceBlock("Hold.")
        assertFalse(empty == populated)
    }
}