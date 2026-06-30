package matchmaking

import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies the [MatchPoolSpec] invariants and the [MatchmakingLadder]
 * promotion order. The ladder is the source of truth for the pool
 * layout — both the promotion timer and the pool bootstrap read from it.
 */
class MatchmakingPolicyTest
{
    @After
    fun restoreLadder()
    {
        ServerConnector.ladder = MatchmakingLadder.DEFAULT
    }

    @Test
    fun defaultLadderIsFourThreeTwo()
    {
        val ladder = MatchmakingLadder.DEFAULT
        assertEquals(3, ladder.tiers.size)
        assertEquals("pvp-4", ladder.tiers[0].name)
        assertEquals(4, ladder.tiers[0].targetPlayers)
        assertEquals("pvp-3", ladder.tiers[1].name)
        assertEquals(3, ladder.tiers[1].targetPlayers)
        assertEquals("pvp-2", ladder.tiers[2].name)
        assertEquals(2, ladder.tiers[2].targetPlayers)
    }

    @Test
    fun entryTierIsAlwaysTheFirst()
    {
        val ladder = MatchmakingLadder.DEFAULT
        assertEquals(ladder.tiers.first(), ladder.entry)
    }

    @Test
    fun nextAfterWalksTheTiers()
    {
        val ladder = MatchmakingLadder.DEFAULT
        val first = ladder.byName("pvp-4")!!
        val second = ladder.byName("pvp-3")!!
        val third = ladder.byName("pvp-2")!!
        assertEquals(second, ladder.nextAfter(first))
        assertEquals(third, ladder.nextAfter(second))
        assertNull(ladder.nextAfter(third), "the final tier has no successor")
    }

    @Test
    fun byNameReturnsNullForUnknown()
    {
        val ladder = MatchmakingLadder.DEFAULT
        assertNull(ladder.byName("does-not-exist"))
        assertNotNull(ladder.byName("pvp-4"))
    }

    @Test
    fun duplicatePoolNamesAreRejected()
    {
        val spec = MatchPoolSpec("dup", targetPlayers = 4, holdSeconds = 60, sessionTemplateName = "t")
        assertFailsWith<IllegalArgumentException> {
            MatchmakingLadder(listOf(spec, spec))
        }
    }

    @Test
    fun emptyLadderIsRejected()
    {
        assertFailsWith<IllegalArgumentException> {
            MatchmakingLadder(emptyList())
        }
    }

    @Test
    fun invalidTargetPlayersIsRejected()
    {
        assertFailsWith<IllegalArgumentException> {
            MatchPoolSpec("x", targetPlayers = 0, holdSeconds = 60, sessionTemplateName = "t")
        }
        assertFailsWith<IllegalArgumentException> {
            MatchPoolSpec("x", targetPlayers = 99, holdSeconds = 60, sessionTemplateName = "t")
        }
    }

    @Test
    fun zeroHoldSecondsIsRejected()
    {
        assertFailsWith<IllegalArgumentException> {
            MatchPoolSpec("x", targetPlayers = 4, holdSeconds = 0, sessionTemplateName = "t")
        }
    }

    @Test
    fun serverConnectorUsesDefaultLadder()
    {
        // ServerConnector.ladder is a singleton initialized to DEFAULT; ensure
        // the wiring is intact after test runs.
        assertSame(MatchmakingLadder.DEFAULT, ServerConnector.ladder)
        assertTrue(ServerConnector.ladder.tiers.any { it.name == "pvp-4" })
    }
}
