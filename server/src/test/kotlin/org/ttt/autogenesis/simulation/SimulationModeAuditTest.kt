package org.ttt.autogenesis.simulation

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Audit test for Task 13 — verifies that every simulation-mode gate added by
 * Tasks 1-12 emits its expected log-line signature. Each `auditXxxPresent`
 * method reads the production source files as text and asserts that the
 * substring the gate emits appears at least once in the file.
 *
 * This is a static source-scan, not a runtime capture — the runtime gates
 * were verified individually by the per-task tests (e.g. GameplayUIRotationTest,
 * ServerSimulationJoinTest). The static scan ensures no one accidentally
 * removes an audit line during refactors.
 *
 * If you ADD a new gate, add its expected log-line string here so this
 * scan stays the single source of truth.
 */
class SimulationModeAuditTest
{
    @Test
    fun auditWorldManagerGatePresent()
    {
        val src = readSource("server/src/main/kotlin/gameState/WorldManager.kt")
        // WorldManager.isSimulationHumanOwned predicate exists with case-insensitive comparison
        assertTrue("fun isSimulationHumanOwned" in src, "WorldManager.isSimulationHumanOwned predicate missing")
        assertTrue("isSimulationMode" in src, "WorldManager.isSimulationMode field missing")
        assertTrue("simulationHumanPlayerNames" in src, "WorldManager.simulationHumanPlayerNames field missing")
    }

    @Test
    fun auditGameInitModeResolutionPresent()
    {
        val src = readSource("server/src/main/kotlin/gameInit/GameInit.kt")
        assertTrue(
            "GameInit: mode resolution gameType=" in src,
            "GameInit mode-resolution audit line missing"
        )
        assertTrue(
            "GameInit: simulation mode with explicit map=" in src,
            "GameInit explicit-map audit line missing"
        )
    }

    @Test
    fun auditTurnHarnessTimerGatePresent()
    {
        val src = readSource("server/src/main/kotlin/org/ttt/autogenesis/server/TurnHarness.kt")
        assertTrue(
            "simulation-human-owned actor=" in src,
            "TurnHarness.awaitPlayerAction timer-gate audit line missing"
        )
    }

    @Test
    fun auditPromptManagerGatePresent()
    {
        val src = readSource("server/src/main/kotlin/accounting/PromptManager.kt")
        assertTrue(
            "PromptManager.executeAiTakeover: BLOCKED" in src,
            "PromptManager.executeAiTakeover BLOCKED audit line missing"
        )
    }

    @Test
    fun auditServerJoinPresent()
    {
        val src = readSource("server/src/main/kotlin/org/ttt/autogenesis/server/Server.kt")
        assertTrue(
            "Server: simulation mode — single connection" in src,
            "Server simulation-join audit line missing"
        )
    }

    @Test
    fun auditSimulationSettingsPagePresent()
    {
        val src = readSource("kvisionApp/src/jsMain/kotlin/ui/SimulationSettingsPage.kt")
        assertTrue(
            "SimulationSettingsPage: mounted with" in src,
            "SimulationSettingsPage mount audit line missing"
        )
        assertTrue(
            "SimulationSettingsPage: slotCount =" in src,
            "SimulationSettingsPage slotCount audit line missing"
        )
    }

    @Test
    fun auditSimulationMapPickerPresent()
    {
        val src = readSource("kvisionApp/src/jsMain/kotlin/ui/SimulationMapPicker.kt")
        assertTrue(
            "SimulationMapPicker: map selected" in src,
            "SimulationMapPicker selection audit line missing"
        )
    }

    @Test
    fun auditCommanderSelectionDialogPresent()
    {
        val src = readSource("kvisionApp/src/jsMain/kotlin/ui/CommanderSelectionDialog.kt")
        assertTrue(
            "confirmAndPlay SIMULATION" in src,
            "CommanderSelectionDialog confirmAndPlay SIMULATION audit line missing"
        )
    }

    @Test
    fun auditMainMenuPresent()
    {
        val src = readSource("kvisionApp/src/jsMain/kotlin/ui/MainMenu.kt")
        assertTrue(
            "beginSimulationMatchSession" in src,
            "MainMenu beginSimulationMatchSession missing"
        )
    }

    @Test
    fun auditGameplayUIRotationPresent()
    {
        val src = readSource("kvisionApp/src/jsMain/kotlin/ui/gameplay/GameplayUI.kt")
        assertTrue(
            "rotating localPlayer" in src,
            "GameplayUI rotation audit line missing"
        )
    }

    private fun readSource(relativePath: String): String
    {
        // The test JVM's working directory is typically the server/ module
        // directory, not the repo root. Walk up to find kvisionApp at the
        // repo root by looking for the build.gradle.kts marker.
        val cwd = java.io.File(".").absoluteFile
        var probe: java.io.File? = cwd
        while (probe != null)
        {
            val candidate = java.io.File(probe, relativePath)
            if (candidate.exists()) return candidate.readText()
            probe = probe.parentFile
        }
        return ""
    }
}