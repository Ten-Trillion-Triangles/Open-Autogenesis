package org.ttt.autogenesis.serverextend

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Regression test for BUG 26 (2026-06-27): the user's spec is
 *
 *   1. login → dialog appears (the PUSH fires on connect)
 *   2. NO auto-restore on connect (only the explicit Resume click triggers restore)
 *
 * This test pins the "PUSH on connect" half. The "NEVER auto-restore" half
 * is pinned by AutoRestoreDisabledTest on the server module.
 *
 * The push is delivered via [ResumeAvailabilityPushService.checkAndPush],
 * called from [triggerSseResumePush] inside the SSE handler at
 * `get("/events") { ... }`. This is a structural test (source-code
 * assertion) because the push is wired into a ktor SSE streaming handler
 * that's painful to stand up in a unit test.
 *
 * References:
 *   - server-extend/src/main/kotlin/org/ttt/autogenesis/serverextend/ServerExtend.kt
 *     (SSE handler — calls triggerSseResumePush when accelbyteId is non-blank)
 *   - server-extend/src/main/kotlin/org/ttt/autogenesis/serverextend/ResumeAvailabilityPushService.kt
 *     (the push service that sends client.resumeAvailable to the client)
 *   - server-extend/src/main/kotlin/org/ttt/autogenesis/serverextend/ServerConnector.kt:283-368
 *     (requestResume RPC handler — the OTHER push path, invoked on explicit Resume click)
 */
class SseResumePushOnConnectTest
{
    private val serverExtendKtPath = "src/main/kotlin/org/ttt/autogenesis/serverextend/ServerExtend.kt"

    /**
     * Invariant 1: the SSE handler in ServerModule calls
     * triggerSseResumePush when the accelbyteId query parameter is
     * non-blank. The call site is gated on isNotBlank() so blank values
     * (e.g. pre-OAuth WS connects) do NOT trigger the push.
     */
    @Test
    fun `SSE handler calls triggerSseResumePush when accelbyteId is non-blank`() {
        val file = File(serverExtendKtPath)
        assertTrue(file.exists(), "ServerExtend.kt must exist at $serverExtendKtPath")

        val source = file.readText()

        // Find the SSE handler block — `get("/events") {` is the entrypoint.
        val sseEntry = source.indexOf("get(\"/events\")")
        assertTrue(sseEntry >= 0, "SSE handler `get(\"/events\")` must exist in ServerExtend.kt")

        // Brace-balance from the opening `{` to find the matching `}`.
        val bodyOpen = source.indexOf("{", sseEntry)
        var depth = 0
        var bodyClose = bodyOpen
        for (i in bodyOpen until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) { bodyClose = i; break }
                }
            }
        }
        val handlerBody = source.substring(bodyOpen, bodyClose + 1)

        // Assert the helper is called inside the SSE handler body. Use
        // regex to match the call shape (xxx(...)) so comment-only mentions
        // of the helper don't trip the test.
        val regex = Regex("""triggerSseResumePush\s*\(""")
        assertTrue(
            regex.containsMatchIn(handlerBody),
            "BUG 26 (2026-06-27): SSE handler must call triggerSseResumePush when accelbyteId is non-blank — " +
                "this is what makes the ResumeOrNewDialog appear after login. The push is decoupled " +
                "from auto-restore (see AutoRestoreDisabledTest for the never-restore invariant)."
        )
    }

    /**
     * Invariant 2: the triggerSseResumePush helper itself exists and
     * delegates to ResumeAvailabilityPushService.checkAndPush.
     */
    @Test
    fun `triggerSseResumePush helper delegates to ResumeAvailabilityPushService checkAndPush`() {
        val file = File(serverExtendKtPath)
        assertTrue(file.exists(), "ServerExtend.kt must exist")

        val source = file.readText()

        // The helper must exist as a callable function.
        val helperMatch = Regex("""fun\s+triggerSseResumePush\s*\(""").find(source)
        assertTrue(
            helperMatch != null,
            "triggerSseResumePush must exist as a top-level function"
        )

        // Inside the helper body, the call must go to checkAndPush.
        val helperStart = helperMatch!!.range.first
        // Brace-balance from the helper's opening `{` to find its matching `}`.
        val bodyOpen = source.indexOf("{", helperStart)
        var depth = 0
        var bodyClose = bodyOpen
        for (i in bodyOpen until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) { bodyClose = i; break }
                }
            }
        }
        val helperBody = source.substring(bodyOpen, bodyClose + 1)
        assertTrue(
            Regex("""ResumeAvailabilityPushService\.checkAndPush\s*\(""").containsMatchIn(helperBody),
            "triggerSseResumePush must delegate to ResumeAvailabilityPushService.checkAndPush"
        )
    }
}
