package network

import globals.RpcUsageTracker
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.RpcError
import org.ttt.autogenesis.network.RpcOrigin
import org.ttt.autogenesis.network.RpcTelemetrySink

/**
 * Per-session production [RpcTelemetrySink] for server-extend that feeds
 * the in-process [RpcUsageTracker] whenever a Request is dispatched.
 *
 * The sink is constructed once per session inside
 * [org.ttt.autogenesis.serverextend.RestPlayerConnectionManager.register],
 * so the [origin] captured in the constructor is the only thing the
 * sink ever reports. This avoids the
 * `sessions[requestId]?.origin` lookup shape that
 * [org.ttt.autogenesis.network.RpcTelemetrySink.onRequestSent] cannot
 * support — the `id` parameter on the sink interface is the per-RPC
 * id (a random long), not a playerId.
 *
 * The connection manager is the natural place to construct the sink
 * because that is the only place that knows the per-session origin at
 * the moment the per-session [org.ttt.autogenesis.network.RpcInvoker] is
 * built. The sink is a `val` on the invoker's `telemetry` slot, so it
 * is read on every Request dispatch and incurs no per-call allocation.
 *
 * ## What gets counted
 *
 * Only [onRequestSent] advances a counter. [onRequestCompleted]
 * and [onRequestCancelled] are explicit no-ops so the tracker
 * matches the user's framing of "rpc's coming in" as invocations
 * only. Notifications, StreamChunks, and Responses are not counted.
 */
class UsageTrackingTelemetrySink(
    private val origin : RpcOrigin
) : RpcTelemetrySink
{
    override fun onRequestSent(id : String, method : String)
    {
        when (origin)
        {
            RpcOrigin.GAME_CLIENT -> RpcUsageTracker.markClientSeen()
            RpcOrigin.GAME_SERVER -> RpcUsageTracker.markServerSeen()
        }
    }

    override fun onRequestCompleted(id : String, method : String, durationMillis : Long, error : RpcError?)
    {
        // Intentionally a no-op. The tracker advances on the *request*
        // boundary (onRequestSent), not on completion. Counting
        // completions would double-count retried requests and skew
        // the "requests per origin" semantics.
    }

    override fun onRequestCancelled(id : String, method : String, reason : String?)
    {
        // Intentionally a no-op. Cancellations are not "fresh traffic"
        // and should not reset the idle clock.
    }
}
