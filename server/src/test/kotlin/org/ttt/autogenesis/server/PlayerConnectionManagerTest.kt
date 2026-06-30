package org.ttt.autogenesis.server

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Lightweight tests for [PlayerConnectionManager.register] confirming the
 * accelbyteId plumbing is wired through to [PlayerSession] (BUG #1 fix).
 *
 * The WebSocket session is mocked — the manager stores it on the
 * [PlayerSession] but never invokes it during [register]/[deregister],
 * so a stub is sufficient.
 */
class PlayerConnectionManagerTest
{
    @Test
    fun `register stores accelbyteId on the PlayerSession when provided`()
    {
        runBlocking {
            val manager = PlayerConnectionManager()
            val stubSession = mockk<DefaultWebSocketServerSession>(relaxed = true)
            val playerId = "test-conn-acc-${System.nanoTime()}"
            val accelbyteId = "test-acc-user-${System.nanoTime()}"

            val registration = manager.register(
                playerId = playerId,
                session = stubSession,
                role = SessionRole.PRIMARY,
                accelbyteId = accelbyteId
            )

            assertEquals(
                accelbyteId,
                registration.session.accelbyteId,
                "register() should propagate the accelbyteId to PlayerSession.accelbyteId"
            )
            manager.deregister(registration.session)
        }
    }

    @Test
    fun `register defaults accelbyteId to empty string when omitted`()
    {
        runBlocking {
            val manager = PlayerConnectionManager()
            val stubSession = mockk<DefaultWebSocketServerSession>(relaxed = true)
            val playerId = "test-conn-noacc-${System.nanoTime()}"

            val registration = manager.register(
                playerId = playerId,
                session = stubSession,
                role = SessionRole.PRIMARY
            )

            assertEquals(
                "",
                registration.session.accelbyteId,
                "register() should default accelbyteId to the empty string when omitted"
            )
            manager.deregister(registration.session)
        }
    }
}
