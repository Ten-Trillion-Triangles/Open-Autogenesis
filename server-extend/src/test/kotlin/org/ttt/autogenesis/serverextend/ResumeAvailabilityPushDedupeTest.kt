package org.ttt.autogenesis.serverextend

import org.junit.After
import org.junit.Test
import org.ttt.autogenesis.serverextend.ResumeAvailabilityPushService
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TDD tests for [ResumeAvailabilityPushService] push-side dedup.
 *
 * ## Why these tests exist
 *
 * The user's reported bug (BUG 26/27, 2026-06-27 / 2026-07-01) was that the
 * `ResumeOrNewDialog` reappeared mid-gameplay every ~45-60s. Two layers of
 * dedupe were added:
 *
 *   - Server-side dedupe in `UiSignalRpcHandlers.notifyResumeAvailable` keyed on
 *     `pushedResumeThisSession` (ConcurrentHashMap keyed by userId), with the
 *     client re-arming via `server.consumeResumePush` on dialog dismiss.
 *   - Client-side dedupe in `ResumeAvailabilityListener.mountResumeDialog` keyed
 *     on `currentDialogUserId`.
 *
 * Both layers together were INSUFFICIENT. Log evidence from
 * `autogenesis-2026-07-05-132309.log` shows the dialog being mounted twice in
 * a 3-minute window despite both layers being in place:
 *
 *   18:33:38.941 — first push received, dialog mounted at 18:33:39.569
 *   18:33:41.522 — client fires `server.consumeResumePush` after dismissing the
 *                  dialog (Cancel or Resume button), `removedFromDedupe=true`
 *   18:36:37.178 — SSE reconnect ~3 minutes later
 *   18:36:38.169 — second push received (server-side dedupe is now empty)
 *   18:36:38.179 — dialog mounted AGAIN, mid-gameplay
 *
 * The root cause is that the server-extend-side push service had no
 * push-side dedup. Every SSE reconnect invoked `checkAndPush`, which
 * unconditionally re-read the VFS and re-push the same notification. The
 * downstream server-side `pushedResumeThisSession` dedupe and the
 * client-side `currentDialogUserId` dedupe BOTH depended on dedup state
 * surviving between reconnects — but the client's `consumeResumePush` call
 * clears the server-side dedupe state, and the client's own dedupe state
 * is also cleared in the same `clearCurrentDialog()` call. By the time the
 * next SSE reconnect arrives, every dedupe layer is empty.
 *
 * ## The fix shape (server-extend push-side dedup)
 *
 * Inside `checkAndPushBlocking`, AFTER building the
 * [ResumeAvailabilityNotification] but BEFORE calling `pushToMainServer`,
 * consult a `ConcurrentHashMap<String, LastPush>` keyed by userId. If the
 * cached entry's `savedAt == notification.savedAt` AND
 * `now - lastPushAt < COOLDOWN_MS`, skip the push with a DEBUG log.
 *
 * The dedup signal is the snapshot's `savedAt`. It is invariant under
 * reconnects — the snapshot doesn't change between SSE reconnects that
 * happen close together. Only when the user actually saves a new game (or
 * resumes and saves) does `savedAt` change, which correctly re-arms the
 * push.
 *
 * The wall-clock cooldown (5 minutes) is a second-best option for the case
 * where `savedAt` IS available but the timing signal is unreliable. We use
 * BOTH signals as AND, so any one of them changing unblocks a re-push.
 *
 * ## What these tests pin
 *
 *  1. First push for a user → `shouldPush=true` (cache miss)
 *  2. Second push for same user with same `savedAt` within cooldown →
 *     `shouldPush=false`
 *  3. Second push for same user with DIFFERENT `savedAt` → `shouldPush=true`
 *     (a new save legitimately warrants a re-push)
 *  4. Second push for same user with same `savedAt` AFTER cooldown
 *     elapsed → `shouldPush=true` (cooldown backstop)
 *  5. `markPushed(userId, savedAt)` updates the cache so subsequent
 *     pushes within cooldown are blocked
 *  6. `resetDedupeForTest()` clears the cache (test isolation)
 *
 * The dedupe is implemented as a `data class Decision` + a
 * `ConcurrentHashMap<String, LastPush>` so the decision logic is
 * testable as a pure function. The runtime cache lives in the singleton
 * (so server restart re-arms, matching the spec).
 *
 * ## Why this lives at the PUSH service, not the RECEIVER
 *
 * `UiSignalRpcHandlers.notifyResumeAvailable` already has a `pushedResumeThisSession`
 * dedupe keyed on userId — but the client can clear it via `consumeResumePush`,
 * and the same user reconnecting under a different accelbyteId would also
 * clear it. The push-side dedupe at `ResumeAvailabilityPushService` is keyed on
 * `savedAt` (an invariant of the snapshot itself, not a session-scoped flag),
 * so it survives client consume and accelbyteId changes.
 *
 * Both layers together: the receiver-side dedupe stops the spam if the
 * push service is buggy; the push-side dedupe stops the spam at the source.
 */
class ResumeAvailabilityPushDedupeTest
{
    private val testUserId = "test-user-dedupe-${System.nanoTime()}"
    private val testSavedAt = "2026-07-05T18:33:38.938Z"

    @After
    fun cleanup()
    {
        ResumeAvailabilityPushService.resetResumePushDedupeForTest()
    }

    /**
     * Test 1: First push for a user → shouldPush=true (cache miss).
     * The first call always pushes — there is no prior state to dedup against.
     */
    @Test
    fun `first push for a user always proceeds (cache miss)`()
    {
        val decision = ResumeAvailabilityPushService.shouldPushResumeAvailability(
            userId = testUserId,
            savedAt = testSavedAt,
            nowMs = 1_000_000L,
            cooldownMs = 300_000L
        )
        assertTrue(
            decision.shouldPush,
            "first push for a user must always proceed (no prior state to dedup against); got $decision"
        )
    }

    /**
     * Test 2: Second push with same `savedAt` within cooldown → shouldPush=false.
     * This is the core BUG 26 scenario — SSE reconnect 45s after first push,
     * same snapshot, same `savedAt`, push must be skipped.
     */
    @Test
    fun `second push with same savedAt within cooldown is skipped (BUG 26 core case)`()
    {
        // First push at t=0 — must proceed
        val firstDecision = ResumeAvailabilityPushService.shouldPushResumeAvailability(
            userId = testUserId,
            savedAt = testSavedAt,
            nowMs = 0L,
            cooldownMs = 300_000L
        )
        assertTrue(firstDecision.shouldPush, "first push must proceed")
        ResumeAvailabilityPushService.markPushed(testUserId, testSavedAt, nowMs = 0L)

        // Second push at t=45s with the same savedAt — must be skipped
        val secondDecision = ResumeAvailabilityPushService.shouldPushResumeAvailability(
            userId = testUserId,
            savedAt = testSavedAt,
            nowMs = 45_000L,
            cooldownMs = 300_000L
        )
        assertFalse(
            secondDecision.shouldPush,
            "second push with same savedAt within cooldown must be skipped; got $secondDecision"
        )
        assertEquals(
            ResumeAvailabilityPushService.PushSkipReason.SAME_SAVED_AT_WITHIN_COOLDOWN,
            secondDecision.reason,
            "skip reason must identify the BUG 26 condition for log diagnostics"
        )
    }

    /**
     * Test 3: Second push with DIFFERENT `savedAt` → shouldPush=true.
     * A new save legitimately warrants a re-push — the user actually has a
     * different saved game now.
     */
    @Test
    fun `second push with different savedAt proceeds (new save warrants re-push)`()
    {
        // First push at t=0 with savedAt=A
        val firstDecision = ResumeAvailabilityPushService.shouldPushResumeAvailability(
            userId = testUserId,
            savedAt = testSavedAt,
            nowMs = 0L,
            cooldownMs = 300_000L
        )
        assertTrue(firstDecision.shouldPush, "first push must proceed")
        ResumeAvailabilityPushService.markPushed(testUserId, testSavedAt, nowMs = 0L)

        // Second push at t=45s with a NEW savedAt (e.g. user saved a new game)
        val newSavedAt = "2026-07-05T18:50:00.000Z"
        val secondDecision = ResumeAvailabilityPushService.shouldPushResumeAvailability(
            userId = testUserId,
            savedAt = newSavedAt,
            nowMs = 45_000L,
            cooldownMs = 300_000L
        )
        assertTrue(
            secondDecision.shouldPush,
            "second push with different savedAt must proceed; got $secondDecision"
        )
    }

    /**
     * Test 4: Second push with same `savedAt` AFTER cooldown → shouldPush=true.
     * The cooldown is a backstop; if it elapses (e.g. server clock skew or a
     * very long disconnect), the push goes through. The `savedAt` check is the
     * primary signal; the cooldown is the safety net.
     */
    @Test
    fun `second push with same savedAt after cooldown proceeds (cooldown backstop)`()
    {
        // First push at t=0
        ResumeAvailabilityPushService.markPushed(testUserId, testSavedAt, nowMs = 0L)

        // Second push at t=6min (cooldown is 5min) with the same savedAt
        val secondDecision = ResumeAvailabilityPushService.shouldPushResumeAvailability(
            userId = testUserId,
            savedAt = testSavedAt,
            nowMs = 360_000L,
            cooldownMs = 300_000L
        )
        assertTrue(
            secondDecision.shouldPush,
            "second push after cooldown must proceed (cooldown backstop); got $secondDecision"
        )
    }

    /**
     * Test 5: markPushed updates the cache so subsequent same-savedAt pushes are blocked.
     * Verifies the runtime state machinery, not just the decision function.
     */
    @Test
    fun `markPushed updates the cache so subsequent same-savedAt pushes within cooldown are blocked`()
    {
        ResumeAvailabilityPushService.markPushed(testUserId, testSavedAt, nowMs = 1_000L)

        // Decision function must consult the cache
        val decision = ResumeAvailabilityPushService.shouldPushResumeAvailability(
            userId = testUserId,
            savedAt = testSavedAt,
            nowMs = 2_000L,
            cooldownMs = 300_000L
        )
        assertFalse(
            decision.shouldPush,
            "markPushed must register the push so the next same-savedAt push within cooldown is blocked"
        )
    }

    /**
     * Test 6: resetResumePushDedupeForTest clears the cache (test isolation).
     * Multiple test cases run in the same JVM; without isolation, state leaks
     * between cases and the assertions become order-dependent.
     */
    @Test
    fun `resetResumePushDedupeForTest clears the cache for test isolation`()
    {
        ResumeAvailabilityPushService.markPushed(testUserId, testSavedAt, nowMs = 0L)
        ResumeAvailabilityPushService.resetResumePushDedupeForTest()

        val decision = ResumeAvailabilityPushService.shouldPushResumeAvailability(
            userId = testUserId,
            savedAt = testSavedAt,
            nowMs = 1_000L,
            cooldownMs = 300_000L
        )
        assertTrue(
            decision.shouldPush,
            "after resetResumePushDedupeForTest, the same userId+savedAt must be treated as a first push; got $decision"
        )
    }

    /**
     * Test 7: blank userId → shouldPush=false (defensive — never push for a blank id).
     * Mirrors the existing isBlank() guard in checkAndPush at line 59.
     */
    @Test
    fun `blank userId is never pushed (defensive guard)`()
    {
        val decision = ResumeAvailabilityPushService.shouldPushResumeAvailability(
            userId = "",
            savedAt = testSavedAt,
            nowMs = 0L,
            cooldownMs = 300_000L
        )
        assertFalse(decision.shouldPush, "blank userId must never be pushed")
    }

    /**
     * Test 8: blank savedAt → dedup cannot match (savedAt is the dedup key) → push proceeds.
     * When the server's response.updatedAt is missing we fall back to `Instant.now()`
     * (see checkAndPushBlocking line 123). The now-derived savedAt is unique per
     * push, so the dedup never matches. We accept that as a known limitation
     * — the cooldown is the only backstop in this case.
     */
    @Test
    fun `push proceeds when savedAt is blank (cooldown is the only backstop)`()
    {
        // First push with blank savedAt
        val first = ResumeAvailabilityPushService.shouldPushResumeAvailability(
            userId = testUserId,
            savedAt = "",
            nowMs = 0L,
            cooldownMs = 300_000L
        )
        assertTrue(first.shouldPush, "first push with blank savedAt must proceed")

        // Second push with blank savedAt — within cooldown, the savedAt match
        // cannot fire (both are blank, but the dedup logic must treat blank
        // savedAt as "no dedup signal"). Document and pin the behavior.
        // Decision: blank savedAt disables savedAt-dedup; cooldown is the only gate.
        // For this test, we'll assert that the behavior is reasonable (push or
        // skip is acceptable, but it must be deterministic).
        // We choose: blank savedAt → no dedup → push proceeds (rely on cooldown
        // alone, which the production call site does NOT enforce because the
        // cooldown check requires a non-blank savedAt cache entry — see fix
        // design discussion).
        val second = ResumeAvailabilityPushService.shouldPushResumeAvailability(
            userId = testUserId,
            savedAt = "",
            nowMs = 1_000L,
            cooldownMs = 300_000L
        )
        // Document the chosen behavior — production cooldown will be set by
        // markPushed which only runs when savedAt is non-blank.
        // For a fully-blank-savedAt scenario, no markPushed ever runs, so
        // every push proceeds. This is the production behavior the cooldown
        // is designed to backstop.
        assertTrue(second.shouldPush, "blank savedAt means no dedup signal — push proceeds (cooldown backstop relies on savedAt being non-blank)")
    }
}
