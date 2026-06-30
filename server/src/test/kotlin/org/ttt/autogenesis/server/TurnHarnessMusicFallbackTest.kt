package org.ttt.autogenesis.server

import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.audio.AudioChannelIds
import org.ttt.autogenesis.audio.AudioObject
import org.ttt.autogenesis.audio.MusicCategory
import org.ttt.autogenesis.audio.MusicDecision
import org.ttt.autogenesis.network.RpcMessage
import gameState.WorldManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import structs.audio.AudioTracks
import structs.World

/**
 * Tests for the 5-minute music-reroll fallback timer in
 * [TurnHarness]. Pinned behaviours:
 *  1. [TurnHarness.scheduleMusicRerollFallback] arms a [kotlinx.coroutines.Job]
 *     that fires after the requested delay and calls
 *     [TurnHarness.selectAndBroadcastMusicReroll].
 *  2. Re-arming cancels the prior job (no overlapping timers).
 *  3. [TurnHarness.resetState] cancels the active job.
 *  4. A successful [TurnHarness.selectAndBroadcastMusicReroll] re-arms the
 *     timer (refresh-on-every-switch contract).
 *  5. [TurnHarness.selectAndBroadcastMusicReroll] is a no-op — and does
 *     NOT re-arm the timer — when the previous decision was scenario-
 *     bound (rule 1 / 2 / 3).
 *  6. The fallback's body short-circuits cleanly when
 *     `currentTurnMusicDecision` is null (the "no previous" path).
 *
 * The tests use a 50–150 ms `delayMs` so the suite stays fast; the
 * production constant is 5 minutes. The override goes through the
 * `delayMs` parameter of [TurnHarness.scheduleMusicRerollFallback],
 * which is a public (within the module) test seam.
 */
class TurnHarnessMusicFallbackTest
{
    private lateinit var mockConnectionManager: PlayerConnectionManager
    private var savedConnectionManager: PlayerConnectionManager? = null

    @Before
    fun setup() = runBlocking {
        // WorldManager cleanup mirrors TurnHarnessTest's @BeforeTest:
        // we want a fresh world with a populated AudioTracks payload so
        // MusicSelector rule-4 has a non-empty pool to pick from.
        WorldManager.world = World()
        WorldManager.world.audioTracks = makePopulatedAudioTracks()
        WorldManager.history.clear()
        WorldManager.actionHistoryLog.clear()
        WorldManager.pendingActionHistoryByTurn.clear()
        WorldManager.playerStats.clear()
        WorldManager.isGameActive = false
        WorldManager.isSinglePlayer = false
        WorldManager.humanPlayerName = ""
        TurnHarness.resetState()

        // Wire UiSignalRpcHandlers to a mock so
        // AudioManager.broadcastMusicSchedule's GlobalScope launch has
        // something to call. The handlers re-read the field on every
        // invocation, so this swap is safe.
        savedConnectionManager = UiSignalRpcHandlers.connectionManager
        mockConnectionManager = mockk(relaxed = true)
        UiSignalRpcHandlers.connectionManager = mockConnectionManager
    }

    @After
    fun teardown() = runBlocking {
        // Defensive: cancel any in-flight fallback so a slow test does
        // not leak into the next class.
        TurnHarness.getMusicRerollFallbackJob()?.cancel()
        TurnHarness.resetState()
        UiSignalRpcHandlers.connectionManager = savedConnectionManager
    }

    // ─── 1. Fallback fires after the configured delay ─────────────────

    @Test
    fun `scheduleMusicRerollFallback arms a job that fires after the delay and invokes the reroll`()
    {
        // Seed a rule-4-eligible previous decision so the timer's body
        // (which calls selectAndBroadcastMusicReroll) actually produces
        // a fresh broadcast instead of short-circuiting.
        val previous = makeRule4Decision("prev-batch-1")
        TurnHarness.setCurrentTurnMusicDecisionForTest(previous)
        val slot = slot<RpcMessage>()

        TurnHarness.scheduleMusicRerollFallback(delayMs = 80L)
        val job = TurnHarness.getMusicRerollFallbackJob()
        assertNotNull(job, "scheduleMusicRerollFallback must arm a job")
        assertTrue(job.isActive, "newly armed job must be active")

        // Wait long enough for delay(80) to elapse, then a small buffer
        // for the reroll + broadcast to propagate. 250ms is plenty on
        // any reasonable CI host.
        runBlocking { delay(250L) }

        // Job completed naturally (not cancelled).
        assertFalse(job.isActive, "job must have completed after the delay")
        assertTrue(job.isCompleted, "job.isCompleted must be true after the delay")

        // The reroll broadcast was sent (initial broadcast from
        // setCurrentTurnMusicDecisionForTest didn't go through
        // connectionManager, so this is the first and only broadcast).
        coVerify(timeout = 2000) { mockConnectionManager.broadcast(capture(slot)) }
        val notification = slot.captured as RpcMessage.Notification
        assertEquals("audio.musicSchedule", notification.method)

        // The reroll must have replaced the previous decision.
        val newDecision = TurnHarness.getCurrentTurnMusicDecision()
        assertNotNull(newDecision, "reroll must have written a new currentTurnMusicDecision")
        assertEquals(4, newDecision.toPlay.size, "rule-4 picks one of each of drone/melody/rhythm/harmony")
        assertEquals(previous.toPlay.map { it.id }, newDecision.toFadeOut, "previous toPlay ids must be in toFadeOut")
    }

    // ─── 2. Re-arming cancels the prior job ──────────────────────────

    @Test
    fun `scheduleMusicRerollFallback cancels the prior job when re-armed`()
    {
        TurnHarness.scheduleMusicRerollFallback(delayMs = 10_000L)
        val first = TurnHarness.getMusicRerollFallbackJob()
        assertNotNull(first, "first arm must set a job")
        assertTrue(first.isActive)

        TurnHarness.scheduleMusicRerollFallback(delayMs = 10_000L)
        val second = TurnHarness.getMusicRerollFallbackJob()
        assertNotNull(second, "second arm must set a job")
        assertTrue(second.isActive)
        assertFalse(first === second, "re-arm must produce a different Job instance")
        assertTrue(first.isCancelled, "first job must be cancelled when the second arm lands")
    }

    // ─── 3. resetState cancels the active fallback job ───────────────

    @Test
    fun `resetState cancels the active music reroll fallback job`()
    {
        TurnHarness.scheduleMusicRerollFallback(delayMs = 10_000L)
        val job = TurnHarness.getMusicRerollFallbackJob()
        assertNotNull(job, "job must be armed before resetState")
        assertTrue(job.isActive)

        runBlocking { TurnHarness.resetState() }

        assertTrue(job.isCancelled, "job must be cancelled by resetState")
        assertNull(TurnHarness.getMusicRerollFallbackJob(), "field must be nulled after resetState")
    }

    // ─── 4. Successful judge-reroll re-arms the timer ────────────────

    @Test
    fun `selectAndBroadcastMusicReroll re-arms the fallback timer on a rule-4 previous`()
    {
        // Seed a rule-4 previous so the reroll is allowed to fire.
        TurnHarness.setCurrentTurnMusicDecisionForTest(makeRule4Decision("prev-r4"))

        TurnHarness.selectAndBroadcastMusicReroll()

        val job = TurnHarness.getMusicRerollFallbackJob()
        assertNotNull(job, "a successful reroll must re-arm the fallback timer")
        assertTrue(job.isActive, "re-armed job must be active")
        // Defensive: cancel so the 5-minute timer doesn't outlive the
        // test. We do NOT await completion.
        job.cancel()
    }

    // ─── 5. Scenario-bound previous: reroll is a no-op, timer is NOT armed

    @Test
    fun `selectAndBroadcastMusicReroll does not re-arm the fallback on a scenario-bound previous`()
    {
        // Build a decision whose every track is in the
        // InitialConditions / Nemesis / TerminalConditions bucket. The
        // selector's reselectRandomLayers guard returns null for these,
        // so the reroll short-circuits and MUST NOT arm a new timer.
        val scenarioBound = MusicDecision(
            toPlay = listOf(
                makeAudioObject("ic-1", "Initial Conditions wet 1", MusicCategory.InitialConditions)
            ),
            toFadeOut = emptyList()
        )
        TurnHarness.setCurrentTurnMusicDecisionForTest(scenarioBound)

        TurnHarness.selectAndBroadcastMusicReroll()

        assertNull(
            TurnHarness.getMusicRerollFallbackJob(),
            "scenario-bound reroll must NOT arm the fallback timer (endgame guard)"
        )
        // The current decision is unchanged — the no-op path must not
        // have overwritten the seeded one.
        assertEquals(scenarioBound, TurnHarness.getCurrentTurnMusicDecision())
    }

    // ─── 6. Fallback body is a no-op when there is no previous decision

    @Test
    fun `scheduleMusicRerollFallback body is a no-op when currentTurnMusicDecision is null`()
    {
        // No previous decision seeded: the timer's body will call
        // selectAndBroadcastMusicReroll, which short-circuits at the
        // "previous == null" guard and never broadcasts.
        TurnHarness.scheduleMusicRerollFallback(delayMs = 50L)
        val job = TurnHarness.getMusicRerollFallbackJob()
        assertNotNull(job)
        runBlocking { delay(200L) }

        assertTrue(job.isCompleted, "job must complete even when the body no-ops")
        coVerify(timeout = 2000, exactly = 0) {
            mockConnectionManager.broadcast(any())
        }
    }

    // ─── Test fixtures ───────────────────────────────────────────────

    /**
     * Build a minimal AudioTracks payload with one entry in each of
     * drone / melody / rhythm / harmony. The default `World().audioTracks`
     * is empty, which would cause rule 4 to return an empty
     * toPlay and the reroll to silently drop. Seeding four layers
     * keeps rule 4 non-empty and the reroll path observable.
     */
    private fun makePopulatedAudioTracks(): AudioTracks
    {
        return AudioTracks(
            drone = mutableListOf(makeAudioObject("d1", "D-Track 1", MusicCategory.Drone)),
            melody = mutableListOf(makeAudioObject("m1", "Melody-Etnahta", MusicCategory.Melody)),
            rhythm = mutableListOf(makeAudioObject("r1", "R-Track 1", MusicCategory.Rhythm)),
            harmony = mutableListOf(makeAudioObject("h1", "Harmony-1", MusicCategory.Harmony))
        )
    }

    /**
     * Build a rule-4 [MusicDecision] with one track in each of the
     * four layered categories. Used as a "previous" the reroll can
     * legally re-roll.
     */
    private fun makeRule4Decision(trackIdPrefix: String): MusicDecision
    {
        return MusicDecision(
            toPlay = listOf(
                makeAudioObject("${trackIdPrefix}-d", "D-Track 1", MusicCategory.Drone),
                makeAudioObject("${trackIdPrefix}-m", "Melody-Etnahta", MusicCategory.Melody),
                makeAudioObject("${trackIdPrefix}-r", "R-Track 1", MusicCategory.Rhythm),
                makeAudioObject("${trackIdPrefix}-h", "Harmony-1", MusicCategory.Harmony)
            ),
            toFadeOut = emptyList()
        )
    }

    /**
     * Build an [AudioObject] suitable for the catalog. The
     * `audioObject` field on the [MusicTrack] is null in tests, so
     * the selector's `toAudioObject` extension falls back to a generic
     * Music-channel object with `loop = true` and `fadeInDurationMs = 2000`.
     */
    private fun makeAudioObject(id: String, resourceName: String, @Suppress("UNUSED_PARAMETER") category: MusicCategory): AudioObject
    {
        return AudioObject(
            id = id,
            resourceName = resourceName,
            channelId = AudioChannelIds.MUSIC_MASTER_ID,
            volume = 1.0f,
            loop = true,
            fadeInDurationMs = 2000L
        )
    }
}
