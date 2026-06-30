package structs.rpcRequests

import kotlinx.serialization.Serializable

/**
 * Request to fetch a player's [structs.account.UsageLedger] from cloud save via server-extend.
 *
 * @param userId The AccelByte user ID.
 */
@Serializable
data class GetUsageLedgerRequest(val userId: String)

/**
 * Request to persist a player's [structs.account.UsageLedger] to cloud save via server-extend.
 *
 * @param userId The AccelByte user ID.
 * @param ledgerJson JSON-encoded [structs.account.UsageLedger].
 */
@Serializable
data class SaveUsageLedgerRequest(val userId: String, val ledgerJson: String)

/**
 * Server-extend RPC payload for `server.extend.getUsageHistory`.
 *
 * Mirrors [structs.account.GetUsageHistoryRequest] but is its own type
 * so the client-side serializer registers the exact payload shape
 * the server-extend handler expects (vs. the main-server shape used
 * by `usage.history.get` over gRPC). They have the same field set
 * today, but keeping them split means a future divergence (extra
 * field, dropped field, naming change) only touches the bridge it
 * applies to.
 */
@Serializable
data class GetUsageHistoryRequestRpc(
    val userId: String,
    val window: structs.account.UsageWindow = structs.account.UsageWindow.WEEK,
    val limit: Int = 50,
    val cursorEntryId: String? = null,
    val sessionId: String? = null
)
