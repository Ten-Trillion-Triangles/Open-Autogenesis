package network

import accounting.ModelPricing
import accounting.TokenUsage
import accounting.TraceParser
import accounting.TurnBillingRecord
import com.TTT.Config.TPipeConfig
import globals.ExtendConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcInvoker
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcMessage
import org.ttt.autogenesis.serverextend.RestPlayerConnectionManager
import org.ttt.autogenesis.serverextend.RestPlayerSession
import structs.account.AccountSettings
import structs.account.CostClass
import structs.account.UsageEntry
import structs.account.UsageLedger
import structs.account.costClass
import structs.rpcRequests.GetAccountSettingsRequest
import structs.rpcRequests.GetUsageLedgerRequest
import structs.rpcRequests.SaveAccountSettingsRequest
import structs.rpcRequests.SaveUsageLedgerRequest
import java.io.File
import java.util.UUID

/**
 * Singleton that reads the safety-agent trace JSON the [MapUploadGate] writes
 * and persists the resulting [UsageLedger] entry + [AccountSettings] update
 * against the uploading player's per-cycle token counter.
 *
 * See `.hermes/plans/2026-08-13_134750-map-safety-billing.md` for the full
 * design.
 *
 * Operator decisions (Phase 2, 2026-08-13):
 *  - Gate on cap, reject early.
 *  - BYO-key users still charged (could feed a player-side dashboard later).
 *  - Safety rejects still charged (operator paid for the LLM call).
 *  - Direct write via server-extend's own gRPC RPCs (no gRPC hop back to main).
 */

/**
 * Returns the per-session [RpcInvoker] of the first registered session, if any.
 * Used by [MapUploadSafetyBilling] to look up the SSE session's invoker without
 * threading a playerId through every RPC dispatch.
 */
internal fun RestPlayerConnectionManager.invkForFirstSession(): RpcInvoker?
{
    val session = runBlocking { sessionsSnapshot() }.firstOrNull() ?: return null
    return session.invoker
}

/**
 * Returns a snapshot of every currently-registered session, in registration
 * order. Suspend because [RestPlayerConnectionManager.sessions] is guarded
 * by a mutex on the original class.
 */
private suspend fun RestPlayerConnectionManager.sessionsSnapshot(): List<RestPlayerSession>
{
    // The real sessions map is private; we reach it via reflection on the
    // private field. The test-only override path uses
    // [setFakeConnectionManagerForTest] to substitute a real manager with
    // known registered sessions.
    val sessionsField = this::class.java.getDeclaredField("sessions").apply { isAccessible = true }
    val sessions = sessionsField.get(this) as Map<String, RestPlayerSession>
    return sessions.values.toList()
}

object MapUploadSafetyBilling
{
    /**
     * Records the safety-agent token usage to the uploader's usage ledger.
     *
     * @param context RPC call context (unused — [playerId] carries the
     *   SSE/REST connection id).
     * @param playerId The SSE/REST session id (`connectionId`); resolved to
     *   the canonical AccelByte user id via [RestPlayerConnectionManager].
     * @param mapId The newly-minted map id (null until the gate stamps it).
     * @param safetyPass Whether the safety classifier accepted the upload;
     *   recorded on the [TurnBillingRecord] for forensic dashboards.
     */
    suspend fun recordSafetyUsage(
        context: RpcCallContext,
        playerId: String,
        mapId: String?,
        safetyPass: Boolean
    ): SafetyBillingOutcome
    {
        // T12: dev-mode bypass — skip entirely when ExtendConfig.debugMode is true.
        if (ExtendConfig.debugMode)
        {
            Logger.debug(
                LogCategory.DATABASE,
                "MapUploadSafetyBilling: dev mode (ExtendConfig.debugMode=true); skipping ledger write for playerId=$playerId"
            )
            return SafetyBillingOutcome.Skipped("dev mode")
        }

        // T4: read the trace JSON MapUploadGate writes at the canonical path.
        val traceFile = File(File(TPipeConfig.getTraceDir()), "MapUploadGate/trace.json")
        if (!traceFile.exists())
        {
            Logger.debug(
                LogCategory.DATABASE,
                "MapUploadSafetyBilling: trace.json not found at ${traceFile.absolutePath}; skipping"
            )
            return SafetyBillingOutcome.Skipped("trace.json missing")
        }

        val tokenUsages: List<TokenUsage> = TraceParser.parseTraceFile(traceFile)
        if (tokenUsages.isEmpty())
        {
            Logger.debug(LogCategory.DATABASE, "MapUploadSafetyBilling: trace.json had no token usage; skipping")
            return SafetyBillingOutcome.Skipped("no token usage in trace")
        }

        // T6: resolve playerId → accelbyteId via the connection manager.
        val accelByteUserId = resolveAccelByteId(playerId)

        var totalInput = 0
        var totalOutput = 0
        var totalCost = 0.0
        var totalInference = 0L
        for (u in tokenUsages)
        {
            totalInput += u.inputTokens
            totalOutput += u.outputTokens
            totalCost += ModelPricing.calculateCost(u.modelId, u.inputTokens, u.outputTokens)
            totalInference += u.inferenceTimeMs
        }

        val timestamp = System.currentTimeMillis()
        val turnKey = "MapUploadSafety_${mapId ?: "unknown"}_$timestamp"

        // Fetch current AccountSettings to evaluate the cap. The fetch uses
        // the per-session RpcInvoker — when no session is registered (test
        // rigs, curl probes) we get a blank AccountSettings and the cap
        // defaults to 0, which the check below treats as "uninitialised,
        // allow".
        val settingsForGate = currentAccountSettings(accelByteUserId)

        // T10: cap pre-check. The map upload is NOT a game-start event, so
        // we don't reuse QuotaGate.evaluate (which is game-mode-aware and
        // would route FREE-plan users to BlockNewGame because FREE doesn't
        // unlock ONE_V_ONE). The actual contract: refuse the upload when the
        // additional safety-agent tokens would push the user over their
        // per-cycle cap. Cap = 0 means uninitialised — allow.
        val currentCap = settingsForGate.billingStatus.tokensCapPerCycle
        val currentUsed = settingsForGate.billingStatus.tokensUsedThisCycle
        val additionalTokens = (totalInput + totalOutput).toLong()
        val capOk = currentCap <= 0L || (currentUsed + additionalTokens) <= currentCap
        if (!capOk)
        {
            Logger.info(
                LogCategory.DATABASE,
                "MapUploadSafetyBilling: $accelByteUserId over cap (used=$currentUsed, additional=$additionalTokens, cap=$currentCap); rejecting"
            )
            return SafetyBillingOutcome.CapExceeded(
                tokensUsedThisCycle = currentUsed,
                tokensCapPerCycle = currentCap
            )
        }

        val record = TurnBillingRecord(
            turnKey = turnKey,
            actorName = accelByteUserId,
            accelByteUserId = accelByteUserId,
            roundNumber = -1,
            turnIndex = -1,
            isNpcTurn = false,
            tokenUsages = tokenUsages,
            totalInputTokens = totalInput,
            totalOutputTokens = totalOutput,
            totalCostUsd = totalCost,
            totalInferenceTimeMs = totalInference,
            timestampMillis = timestamp
        )

        // T8: persist the ledger entry + account-settings update.
        val flushed = runCatching { flushRecord(record) }.getOrElse { err ->
            Logger.error(LogCategory.DATABASE, "MapUploadSafetyBilling: flushRecord threw for $accelByteUserId: ${err.message}")
            false
        }
        if (!flushed)
        {
            Logger.warn(
                LogCategory.DATABASE,
                "MapUploadSafetyBilling: ledger flush returned false for $accelByteUserId ($turnKey); entry NOT recorded"
            )
        }

        Logger.info(
            LogCategory.DATABASE,
            "MapUploadSafetyBilling: recorded $turnKey for $accelByteUserId — input=$totalInput output=$totalOutput cost=$%.6f flushed=$flushed".format(totalCost)
        )
        return SafetyBillingOutcome.Recorded(turnKey = turnKey, costUsd = totalCost)
    }

    /**
     * Test seam: when set, overrides the actual [RpcInvoker.invoke] call.
     * Production calls route through the per-session [RpcInvoker]; tests
     * inject a stub function via [setFakeInvokerForTest] / [setInvokeOverrideForTest].
     */
    @Volatile
    internal var invokeOverride: (suspend (String, JsonElement?) -> RpcMessage.Response)? = null

    /**
     * Fetches the user's [AccountSettings] via the active [RpcInvoker].
     * Returns a blank [AccountSettings] when the fetch fails — the cap check
     * then defaults to allowed (safe-by-default).
     */
    private suspend fun currentAccountSettings(accelByteUserId: String): AccountSettings
    {
        val params: JsonElement = RpcJson.encodeToJsonElement(GetAccountSettingsRequest.serializer(), GetAccountSettingsRequest(userId = accelByteUserId))
        val resp = invokeRpc("server.extend.getAccountSettings", params) ?: return AccountSettings(accelByteUserId = accelByteUserId)
        val settingsElement: JsonElement = resp.result ?: return AccountSettings(accelByteUserId = accelByteUserId)
        return runCatching { RpcJson.decodeFromJsonElement(AccountSettings.serializer(), settingsElement) }
            .getOrElse { AccountSettings(accelByteUserId = accelByteUserId) }
    }

    /**
     * Writes a single [TurnBillingRecord] to the user's [UsageLedger] and
     * updates [AccountSettings]. Mirrors the contract of
     * `server/.../accounting/Billing.flushTurnUsageForUser` but on the
     * server-extend JVM (no gRPC hop).
     *
     * Returns true on success, false on any failure (per-user fail-fast —
     * the cap decision already passed, so the failure is logged but the
     * gate's success path is not interrupted).
     */
    private suspend fun flushRecord(record: TurnBillingRecord): Boolean
    {
        // Probe for a working invoker via a cheap ping. If both the override
        // and the production invoker are absent, bail.
        val pingResp = invokeRpc("server.extend.getAccountSettings", Json.parseToJsonElement("{}"))
        if (pingResp == null && invokeOverride == null)
        {
            Logger.warn(LogCategory.DATABASE, "MapUploadSafetyBilling: no RpcInvoker available; skipping flush for ${record.accelByteUserId}")
            return false
        }
        val userId = record.accelByteUserId

        val getLedgerParams: JsonElement = RpcJson.encodeToJsonElement(GetUsageLedgerRequest.serializer(), GetUsageLedgerRequest(userId = userId))
        val getSettingsParams: JsonElement = RpcJson.encodeToJsonElement(GetAccountSettingsRequest.serializer(), GetAccountSettingsRequest(userId = userId))

        // Fetch the existing ledger + account settings to compute the new
        // running balance. Mirrors Billing.flushTurnUsageForUser.
        val currentLedger: UsageLedger = runCatching {
            val resp = invokeRpc("server.extend.getUsageLedger", getLedgerParams) ?: return@runCatching UsageLedger(accelByteUserId = userId)
            val element: JsonElement = resp.result ?: return@runCatching UsageLedger(accelByteUserId = userId)
            RpcJson.decodeFromJsonElement(UsageLedger.serializer(), element)
        }.getOrElse { UsageLedger(accelByteUserId = userId) }

        val currentSettings: AccountSettings = runCatching {
            val resp = invokeRpc("server.extend.getAccountSettings", getSettingsParams) ?: return@runCatching AccountSettings(accelByteUserId = userId)
            val element: JsonElement = resp.result ?: return@runCatching AccountSettings(accelByteUserId = userId)
            RpcJson.decodeFromJsonElement(AccountSettings.serializer(), element)
        }.getOrElse { AccountSettings(accelByteUserId = userId) }

        val klass = currentSettings.costClass()
        val byoKey = currentSettings.bringYourOwnApiKey
        val region = System.getenv("AB_REGION") ?: System.getenv("AWS_REGION") ?: "us-west-2"

        val creditsUsed = ModelPricing.tokensToCredits(record.totalInputTokens, record.totalOutputTokens)
        val operatorCost = ModelPricing.calculateCost(
            record.tokenUsages.firstOrNull()?.modelId ?: "",
            record.totalInputTokens,
            record.totalOutputTokens
        )
        val modelShortId = ModelPricing.resolveShortId(
            record.tokenUsages.firstOrNull()?.modelId ?: ""
        )

        var runningBalance = currentSettings.billingStatus.credits
        val creditsDelta: Double
        when (klass)
        {
            CostClass.FREE ->
            {
                val next = (runningBalance - creditsUsed).coerceAtLeast(0.0)
                creditsDelta = -creditsUsed
                runningBalance = next
            }
            else ->
            {
                creditsDelta = 0.0
                runningBalance = runningBalance
            }
        }

        val newEntry = UsageEntry(
            entryId = UUID.randomUUID().toString(),
            timestampMillis = record.timestampMillis,
            sourceLabel = "Map upload safety check",
            sourceIconKey = "agent-run",
            creditsDelta = creditsDelta,
            balanceAfter = runningBalance,
            sessionId = "map-upload-safety",
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

        val updatedLedger = UsageLedger(
            accelByteUserId = userId,
            entries = listOf(newEntry) + currentLedger.entries,
            lastUpdatedMillis = record.timestampMillis,
            maxEntries = currentLedger.maxEntries
        )

        val updatedSettings = currentSettings.copy(
            billingStatus = currentSettings.billingStatus.copy(
                credits = runningBalance,
                tokensUsedThisCycle = currentSettings.billingStatus.tokensUsedThisCycle + record.totalInputTokens + record.totalOutputTokens
            )
        )

        val saveLedgerParams: JsonElement = RpcJson.encodeToJsonElement(
            SaveUsageLedgerRequest.serializer(),
            SaveUsageLedgerRequest(
                userId = userId,
                ledgerJson = RpcJson.encodeToString(UsageLedger.serializer(), updatedLedger)
            )
        )
        val saveLedgerResp = invokeRpc("server.extend.saveUsageLedger", saveLedgerParams)
        if (saveLedgerResp == null || saveLedgerResp.error != null)
        {
            Logger.warn(LogCategory.DATABASE, "MapUploadSafetyBilling: saveUsageLedger error for $userId: ${saveLedgerResp?.error?.message}")
            return false
        }

        val saveSettingsParams: JsonElement = RpcJson.encodeToJsonElement(
            SaveAccountSettingsRequest.serializer(),
            SaveAccountSettingsRequest(
                userId = userId,
                accountSettingsJson = RpcJson.encodeToString(AccountSettings.serializer(), updatedSettings)
            )
        )
        val saveSettingsResp = invokeRpc("server.extend.saveAccountSettings", saveSettingsParams)
        if (saveSettingsResp == null || saveSettingsResp.error != null)
        {
            Logger.warn(LogCategory.DATABASE, "MapUploadSafetyBilling: saveAccountSettings error for $userId: ${saveSettingsResp?.error?.message}")
            return false
        }

        return true
    }

    /**
     * T6: resolves [playerId] (SSE connection id) → canonical AccelByte user id
     * via the connection manager. Falls back to [playerId] when the session
     * has no `accelbyteId` (curl probes, test rigs) — same debt as the rest
     * of server-extend.
     *
     * `findSession` is suspend; wrap with runBlocking so this fn stays
     * non-suspend (callers in [recordSafetyUsage] already use coroutines,
     * but the test seam in T5 expects a non-suspend accessor).
     */
    internal fun resolveAccelByteId(playerId: String): String
    {
        val manager = fakeConnectionManagerOverride ?: MapUploadSuccessHandlers.currentConnectionManager()
        val session: RestPlayerSession? = runCatching {
            runBlocking { manager?.findSession(playerId) }
        }.getOrNull()
        val accelbyteId = session?.accelbyteId
        return accelbyteId?.ifBlank { null } ?: playerId
    }

    /**
     * Dispatches an RPC call: production routes through the per-session
     * [RpcInvoker] resolved via [activeInvoker]; tests inject [invokeOverride].
     * Returns null on failure (so callers can short-circuit to blank defaults).
     */
    private suspend fun invokeRpc(method: String, params: JsonElement): RpcMessage.Response?
    {
        val override = invokeOverride
        if (override != null)
        {
            return runCatching { override(method, params) }.getOrNull()
        }
        val invoker = activeInvoker() ?: return null
        return runCatching { invoker.invoke(method, params) }.getOrNull()
    }

    /**
     * Resolves the active [RpcInvoker] for the per-session invoker of the
     * currently-registered SSE session. Returns null when no manager is
     * wired (test rigs, curl probes) OR when only a test seam is wired
     * (i.e. the test suite owns the RPC contract via [invokeOverride] or
     * [fakeInvokers] — the production connection manager is never touched
     * in that case, which keeps test isolation tight).
     *
     * The lookup uses the [MapUploadSuccessHandlers.currentConnectionManager]
     * accessor — registered at server-extend boot by the same call site that
     * wires the per-session notification path.
     */
    private fun activeInvoker(): RpcInvoker?
    {
        // Test isolation: if a fake invoker is registered, prefer it; do
        // NOT also reach for the production connection manager (which may
        // be polluted by other tests in the same JVM run).
        fakeInvokers["default"]?.let { return it }
        if (fakeConnectionManagerOverride != null)
        {
            val session = runCatching { runBlocking { fakeConnectionManagerOverride?.findSession(/* any */ "") } }.getOrNull()
            return session?.invoker
        }
        // Production path: any registered SSE session's invoker. (When
        // [MapUploadGate.uploadMapGate] wires us, the originating session
        // is the one that called uploadMapGate, so its invoker is the
        // right one.)
        val manager = MapUploadSuccessHandlers.currentConnectionManager() ?: return null
        return manager.invkForFirstSession()
    }

    internal fun setFakeInvokerForTest(invoker: RpcInvoker?)
    {
        if (invoker == null) fakeInvokers.remove("default")
        else fakeInvokers["default"] = invoker
    }

    /**
     * Test seam: inject a suspend function that returns canned
     * [RpcMessage.Response]s. Used by tests that don't need a real
     * [RpcInvoker] (since [RpcInvoker] is a final class that cannot be
     * subclassed). Records every call via [invokeOverride].
     */
    internal fun setInvokeOverrideForTest(handler: (suspend (String, JsonElement?) -> RpcMessage.Response)?)
    {
        invokeOverride = handler
    }

    internal fun setFakeConnectionManagerForTest(manager: RestPlayerConnectionManager?)
    {
        fakeConnectionManagerOverride = manager
    }

    internal fun setFakeOutcomesForTest(handler: ((playerId: String, mapId: String?, safetyPass: Boolean) -> SafetyBillingOutcome)?)
    {
        fakeOutcomeHandler = handler
    }

    /**
     * Test seam: empties any stubs set by the test suite. Called from
     * `MapUploadSafetyBilling*Test.setUp()` and `tearDown()`.
     */
    internal fun resetForTest()
    {
        fakeInvokers.clear()
        fakeConnectionManagerOverride = null
        fakeOutcomeHandler = null
    }

    private val fakeInvokers: MutableMap<String, RpcInvoker> = mutableMapOf()
    private var fakeConnectionManagerOverride: RestPlayerConnectionManager? = null
    private var fakeOutcomeHandler: ((playerId: String, mapId: String?, safetyPass: Boolean) -> SafetyBillingOutcome)? = null
}

/**
 * Outcome of a [MapUploadSafetyBilling.recordSafetyUsage] call. The gate
 * branches on [CapExceeded] to push `Map.Upload.Error` and short-circuit
 * the save; [Recorded] + [Skipped] are silent success from the gate's
 * perspective (the gate does not gate its own success/failure on whether
 * the billing write landed — failures here are tolerated with WARN logs).
 */
sealed class SafetyBillingOutcome
{
    /** Tokens were written to the user's ledger. */
    data class Recorded(val turnKey: String, val costUsd: Double) : SafetyBillingOutcome()

    /** Caller is over the per-cycle token cap. Gate should push `Map.Upload.Error`. */
    data class CapExceeded(val tokensUsedThisCycle: Long, val tokensCapPerCycle: Long) : SafetyBillingOutcome()

    /** Write was skipped (dev mode, missing trace, etc.). No-op for the gate. */
    data class Skipped(val reason: String) : SafetyBillingOutcome()
}
