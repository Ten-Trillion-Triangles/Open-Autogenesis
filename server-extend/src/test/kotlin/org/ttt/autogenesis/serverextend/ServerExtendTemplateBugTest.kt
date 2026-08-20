package org.ttt.autogenesis.serverextend

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Regression: ServerExtend.kt had three Logger.error calls with `$${e.message}`
 * in the format string (extra `$` escapes the template, so the actual exception
 * message is silently dropped and the log only shows a literal `$`). This made
 * every RPC dispatch failure invisible to operators — when MapUploadGate
 * threw, the 500 response went back to the client but the server log contained
 * no clue what had actually happened.
 *
 * Probing the failure: when this regex matches, the bug is present.
 */
class ServerExtendTemplateBugTest
{
    /**
     * Pinned fingerprint of the bug. Every regression of this shape must
     * cause this test to FAIL — the operator-side fix is to delete the
     * extra `$` so the actual exception message interpolates.
     */
    @Test
    fun serverExtendHasNoDoubleDollarTemplateBugs() {
        val file = java.io.File("src/main/kotlin/org/ttt/autogenesis/serverextend/ServerExtend.kt")
        val text = file.readText()
        // The bug pattern: "$$\b" (literal $$) — the extra $ escapes
        // the template, swallowing the actual exception message.
        val bad = Regex("""\$\$\{[^}]*}""")
        val matches = bad.findAll(text).count()
        assertEquals(
            0, matches,
            "ServerExtend.kt contains ${'$'}${'$'}{...} double-dollar template escapes that swallow actual exception messages. " +
                "Affected lines must be fixed: change \$\${e.message} → \${e.message} so the actual exception interpolates. " +
                "See probe receipts in kvisionApp-e2e/probes/map-upload-e2e.mjs (Phase 7 500-during-uploadMapGate, 2026-08-12)."
        )
    }
}
