package org.ttt.autogenesis

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Hygiene test for production code.
 *
 * Guards against debug-only call sites leaking into shipped source.
 * This file accumulates all such guards so they run in one fast JVM test
 * instead of being scattered across modules.
 *
 * ## What it catches
 *
 *  - `[BUGn_*]` log markers from old bug hunts — once the bug is fixed
 *    the marker should be removed (the message itself stays).
 *  - Bare `println(...)` and `print(...)` calls in production code —
 *    these bypass [org.ttt.autogenesis.logging.Logger] and pollute
 *    stdout in a way the user can't disable. The Logger is the single
 *    configured logging sink with priority filtering and disk
 *    rotation; debug prints must go through it.
 *
 * ## Scope
 *
 * Scans `server/src/main` and `kvisionApp/src/jsMain` (production
 * sources). Excludes test source sets and generated KSP code.
 */
class ProductionCodeHygieneTest
{
    private val productionRoots: List<File> by lazy {
        val cwd = File(".").absoluteFile
        val workspaceRoot = generateSequence(cwd) { it.parentFile }
            .firstOrNull { it.name == "Autogenesis" }
            ?: cwd
        listOf(
            File(workspaceRoot, "server/src/main"),
            File(workspaceRoot, "kvisionApp/src/jsMain")
        )
    }

    /**
     * Asserts no production .kt source file contains a leftover
     * "[BUG*_INVESTIGATION]" or "[BUG*_ICON]" log marker outside
     * of a comment. These are diagnostic-only tags from old bug
     * hunts; the underlying log messages are still emitted
     * (without the tag) so triage value is preserved.
     */
    @Test
    fun `production code contains no leftover BUG investigation markers in log calls`() {
        val forbidden = Regex("""\[BUG\d+_(?:INVESTIGATION|ICON)\]""")
        val offenders = mutableListOf<String>()

        for (root in productionRoots) {
            if (!root.exists()) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    file.readLines().forEachIndexed { idx, line ->
                        val codePart = line.substringBefore("//")
                        if (forbidden.containsMatchIn(codePart)) {
                            offenders += "${file.path}:${idx + 1}: ${line.trim()}"
                        }
                    }
                }
        }

        assertTrue(
            offenders.isEmpty(),
            "Production code contains leftover BUG*_INVESTIGATION/ICON markers in log calls. " +
                "Remove the marker prefix from the log message (keep the message itself). " +
                "Offending lines:\n" + offenders.joinToString("\n")
        )
    }

    /**
     * Asserts no production .kt source file calls bare `println(...)`
     * or `print(...)` as a top-level call site. These bypass the
     * configured [org.ttt.autogenesis.logging.Logger] and pollute
     * stdout in a way the user cannot disable or filter.
     *
     * The scan is intentionally line-anchored on `print`/`println`
     * so it only flags actual call sites, not identifiers that
     * happen to contain the substring.
     *
     * Excludes [org.ttt.autogenesis.server.config.env.ProcessEnvironmentCompat]
     * and [org.ttt.autogenesis.server.BrowserLogHandler] — both are
     * bootstrap utilities that print intentionally before the Logger
     * is configured (the PrintStream is the only sink at that point).
     */
    @Test
    fun `production code routes all logging through Logger not println`() {
        // Matches `println(...)` or `print(...)` calls, anchored to a
        // word boundary so identifiers like `myPrinter` are not matched.
        val forbidden = Regex("""(?<![A-Za-z0-9_])(?:println|print)\s*\(""")
        val exempt = setOf(
            // Pre-Logger bootstrap: the Logger is not configured yet at
            // the point these run, so println is the only available sink.
            "server/src/main/kotlin/org/ttt/autogenesis/server/config/env/ProcessEnvironmentCompat.kt",
            "server/src/main/kotlin/org/ttt/autogenesis/server/BrowserLogHandler.kt"
        )
        val offenders = mutableListOf<String>()

        for (root in productionRoots) {
            if (!root.exists()) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    val relative = file.absolutePath.removePrefix(workspaceRoot().absolutePath + "/")
                    if (relative in exempt) return@forEach
                    file.readLines().forEachIndexed { idx, line ->
                        val codePart = line.substringBefore("//")
                        if (forbidden.containsMatchIn(codePart)) {
                            offenders += "${file.path}:${idx + 1}: ${line.trim()}"
                        }
                    }
                }
        }

        assertTrue(
            offenders.isEmpty(),
            "Production code contains println/print call sites that should use " +
                "org.ttt.autogenesis.logging.Logger. Offending lines:\n" +
                offenders.joinToString("\n")
        )
    }

    /**
     * Returns the workspace root (used to make file paths relative in
     * the test output). Mirrors the lazy logic in [productionRoots].
     */
    private fun workspaceRoot(): File {
        val cwd = File(".").absoluteFile
        return generateSequence(cwd) { it.parentFile }
            .firstOrNull { it.name == "Autogenesis" }
            ?: cwd
    }
}
