package accelbyte.dsm

import accelbyte.dsm.DrainSignalHandler
import gameState.WorldManager
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import kotlin.test.assertTrue

class DrainSignalHandlerTest {

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        mockkObject(WorldManager)
        mockkObject(DSM)

        every { WorldManager.draining } returns false
        every { WorldManager.drained } returns false
        every { WorldManager.activeSessionCount } returns 0
        every { DSM.shutdownDedicatedServer(any()) } returns Result.success(Unit)
    }

    @Test
    fun drainSignalReceivedSetsDrainingState() = runBlocking {
        val handler = DrainSignalHandler(WorldManager, DSM)
        handler.handleDrainSignal()
        verify { WorldManager.setDraining() }
    }

    @Test
    fun drainSignalWithZeroActiveSessionsCallsShutdownImmediately() = runBlocking {
        every { WorldManager.activeSessionCount } returns 0

        val handler = DrainSignalHandler(WorldManager, DSM)
        handler.handleDrainSignal()

        verify { DSM.shutdownDedicatedServer(any()) }
    }

    @Test
    fun drainSignalWithActiveSessionsWaitsForSessionsToEndThenCallsShutdown() = runBlocking {
        every { WorldManager.activeSessionCount } returns 3

        val handler = DrainSignalHandler(WorldManager, DSM)

        launch {
            delay(50)
            every { WorldManager.activeSessionCount } returns 0
        }

        handler.handleDrainSignal()
        verify { DSM.shutdownDedicatedServer(any()) }
    }

    @Test
    fun drainedStateIsSetAfterShutdownCompletes() = runBlocking {
        every { WorldManager.activeSessionCount } returns 0

        val handler = DrainSignalHandler(WorldManager, DSM)
        handler.handleDrainSignal()

        verify { WorldManager.setDrained() }
    }

    @Test
    fun drainSignalWithFailedShutdownStillSetsDrainedState() = runBlocking {
        every { WorldManager.activeSessionCount } returns 0
        every { DSM.shutdownDedicatedServer(any()) } returns Result.failure(Exception("shutdown failed"))

        val handler = DrainSignalHandler(WorldManager, DSM)
        handler.handleDrainSignal()

        verify { WorldManager.setDrained() }
    }

    @Test
    fun multipleDrainSignalsAreIdempotent() = runBlocking {
        every { WorldManager.activeSessionCount } returns 0

        val handler = DrainSignalHandler(WorldManager, DSM)

        handler.handleDrainSignal()
        handler.handleDrainSignal()
        handler.handleDrainSignal()

        verify { WorldManager.setDraining() }
        verify { WorldManager.setDrained() }
    }
}