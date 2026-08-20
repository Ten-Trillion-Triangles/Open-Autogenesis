package accounting

import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcDirection
import org.ttt.autogenesis.network.RpcMethod
import structs.account.GetUsageHistoryRequest
import structs.account.GetUsageHistoryResponse
import structs.account.UsageHistoryAggregator

/**
 * Server-side RPC handler that serves the in-game Usage & Plan dashboard.
 *
 * `usage.history.get` reads the player's persistent [structs.account.UsageLedger]
 * from cloud save (via [BillingSync.fetchUsageLedger]), then delegates the
 * windowing / pagination / aggregation work to [UsageHistoryAggregator] in
 * sharedModel.
 *
 * ## Why the aggregator lives in sharedModel
 *
 * The pre-fix design had the aggregation logic inlined here AND the RPC
 * was only registered in the main server's KSP output. The client
 * (BillingState) calls the dashboard via ServerExtendBridge, so every
 * fetch 404'd with "Method usage.history.get not found". The fix is:
 *  - extract the pure aggregation to sharedModel ([UsageHistoryAggregator])
 *  - keep this main-server handler as the main-server surface (gRPC
 *    callers, operator dashboard)
 *  - add a thin server-extend proxy `server.extend.getUsageHistory`
 *    that calls the same aggregator with the locally-fetched ledger
 *
 * Both code paths now share one aggregator and the dashboard fetch
 * stops 404'ing.
 */
object UsageHistoryRpcHandlers
{
    @RpcMethod(name = "usage.history.get", direction = RpcDirection.SERVER)
    suspend fun getUsageHistory(ctx: RpcCallContext, request: GetUsageHistoryRequest): GetUsageHistoryResponse
    {
        Logger.info(
            LogCategory.NETWORK,
            "UsageHistoryRpcHandlers.getUsageHistory: userId=${request.userId} window=${request.window} sessionId=${request.sessionId}"
        )

        val ledger = BillingSync.fetchUsageLedger(request.userId)
        val response = UsageHistoryAggregator.aggregate(
            ledger = ledger,
            request = request,
            now = System.currentTimeMillis(),
            liveSessionId = Billing.sessionId
        )

        Logger.info(
            LogCategory.DATABASE,
            "UsageHistoryRpcHandlers.getUsageHistory: returning ${response.entries.size} entries, " +
            "${response.daily.size} daily buckets, ${response.gameSummaries.size} game summaries " +
            "for userId=${request.userId}"
        )

        return response
    }
}