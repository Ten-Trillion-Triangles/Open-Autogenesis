package proxy

import commonGlobals.VfsSanitizer
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcDirection
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcMethod
import org.ttt.autogenesis.server.vfs.VirtualFileSystemManager
import structs.account.GetUsageHistoryRequest
import structs.account.GetUsageHistoryResponse
import structs.account.UsageHistoryAggregator
import structs.account.UsageLedger
import structs.rpcRequests.GetUsageHistoryRequestRpc
import structs.rpcRequests.GetUsageLedgerRequest
import structs.rpcRequests.SaveUsageLedgerRequest

/**
 * Server-extend proxy that exposes the player's persistent
 * [UsageLedger] as a pair of cloud-save RPCs. Mirrors the
 * `getAccountSettings` / `saveAccountSettings` handlers in [CloudSaveProxy].
 *
 * The ledger is keyed `"usage-ledger"` in each user's record namespace and is
 * written by the game server at the end of every turn so the in-game dashboard
 * always reflects an authoritative, cross-session view of credit usage.
 */
object UsageLedgerProxy
{
    private const val LEDGER_KEY = "usage-ledger"

    /**
     * Retrieves a player's usage ledger from cloud save.
     *
     * @param context RPC call context (unused; required by [RpcMethod]).
     * @param request The request containing the user ID.
     * @return The decoded [UsageLedger], or an empty ledger owned by the user if none exists.
     */
    @RpcMethod("server.extend.getUsageLedger", RpcDirection.SERVER)
    suspend fun getUsageLedger(context: RpcCallContext, request: GetUsageLedgerRequest): UsageLedger
    {
        val userId = request.userId
        Logger.info(LogCategory.DATABASE, "UsageLedgerProxy: getUsageLedger called with userId=$userId")

        val effectiveUserId = if (userId.isBlank() || userId.startsWith("rest-client")) "guest-user" else userId
        val sanitizedKey = VfsSanitizer.sanitize(LEDGER_KEY)
        val vfs = VirtualFileSystemManager.forUser(effectiveUserId)
        val result = vfs.fetchUserRecord(effectiveUserId, sanitizedKey)

        return result.fold(
            onSuccess = { response ->
                val value = response.value
                if (value == null)
                {
                    Logger.debug(LogCategory.DATABASE, "UsageLedgerProxy: usage-ledger missing for user=$userId, returning empty")
                    UsageLedger(accelByteUserId = userId)
                }
                else
                {
                    try
                    {
                        val actualValue = if (value is kotlinx.serialization.json.JsonObject && value.containsKey("value"))
                        {
                            value["value"]!!
                        }
                        else
                        {
                            value
                        }
                        val ledger = RpcJson.decodeFromJsonElement(UsageLedger.serializer(), actualValue)
                        Logger.info(LogCategory.DATABASE, "UsageLedgerProxy: getUsageLedger succeeded for user=$userId (entries=${ledger.entries.size})")
                        ledger
                    }
                    catch (e: Exception)
                    {
                        Logger.error(LogCategory.DATABASE, "UsageLedgerProxy: Failed to deserialize usage ledger for user=$userId: ${e.message}")
                        UsageLedger(accelByteUserId = userId)
                    }
                }
            },
            onFailure = { err ->
                Logger.warn(LogCategory.DATABASE, "UsageLedgerProxy: failed to fetch usage ledger for user=$userId: ${err.message}")
                UsageLedger(accelByteUserId = userId)
            }
        )
    }

    /**
     * Persists a player's usage ledger to cloud save. The full ledger JSON is
     * supplied by the caller (typically the game server after appending a new
     * [structs.account.UsageEntry] for the just-finished turn).
     *
     * @param context RPC call context (unused; required by [RpcMethod]).
     * @param request The request containing the user ID and the JSON-encoded ledger.
     * @return True if save succeeded, false otherwise.
     */
    @RpcMethod("server.extend.saveUsageLedger", RpcDirection.SERVER)
    suspend fun saveUsageLedger(context: RpcCallContext, request: SaveUsageLedgerRequest): Boolean
    {
        val userId = request.userId
        Logger.info(LogCategory.DATABASE, "UsageLedgerProxy: saveUsageLedger called with userId=$userId")

        val effectiveUserId = if (userId.isBlank() || userId.startsWith("rest-client")) "guest-user" else userId
        val sanitizedKey = VfsSanitizer.sanitize(LEDGER_KEY)
        val vfs = VirtualFileSystemManager.forUser(effectiveUserId)
        val result = vfs.saveUserRecordFromJsonString(effectiveUserId, sanitizedKey, request.ledgerJson)

        return result.fold(
            onSuccess = {
                Logger.info(LogCategory.DATABASE, "UsageLedgerProxy: saveUsageLedger succeeded for user=$userId")
                true
            },
            onFailure = { err ->
                Logger.error(LogCategory.DATABASE, "UsageLedgerProxy: saveUsageLedger failed for user=$userId: ${err.message}")
                false
            }
        )
    }

    /**
     * Aggregates a player's [UsageLedger] into the per-turn entries,
     * per-day chart buckets, and per-game summaries the in-game Usage
     * & Plan dashboard renders.
     *
     * The client (BillingState in kvisionApp) calls the dashboard via
     * ServerExtendBridge, so the RPC surface it sees must live on
     * server-extend — the main server's `usage.history.get` was 404'ing
     * because the client was never reaching it. The aggregation logic
     * itself lives in [UsageHistoryAggregator] (sharedModel) so the
     * main server and server-extend share one code path.
     *
     * Reads the ledger directly from VFS (same source as
     * [getUsageLedger]); the main server's per-server `Billing.sessionId`
     * is not available here, so the `isLive` flag is always false on
     * this code path — the dashboard handles that as "no live game
     * visible from this bridge" and the main server handler covers
     * the gRPC path with the live flag set.
     *
     * @param context RPC call context (unused; required by [RpcMethod]).
     * @param request The aggregation request (window, limit, cursor, optional sessionId).
     * @return The dashboard-ready response, or an empty response on failure.
     */
    @RpcMethod("server.extend.getUsageHistory", RpcDirection.SERVER)
    suspend fun getUsageHistory(context: RpcCallContext, request: GetUsageHistoryRequestRpc): GetUsageHistoryResponse
    {
        val userId = request.userId
        Logger.info(
            LogCategory.DATABASE,
            "UsageLedgerProxy: getUsageHistory called with userId=$userId window=${request.window}"
        )

        val effectiveUserId = if (userId.isBlank() || userId.startsWith("rest-client")) "guest-user" else userId
        val sanitizedKey = VfsSanitizer.sanitize(LEDGER_KEY)
        val vfs = VirtualFileSystemManager.forUser(effectiveUserId)
        val result = vfs.fetchUserRecord(effectiveUserId, sanitizedKey)

        val ledger: UsageLedger = result.fold(
            onSuccess = { response ->
                val value = response.value
                if (value == null)
                {
                    Logger.debug(LogCategory.DATABASE, "UsageLedgerProxy: usage-ledger missing for user=$userId, aggregating empty ledger")
                    UsageLedger(accelByteUserId = userId)
                }
                else
                {
                    try
                    {
                        val actualValue = if (value is kotlinx.serialization.json.JsonObject && value.containsKey("value"))
                        {
                            value["value"]!!
                        }
                        else
                        {
                            value
                        }
                        RpcJson.decodeFromJsonElement(UsageLedger.serializer(), actualValue)
                    }
                    catch (e: Exception)
                    {
                        Logger.error(LogCategory.DATABASE, "UsageLedgerProxy: failed to deserialize usage ledger for user=$userId: ${e.message}")
                        UsageLedger(accelByteUserId = userId)
                    }
                }
            },
            onFailure = { err ->
                Logger.warn(LogCategory.DATABASE, "UsageLedgerProxy: failed to fetch usage ledger for user=$userId: ${err.message}")
                UsageLedger(accelByteUserId = userId)
            }
        )

        val aggregatorRequest = GetUsageHistoryRequest(
            userId = request.userId,
            window = request.window,
            limit = request.limit,
            cursorEntryId = request.cursorEntryId,
            sessionId = request.sessionId
        )

        val response = UsageHistoryAggregator.aggregate(
            ledger = ledger,
            request = aggregatorRequest,
            now = System.currentTimeMillis(),
            liveSessionId = null
        )

        Logger.info(
            LogCategory.DATABASE,
            "UsageLedgerProxy: getUsageHistory returned ${response.entries.size} entries, " +
            "${response.daily.size} daily buckets, ${response.gameSummaries.size} game summaries " +
            "for user=$userId"
        )

        return response
    }
}