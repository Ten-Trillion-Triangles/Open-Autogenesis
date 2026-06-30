package gameInit

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression coverage for the RIG env-var / `--rig=<substrings>` matching pipeline.
 *
 * Bug: `RIG=bob,dave,bigwang` only ever rigged "bigwang" into the map because
 * [AiPromptProvider.descriptorsMatching] checked each substring against
 * [AiDescriptor.name] only, and the parsed display name was the first word of
 * the prompt ("You are Shitty Bob..." -> "Shitty"). Multi-word names like
 * "Officer Dave" never matched the user-supplied "dave" substring, and the
 * first-word version of "bob" ("Shitty") didn't contain "bob" either.
 *
 * These tests pin the fix in two places:
 *  1. [parseName] now captures the multi-word display name.
 *  2. [descriptorsMatching] also accepts a substring that exactly matches a
 *     descriptor key (e.g., `bob` -> `bob` key) in addition to the
 *     case-insensitive name contains check.
 */
class AiPromptProviderTest
{
    // ---- descriptorsMatching ----------------------------------------------------

    @Test
    fun `rig with bob dave bigwang returns all three in input order -- the reported bug regression`() {
        val matched = AiPromptProvider.descriptorsMatching(listOf("bob", "dave", "bigwang"))
        assertEquals(3, matched.size, "Expected 3 descriptors (one per RIG entry), got ${matched.size}: ${matched.map { it.key }}")
        assertEquals(listOf("bob", "dave", "bmd"), matched.map { it.key }, "RIG order should be preserved in descriptor order")
    }

    @Test
    fun `rig with case-insensitive substrings matches the same descriptors`() {
        val matched = AiPromptProvider.descriptorsMatching(listOf("Bob", "DAVE", "BiGwAnG"))
        assertEquals(3, matched.size)
        assertEquals(listOf("bob", "dave", "bmd"), matched.map { it.key })
    }

    @Test
    fun `rig with descriptor key exactly matches even when the parsed name does not contain the substring`() {
        val matched = AiPromptProvider.descriptorsMatching(listOf("bmd"))
        assertEquals(1, matched.size)
        assertEquals("bmd", matched.first().key)
        // The display name is the full multi-word form captured from the prompt.
        assertEquals("Bigwang McDouchebag", matched.first().name)
    }

    @Test
    fun `rig with name-substring still works for single-word parsed names`() {
        val matched = AiPromptProvider.descriptorsMatching(listOf("bigwang"))
        assertEquals(1, matched.size)
        assertEquals("bmd", matched.first().key)
        assertEquals("Bigwang McDouchebag", matched.first().name)
    }

    @Test
    fun `rig substring list deduplicates by descriptor key when the same descriptor is matched twice`() {
        val matched = AiPromptProvider.descriptorsMatching(listOf("bob", "bob", "Bob"))
        assertEquals(1, matched.size, "Duplicate substrings should resolve to a single descriptor")
        assertEquals("bob", matched.first().key)
    }

    @Test
    fun `rig with no matching substrings returns an empty list rather than throwing`() {
        val matched = AiPromptProvider.descriptorsMatching(listOf("no-such-ai-12345"))
        assertTrue(matched.isEmpty(), "Expected empty list for unmatched substring, got ${matched.map { it.key }}")
    }

    @Test
    fun `rig with empty substrings list returns empty without scanning descriptors`() {
        val matched = AiPromptProvider.descriptorsMatching(emptyList())
        assertTrue(matched.isEmpty())
    }

    @Test
    fun `matched descriptor names are the multi-word display name captured from the prompt`() {
        val matched = AiPromptProvider.descriptorsMatching(listOf("bob", "dave", "bigwang"))
        val byKey = matched.associateBy { it.key }
        assertEquals("Shitty Bob", byKey.getValue("bob").name)
        assertEquals("Officer Dave", byKey.getValue("dave").name)
        assertEquals("Bigwang McDouchebag", byKey.getValue("bmd").name)
    }

    // ---- parseName (verified through descriptorsMatching / descriptor snapshot) -

    @Test
    fun `parseName captures multi-word name that is followed by a lowercase descriptor word`() {
        // "bob" prompt: "You are Shitty Bob the great grandson of Sooty Bob the chimney cleaner."
        // The trailing "the" is descriptive, not part of the name.
        val matched = AiPromptProvider.descriptorsMatching(listOf("Shitty"))
        val bob = matched.firstOrNull { it.key == "bob" }
        assertNotNull(bob, "Expected 'Shitty' substring to reach the 'bob' descriptor via its multi-word name")
        assertEquals("Shitty Bob", bob.name)
    }

    @Test
    fun `parseName captures multi-word name terminated by a period`() {
        // "dave" prompt: "You are Officer Dave. You are the Police King..."
        val matched = AiPromptProvider.descriptorsMatching(listOf("Officer"))
        val dave = matched.firstOrNull { it.key == "dave" }
        assertNotNull(dave, "Expected 'Officer' substring to reach the 'dave' descriptor via its multi-word name")
        assertEquals("Officer Dave", dave.name)
    }

    @Test
    fun `parseName captures multi-word name terminated by a comma`() {
        // "zzs" prompt: "You are Zuzusarogorata Suguruzands, High Priest..."
        val matched = AiPromptProvider.descriptorsMatching(listOf("Zuzusarogorata"))
        val zzs = matched.firstOrNull { it.key == "zzs" }
        assertNotNull(zzs, "Expected 'Zuzusarogorata' substring to reach the 'zzs' descriptor via its multi-word name")
        assertEquals("Zuzusarogorata Suguruzands", zzs.name)
    }

    @Test
    fun `parseName preserves the period on recognised abbreviations like Dr`() {
        // "ptm" prompt: "You are Dr. Percival Thrustmore. As an octocentenarian..."
        val matched = AiPromptProvider.descriptorsMatching(listOf("Percival"))
        val ptm = matched.firstOrNull { it.key == "ptm" }
        assertNotNull(ptm, "Expected 'Percival' substring to reach the 'ptm' descriptor (Dr. should not have stopped the parse)")
        assertEquals("Dr. Percival Thrustmore", ptm.name)
    }

    @Test
    fun `parseName falls back to the upper-cased key for prompts missing the You are prefix`() {
        // "bg" prompt: "You Big Googar. You no good at grammar..." (intentionally malformed)
        val matched = AiPromptProvider.descriptorsMatching(listOf("BG"))
        val bg = matched.firstOrNull { it.key == "bg" }
        assertNotNull(bg, "Expected 'BG' (upper-cased fallback key) to match the 'bg' descriptor")
        assertEquals("BG", bg.name)
    }

    @Test
    fun `parseName captures single-word names without losing them at the next punctuation`() {
        // "c" prompt: "You are Cleopatrick. You are the childhood friend..."
        val matched = AiPromptProvider.descriptorsMatching(listOf("Cleopatrick"))
        val c = matched.firstOrNull { it.key == "c" }
        assertNotNull(c, "Expected 'Cleopatrick' substring to reach the 'c' descriptor")
        assertEquals("Cleopatrick", c.name)
    }
}
