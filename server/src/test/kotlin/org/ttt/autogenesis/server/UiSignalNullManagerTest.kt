package org.ttt.autogenesis.server

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.logging.LogPriority
import org.ttt.autogenesis.network.ActiveTurnData
import org.ttt.autogenesis.network.GameOverData
import org.ttt.autogenesis.network.NemesisThreatAnnouncementData
import org.ttt.autogenesis.network.NemesisThreatKind
import org.ttt.autogenesis.network.OpenWidgetData
import org.ttt.autogenesis.network.OpenWidgetType
import org.ttt.autogenesis.network.PlacementEntry
import org.ttt.autogenesis.network.ResolutionStep
import org.ttt.autogenesis.network.TurnOrderAnnouncementData
import org.ttt.autogenesis.network.TurnOrderParticipant
import structs.World
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for [UiSignalRpcHandlers] when the global connection
 * manager has not been initialised (e.g. headless TurnHarness runs, server
 * boot before [Server] sets the singleton, or shutdown after teardown).
 *
 * Contract: every broadcast helper must short-circuit cleanly when no
 * connection manager is wired up. Throwing or logging at WARN/ERROR for
 * this case produces noise in the log and obscures real errors.
 *
 * The JVM [org.ttt.autogenesis.logging.LogWriter] writes formatted log
 * lines to [System.out] using the pattern `timestamp [PRIORITY] [CATEGORY]:
 * message`. The tests redirect [System.out] to a [ByteArrayOutputStream],
 * invoke the broadcast helpers with `connectionManager = null`, and assert
 * that none of the captured lines for the "no manager" path use the
 * `[WARN]` or `[ERROR]` priority markers.
 */
class UiSignalNullManagerTest
{
    private var originalOut: PrintStream? = null
    private var captured: ByteArrayOutputStream = ByteArrayOutputStream()
    private var previousManager: PlayerConnectionManager? = null

    @Before
    fun setUp()
    {
        originalOut = System.out
        System.setOut(PrintStream(captured, true, Charsets.UTF_8))
        // Save & reset connection manager.
        previousManager = UiSignalRpcHandlers.connectionManager
        UiSignalRpcHandlers.connectionManager = null
    }

    @After
    fun tearDown()
    {
        // Restore System.out.
        System.setOut(originalOut)
        // Restore connection manager.
        UiSignalRpcHandlers.connectionManager = previousManager
        // Reset captured buffer.
        captured.reset()
    }

    /**
     * Every broadcast path through `broadcastNotification` should be a no-op
     * when no connection manager is wired up. None of the captured log lines
     * for that path should be tagged [WARN] or [ERROR].
     */
    @Test
    fun `broadcasts do not log WARN or ERROR when connectionManager is null`() = runTest {
        // Ensure Logger min priority is low enough to let both DEBUG and WARN through.
        Logger.configure(LogPriority.DEBUG, saveToDisk = false, maxLogFiles = 0, serverType = "test-null-mgr")
        captured.reset()

        // Exercise every code path that goes through broadcastNotification.
        UiSignalRpcHandlers.broadcastResolutionStep(ResolutionStep.STORY, "msg")
        UiSignalRpcHandlers.broadcastProgressBar(0, "instruction")
        UiSignalRpcHandlers.broadcastNarrativeChunk("chunk")
        UiSignalRpcHandlers.broadcastNarrativeUpdate("update")
        UiSignalRpcHandlers.broadcastPrepareStory("style")
        UiSignalRpcHandlers.broadcastCommandInteractive(true)
        UiSignalRpcHandlers.broadcastWorldUpdate(World())
        UiSignalRpcHandlers.broadcastSummitResult(
            success = true,
            narrativeSummary = "summary",
            statChanges = mapOf("P1" to "+1")
        )
        UiSignalRpcHandlers.broadcastJudgementResult(
            isSuccess = true,
            header = "h",
            subtext = "s"
        )
        UiSignalRpcHandlers.broadcastTurnTimerUpdate(
            remainingSeconds = 1L,
            totalDuration = 60L,
            isRunning = true
        )
        UiSignalRpcHandlers.broadcastIntentUpdate("intent")
        UiSignalRpcHandlers.broadcastTurnOrderAnnouncement(
            TurnOrderAnnouncementData(
                roundNumber = 1,
                participants = listOf(TurnOrderParticipant(name = "P1", isPlayer = true)),
                firstActor = "P1"
            )
        )
        UiSignalRpcHandlers.broadcastActiveTurn(
            ActiveTurnData(
                roundNumber = 1,
                turnIndex = 0,
                actorName = "P1"
            )
        )
        UiSignalRpcHandlers.broadcastNemesisThreatAnnouncement(
            NemesisThreatAnnouncementData(
                roundNumber = 1,
                nemesisName = "Nem",
                kind = NemesisThreatKind.ARRIVAL,
                reason = "r"
            )
        )
        UiSignalRpcHandlers.broadcastGameOver(
            GameOverData(
                winnerName = "P1",
                isVictory = true,
                rounds = 1,
                victoryPoints = 10,
                territoriesClaimed = 5,
                placements = listOf(PlacementEntry(name = "P1", rank = 1, territoryPoints = 10, isPlayer = true))
            )
        )
        UiSignalRpcHandlers.broadcastOpenWidget(
            OpenWidgetData(widget = OpenWidgetType.WORLD_STATS)
        )
        UiSignalRpcHandlers.broadcastForceShowTurnResolution()

        val text = captured.toString(Charsets.UTF_8)

        // Filter to the lines that report the null-manager condition.
        val nullManagerLines = text.lineSequence()
            .filter { it.contains("Cannot broadcast", ignoreCase = false) }
            .filter { it.contains("connectionManager is null", ignoreCase = false) }
            .toList()

        // Sanity: we should have seen at least one such line in the current
        // implementation. After the fix, the line stays but its priority
        // changes from WARN to DEBUG.
        assertTrue(
            nullManagerLines.isNotEmpty(),
            "Expected at least one 'connectionManager is null' log line; got none. " +
                "Captured output was:\n$text"
        )

        // The actual contract: no null-manager line may be tagged WARN or ERROR.
        val noisy = nullManagerLines.filter {
            it.contains("[WARN]") || it.contains("[ERROR]")
        }
        assertTrue(
            noisy.isEmpty(),
            "Expected no WARN/ERROR entries when connectionManager is null, " +
                "but got:\n${noisy.joinToString("\n")}"
        )
    }

    /**
     * Regression check: each broadcast must short-circuit cleanly without
     * throwing when `connectionManager` is null. This codifies the
     * behaviour of the `if (manager == null) return` early-exit so a
     * future refactor cannot accidentally NPE here.
     */
    @Test
    fun `broadcasts do not throw when connectionManager is null`() = runTest {
        Logger.configure(LogPriority.DEBUG, saveToDisk = false, maxLogFiles = 0, serverType = "test-null-mgr")
        captured.reset()

        // Each call should complete without throwing. Any NPE here would
        // surface as a test failure.
        UiSignalRpcHandlers.broadcastResolutionStep(ResolutionStep.STORY, "msg")
        UiSignalRpcHandlers.broadcastProgressBar(0, "instruction")
        UiSignalRpcHandlers.broadcastNarrativeChunk("chunk")
        UiSignalRpcHandlers.broadcastNarrativeUpdate("update")
        UiSignalRpcHandlers.broadcastWorldUpdate(World())
        UiSignalRpcHandlers.broadcastTurnTimerUpdate(
            remainingSeconds = 1L,
            totalDuration = 60L,
            isRunning = true
        )
        UiSignalRpcHandlers.broadcastGameOver(
            GameOverData(
                winnerName = "P1",
                isVictory = true,
                rounds = 1,
                victoryPoints = 10,
                territoriesClaimed = 5,
                placements = listOf(PlacementEntry(name = "P1", rank = 1, territoryPoints = 10, isPlayer = true))
            )
        )

        // No assertion on the captured buffer is required for this test;
        // the absence of an exception is the contract.
        assertFalse(
            captured.toString(Charsets.UTF_8).contains("Exception"),
            "Broadcasts should not propagate exceptions when connectionManager is null"
        )
    }
}