package accounting

import gameState.WorldManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.GrpcRpcClient
import org.ttt.autogenesis.network.GrpcRpcClientConfig
import org.ttt.autogenesis.network.RpcDirection
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcRegistry
import org.ttt.autogenesis.network.RpcInvoker
import kotlinx.serialization.serializer
import agent.runners.getTurnTraceDir
import structs.account.AccountSettings
import structs.account.UsageEntry
import structs.account.UsageLedger
import structs.account.costClass
import structs.rpcRequests.GetAccountSettingsRequest
import structs.rpcRequests.GetUsageLedgerRequest
import structs.rpcRequests.SaveUsageLedgerRequest
import structs.rpcRequests.SaveAccountSettingsRequest
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID

/**
 * Main billing and accounting object for tracking token usage and costs per player.
 *
 * Token usage is extracted from TPipe trace JSON files after each turn completes.
 * In single-player mode all tokens are attributed to the human player.
 * In multiplayer mode tokens are attributed to the player whose turn it was.
 *
 * Records are held in-memory for the session and exported to JSON on game end.
 */
object Billing
{
    private val billingMutex = Mutex()

    /** All turn billing records for the current game session */
    private val turnBillingRecords = mutableListOf<TurnBillingRecord>()

    /** Session ID for this billing period */
    val sessionId: String = UUID.randomUUID().toString()

    /**
     * Records billing for a completed turn.
     * Called from TurnHarness after setCurrentTurnFolderName(null).
     *
     * @param turnFolderName The folder name used for traces (e.g., "Round_1_Turn_0_Commander_Shepard")
     */
    suspend fun recordTurnBilling(turnFolderName: String) : TurnBillingRecord?
    {
        return billingMutex.withLock {
            try
            {
                val actorName = BillingAggregator.extractActorName(turnFolderName)
                val record = BillingAggregator.generateTurnBillingRecord(turnFolderName, actorName, isNpcTurn = false)

                if (record != null)
                {
                    turnBillingRecords.add(record)
                    Logger.info(
                        LogCategory.SYSTEM,
                        "Billing: Recorded billing for $turnFolderName — ${turnBillingRecords.size} turn(s) total this session"
                    )
                }
                record
            }
            catch (e: Exception)
            {
                Logger.error(LogCategory.SYSTEM, "Billing: Failed to record turn billing: ${e.message}")
                null
            }
        }
    }

    /**
     * Records billing for a completed NPC turn.
     * NPC costs are attributed to the human player in single-player mode,
     * or to the NPC owner in multiplayer.
     *
     * The returned record is what the caller passes to [BillingSync.flushTurnUsage] so
     * the persistent ledger covers NPC turns too (Phase 4 of feature/live-pvp-and-billing).
     * `null` is returned when no trace was found or the parse failed.
     *
     * @param turnFolderName The folder name for the NPC turn
     * @param npcName The NPC name
     * @return The produced [TurnBillingRecord], or `null` when no usage was found.
     */
    suspend fun recordNpcTurnBilling(turnFolderName: String, npcName: String): TurnBillingRecord?
    {
        return billingMutex.withLock {
            try
            {
                val actorName = BillingAggregator.extractActorName(turnFolderName)
                val record = BillingAggregator.generateTurnBillingRecord(turnFolderName, actorName, isNpcTurn = true)

                if (record != null)
                {
                    // In single-player, attribute NPC costs to human (accelByteUserId already resolved correctly)
                    turnBillingRecords.add(record)
                    Logger.info(
                        LogCategory.SYSTEM,
                        "Billing: Recorded NPC billing for $turnFolderName (npc=$npcName)"
                    )
                }
                record
            }
            catch (e: Exception)
            {
                Logger.error(LogCategory.SYSTEM, "Billing: Failed to record NPC turn billing: ${e.message}")
                null
            }
        }
    }

    /**
     * Generates a complete game billing report.
     *
     * @return GameBillingReport with all player summaries and totals
     */
    suspend fun generateGameBillingReport(): GameBillingReport
    {
        return billingMutex.withLock {
            val summaries = BillingAggregator.aggregatePlayerSummaries(turnBillingRecords)

            val humanStats = if (WorldManager.isSinglePlayer)
            {
                WorldManager.playerStats.firstOrNull { !it.isControlledByNpc }
            }
            else
            {
                null
            }

            GameBillingReport(
                sessionId = sessionId,
                isSinglePlayerMode = WorldManager.isSinglePlayer,
                humanPlayerAccelByteId = humanStats?.accelByteUserId,
                humanPlayerName = humanStats?.playerData?.name,
                playerSummaries = summaries,
                totalGameCostUsd = summaries.sumOf { it.totalCostUsd },
                totalInputTokens = summaries.sumOf { it.totalInputTokens },
                totalOutputTokens = summaries.sumOf { it.totalOutputTokens },
                totalInferenceTimeMs = summaries.sumOf { it.totalInferenceTimeMs },
                generatedAtMillis = System.currentTimeMillis()
            )
        }
    }

    /**
     * Returns all turn billing records.
     *
     * @return Copy of the current list of TurnBillingRecord
     */
    suspend fun getAllTurnRecords(): List<TurnBillingRecord>
    {
        return billingMutex.withLock { turnBillingRecords.toList() }
    }

    /**
     * Exports the billing report to a JSON file.
     * Returns the absolute path of the exported file.
     *
     * @return Absolute path of the exported JSON file
     */
    suspend fun exportBillingReport(): String
    {
        val report = generateGameBillingReport()
        val json = kotlinx.serialization.json.Json {
            prettyPrint = true
            encodeDefaults = true
        }.encodeToString(GameBillingReport.serializer(), report)

        val exportDir = File(getTurnTraceDir()).parentFile
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val exportFile = File(exportDir, "billing_report_$timestamp.json")

        exportFile.writeText(json)
        Logger.info(LogCategory.SYSTEM, "Billing: Exported report to ${exportFile.absolutePath}")

        return exportFile.absolutePath
    }

    /**
     * Records billing for a chat or answer agent invocation.
     * Chat/answer agents are auxiliary prompts not tied to a game turn.
     * Traces are saved under getTurnTraceDir()/subFolder/connectionId/.
     *
     * The returned record is what the caller passes to [BillingSync.flushTurnUsage] so
     * the persistent ledger covers chat/answer agent invocations too (Phase 4 of
     * feature/live-pvp-and-billing). `null` is returned when no trace was found.
     *
     * @param connectionId Player connection ID
     * @param agentType "ChatAgent" or "AnswerAgent"
     * @return The produced [TurnBillingRecord], or `null` when no usage was found.
     */
    suspend fun recordAuxAgentBilling(connectionId: String, agentType: String): TurnBillingRecord?
    {
        return billingMutex.withLock {
            try
            {
                val traceDir = File(File(getTurnTraceDir()), agentType)
                val connectionTraceDir = File(traceDir, connectionId)
                if (!connectionTraceDir.exists())
                {
                    Logger.debug(LogCategory.SYSTEM, "Billing: No trace directory found for $agentType (conn=$connectionId)")
                    return@withLock null
                }

                val usages = TraceParser.parseTraceDirectory(connectionTraceDir.absolutePath)
                if (usages.isEmpty())
                {
                    Logger.debug(LogCategory.SYSTEM, "Billing: No token usage found for $agentType (conn=$connectionId)")
                    return@withLock null
                }

                val accelByteUserId = resolveAccelByteIdFromConnectionId(connectionId)
                var totalInput = 0
                var totalOutput = 0
                var totalCost = 0.0
                var totalInference = 0L
                for (u in usages)
                {
                    totalInput += u.inputTokens
                    totalOutput += u.outputTokens
                    totalCost += ModelPricing.calculateCost(u.modelId, u.inputTokens, u.outputTokens)
                    totalInference += u.inferenceTimeMs
                }

                val record = TurnBillingRecord(
                    turnKey = "${agentType}_${System.currentTimeMillis()}",
                    actorName = accelByteUserId,
                    accelByteUserId = accelByteUserId,
                    roundNumber = -1,
                    turnIndex = -1,
                    isNpcTurn = false,
                    tokenUsages = usages,
                    totalInputTokens = totalInput,
                    totalOutputTokens = totalOutput,
                    totalCostUsd = totalCost,
                    totalInferenceTimeMs = totalInference,
                    timestampMillis = System.currentTimeMillis()
                )
                turnBillingRecords.add(record)
                Logger.info(
                    LogCategory.SYSTEM,
                    "Billing: Recorded $agentType billing for conn=$connectionId — input=$totalInput, output=$totalOutput, cost=$%.6f".format(totalCost)
                )
                record
            }
            catch (e: Exception)
            {
                Logger.error(LogCategory.SYSTEM, "Billing: Failed to record $agentType billing: ${e.message}")
                null
            }
        }
    }

    /**
     * Clears all billing records (e.g., when starting a new game session).
     */
    suspend fun resetBilling()
    {
        billingMutex.withLock {
            turnBillingRecords.clear()
            Logger.info(LogCategory.SYSTEM, "Billing: Reset all billing records")
        }
    }

    /**
     * Resolves a connection ID to an AccelByte user ID.
     *
     * @param connectionId The RPC connection ID
     * @return The AccelByte user ID, or empty string if not found
     */
    private fun resolveAccelByteIdFromConnectionId(connectionId: String): String
    {
        if (WorldManager.isSinglePlayer)
        {
            return WorldManager.playerStats.firstOrNull { !it.isControlledByNpc }?.accelByteUserId ?: ""
        }
        return WorldManager.playerStats.firstOrNull { it.playerID == connectionId }?.accelByteUserId ?: ""
    }
}

/**
 * Manages a lazy gRPC connection from the game server to server-extend.
 * Used exclusively for billing sync operations.
 *
 * Test seam: [setTestInvoker] / [setTestInvoker] `null` let unit tests inject a fake
 * [org.ttt.autogenesis.network.RpcInvoker] that returns canned responses for the
 * `server.extend.*` RPCs. The seam is package-internal and intended for
 * `accounting.*` tests only.
 */
private object ServerExtendConnection
{
    private const val SERVER_EXTEND_ENDPOINT = "127.0.0.1:9092"
    private const val PLAYER_ID = "billing-service"

    private val connectionMutex = Mutex()
    private var grpcClient: GrpcRpcClient? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Optional test invoker. When non-null, [getInvoker] returns it directly without
     * touching the gRPC stack. Production code never sets this.
     */
    @JvmStatic
    internal var testInvoker: RpcInvoker? = null

    /**
     * Installs (or clears) the test invoker and returns the previous value so the
     * caller can restore it in a teardown step.
     */
    @JvmStatic
    internal fun setTestInvoker(invoker: RpcInvoker?): RpcInvoker?
    {
        val previous = testInvoker
        testInvoker = invoker
        return previous
    }

    /**
     * Returns the RPC invoker for making calls to server-extend.
     * Lazily initializes the connection on first call. Returns the test invoker
     * if one is installed; otherwise boots the real gRPC client.
     *
     * @return RpcInvoker, or null if connection failed
     */
    @Suppress("LiftReturnOrAssignment")
    suspend fun getInvoker(): RpcInvoker?
    {
        testInvoker?.let { return it }

        val existingClient = grpcClient
        if (existingClient != null && existingClient.isConnected())
        {
            return existingClient.rpcInvoker
        }

        connectionMutex.withLock {
            val recheckClient = grpcClient
            if (recheckClient != null && recheckClient.isConnected())
            {
                return recheckClient.rpcInvoker
            }

            return try
            {
                val newClient = GrpcRpcClient(
                    config = GrpcRpcClientConfig(
                        endpoint = SERVER_EXTEND_ENDPOINT,
                        playerId = PLAYER_ID
                    ),
                    rpcRegistry = RpcRegistry(RpcDirection.CLIENT),
                    coroutineScope = scope
                )
                newClient.connect()
                grpcClient = newClient
                Logger.info(LogCategory.SYSTEM, "ServerExtendConnection: Connected to server-extend at $SERVER_EXTEND_ENDPOINT")
                newClient.rpcInvoker
            }
            catch (e: Exception)
            {
                Logger.error(LogCategory.SYSTEM, "ServerExtendConnection: Failed to connect to server-extend: ${e.message}")
                null
            }
        }
    }
}

/**
 * Billing sync functions for writing usage ledgers and account settings to cloud save via
 * server-extend.
 *
 * The persistent [structs.account.UsageLedger] is the source of truth for per-turn
 * usage, and the dashboard derives per-game metrics by grouping ledger entries by
 * [structs.account.UsageEntry.sessionId] at read time. See
 * `usage.history.get` in `accounting.UsageHistoryRpcHandlers`.
 */
object BillingSync
{
    /** Per-user mutex so concurrent turns for the same user cannot interleave a read-modify-write on the ledger. */
    private val userMutexes: MutableMap<String, Mutex> = java.util.concurrent.ConcurrentHashMap()

    private fun mutexFor(userId: String): Mutex = userMutexes.computeIfAbsent(userId) { Mutex() }

    /**
     * Appends the given turn records to the player's persistent usage ledger and updates
     * [structs.account.BillingStatus.credits] in cloud save. One [structs.account.UsageEntry]
     * is synthesized per record, tagged with [Billing.sessionId] and credited via
     * [ModelPricing.tokensToCredits].
     *
     * @param turnRecords Turn records from [Billing.recordTurnBilling] for the just-finished turn.
     * @return True when the ledger and balance were both persisted; false on failure.
     */
    suspend fun flushTurnUsage(turnRecords: List<TurnBillingRecord>): Boolean
    {
        if (turnRecords.isEmpty())
        {
            Logger.info(LogCategory.SYSTEM, "BillingSync: flushTurnUsage called with no records, skipping")
            return true
        }

        // Group by AccelByte ID so multiplayer / NPC attributions are each handled once.
        val recordsByUser = turnRecords.groupBy { it.accelByteUserId }
        var allSucceeded = true

        for ((userId, records) in recordsByUser)
        {
            if (userId.isBlank()) continue
            val ok = flushTurnUsageForUser(userId, records)
            if (!ok) allSucceeded = false
        }
        return allSucceeded
    }

    /**
     * Test-friendly overload that lets callers inject an [RpcInvoker] directly. Production
     * code (see [flushTurnUsage]) goes through [getInvoker] which lazily boots the gRPC
     * client; tests can pass a mock that returns canned ledger / settings responses.
     */
    internal suspend fun flushTurnUsageForUser(
        userId: String,
        turnRecords: List<TurnBillingRecord>,
        invokerOverride: RpcInvoker? = null
    ): Boolean
    {
        return mutexFor(userId).withLock {
            try
            {
                val activeInvoker = invokerOverride ?: ServerExtendConnection.getInvoker()
                val currentLedger = fetchUsageLedger(userId, activeInvoker)
                val fetchedSettings = getAccountSettings(userId, activeInvoker)
                val startingBalance = if (currentLedger.accelByteUserId.isBlank() && fetchedSettings.accelByteUserId.isBlank())
                {
                    // First flush ever; trust whatever balance the player has on their account-settings record.
                    fetchedSettings.billingStatus.credits
                }
                else
                {
                    fetchedSettings.billingStatus.credits
                }
                val sessionId = Billing.sessionId
                // Cost-class policy (Phase 4 of feature/live-pvp-and-billing):
                //   BYO_KEY -> 0 (player paid the LLM provider)
                //   PRO/CASUAL/CREDIT -> 0 (subscription / pre-paid balance already covers it)
                //   FREE -> -tokensToCredits(...) (eligible for Phase 5 wallet debit)
                // operatorCostUsd always reflects the raw ModelPricing.calculateCost
                // so the operator dashboard sees the true LLM cost regardless of class.
                val klass = fetchedSettings.costClass()
                val byoKey = fetchedSettings.bringYourOwnApiKey
                val region = resolveRegionForEntry(fetchedSettings, byoKey)
                var runningBalance = startingBalance
                val newEntries = turnRecords.map { record ->
                    val creditsUsed = ModelPricing.tokensToCredits(record.totalInputTokens, record.totalOutputTokens)
                    val operatorCost = ModelPricing.calculateCost(
                        record.tokenUsages.firstOrNull()?.modelId ?: "",
                        record.totalInputTokens,
                        record.totalOutputTokens
                    )
                    val modelShortId = ModelPricing.resolveShortId(
                        record.tokenUsages.firstOrNull()?.modelId ?: ""
                    )
                    val (creditsDelta, nextBalance) = applyCostClassPolicy(
                        klass = klass,
                        creditsUsed = creditsUsed,
                        runningBalance = runningBalance
                    )
                    runningBalance = nextBalance
                    UsageEntry(
                        entryId = UUID.randomUUID().toString(),
                        timestampMillis = record.timestampMillis,
                        sourceLabel = "Turn ${record.roundNumber}.${record.turnIndex} — ${record.actorName}",
                        sourceIconKey = if (record.isNpcTurn) "agent-run" else "turn-resolution",
                        creditsDelta = creditsDelta,
                        balanceAfter = runningBalance,
                        sessionId = sessionId,
                        turnKey = record.turnKey,
                        inputTokens = record.totalInputTokens,
                        outputTokens = record.totalOutputTokens,
                        accelByteUserId = userId,
                        model = modelShortId,
                        byoKey = byoKey,
                        costClass = klass.name,
                        region = region,
                        operatorCostUsd = operatorCost
                    )
                }

                val merged = (newEntries + currentLedger.entries)
                    .distinctBy { it.entryId }
                    .take(currentLedger.maxEntries.coerceAtLeast(1))

                val updatedLedger = UsageLedger(
                    accelByteUserId = userId,
                    entries = merged,
                    lastUpdatedMillis = System.currentTimeMillis(),
                    maxEntries = currentLedger.maxEntries
                )

                val ledgerOk = saveUsageLedger(userId, updatedLedger, activeInvoker)
                if (!ledgerOk)
                {
                    Logger.error(LogCategory.SYSTEM, "BillingSync: flushTurnUsageForUser ledger save failed for userId=$userId")
                    false
                }
                else
                {
                    // Persist the running balance on the AccountSettings record.
                    val baseSettings = if (fetchedSettings.accelByteUserId.isBlank())
                        AccountSettings(accelByteUserId = userId)
                    else
                        fetchedSettings
                    val updatedSettings = baseSettings.copy(
                        billingStatus = baseSettings.billingStatus.copy(credits = runningBalance)
                    )
                    val settingsOk = saveAccountSettings(userId, updatedSettings, activeInvoker)
                    if (!settingsOk)
                    {
                        Logger.error(LogCategory.SYSTEM, "BillingSync: flushTurnUsageForUser account settings save failed for userId=$userId")
                        false
                    }
                    else
                    {
                        // Phase 5 of feature/live-pvp-and-billing: charge the wallet for
                        // FREE-class entries only. Non-FREE classes have creditsDelta=0 so
                        // there is nothing to debit. The local BillingStatus.credits write
                        // above keeps the dashboard math consistent; the wallet debit is
                        // additive (so the AccelByte platform wallet can diverge from the
                        // local ledger when WALLET_ADAPTER=accelbyte). We log on failure
                        // rather than failing the flush so a transient wallet hiccup
                        // never drops a usage entry.
                        if (klass == structs.account.CostClass.FREE)
                        {
                            val freeCredits = newEntries.sumOf { kotlin.math.abs(it.creditsDelta) }
                            if (freeCredits > 0.0)
                            {
                                val walletOk = runCatching {
                                    WalletAdapterRegistry.get().debit(
                                        userId = userId,
                                        credits = freeCredits,
                                        reason = "inference:turn-fee"
                                    )
                                }.getOrDefault(false)
                                if (!walletOk)
                                {
                                    Logger.warn(
                                        LogCategory.DATABASE,
                                        "BillingSync: flushTurnUsageForUser wallet debit returned false for userId=$userId credits=$freeCredits (continuing with local ledger)"
                                    )
                                }
                            }
                        }
                        Logger.info(
                            LogCategory.SYSTEM,
                            "BillingSync: flushTurnUsageForUser persisted {newEntries.size} entries for userId=${userId} (newBalance=runningBalance)"
                        )
                        true
                    }
                }
            }
            catch (e: Exception)
            {
                Logger.error(LogCategory.SYSTEM, "BillingSync: flushTurnUsageForUser failed for userId=${userId}: ${e.message}")
                false
            }
        }
    }

    /**
     * Applies the per-cost-class credit-delta policy. Returns the (delta, newBalance) pair to
     * stamp on the [UsageEntry] and the running balance respectively. See the policy table in
     * [flushTurnUsageForUser] for the rationale.
     *
     * @param klass The player's resolved cost class at flush time.
     * @param creditsUsed Credits the player would have been charged under a flat-rate policy.
     * @param runningBalance The player's pre-deduction credit balance.
     * @return A pair of (creditsDelta, newBalance). For non-FREE classes, the delta is always
     *   0.0 and the balance is unchanged. For FREE, the balance is floored at 0.0.
     */
    private fun applyCostClassPolicy(
        klass: structs.account.CostClass,
        creditsUsed: Double,
        runningBalance: Double
    ): Pair<Double, Double>
    {
        return when (klass)
        {
            structs.account.CostClass.FREE ->
            {
                val next = (runningBalance - creditsUsed).coerceAtLeast(0.0)
                -creditsUsed to next
            }
            // BYO_KEY / PRO / CASUAL / CREDIT: subscription / wallet / provider already
            // covers the inference; the player sees a 0-delta entry but the operator
            // dashboard still records operatorCostUsd for true-cost reconciliation.
            else -> 0.0 to runningBalance
        }
    }

    /**
     * Resolves the AWS region the entry's inference was billed to. For BYO-keyed
     * players, the region pinned to their stored key takes precedence so the
     * reconciliation matches the provider that actually received the charge.
     * For platform-paid players, the active `bedrockEnv` region is used as a
     * best-effort hint. The actual region used at SDK-call time is not exposed
     * publicly by `bedrockEnv`, so we read it from `AB_REGION` / `AWS_REGION`
     * (the env vars the operator set when configuring Bedrock credentials).
     */
    private fun resolveRegionForEntry(settings: AccountSettings, byoKey: Boolean): String
    {
        // Both BYO and platform paths use the same env-driven hint for now; a follow-up
        // PR can plumb ByoCredentialStore.getDecrypted(...).region when the slot is
        // present and the lookup is cheap. The flat-string fallback keeps the flush
        // non-blocking and matches the operator dashboard's "approximate region" UX.
        return platformBedrockRegion()
    }

    /**
     * Returns the platform Bedrock region. Tries `AB_REGION` then `AWS_REGION`; falls back
     * to `us-west-2` (the project's default) when neither is set. The result is purely
     * a reconciliation hint for the operator dashboard — actual AWS charging happens
     * server-side via the SDK and is not affected by this string.
     */
    private fun platformBedrockRegion(): String
    {
        return System.getenv("AB_REGION")
            ?: System.getenv("AWS_REGION")
            ?: "us-west-2"
    }

    /**
     * Convenience wrapper used by [accounting.UsageHistoryRpcHandlers] to read the persistent
     * ledger for a user via the server-extend `server.extend.getUsageLedger` RPC.
     *
     * @param userId The AccelByte user ID.
     * @return The decoded [UsageLedger], or an empty ledger owned by the user on failure.
     */
    suspend fun fetchUsageLedger(
        userId: String,
        invokerOverride: RpcInvoker? = null
    ): UsageLedger
    {
        val invoker = invokerOverride ?: ServerExtendConnection.getInvoker()
            ?: return UsageLedger(accelByteUserId = userId)

        val request = GetUsageLedgerRequest(userId)
        val response = invoker.invoke("server.extend.getUsageLedger", request)
        val error = response.error
        if (error != null)
        {
            Logger.error(LogCategory.SYSTEM, "BillingSync: fetchUsageLedger RPC error: ${error.message}")
            return UsageLedger(accelByteUserId = userId)
        }

        return response.result?.let { RpcJson.decodeFromJsonElement(UsageLedger.serializer(), it) }
            ?: UsageLedger(accelByteUserId = userId)
    }

    /**
     * Saves the supplied [UsageLedger] for a user via the server-extend `server.extend.saveUsageLedger` RPC.
     *
     * @param userId The AccelByte user ID.
     * @param ledger The ledger to persist.
     * @return True on success, false on failure.
     */
    suspend fun saveUsageLedger(
        userId: String,
        ledger: UsageLedger,
        invokerOverride: RpcInvoker? = null
    ): Boolean
    {
        val invoker = invokerOverride ?: ServerExtendConnection.getInvoker()
            ?: return false

        val ledgerJson = RpcJson.encodeToString(UsageLedger.serializer(), ledger)
        val request = SaveUsageLedgerRequest(userId, ledgerJson)
        val response = invoker.invoke("server.extend.saveUsageLedger", request)

        val error = response.error
        if (error != null)
        {
            Logger.error(LogCategory.SYSTEM, "BillingSync: saveUsageLedger RPC error: ${error.message}")
            return false
        }

        val result = response.result?.let { RpcJson.decodeFromJsonElement(serializer<Boolean>(), it) } ?: false
        Logger.info(LogCategory.SYSTEM, "BillingSync: saveUsageLedger result=$result for userId=$userId")
        return result
    }

    /**
     * Saves AccountSettings to server-extend cloud save via gRPC.
     *
     * @param userId The AccelByte user ID
     * @param settings The AccountSettings to save
     * @return True if save succeeded, false otherwise
     */
    suspend fun saveAccountSettings(
        userId: String,
        settings: AccountSettings,
        invokerOverride: RpcInvoker? = null
    ): Boolean
    {
        val invoker = invokerOverride ?: ServerExtendConnection.getInvoker()
            ?: return false

        val settingsJson = RpcJson.encodeToString(AccountSettings.serializer(), settings)
        val request = SaveAccountSettingsRequest(userId, settingsJson)
        val response = invoker.invoke("server.extend.saveAccountSettings", request)

        val error = response.error
        if (error != null)
        {
            Logger.error(LogCategory.SYSTEM, "BillingSync: saveAccountSettings RPC error: ${error.message}")
            return false
        }

        val result = response.result?.let { RpcJson.decodeFromJsonElement(serializer<Boolean>(), it) } ?: false
        Logger.info(LogCategory.SYSTEM, "BillingSync: saveAccountSettings result=$result for userId=$userId")
        return result
    }

    /**
     * Fetches AccountSettings from server-extend cloud save via gRPC.
     *
     * @param userId The AccelByte user ID
     * @return AccountSettings, or empty AccountSettings if not found/error
     */
    suspend fun getAccountSettings(
        userId: String,
        invokerOverride: RpcInvoker? = null
    ): AccountSettings
    {
        val invoker = invokerOverride ?: ServerExtendConnection.getInvoker()
            ?: return AccountSettings()

        val request = GetAccountSettingsRequest(userId)
        val response = invoker.invoke("server.extend.getAccountSettings", request)

        val error = response.error
        if (error != null)
        {
            Logger.error(LogCategory.SYSTEM, "BillingSync: getAccountSettings RPC error: ${error.message}")
            return AccountSettings()
        }

        return response.result?.let { RpcJson.decodeFromJsonElement(AccountSettings.serializer(), it) } ?: AccountSettings()
    }
}
