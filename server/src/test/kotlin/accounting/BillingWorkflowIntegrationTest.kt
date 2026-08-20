package accounting

import com.TTT.Config.TPipeConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.Test
import org.ttt.autogenesis.network.RestRpcClient
import org.ttt.autogenesis.network.RestRpcClientConfig
import org.ttt.autogenesis.network.RpcDirection
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcRegistry
import structs.account.AccountPlan
import structs.account.AccountSettings
import structs.account.BillingStatus
import structs.account.UsageLedger
import structs.rpcRequests.GetAccountSettingsRequest
import structs.rpcRequests.SaveAccountSettingsRequest
import structs.rpcRequests.SaveUsageLedgerRequest
import java.io.File
import java.net.Socket

/**
 * End-to-end integration test for the Autogenesis billing workflow.
 *
 * The test:
 *  1. Loads AccelByte test config from `server/accelbyte.local.properties`.
 *  2. Verifies server-extend is reachable on the gRPC port (`:9092`) and the
 *     REST port (`:7070`). Fails fast with a clear start instruction otherwise.
 *  3. Seeds the test user's account-settings record with a starting balance
 *     of `10000.0` credits via the REST RPC.
 *  4. Writes a synthetic `trace.json` to the TPipe trace root.
 *  5. Parses the synthetic trace with [TraceParser], computes the expected
 *     cost via [ModelPricing], and constructs a [TurnBillingRecord] directly.
 *     The path through [Billing.recordTurnBilling] is bypassed because that
 *     function depends on [gameState.WorldManager] which is not initialised
 *     in this test context. The end-to-end behavior under test (parse →
 *     flush → read-back) is unaffected.
 *  6. Calls [BillingSync.flushTurnUsage] to persist the resulting
 *     [UsageLedger] to cloud save over the production gRPC path.
 *  7. Reads the ledger back via gRPC and asserts the running balance.
 *  8. Reads the account-settings record back via REST and asserts the
 *     credit balance matches the expected deduction.
 *  9. Cleans up: writes an empty ledger, restores the starting balance, and
 *     deletes the synthetic trace folder.
 *
 * The test writes real data to the namespace configured in
 * `server/accelbyte.local.properties` under the user id `"guest-user"`
 * (matching the kvisionApp's skipLogin flow so the Playwright visual
 * verification in `browser-smoke/tests/usage-meter-population.spec.mjs` reads
 * the same data). The teardown leaves the user's cloud-save record visually
 * indistinguishable from a fresh player.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("sandbox")
class BillingWorkflowIntegrationTest
{
    private val startingBalance: Double = 10_000.0
    private val testFolderName: String = "Round_1_Turn_0_Commander_TestPlayer"

    @org.junit.Before
    fun setUp()
    {
        // The test makes real RPC calls against the namespace configured in
        // `server/accelbyte.local.properties` and requires server-extend running
        // locally on `:7070`/`:9092`. Both preconditions are operator-only —
        // guard with assumeTrue so the test self-skips in CI / on dev machines
        // without those services, and also carry `@Tag("sandbox")` so Gradle's
        // default `test` task excludes it (operators run it via
        // `./gradlew :server:testSandbox`).
        assumeTrue(
            "Billing workflow integration test requires server-extend running " +
                "on 127.0.0.1:7070 (REST) and 127.0.0.1:9092 (gRPC). " +
                "Start it with: ./gradlew :server-extend:run. Skipping.",
            checkServerExtendReachablePreflight()
        )
    }

    @Test
    fun `simulated turn billing saves ledger to cloud and reads it back`() = runTest {
        val config = TestApiKeys.load()
        // We use "guest-user" as the test subject because the kvisionApp's
        // skipLogin flow reads from this same id, which lets the Playwright
        // visual verification in browser-smoke/tests/usage-meter-population
        // .spec.mjs observe the data this test writes.
        val testUserId = "guest-user"
        val startingSettings = AccountSettings(
            accelByteUserId = testUserId,
            displayName = "TestPlayer",
            billingStatus = BillingStatus(
                credits = startingBalance,
                plan = AccountPlan.PRO
            )
        )

        checkServerExtendReachable()

        val client = RestRpcClient(
            config = RestRpcClientConfig(
                baseUrl = "http://localhost:7070",
                playerId = "rpc-test-billing-workflow"
            ),
            rpcRegistry = RpcRegistry(RpcDirection.CLIENT)
        )

        try {
            client.connect()
            waitForSessionReady(client)

            seedStartingBalance(client, testUserId, startingSettings)

            val traceEvents = listOf(
                TraceEvent(pipeId = "agent-qwen-large", modelId = "qwen3-235b", inputTokens = 100_000, outputTokens = 50_000),
                TraceEvent(pipeId = "agent-qwen-coder", modelId = "qwen3-coder-30b", inputTokens = 200_000, outputTokens = 100_000),
                TraceEvent(pipeId = "agent-palmyra", modelId = "palmyra-x5", inputTokens = 50_000, outputTokens = 25_000)
            )
            TraceFixture.writeTraceJson(testFolderName, traceEvents)

            val expectedCost = traceEvents.sumOf { event ->
                ModelPricing.calculateCost(event.modelId, event.inputTokens, event.outputTokens)
            }
            val expectedInput = traceEvents.sumOf { it.inputTokens }
            val expectedOutput = traceEvents.sumOf { it.outputTokens }
            val expectedCredits = ModelPricing.tokensToCredits(expectedInput, expectedOutput)

            val (expectedRound, expectedTurn) = BillingAggregator.parseTurnIndices(testFolderName)
            check(expectedRound == 1 && expectedTurn == 0) {
                "Folder name '$testFolderName' should parse to (round=1, turn=0)"
            }

            // We bypass Billing.recordTurnBilling here because it depends on
            // WorldManager (which is not initialised in a unit test). We
            // construct the TurnBillingRecord directly from the parsed
            // trace usages, which is what recordTurnBilling does internally.
            val parsedUsages = TraceParser.parseTraceDirectory(
                File(TPipeConfig.getTraceDir(), testFolderName).absolutePath
            )
            check(parsedUsages.size == traceEvents.size) {
                "TraceParser returned ${parsedUsages.size} usages, expected ${traceEvents.size}"
            }
            val turnRecord = TurnBillingRecord(
                turnKey = testFolderName,
                actorName = "TestPlayer",
                accelByteUserId = testUserId,
                roundNumber = expectedRound,
                turnIndex = expectedTurn,
                isNpcTurn = false,
                tokenUsages = parsedUsages,
                totalInputTokens = expectedInput,
                totalOutputTokens = expectedOutput,
                totalCostUsd = expectedCost,
                totalInferenceTimeMs = 0L,
                timestampMillis = System.currentTimeMillis()
            )

            val flushed = BillingSync.flushTurnUsage(listOf(turnRecord))
            check(flushed) { "BillingSync.flushTurnUsage returned false" }

            val ledgerViaGrpc = BillingSync.fetchUsageLedger(testUserId)
            check(ledgerViaGrpc.accelByteUserId == testUserId) {
                "Expected ledger owner=$testUserId, got ${ledgerViaGrpc.accelByteUserId}"
            }
            check(ledgerViaGrpc.entries.isNotEmpty()) {
                "Expected at least one ledger entry after flush, got ${ledgerViaGrpc.entries.size}"
            }
            val newestEntry = ledgerViaGrpc.entries.maxByOrNull { it.timestampMillis }!!
            // Phase 4 of feature/live-pvp-and-billing: assert the new UsageEntry fields.
            // The test uses plan=PRO so costClass is PRO and creditsDelta must be 0.
            // operatorCostUsd is the raw LLM cost regardless of class.
            check(newestEntry.costClass == "PRO") {
                "Expected newest entry costClass=PRO, got ${newestEntry.costClass}"
            }
            check(newestEntry.byoKey == false) {
                "Expected newest entry byoKey=false (PRO user), got ${newestEntry.byoKey}"
            }
            check(newestEntry.model.isNotBlank()) {
                "Expected newest entry model to be populated, got blank"
            }
            check(kotlin.math.abs(newestEntry.creditsDelta - 0.0) < 1e-6) {
                "Expected newest entry creditsDelta=0.0 (PRO user), got ${newestEntry.creditsDelta}"
            }
            check(kotlin.math.abs(newestEntry.operatorCostUsd - expectedCost) < 1e-6) {
                "Expected newest entry operatorCostUsd=$expectedCost, got ${newestEntry.operatorCostUsd}"
            }
            check(newestEntry.region.isNotBlank()) {
                "Expected newest entry region to be populated, got blank"
            }
            val expectedBalance = startingBalance // PRO class -> 0 deduction
            check(kotlin.math.abs(newestEntry.balanceAfter - expectedBalance) < 1e-6) {
                "Expected newest entry balanceAfter=$expectedBalance (PRO user), got ${newestEntry.balanceAfter}"
            }

            val settingsViaRest = readAccountSettingsViaRest(client, testUserId)
            check(settingsViaRest != null) { "Failed to read AccountSettings back via REST" }
            check(kotlin.math.abs(settingsViaRest!!.billingStatus.credits - expectedBalance) < 1e-6) {
                "Expected credits=$expectedBalance via REST (PRO user balance unchanged), got ${settingsViaRest.billingStatus.credits}"
            }

            println("=== BillingWorkflowIntegrationTest SUMMARY ===")
            println("  Wrote ledger: ${ledgerViaGrpc.entries.size} entries, $expectedCredits total credits used")
            println("  Final balance: ${settingsViaRest.billingStatus.credits} credits")
            println("  Expected cost: $${"%.6f".format(expectedCost)}")
            println("==============================================")
        }
        finally {
            try {
                cleanupTestUserData(client, testUserId, startingSettings)
            }
            finally {
                TraceFixture.cleanupTraceFolder(testFolderName)
                client.close()
            }
        }
    }

    /**
     * Returns true when server-extend is reachable on BOTH the gRPC port
     * (`:9092`) and the REST port (`:7070`). Does NOT throw — designed for
     * the `@Before` `assumeTrue` gate so the test self-skips with a clear
     * message instead of crashing mid-test.
     *
     * Mirrors the throwing socket probes in [checkServerExtendReachable],
     * which IS used inside the test body to fail fast once we've committed
     * to running.
     */
    private fun checkServerExtendReachablePreflight(): Boolean
    {
        val grpcReachable = runCatching {
            Socket("127.0.0.1", 9092).use { true }
        }.getOrDefault(false)

        val restReachable = runCatching {
            Socket("127.0.0.1", 7070).use { true }
        }.getOrDefault(false)

        return grpcReachable && restReachable
    }

    /**
     * Verifies server-extend is reachable on both the gRPC port (`:9092`) and
     * the REST port (`:7070`). Throws [IllegalStateException] with a clear
     * start instruction when either port is unreachable.
     */
    private fun checkServerExtendReachable()
    {
        val grpcReachable = runCatching {
            Socket("127.0.0.1", 9092).use { true }
        }.getOrDefault(false)

        val restReachable = runCatching {
            Socket("127.0.0.1", 7070).use { true }
        }.getOrDefault(false)

        check(grpcReachable && restReachable) {
            "server-extend is not reachable on 127.0.0.1:9092 (gRPC) or :7070 (REST). " +
                "Start it with: ./gradlew :server-extend:run"
        }
    }

    /**
     * Waits for the REST RPC client to finish its session-ready handshake.
     * Mirrors the pattern in `AccountSettingsRpcTest`.
     */
    private suspend fun waitForSessionReady(client: RestRpcClient)
    {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(10_000) {
                while (!client.isSessionReady())
                {
                    delay(50)
                }
            }
        }
    }

    /**
     * Writes the test user's starting [AccountSettings] to cloud save so the
     * running balance is deterministic. Uses the REST RPC at `:7070`.
     */
    private suspend fun seedStartingBalance(
        client: RestRpcClient,
        testUserId: String,
        settings: AccountSettings
    )
    {
        val settingsJson = RpcJson.encodeToString(AccountSettings.serializer(), settings)
        val saveRequest = SaveAccountSettingsRequest(testUserId, settingsJson)
        val handle = client.rpcInvoker.request(
            method = "server.extend.saveAccountSettings",
            params = saveRequest,
            timeoutMillis = 5_000L
        )
        val response = handle.await()
        val error = response.error
        check(error == null) {
            "saveAccountSettings failed: ${error?.message}"
        }
    }

    /**
     * Reads the test user's [AccountSettings] back from cloud save via REST.
     */
    private suspend fun readAccountSettingsViaRest(
        client: RestRpcClient,
        testUserId: String
    ): AccountSettings?
    {
        val getRequest = GetAccountSettingsRequest(testUserId)
        val handle = client.rpcInvoker.request(
            method = "server.extend.getAccountSettings",
            params = getRequest,
            timeoutMillis = 5_000L
        )
        val response = handle.await()
        val error = response.error
        if (error != null)
        {
            println("getAccountSettings RPC error: ${error.message}")
            return null
        }
        val resultElement = response.result ?: return null
        return RpcJson.decodeFromJsonElement(AccountSettings.serializer(), resultElement)
    }

    /**
     * Restores the test user's cloud-save record to a clean state: empty
     * ledger and the original starting balance.
     */
    private suspend fun cleanupTestUserData(
        client: RestRpcClient,
        testUserId: String,
        startingSettings: AccountSettings
    )
    {
        val emptyLedger = UsageLedger(accelByteUserId = testUserId, entries = emptyList())
        val emptyLedgerJson = RpcJson.encodeToString(UsageLedger.serializer(), emptyLedger)
        val saveLedgerRequest = SaveUsageLedgerRequest(testUserId, emptyLedgerJson)
        val saveHandle = client.rpcInvoker.request(
            method = "server.extend.saveUsageLedger",
            params = saveLedgerRequest,
            timeoutMillis = 5_000L
        )
        val saveResponse = saveHandle.await()
        val saveError = saveResponse.error
        if (saveError != null)
        {
            println("Cleanup: saveUsageLedger returned error: ${saveError.message}")
        }

        val settingsJson = RpcJson.encodeToString(AccountSettings.serializer(), startingSettings)
        val saveSettingsRequest = SaveAccountSettingsRequest(testUserId, settingsJson)
        val settingsHandle = client.rpcInvoker.request(
            method = "server.extend.saveAccountSettings",
            params = saveSettingsRequest,
            timeoutMillis = 5_000L
        )
        val settingsResponse = settingsHandle.await()
        val settingsError = settingsResponse.error
        if (settingsError != null)
        {
            println("Cleanup: saveAccountSettings returned error: ${settingsError.message}")
        }
    }
}