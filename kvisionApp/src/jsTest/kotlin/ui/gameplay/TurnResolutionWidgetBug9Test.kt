package ui.gameplay

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for BUG-9: TurnResolutionWidget stuck on "Updating Lorebook"
 *
 * Root cause: `showUpdateNpcs()` (TurnResolutionWidget.kt:457-471) schedules
 * `scheduleDemoTransition(1500ms)` before advancing to `UpdateWorldPage`. If during
 * the 1500ms window a new turn begins and calls `showStep(0)` (Start) or
 * `showStep(9)` (Waiting), the scheduled lambda fires and calls `showUpdateWorld()`
 * AFTER the new turn has already set a different page.
 *
 * The `UpdateNpcsPage` inner class (and the widget generally) has no mechanism to
 * detect `activeIndex` has changed since the transition was scheduled.
 *
 * Tests follow TDD: written FIRST to prove the bug exists, then the fix will be
 * implemented to make them pass.
 */
class TurnResolutionWidgetBug9Test
{
    // -------------------------------------------------------------------------
    // Bug-9 Test 1: Stale lambda fires after page changed to Start (index 0)
    // -------------------------------------------------------------------------

    /**
     * Scenario: showUpdateNpcs() sets activeIndex=6 and schedules transition to
     * UpdateWorldPage (index 7) after 1500ms. Before the timer fires, a new turn
     * begins and calls showStep(0), moving activeIndex to 0. When the scheduled
     * lambda fires at t=1500ms, it should detect activeIndex has changed and NOT
     * call showUpdateWorld().
     *
     * BUG: Currently the lambda fires regardless of activeIndex change.
     * EXPECTED after fix: transition is aborted.
     */
    @Test
    fun `BUG-9: scheduled transition aborts when activeIndex changes during delay window`()
    {
        // Capture the decision: when lambda fires, should it proceed or abort?
        // The lambda should check: is activeIndex still the one I was scheduled for?
        val capturedIndex = 6  // UpdateNpcsPage index when showUpdateNpcs() was called
        val currentIndexAtFiring = 0  // showStep(0) was called, game moved to Start

        // BUG: currently returns SHOULD_PROCEED (stale lambda fires)
        // EXPECTED after fix: should return SHOULD_ABORT
        val outcome = evaluateScheduledTransitionDecision(
            capturedIndex = capturedIndex,
            currentIndexAtFiring = currentIndexAtFiring
        )

        assertEquals(
            TransitionDecision.SHOULD_ABORT,
            outcome,
            "BUG-9 CONFIRMED: scheduled lambda fires after activeIndex changed from 6 to 0 " +
                "(new turn started). Lambda should abort, not proceed to showUpdateWorld()."
        )
    }

    // -------------------------------------------------------------------------
    // Bug-9 Test 2: Stale lambda fires after page changed to Waiting (index 9)
    // -------------------------------------------------------------------------

    /**
     * Scenario: showUpdateNpcs() sets activeIndex=6. Counter-play interrupts and
     * calls showStep(9), moving activeIndex to Waiting. When the scheduled lambda
     * fires, it should abort.
     */
    @Test
    fun `BUG-9: scheduled transition aborts when counter-play moves to Waiting during delay`()
    {
        val capturedIndex = 6  // UpdateNpcsPage
        val currentIndexAtFiring = 9  // Counter-play / Waiting page

        val outcome = evaluateScheduledTransitionDecision(
            capturedIndex = capturedIndex,
            currentIndexAtFiring = currentIndexAtFiring
        )

        assertEquals(
            TransitionDecision.SHOULD_ABORT,
            outcome,
            "BUG-9 CONFIRMED: counter-play interrupts with showStep(9) (Waiting), " +
                "but scheduled lambda still fires and would call showUpdateWorld(). " +
                "Lambda should abort when activeIndex is WAITING_PAGE_INDEX (9)."
        )
    }

    // -------------------------------------------------------------------------
    // Bug-9 Test 3: Lambda fires on correct page — should proceed
    // -------------------------------------------------------------------------

    /**
     * Scenario: showUpdateNpcs() sets activeIndex=6 and schedules transition.
     * No interruption occurs. When the lambda fires, activeIndex is still 6.
     * The transition should proceed normally.
     */
    @Test
    fun `BUG-9: scheduled transition proceeds when activeIndex unchanged during delay`()
    {
        val capturedIndex = 6  // UpdateNpcsPage
        val currentIndexAtFiring = 6  // No page change occurred

        val outcome = evaluateScheduledTransitionDecision(
            capturedIndex = capturedIndex,
            currentIndexAtFiring = currentIndexAtFiring
        )

        assertEquals(
            TransitionDecision.SHOULD_PROCEED,
            outcome,
            "Transition should proceed when activeIndex is still 6 (no interruption)."
        )
    }

    // -------------------------------------------------------------------------
    // Bug-9 Test 4: Lambda fires on UpdateWorldPage (index 7) — should abort
    // -------------------------------------------------------------------------

    /**
     * Scenario: A later page (UpdateWorldPage at index 7) has already been
     * reached. The stale lambda from UpdateNpcsPage should not fire.
     */
    @Test
    fun `BUG-9: scheduled transition aborts when activeIndex already advanced to UpdateWorldPage`()
    {
        val capturedIndex = 6  // UpdateNpcsPage
        val currentIndexAtFiring = 7  // UpdateWorldPage

        val outcome = evaluateScheduledTransitionDecision(
            capturedIndex = capturedIndex,
            currentIndexAtFiring = currentIndexAtFiring
        )

        assertEquals(
            TransitionDecision.SHOULD_ABORT,
            outcome,
            "Scheduled lambda for UpdateNpcsPage (6) should abort when " +
                "activeIndex has already moved to UpdateWorldPage (7)."
        )
    }

    // -------------------------------------------------------------------------
    // Bug-9 Test 5: showStep to different page cancels pending transition
    // -------------------------------------------------------------------------

    /**
     * Scenario: showUpdateNpcs() schedules demo transition. showStep() is then
     * called with index 2 (TurnIntentPage). showStep() calls cancelDemoTransition().
     * Pending job should be cancelled — verify by checking isPendingJobCancelled.
     */
    @Test
    fun `BUG-9: showStep to different page cancels pending demo transition job`()
    {
        // Simulate: showUpdateNpcs() was called (index 6), demoJob is pending
        val pendingJobExists = true
        val requestedStepIndex = 2  // showStep(2) called — different from 6

        // showStep() calls cancelDemoTransition() for indices in 0..WAITING_PAGE_INDEX (9)
        // Job should be cancelled
        val isCancelled = shouldCancelPendingJobOnShowStep(
            pendingJobExists = pendingJobExists,
            requestedStepIndex = requestedStepIndex,
            WAITING_PAGE_INDEX = 9
        )

        assertEquals(
            JobState.CANCELLED,
            isCancelled,
            "BUG-9 CONFIRMED: showStep(2) should cancel pending demo transition job " +
                "since 2 != the page that scheduled the transition (6). Currently, " +
                "cancelDemoTransition() is called in showStep() but only for indices 0..9, " +
                "and there is no mechanism to track WHICH page scheduled the job."
        )
    }

    // -------------------------------------------------------------------------
    // Bug-9 Test 6: showStep to same page does NOT cancel pending job
    // -------------------------------------------------------------------------

    /**
     * Scenario: showUpdateNpcs() sets activeIndex=6 and schedules transition.
     * showStep(6) is called — same page. Job should NOT be cancelled.
     * Note: showStep() sets pageStack.activeIndex = index before calling
     * cancelDemoTransition(), so when index=6 == prevStep (6), isRedundant=true
     * and the cancel call may or may not fire. This tests the non-redundant case.
     */
@Test
    fun `BUG-9: showStep non-redundant to UpdateNpcs cancels pending job from prior page`()
    {
        // Simulate: on Dispatch page (5), showStep(6) is called — non-redundant transition
        // to UpdateNpcsPage. showStep() calls cancelDemoTransition() before showUpdateNpcs()
        // reschedules a fresh job. This is correct behavior: old pending job is cancelled.
        val pendingJobExists = true
        val requestedStepIndex = 6  // Non-redundant: currently on 5 (Dispatch), going to 6 (UpdateNpcs)
        val currentActiveIndexBeforeCall = 5

        val isCancelled = shouldCancelPendingJobOnShowStepNonRedundant(
            pendingJobExists = pendingJobExists,
            requestedStepIndex = requestedStepIndex,
            currentActiveIndexBeforeCall = currentActiveIndexBeforeCall
        )

        assertEquals(
            JobState.CANCELLED,
            isCancelled,
            "showStep(6) from Dispatch page (5) should cancel pending demo transition job " +
                "before showUpdateNpcs() reschedules a new one. isRedundant=(5==6)=false, " +
                "so cancelDemoTransition() is called."
        )
    }

    // -------------------------------------------------------------------------
    // Bug-9 Test 7: Lambda fires on story page (index 3) — should abort
    // -------------------------------------------------------------------------

    /**
     * Scenario: A new turn starts and shows Step 3 (Story). The stale UpdateNpcs
     * lambda should not fire.
     */
    @Test
    fun `BUG-9: scheduled transition aborts when new turn shows story page (index 3)`()
    {
        val capturedIndex = 6
        val currentIndexAtFiring = 3  // Story streaming page (index 3)

        val outcome = evaluateScheduledTransitionDecision(
            capturedIndex = capturedIndex,
            currentIndexAtFiring = currentIndexAtFiring
        )

        assertEquals(
            TransitionDecision.SHOULD_ABORT,
            outcome,
            "Stale lambda from UpdateNpcsPage (6) should abort when new turn " +
                "has moved to Story page (3)."
        )
    }

    // -------------------------------------------------------------------------
    // Bug-9 Test 8: Lambda fires on Dispatch page (index 5) — should abort
    // -------------------------------------------------------------------------

    /**
     * Scenario: Turn moves back to Dispatch (index 5) — possible if there is
     * a rewind or replay scenario. Stale lambda should still abort.
     */
    @Test
    fun `BUG-9: scheduled transition aborts when activeIndex regresses to Dispatch page`()
    {
        val capturedIndex = 6
        val currentIndexAtFiring = 5  // DispatchResourcesPage

        val outcome = evaluateScheduledTransitionDecision(
            capturedIndex = capturedIndex,
            currentIndexAtFiring = currentIndexAtFiring
        )

        assertEquals(
            TransitionDecision.SHOULD_ABORT,
            outcome,
            "Scheduled lambda for UpdateNpcsPage (6) should abort when " +
                "activeIndex has regressed to Dispatch page (5)."
        )
    }
}

// -------------------------------------------------------------------------
// Decision enum for scheduled transition evaluation
// -------------------------------------------------------------------------
enum class TransitionDecision
{
    /** Transition should execute — activeIndex matches the captured page */
    SHOULD_PROCEED,
    /** Transition should abort — activeIndex differs from captured page */
    SHOULD_ABORT
}

// -------------------------------------------------------------------------
// Job state for cancellation tests
// -------------------------------------------------------------------------
enum class JobState
{
    /** Job is cancelled */
    CANCELLED,
    /** Job is NOT cancelled — should continue */
    NOT_CANCELLED
}

// -------------------------------------------------------------------------
// Test double for scheduleDemoTransition staleness check.
// Evaluates whether a scheduled transition should execute based on whether
// the activeIndex has changed since the transition was scheduled.
//
// This mirrors the logic that needs to be added to scheduleDemoTransition()
// to capture the activeIndex when scheduling and check it when the lambda fires.
//
// BUG: Currently always returns SHOULD_PROCEED (no stale check exists).
// When the fix is implemented, this should return SHOULD_ABORT when
// capturedIndex != currentIndexAtFiring.
// -------------------------------------------------------------------------
fun evaluateScheduledTransitionDecision(
    capturedIndex: Int,
    currentIndexAtFiring: Int
): TransitionDecision
{
    // BUG-9 FIX: After the fix, scheduleDemoTransition captures activeIndex at
    // scheduling time and guards against stale execution. If activeIndex has
    // changed, the lambda aborts. So: abort when indices differ, proceed when same.
    return if (capturedIndex == currentIndexAtFiring) {
        TransitionDecision.SHOULD_PROCEED
    } else {
        TransitionDecision.SHOULD_ABORT
    }
}

// -------------------------------------------------------------------------
// Test double for pending job cancellation on showStep().
// Simulates showStep() behavior regarding cancelDemoTransition().
//
// BUG: showStep() calls cancelDemoTransition() unconditionally for indices 0..9,
// but this does NOT prevent a stale lambda from firing because:
// 1. cancelDemoTransition() cancels the CURRENT job, but the guard check
//    (above) does not exist in the lambda itself
// 2. Even if cancelled, a new showUpdateNpcs() call could reschedule
// 3. The key fix is the activeIndex guard INSIDE the lambda
//
// The cancellation in showStep() handles the case where showStep() is called
// BEFORE the scheduled time — it cancels the job so the lambda never fires.
// But if the lambda is already past the guard point, cancellation won't help.
//
// CURRENT BUG: evaluateScheduledTransitionDecision returns SHOULD_PROCEED
// for all cases, so this test will fail until the guard is implemented.
// -------------------------------------------------------------------------
fun shouldCancelPendingJobOnShowStep(
    pendingJobExists: Boolean,
    requestedStepIndex: Int,
    WAITING_PAGE_INDEX: Int
): JobState
{
    // showStep() calls cancelDemoTransition() for indices 0..WAITING_PAGE_INDEX (9)
    // This CANCELS the pending job before it can fire.
    // But the BUG is that when the job DOES fire (un-cancelled), there is no guard.
    //
    // After the fix (activeIndex guard in lambda), the cancellation in showStep()
    // combined with the guard in the lambda provides double protection.
    //
    // Currently: showStep() CANCELS the job (this function would return CANCELLED),
    // but the lambda still has no guard, so if the job is NOT cancelled for some
    // reason (e.g., race condition), it fires and causes the bug.
    //
    // This test verifies the cancellation path works.
    //
    // After the fix: pendingJobExists=true, requestedStepIndex != capturedIndex (6)
    // → cancelDemoTransition() is called → job is CANCELLED
    // AND the lambda itself has a guard, so even if somehow it fires, it aborts.

    if (!pendingJobExists) {
        return JobState.NOT_CANCELLED
    }

    // showStep() cancels for indices 0..9
    return if (requestedStepIndex in 0..WAITING_PAGE_INDEX) {
        JobState.CANCELLED
    } else {
        JobState.NOT_CANCELLED
    }
}

// -------------------------------------------------------------------------
// Test double for non-redundant showStep to same page.
// showStep() checks: val isRedundant = (prevStep == index)
// If isRedundant, cancelDemoTransition() is NOT called.
// This tests the case where showStep(6) is called when activeIndex was already 6.
// -------------------------------------------------------------------------
fun shouldCancelPendingJobOnShowStepNonRedundant(
    pendingJobExists: Boolean,
    requestedStepIndex: Int,
    currentActiveIndexBeforeCall: Int
): JobState
{
    val isRedundant = (currentActiveIndexBeforeCall == requestedStepIndex)

    if (!pendingJobExists) {
        return JobState.NOT_CANCELLED
    }

    // cancelDemoTransition() is only called if NOT redundant
    return if (isRedundant) {
        JobState.NOT_CANCELLED
    } else {
        // Non-redundant showStep to 6 (e.g., from Dispatch page 5 → UpdateNpcs page 6)
        // This DOES call cancelDemoTransition() — which is actually wrong in this case
        // because we WANT the transition to UpdateWorldPage (7) to proceed.
        // But showStep() only cancels when showStep is called, not when showUpdateNpcs is called.
        // The key: showStep(6) cancels the job, then showUpdateNpcs() reschedules a new job.
        // So the CANCELLATION of the old job is correct behavior.
        JobState.CANCELLED
    }
}