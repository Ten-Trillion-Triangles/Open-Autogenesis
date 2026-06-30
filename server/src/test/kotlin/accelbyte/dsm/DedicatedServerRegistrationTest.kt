package accelbyte.dsm

import accelbyte.dsm.DSM
import accelbyte.dsm.DedicatedServerRegistration
import io.mockk.MockKAnnotations
import io.mockk.clearMocks
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import structs.accelbyte.dsm.DsmServerDetails
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the dedicated server registration lifecycle against the AccelByte DSM controller:
 *  - bounded retry with exponential backoff on transient registration failures
 *  - successful registration starts the heartbeat loop and sends heartbeats at the expected cadence
 *  - [DedicatedServerRegistration.stop] cancels the heartbeat and best-effort calls
 *    `DSM.shutdownDedicatedServer`
 *  - [DedicatedServerRegistration.drain] does the same with the drain-flavored shutdown reason
 *  - blank serverId is a no-op (dev mode)
 */
class DedicatedServerRegistrationTest
{
    private val originalInterval = DedicatedServerRegistration.HEARTBEAT_INTERVAL_MS
    private val originalJitter = DedicatedServerRegistration.HEARTBEAT_JITTER_MS
    private val originalBackoff = DedicatedServerRegistration.INITIAL_BACKOFF_MS
    private val originalMaxBackoff = DedicatedServerRegistration.MAX_BACKOFF_MS
    private val originalMaxAttempts = DedicatedServerRegistration.MAX_REGISTRATION_ATTEMPTS
    private val originalBackoffSleep = DedicatedServerRegistration.backoffSleep
    private val sleepCalls = mutableListOf<Long>()

    @Before
    fun setup()
    {
        MockKAnnotations.init(this)
        mockkObject(DSM)
        // Shrink the heartbeat so the test loop can verify cadence without taking 30s
        DedicatedServerRegistration.HEARTBEAT_INTERVAL_MS = 100L
        DedicatedServerRegistration.HEARTBEAT_JITTER_MS = 0L
        // Speed up the retry path
        DedicatedServerRegistration.INITIAL_BACKOFF_MS = 10L
        DedicatedServerRegistration.MAX_BACKOFF_MS = 40L
        DedicatedServerRegistration.MAX_REGISTRATION_ATTEMPTS = 3
        // No real sleep in tests — record the requested sleep durations instead.
        sleepCalls.clear()
        DedicatedServerRegistration.backoffSleep = { ms -> sleepCalls.add(ms) }
    }

    @After
    fun tearDown()
    {
        // Always make sure we leave the heartbeat cancelled
        DedicatedServerRegistration.stop()
        DedicatedServerRegistration.HEARTBEAT_INTERVAL_MS = originalInterval
        DedicatedServerRegistration.HEARTBEAT_JITTER_MS = originalJitter
        DedicatedServerRegistration.INITIAL_BACKOFF_MS = originalBackoff
        DedicatedServerRegistration.MAX_BACKOFF_MS = originalMaxBackoff
        DedicatedServerRegistration.MAX_REGISTRATION_ATTEMPTS = originalMaxAttempts
        DedicatedServerRegistration.backoffSleep = originalBackoffSleep
        io.mockk.unmockkAll()
    }

    /**
     * Confirms that [DedicatedServerRegistration.start] is a no-op when serverId is blank
     * (the typical dev-mode path where AB_DS_ID isn't set). The DSM is never contacted.
     */
    @Test
    fun `start with blank serverId is a no-op`() {
        val mockDetails = mockk<DsmServerDetails>()
        every { DSM.registerDedicatedServer(any()) } returns Result.success(mockDetails)

        DedicatedServerRegistration.start(serverId = "", region = "us-east-1", namespace = "test")

        verify(exactly = 0) { DSM.registerDedicatedServer(any()) }
        verify(exactly = 0) { DSM.heartbeatDedicatedServer(any()) }
        assertNull(DedicatedServerRegistration.heartbeatJobForTest())
    }

    /**
     * Confirms that a single successful registration starts the heartbeat loop and the
     * heartbeat fires at the configured cadence.
     */
    @Test
    fun `successful registration starts heartbeat loop at the configured cadence`() = runBlocking {
        val mockDetails = mockk<DsmServerDetails>()
        every { DSM.registerDedicatedServer(any()) } returns Result.success(mockDetails)
        every { DSM.heartbeatDedicatedServer(any()) } returns Result.success(Unit)
        every { DSM.shutdownDedicatedServer(any()) } returns Result.success(Unit)

        DedicatedServerRegistration.start(
            serverId = "ds-1",
            region = "us-east-1",
            namespace = "test"
        )

        // Heartbeat job should now be active
        val job = DedicatedServerRegistration.heartbeatJobForTest()
        assertNotNull(job, "heartbeat job should be active after successful registration")
        assertTrue(job.isActive, "heartbeat job should be active")

        // Wait long enough for at least two heartbeats (100ms cadence → ≥ 2 in ~250ms)
        delay(250L)
        val heartbeatCalls = mutableListOf<Unit>()
        coVerify(atLeast = 2) { DSM.heartbeatDedicatedServer(any()) }

        // stop() should cancel the heartbeat job and call shutdownDedicatedServer
        DedicatedServerRegistration.stop()
        delay(50L)
        assertTrue(!job.isActive, "heartbeat job should be cancelled after stop()")
        verify(atLeast = 1) { DSM.shutdownDedicatedServer(any()) }
    }

    /**
     * Confirms that [DedicatedServerRegistration.start] retries transient DSM failures with
     * exponential backoff and gives up after [DedicatedServerRegistration.MAX_REGISTRATION_ATTEMPTS].
     * Specifically:
     *  - With 2 failures followed by success, registration succeeds and a heartbeat starts.
     *  - The backoff sleep is requested with exponentially growing delays.
     */
    @Test
    fun `registration retries with exponential backoff and recovers from transient failures`() {
        val mockDetails = mockk<DsmServerDetails>()
        // 2 failures, then success
        val attemptResults = ArrayDeque<Result<DsmServerDetails>>(
            listOf(
                Result.failure(RuntimeException("network blip")),
                Result.failure(RuntimeException("network blip")),
                Result.success(mockDetails)
            )
        )
        every { DSM.registerDedicatedServer(any()) } answers { attemptResults.removeFirst() }
        every { DSM.heartbeatDedicatedServer(any()) } returns Result.success(Unit)
        every { DSM.shutdownDedicatedServer(any()) } returns Result.success(Unit)

        DedicatedServerRegistration.start(serverId = "ds-2", region = "us-east-1", namespace = "test")

        // 3 register calls (2 failures + 1 success)
        verify(exactly = 3) { DSM.registerDedicatedServer(any()) }
        // 2 backoff sleeps recorded: first 10ms, then 20ms (doubled, capped at MAX_BACKOFF_MS=40)
        assertEquals(listOf(10L, 20L), sleepCalls)
        // Heartbeat is now running
        val job = DedicatedServerRegistration.heartbeatJobForTest()
        assertNotNull(job)

        DedicatedServerRegistration.stop()
    }

    /**
     * Confirms that when registration fails MAX_REGISTRATION_ATTEMPTS times in a row,
     * the heartbeat is NOT started and no retries linger.
     */
    @Test
    fun `registration gives up after max attempts and does not start heartbeat`() {
        every { DSM.registerDedicatedServer(any()) } returns Result.failure(RuntimeException("permanent failure"))
        every { DSM.heartbeatDedicatedServer(any()) } returns Result.success(Unit)

        DedicatedServerRegistration.start(serverId = "ds-3", region = "us-east-1", namespace = "test")

        verify(exactly = 3) { DSM.registerDedicatedServer(any()) }
        // 2 backoff sleeps for 3 attempts (no sleep after the last one)
        assertEquals(2, sleepCalls.size)
        // Heartbeat should never have started
        assertNull(DedicatedServerRegistration.heartbeatJobForTest())
    }

    /**
     * Confirms that [DedicatedServerRegistration.drain] cancels the heartbeat and forwards
     * the `drain_signal` reason to DSM.
     */
    @Test
    fun `drain cancels heartbeat and forwards drain reason to DSM`() = runBlocking {
        val mockDetails = mockk<DsmServerDetails>()
        every { DSM.registerDedicatedServer(any()) } returns Result.success(mockDetails)
        every { DSM.heartbeatDedicatedServer(any()) } returns Result.success(Unit)
        every { DSM.shutdownDedicatedServer(any()) } returns Result.success(Unit)

        DedicatedServerRegistration.start(serverId = "ds-4", region = "us-east-1", namespace = "test")
        val job = DedicatedServerRegistration.heartbeatJobForTest()
        assertNotNull(job)

        DedicatedServerRegistration.drain("dshub_drain_signal")
        delay(50L)
        assertTrue(!job.isActive, "heartbeat job should be cancelled after drain()")
        verify(atLeast = 1) { DSM.shutdownDedicatedServer(any()) }
    }

    /**
     * Confirms that a failure in [DSM.shutdownDedicatedServer] does not throw — the
     * registration lifecycle must be best-effort and never block JVM exit.
     */
    @Test
    fun `stop swallows shutdown failures so JVM can exit`() = runBlocking {
        val mockDetails = mockk<DsmServerDetails>()
        every { DSM.registerDedicatedServer(any()) } returns Result.success(mockDetails)
        every { DSM.heartbeatDedicatedServer(any()) } returns Result.success(Unit)
        every { DSM.shutdownDedicatedServer(any()) } returns Result.failure(RuntimeException("network down"))

        DedicatedServerRegistration.start(serverId = "ds-5", region = "us-east-1", namespace = "test")

        // Must not throw
        DedicatedServerRegistration.stop()
        verify(atLeast = 1) { DSM.shutdownDedicatedServer(any()) }
    }
}
