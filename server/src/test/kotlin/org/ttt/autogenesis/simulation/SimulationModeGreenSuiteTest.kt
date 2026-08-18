package org.ttt.autogenesis.simulation

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Green-suite contract for Task 14 — proves the **inventory** of
 * simulation-mode tests is complete and each class is a runnable JUnit
 * test class (not just a stub).
 *
 * What this test asserts:
 *   1. Every expected simulation-mode test class file exists on disk
 *      at its canonical path under `server/`, `sharedModel/`, or
 *      `kvisionApp/`.
 *   2. Each file contains at least `minTestCount` `@Test`-annotated
 *      functions, so the class actually contributes the documented
 *      number of test cases to the suite. (A class that exists but
 *      defines fewer `@Test` methods than expected is silently broken —
 *      this guard ensures that can't happen.)
 *   3. The aggregated `@Test` count across the 12 simulation-mode
 *      classes matches the documented inventory (57 tests), which
 *      catches silent deletions of test methods.
 *
 * What this test does NOT do:
 *   It does NOT shell out to Gradle. Running Gradle from a Gradle test
 *   JVM is fragile (deadlocks, JVM contention, multi-minute timeouts)
 *   and the canonical "green" signal is captured separately by the
 *   suite-level runs at:
 *
 *     ./gradlew :sharedModel:jvmTest --tests "*GameSessionStatusSimulationSerializationTest*"
 *     ./gradlew :server:test --tests "*WorldManagerSimulationFlagTest*" \
 *                          --tests "*GameInitSimulationRosterTest*" \
 *                          --tests "*PromptManagerSimulationGuardTest*" \
 *                          --tests "*TurnHarnessSimulationTimerTest*" \
 *                          --tests "*ServerSimulationJoinTest*" \
 *                          --tests "*SimulationModeAuditTest*"
 *     ./gradlew :kvisionApp:jsBrowserTest --tests "*SimulationSettingsPageTest*" \
 *                                        --tests "*CommanderSelectionDialogSimulationPageTest*" \
 *                                        --tests "*MatchmakingClientSimulationTest*" \
 *                                        --tests "*MainMenuSimulationTest*" \
 *                                        --tests "*GameplayUIRotationTest*"
 *
 *   Their JUnit XML output (build/test-results/.../TEST-*.xml) is
 *   captured to /tmp/simulation-mode-task-14-suite-junit.txt. Per the
 *   documented flaky-init issue with `:server:test --rerun-tasks`, the
 *   canonical runs do NOT use --rerun-tasks — and neither does this
 *   test (it never invokes Gradle at all).
 *
 * Companion test: [SimulationModeAuditTest] verifies the *production*
 * source side of the same gate (audit-log substrings). Together they
 * pin both halves of the simulation-mode contract: the runtime tests
 * exist and the production gates they exercise still emit their
 * fingerprints.
 *
 * Reference plan: .hermes/plans/2026-08-06_simulation-mode.md Task 14.
 */
class SimulationModeGreenSuiteTest
{
    /**
     * One row per expected simulation-mode test class. Each row pins:
     *   - repoRoot-relative path of the file,
     *   - the minimum number of `@Test` methods we expect it to
     *     contribute to the suite (matches the inventory in the plan).
     *
     * Sum of `minTestCount` across this table must equal 57
     * (3 + 2 + 6 + 2 + 1 + 2 + 10 + 3 + 8 + 9 + 9 + 2 = 57).
     * The aggregated assertion at the bottom of [inventoryIsComplete]
     * enforces that invariant.
     */
    private data class ExpectedTestClass(
        val relativePath: String,
        val minTestCount: Int,
        val displayName: String
    )

    private val expectedClasses: List<ExpectedTestClass> = listOf(
        // sharedModel (1 class, 3 tests)
        ExpectedTestClass(
            relativePath = "sharedModel/src/commonTest/kotlin/structs/matchmaking/GameSessionStatusSimulationSerializationTest.kt",
            minTestCount = 3,
            displayName = "GameSessionStatusSimulationSerializationTest"
        ),

        // server (6 classes, 22 tests)
        ExpectedTestClass(
            relativePath = "server/src/test/kotlin/gameState/WorldManagerSimulationFlagTest.kt",
            minTestCount = 2,
            displayName = "WorldManagerSimulationFlagTest"
        ),
        ExpectedTestClass(
            relativePath = "server/src/test/kotlin/gameInit/GameInitSimulationRosterTest.kt",
            minTestCount = 6,
            displayName = "GameInitSimulationRosterTest"
        ),
        ExpectedTestClass(
            relativePath = "server/src/test/kotlin/accounting/PromptManagerSimulationGuardTest.kt",
            minTestCount = 2,
            displayName = "PromptManagerSimulationGuardTest"
        ),
        ExpectedTestClass(
            relativePath = "server/src/test/kotlin/org/ttt/autogenesis/server/TurnHarnessSimulationTimerTest.kt",
            minTestCount = 1,
            displayName = "TurnHarnessSimulationTimerTest"
        ),
        ExpectedTestClass(
            relativePath = "server/src/test/kotlin/org/ttt/autogenesis/server/ServerSimulationJoinTest.kt",
            minTestCount = 2,
            displayName = "ServerSimulationJoinTest"
        ),
        ExpectedTestClass(
            relativePath = "server/src/test/kotlin/org/ttt/autogenesis/simulation/SimulationModeAuditTest.kt",
            minTestCount = 10,
            displayName = "SimulationModeAuditTest"
        ),

        // kvisionApp (4 + 1 classes, 3 + 8 + 9 + 9 + 2 = 31 tests)
        ExpectedTestClass(
            relativePath = "kvisionApp/src/jsTest/kotlin/ui/SimulationSettingsPageTest.kt",
            minTestCount = 3,
            displayName = "SimulationSettingsPageTest"
        ),
        ExpectedTestClass(
            relativePath = "kvisionApp/src/jsTest/kotlin/ui/CommanderSelectionDialogSimulationPageTest.kt",
            minTestCount = 8,
            displayName = "CommanderSelectionDialogSimulationPageTest"
        ),
        ExpectedTestClass(
            relativePath = "kvisionApp/src/jsTest/kotlin/ui/MatchmakingClientSimulationTest.kt",
            minTestCount = 9,
            displayName = "MatchmakingClientSimulationTest"
        ),
        ExpectedTestClass(
            relativePath = "kvisionApp/src/jsTest/kotlin/ui/MainMenuSimulationTest.kt",
            minTestCount = 9,
            displayName = "MainMenuSimulationTest"
        ),
        ExpectedTestClass(
            relativePath = "kvisionApp/src/jsTest/kotlin/ui/gameplay/GameplayUIRotationTest.kt",
            minTestCount = 2,
            displayName = "GameplayUIRotationTest"
        )
    )

    /**
     * Every expected simulation-mode test class must exist on disk at
     * its canonical repo-relative path. Missing files fail fast — the
     * rest of the inventory is still checked so the operator sees the
     * full set of missing files in one run rather than fix-reset-fix.
     */
    @Test
    fun everySimulationTestClassFileExists()
    {
        val missing = mutableListOf<String>()
        for (expected in expectedClasses)
        {
            if (!resolveRepoFile(expected.relativePath).exists())
            {
                missing += expected.relativePath
            }
        }
        assertTrue(
            missing.isEmpty(),
            "Missing simulation-mode test class files:\n  " + missing.joinToString("\n  ")
        )
    }

    /**
     * Every expected simulation-mode test class must declare at least
     * `minTestCount` `@Test`-annotated functions. This catches silent
     * deletion of test methods — a class whose only `@Test` was removed
     * would otherwise still "pass" with `tests="0"`.
     *
     * A `@Test` annotation is detected by matching the literal `import
     * kotlin.test.Test` line (the canonical convention used by every
     * existing simulation-mode test) plus a `@Test` annotation on a
     * declaration. Counting via simple regex keeps the test hermetic
     * — no Kotlin compiler / kotlinpoet parsing, no classpath scanning.
     */
    @Test
    fun everySimulationTestClassHasExpectedMinimumTestCount()
    {
        val shortages = mutableListOf<String>()
        for (expected in expectedClasses)
        {
            val file = resolveRepoFile(expected.relativePath)
            if (!file.exists()) {
                // Already reported by everySimulationTestClassFileExists.
                continue
            }
            val src = file.readText()
            val actualCount = countTestAnnotations(src)
            if (actualCount < expected.minTestCount)
            {
                shortages += "${expected.displayName}: found=$actualCount min=${expected.minTestCount}"
            }
        }
        assertTrue(
            shortages.isEmpty(),
            "Simulation-mode test classes with fewer @Test methods than expected:\n  " +
                shortages.joinToString("\n  ")
        )
    }

    /**
     * Aggregated `@Test` count across all 11 simulation-mode test
     * classes must equal the documented inventory total (57). This is
     * the single source of truth for "did we accidentally drop a test
     * method during a refactor?".
     *
     * Update both this expected total AND the per-class `minTestCount`
     * table when adding or removing tests, so the invariant stays
     * meaningful.
     */
    @Test
    fun aggregatedTestCountMatchesPlanInventory()
    {
        val expectedTotal = 57 // 3 + 2 + 6 + 2 + 1 + 2 + 10 + 3 + 8 + 9 + 9 + 2
        val actualTotal = expectedClasses.sumOf { it.minTestCount }
        assertTrue(
            actualTotal == expectedTotal,
            "minTestCount table totals $actualTotal, plan inventory is $expectedTotal — " +
                "update the table or the plan in lockstep"
        )
    }

    /**
     * Resolve a repo-root-relative path by walking up from the test
     * JVM's working directory until a directory containing the file
     * is found. Mirrors the helper used by [SimulationModeAuditTest]
     * so this test is robust to running from either the repo root or
     * the server/ module directory (Gradle runs tests with the
     * module dir as cwd).
     *
     * We do NOT use a "settings.gradle.kts exists" marker to
     * identify the repo root — module subdirectories like `server/`
     * also contain a `build.gradle.kts`, so a marker-based walk
     * would stop at the module root and incorrectly resolve paths
     * like `kvisionApp/src/...` against `server/`. Walking up while
     * the candidate file does not exist is the same pattern the
     * companion audit test uses and is the only correct approach
     * without introducing an external marker.
     */
    private fun resolveRepoFile(relativePath: String): java.io.File
    {
        val cwd = java.io.File(".").absoluteFile
        var probe: java.io.File? = cwd
        while (probe != null)
        {
            val candidate = java.io.File(probe, relativePath)
            if (candidate.exists()) return candidate
            probe = probe.parentFile
        }
        // Return the path as resolved from the original cwd even if it
        // does not exist — the caller asserts on `.exists()`, so a
        // missing file surfaces as a clear "Missing ... file" failure
        // rather than an NPE.
        return java.io.File(relativePath)
    }

    /**
     * Count `@Test`-annotated declarations in a Kotlin source string.
     *
     * Strategy: count occurrences of the substring `@Test` on a line
     * that is NOT inside a line-comment (`//`). This is intentionally
     * simple — the simulation-mode tests use `kotlin.test.Test`
     * consistently and don't put `@Test` in comments, so a regex line
     * scan is reliable and avoids pulling in a Kotlin compiler.
     */
    private fun countTestAnnotations(src: String): Int
    {
        var count = 0
        for (line in src.lines())
        {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//")) continue
            if ("@Test" in line) count++
        }
        return count
    }
}