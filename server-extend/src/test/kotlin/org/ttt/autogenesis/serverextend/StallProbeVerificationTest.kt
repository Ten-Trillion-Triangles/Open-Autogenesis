package org.ttt.autogenesis.serverextend

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertTrue

/**
 * AD-HOC VERIFICATION (not a permanent regression test) for the
 * STALL_PROBE instrumentation added to [RestPlayerConnectionManager].
 *
 * Captures stdout while the Logger writes through [LogWriter] and asserts
 * that two consecutive `register("same-id")` calls produce the expected
 * `STALL_PROBE cycle=N playerId=X deltaSincePrevOpenMs=Y` line.
 *
 * To be REMOVED once the probe is promoted to a proper regression test
 * or once the live-system stalls are caught and the probe is replaced
 * by a counter-based throttle.
 *
 * Every @Test body ends with `Unit` to keep the test functions void for
 * JUnit, since some kotlin.test assertions return non-Unit.
 */
class StallProbeVerificationTest
{
    private val originalOut = System.out
    private lateinit var captured: ByteArrayOutputStream

    @Before
    fun redirectStdout()
    {
        captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured, true, Charsets.UTF_8))
    }

    @After
    fun restoreStdout()
    {
        System.setOut(originalOut)
    }

    @Test
    fun stallProbeLineFiresOnSessionReplace() : Unit = runBlocking {
        val manager = RestPlayerConnectionManager()
        manager.registerDelayMillis = 0

        // First register: should NOT emit STALL_PROBE (no previous session).
        manager.register("probe-player-1")

        // Small gap so the delta in the STALL_PROBE line is non-zero and visible.
        kotlinx.coroutines.delay(120)

        // Second register for the same playerId: previous != null path fires.
        manager.register("probe-player-1")

        // Give the coroutine a beat to flush Logger output through LogWriter.
        kotlinx.coroutines.delay(50)

        val output = captured.toString(Charsets.UTF_8)
        println("---CAPTURED-START---")
        println(output)
        println("---CAPTURED-END---")

        assertTrue(
            output.contains("STALL_PROBE"),
            "expected STALL_PROBE line in captured stdout, got:\n$output"
        )
        assertTrue(
            output.contains("cycle=1"),
            "expected cycle=1 on first replacement, got:\n$output"
        )
        assertTrue(
            output.contains("playerId=probe-player-1"),
            "expected playerId=probe-player-1 in the line, got:\n$output"
        )
        assertTrue(
            output.contains("deltaSincePrevOpenMs="),
            "expected deltaSincePrevOpenMs= field, got:\n$output"
        )

        // Third register: cycle counter must advance.
        kotlinx.coroutines.delay(80)
        manager.register("probe-player-1")
        kotlinx.coroutines.delay(50)

        val output2 = captured.toString(Charsets.UTF_8)
        assertTrue(
            output2.contains("cycle=2"),
            "expected cycle=2 on second replacement, got:\n$output2"
        )
        Unit
    }
}
