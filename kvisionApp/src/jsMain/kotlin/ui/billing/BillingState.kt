package ui.billing

import globals.AccelByteEnv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.serializer
import org.ttt.autogenesis.kvisionapp.ServerExtendBridge
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.RpcJson
import structs.account.AccountSettings
import structs.account.BillingStatus
import structs.account.GetUsageHistoryRequest
import structs.account.GetUsageHistoryResponse
import structs.account.UsageWindow
import structs.account.UsageLedger
import structs.rpcRequests.GetAccountSettingsRequest
import structs.rpcRequests.GetUsageHistoryRequestRpc
import structs.rpcRequests.SaveUsageLedgerRequest

/**
 * Global, in-memory cache of the player's billing + usage state.
 *
 * The Shop and Usage overlays read from here when they open. The cache is
 * refreshed on demand by [refreshBilling] and [refreshUsage], both of which
 * route through [ServerExtendBridge] using the same pattern as
 * [ui.CommanderDataSync].
 *
 * Listeners (e.g. the top-bar credit pill in `ui.MainMenu`) can subscribe via
 * [addListener] to redraw themselves when the cache changes.
 */
object BillingState
{
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // JS is single-threaded for our purposes; a plain `var` is sufficient.
    private var billingField: BillingStatus? = null

    /** Per-window cache of the last [GetUsageHistoryResponse]. */
    private val usageCache: MutableMap<UsageWindow, GetUsageHistoryResponse> = mutableMapOf()

    private val listeners: MutableList<() -> Unit> = mutableListOf()
    private val listenersLock = Mutex()

    /**
     * Currently cached [BillingStatus], or null when the dashboard has not yet
     * been populated.
     */
    val billing: BillingStatus? get() = billingField

    /**
     * Returns the cached response for the requested [window], or null if not
     * yet fetched.
     */
    fun cachedUsage(window: UsageWindow): GetUsageHistoryResponse? = usageCache[window]

    /**
     * Adds a listener invoked whenever the cache is updated. The listener is
     * not removed automatically; the caller must keep the returned handle and
     * call its `dispose()` to unsubscribe.
     *
     * @param listener Callback invoked on the calling thread after any update.
     * @return A [ListenerHandle] exposing a `dispose()` to unsubscribe.
     */
    fun addListener(listener: () -> Unit): ListenerHandle
    {
        scope.launch { listenersLock.withLock { listeners.add(listener) } }
        return ListenerHandle {
            scope.launch { listenersLock.withLock { listeners.remove(listener) } }
        }
    }

    /**
     * Fires the refresh helpers on a background coroutine. Safe to call
     * multiple times — concurrent refreshes are coalesced by the underlying
     * network layer.
     *
     * Called from `org.ttt.autogenesis.kvisionapp.Main` after login, before
     * the main menu is mounted.
     */
    fun loadOnLogin()
    {
        scope.launch {
            try
            {
                val billingDeferred = async { runCatching { refreshBilling() } }
                val usageDeferred = async { runCatching { refreshUsage(UsageWindow.WEEK) } }
                awaitAll(billingDeferred, usageDeferred)
                Logger.info(
                    LogCategory.DATABASE,
                    "BillingState.loadOnLogin: cache populated (credits=${billing?.credits})"
                )
            }
            catch (e: Throwable)
            {
                Logger.warn(LogCategory.DATABASE, "BillingState.loadOnLogin: refresh failed: ${e.message}")
            }
        }
    }

    /**
     * Re-fetches [AccountSettings] via the server-extend `getAccountSettings`
     * RPC and updates the in-memory [billing] cache. Notifies listeners.
     *
     * @return The freshly fetched [BillingStatus].
     */
    suspend fun refreshBilling(): BillingStatus
    {
        val userId = currentUserId()
        val settings = fetchAccountSettings(userId)
        billingField = settings.billingStatus
        notifyListeners()
        Logger.debug(
            LogCategory.DATABASE,
            "BillingState.refreshBilling: credits=${settings.billingStatus.credits} plan=${settings.billingStatus.plan}"
        )
        return settings.billingStatus
    }

    /**
     * Re-fetches the player's usage history from the server, filtered to
     * [window] and (optionally) paged with [cursorEntryId]. Caches the result
     * and notifies listeners.
     *
     * @param window Time window to fetch.
     * @param cursorEntryId Optional pagination cursor.
     * @return The fetched [GetUsageHistoryResponse].
     */
    suspend fun refreshUsage(window: UsageWindow, cursorEntryId: String? = null): GetUsageHistoryResponse
    {
        val userId = currentUserId()
        // The dashboard fetches via ServerExtendBridge; the
        // server-extend proxy is `server.extend.getUsageHistory`
        // (the main-server `usage.history.get` is unreachable from
        // the REST bridge the client uses). See
        // `proxy.UsageLedgerProxy.getUsageHistory` on server-extend
        // and the matching `usage.history.get` handler on the
        // main server; both call the shared
        // [structs.account.UsageHistoryAggregator].
        val request = GetUsageHistoryRequestRpc(
            userId = userId,
            window = window,
            cursorEntryId = cursorEntryId
        )
        val response = invokeServerRpc(
            "server.extend.getUsageHistory",
            request,
            GetUsageHistoryRequestRpc.serializer(),
            GetUsageHistoryResponse.serializer()
        )
        usageCache[window] = response
        notifyListeners()
        Logger.debug(
            LogCategory.DATABASE,
            "BillingState.refreshUsage: window=$window entries=${response.entries.size} daily=${response.daily.size} games=${response.gameSummaries.size}"
        )
        return response
    }

    /**
     * Locally applies a signed credit delta and notifies listeners. Currently
     * a UI-only mock — the real implementation will be a `usage.credits.adjust`
     * RPC once payment integration lands. Kept as a no-network helper so the
     * Shop buttons can wire to a real endpoint without rewriting the UI.
     *
     * @param creditsDelta Signed credit delta (negative for debit, positive for credit).
     */
    fun applyLocalCreditDelta(creditsDelta: Double)
    {
        val current = billingField ?: return
        val updated = current.copy(credits = (current.credits + creditsDelta).coerceAtLeast(0.0))
        billingField = updated
        notifyListeners()
        Logger.info(
            LogCategory.NETWORK,
            "BillingState.applyLocalCreditDelta: applied local delta=$creditsDelta; new balance=${updated.credits} (no server call yet)"
        )
    }

    /**
     * Mock implementation of the "save usage ledger" hook used by the Shop.
     * Routes to the server-extend `saveUsageLedger` RPC, persisting the
     * supplied ledger to cloud save. No-op if the player is a guest.
     *
     * @param ledger Ledger to persist.
     * @return True if save succeeded, false otherwise.
     */
    suspend fun saveLedger(ledger: UsageLedger): Boolean
    {
        val userId = currentUserId()
        if (userId.isBlank()) return false
        val ledgerJson = RpcJson.encodeToString(UsageLedger.serializer(), ledger)
        val request = SaveUsageLedgerRequest(userId, ledgerJson)
        val result = runCatching {
            invokeServerRpc(
                "server.extend.saveUsageLedger",
                request,
                SaveUsageLedgerRequest.serializer(),
                serializer<Boolean>()
            )
        }.getOrElse {
            Logger.warn(LogCategory.DATABASE, "BillingState.saveLedger: saveUsageLedger RPC failed: ${it.message}")
            false
        }
        Logger.info(LogCategory.DATABASE, "BillingState.saveLedger: persisted ledger (${ledger.entries.size} entries) for userId=$userId result=$result")
        return result
    }

    private suspend fun fetchAccountSettings(userId: String): AccountSettings
    {
        if (userId.isBlank()) return AccountSettings()
        val request = GetAccountSettingsRequest(userId)
        return ServerExtendBridge.withTemporaryConnection {
            val invoker = ServerExtendBridge.rpcInvoker ?: return@withTemporaryConnection AccountSettings()
            val response = invoker.invoke("server.extend.getAccountSettings", request, GetAccountSettingsRequest.serializer())
            val error = response.error
            if (error != null)
            {
                Logger.warn(LogCategory.DATABASE, "BillingState.fetchAccountSettings RPC error: ${error.message}")
                return@withTemporaryConnection AccountSettings()
            }
            response.result?.let { RpcJson.decodeFromJsonElement(AccountSettings.serializer(), it) } ?: AccountSettings()
        }
    }

    private fun notifyListeners()
    {
        scope.launch {
            val snapshot = listenersLock.withLock { listeners.toList() }
            snapshot.forEach { listener ->
                runCatching { listener() }.onFailure { Logger.warn(LogCategory.UI, "BillingState listener failed: ${it.message}") }
            }
        }
    }

    private fun currentUserId(): String = AccelByteEnv.userId

    private suspend fun <Req, Res> invokeServerRpc(
        method: String,
        request: Req,
        requestSerializer: kotlinx.serialization.KSerializer<Req>,
        responseSerializer: kotlinx.serialization.KSerializer<Res>
    ): Res
    {
        return ServerExtendBridge.withTemporaryConnection {
            val invoker = ServerExtendBridge.rpcInvoker ?: error("ServerExtendBridge has no invoker")
            val response = invoker.invoke(method, request, requestSerializer)
            val error = response.error
            if (error != null)
            {
                Logger.warn(LogCategory.NETWORK, "BillingState.invokeServerRpc($method) error: ${error.message}")
                throw IllegalStateException("RPC $method failed: ${error.message}")
            }
            response.result?.let { RpcJson.decodeFromJsonElement(responseSerializer, it) }
                ?: throw IllegalStateException("RPC $method returned no result")
        }
    }

    /**
     * Handle returned by [addListener] so callers can explicitly unsubscribe.
     */
    fun interface ListenerHandle
    {
        fun dispose()
    }
}
