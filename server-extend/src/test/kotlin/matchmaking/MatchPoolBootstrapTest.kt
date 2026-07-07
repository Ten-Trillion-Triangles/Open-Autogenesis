package matchmaking

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import net.accelbyte.sdk.api.match2.models.ApiMatchFunctionOverride
import net.accelbyte.sdk.api.match2.models.ApiMatchPool
import net.accelbyte.sdk.api.match2.models.ApiMatchPoolConfig
import net.accelbyte.sdk.api.match2.operations.match_pools.CreateMatchPool
import net.accelbyte.sdk.api.match2.operations.match_pools.MatchPoolDetails
import net.accelbyte.sdk.api.match2.operations.match_pools.UpdateMatchPool
import net.accelbyte.sdk.api.match2.wrappers.MatchPools
import net.accelbyte.sdk.core.HttpResponseException
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Phase 7 of feature/live-pvp-and-billing. Mocks the [MatchPools] SDK wrapper
 * and drives [MatchPoolBootstrap.reconcileLadder] with a stub ladder.
 *
 * Test cases:
 *  1. AB_NAMESPACE blank                       -> no SDK call
 *  2. Pool does not exist (SDK 404)            -> createMatchPool called once
 *  3. Pool exists, fully aligned               -> updateMatchPool NOT called
 *  4. Pool exists, ticketExpiration drift      -> updateMatchPool called
 *  5. Pool exists, matchFunction drift         -> updateMatchPool called
 *  6. SDK throws non-404                       -> logged, swallowed, no calls
 */
class MatchPoolBootstrapTest
{
    private val ladder: MatchmakingLadder = MatchmakingLadder(
        listOf(
            MatchPoolSpec(
                name = "pvp-4",
                targetPlayers = 4,
                holdSeconds = 60,
                sessionTemplateName = "pvp-4p-session"
            )
        )
    )
    private val namespace: String = "test-ns"
    private lateinit var matchPools: MatchPools

    @Before
    fun setUp()
    {
        matchPools = mockk(relaxed = true)
    }

    private fun stubMissing(): MatchPoolDetails
    {
        // 404 from the platform — the bootstrap treats this as "pool does not exist"
        val op = MatchPoolDetails.builder().namespace(namespace).pool("pvp-4").build()
        every { matchPools.matchPoolDetails(any()) } throws HttpResponseException(404, "not found")
        return op
    }

    private fun stubExisting(
        ticketExpirationSeconds: Int = 60,
        sessionTemplate: String = "pvp-4p-session",
        matchFunction: String = "autogenesis-matchmaker"
    ): MatchPoolDetails
    {
        val op = MatchPoolDetails.builder().namespace(namespace).pool("pvp-4").build()
        val existing = ApiMatchPool()
        existing.name = "pvp-4"
        existing.ticketExpirationSeconds = ticketExpirationSeconds
        existing.sessionTemplate = sessionTemplate
        existing.matchFunction = matchFunction
        every { matchPools.matchPoolDetails(any()) } returns existing
        return op
    }

    @Test
    fun blankNamespaceIsNoOp() {
        // No SDK stubbing: if the bootstrap were to call into MatchPools, mockk
        // would raise a "not stubbed" exception. The blank namespace branch
        // returns before the first SDK call.
        MatchPoolBootstrap.reconcileLadder(matchPools, namespace = "", ladder = ladder)
        coVerify(exactly = 0) { matchPools.matchPoolDetails(any()) }
        coVerify(exactly = 0) { matchPools.createMatchPool(any()) }
        coVerify(exactly = 0) { matchPools.updateMatchPool(any()) }
    }

    @Test
    fun missingPoolTriggersCreate() {
        stubMissing()
        MatchPoolBootstrap.reconcileLadder(matchPools, namespace, ladder)
        coVerify(exactly = 1) { matchPools.createMatchPool(any()) }
        coVerify(exactly = 0) { matchPools.updateMatchPool(any()) }
    }

    @Test
    fun alignedPoolIsNotUpdated() {
        stubExisting()
        MatchPoolBootstrap.reconcileLadder(matchPools, namespace, ladder)
        coVerify(exactly = 0) { matchPools.createMatchPool(any()) }
        coVerify(exactly = 0) { matchPools.updateMatchPool(any()) }
    }

    @Test
    fun ticketExpirationDriftTriggersUpdate() {
        stubExisting(ticketExpirationSeconds = 30) // spec wants 60
        MatchPoolBootstrap.reconcileLadder(matchPools, namespace, ladder)
        coVerify(exactly = 0) { matchPools.createMatchPool(any()) }
        coVerify(exactly = 1) { matchPools.updateMatchPool(any()) }
    }

    @Test
    fun matchFunctionDriftTriggersUpdate() {
        stubExisting(matchFunction = "platform") // spec wants "custom"
        MatchPoolBootstrap.reconcileLadder(matchPools, namespace, ladder)
        coVerify(exactly = 0) { matchPools.createMatchPool(any()) }
        coVerify(exactly = 1) { matchPools.updateMatchPool(any()) }
    }

    @Test
    fun non404ErrorIsSwallowed() {
        val op = MatchPoolDetails.builder().namespace(namespace).pool("pvp-4").build()
        every { matchPools.matchPoolDetails(any()) } throws HttpResponseException(500, "platform error")
        // Must not throw; SDK errors are logged WARN and swallowed.
        MatchPoolBootstrap.reconcileLadder(matchPools, namespace, ladder)
        coVerify(exactly = 0) { matchPools.createMatchPool(any()) }
        coVerify(exactly = 0) { matchPools.updateMatchPool(any()) }
    }

    @Test
    fun createPoolBodyHasAllSpecFields() {
        stubMissing()
        val capturedOp = slot<CreateMatchPool>()
        every { matchPools.createMatchPool(capture(capturedOp)) } returns mockk(relaxed = true)

        MatchPoolBootstrap.reconcileLadder(matchPools, namespace, ladder)

        val body = capturedOp.captured.body
        assertEquals("pvp-4", body.name)
        assertEquals(60, body.ticketExpirationSeconds)
        assertEquals("pvp-4p-session", body.sessionTemplate)
        assertEquals("autogenesis-matchmaker", body.matchFunction)
        // The matchFunctionOverride routes the make_matches RPC to the
        // custom matchmaker gRPC endpoint. The value MUST equal the match
        // function name registered with match2 (see
        // `root build.gradle.kts: matchFunctionName = "autogenesis-matchmaker"`),
        // otherwise match2 has no function to route tickets to.
        assertNotNull(body.matchFunctionOverride)
        assertEquals("autogenesis-matchmaker", body.matchFunctionOverride.makeMatches)
    }

    // -------------------------------------------------------------------
    // TDD RED step for Block-2: the pool's match_function value MUST equal
    // the match function name registered with match2 (root build.gradle.kts
    // `matchFunctionName = "autogenesis-matchmaker"`). The legacy value
    // `"custom"` was a placeholder; if the pool carries "custom" but match2
    // has registered "autogenesis-matchmaker", no matches will route.
    // -------------------------------------------------------------------

    @Test
    fun createPoolMatchFunctionEqualsRegisteredMatch2Name() {
        stubMissing()
        val capturedOp = slot<CreateMatchPool>()
        every { matchPools.createMatchPool(capture(capturedOp)) } returns mockk(relaxed = true)

        MatchPoolBootstrap.reconcileLadder(matchPools, namespace, ladder)

        val body = capturedOp.captured.body
        assertEquals(
            "autogenesis-matchmaker",
            body.matchFunction,
            "Pool matchFunction must equal the match2-registered function name (build.gradle.kts:175)"
        )
        assertEquals(
            "autogenesis-matchmaker",
            body.matchFunctionOverride.makeMatches,
            "Pool matchFunctionOverride.makeMatches must equal the match2-registered function name"
        )
    }

    @Test
    fun existingPoolDriftDetectsLegacyCustomMatchFunctionValue() {
        // Operator's existing pool on the platform has the legacy "custom"
        // value. The bootstrap MUST detect the drift and rewrite it to
        // "autogenesis-matchmaker" via updateMatchPool.
        stubExisting(matchFunction = "custom")
        MatchPoolBootstrap.reconcileLadder(matchPools, namespace, ladder)
        coVerify(exactly = 0) { matchPools.createMatchPool(any()) }
        coVerify(exactly = 1) { matchPools.updateMatchPool(any()) }
    }

    @Test
    fun updateMatchPoolBodyUsesRegisteredMatch2Name() {
        stubExisting(matchFunction = "custom") // drift
        val capturedOp = slot<UpdateMatchPool>()
        every { matchPools.updateMatchPool(capture(capturedOp)) } returns mockk(relaxed = true)

        MatchPoolBootstrap.reconcileLadder(matchPools, namespace, ladder)

        val config = capturedOp.captured.body
        assertEquals(
            "autogenesis-matchmaker",
            config.matchFunction,
            "Updated pool matchFunction must equal the match2-registered function name"
        )
        assertEquals(
            "autogenesis-matchmaker",
            config.matchFunctionOverride.makeMatches,
            "Updated pool matchFunctionOverride.makeMatches must equal the match2-registered function name"
        )
    }
}
