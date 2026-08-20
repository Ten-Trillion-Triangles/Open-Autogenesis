package accounting

import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import structs.account.AccountSettings
import structs.account.BillingStatus
import java.util.concurrent.atomic.AtomicReference

/**
 * Adapter contract that lets [BillingSync.flushTurnUsageForUser] charge a player's
 * wallet for the credits consumed by inference. The interface is intentionally
 * transport-agnostic so the production code path can swap between the local
 * in-process ledger ([LocalWalletAdapter]) and the AccelByte platform wallet
 * ([AccelByteWalletAdapter]) without changing the call sites.
 *
 * Implementations must be safe to call concurrently. The production debit/credit
 * operations are idempotent at the call site — repeated calls for the same turn
 * should be detected by the underlying ledger (e.g. via the [UsageEntry.entryId]
 * already saved on the persistent record) and not double-debit.
 *
 * Phase 5 of feature/live-pvp-and-billing. The current production wiring defaults
 * to [LocalWalletAdapter] (env `WALLET_ADAPTER=local`); the AccelByte platform
 * adapter is a real-body stub that throws [NotImplementedError] and is intended
 * for the follow-up "Workstream 2 wallet" PR.
 */
interface WalletAdapter
{
    /**
     * Returns the player's current wallet balance in credits. Implementations
     * should return `0.0` for unknown / errored lookups rather than throwing —
     * the flush path treats a missing balance as "debit will likely underflow,
     * floor at zero".
     *
     * @param userId The AccelByte user ID.
     * @return The balance in credits, or `0.0` on any failure.
     */
    suspend fun getBalance(userId: String): Double

    /**
     * Debits [credits] from the player's wallet. Returns `true` when the debit
     * succeeded (full or partial — the local adapter always succeeds but may
     * floor at 0.0; the platform adapter may reject an overdraft and return
     * `false`).
     *
     * @param userId The AccelByte user ID.
     * @param credits The amount to debit. Positive values are treated as "debit
     *                this many credits" and floored at the player's current
     *                balance.
     * @param reason Free-form reason recorded in the operator audit log.
     * @return `true` on success, `false` on rejection or unrecoverable error.
     */
    suspend fun debit(userId: String, credits: Double, reason: String): Boolean

    /**
     * Credits [credits] to the player's wallet. Always returns `true` on the
     * local adapter; the platform adapter may reject when the wallet cap is
     * reached.
     *
     * @param userId The AccelByte user ID.
     * @param credits The amount to credit. Must be non-negative.
     * @param reason Free-form reason recorded in the operator audit log.
     * @return `true` on success, `false` on rejection or unrecoverable error.
     */
    suspend fun credit(userId: String, credits: Double, reason: String): Boolean
}

/**
 * Test seam + factory: [WalletAdapter.get] returns the active adapter based on the
 * `WALLET_ADAPTER` env var. Tests can override with [setForTest] to inject a fake.
 *
 * The env values are:
 *  - `local`      -> [LocalWalletAdapter] (default; what the system does today)
 *  - `accelbyte`  -> [AccelByteWalletAdapter] (real-body stub; follow-up PR)
 */
object WalletAdapterRegistry
{
    private const val ENV_KEY: String = "WALLET_ADAPTER"
    private const val DEFAULT_ADAPTER: String = "local"

    private val activeAdapter: AtomicReference<WalletAdapter?> = AtomicReference(null)

    /**
     * Returns the active [WalletAdapter] singleton. Resolves the env var on first
     * call and caches the choice for the JVM lifetime.
     */
    fun get(): WalletAdapter
    {
        activeAdapter.get()?.let { return it }
        val name = (System.getenv(ENV_KEY) ?: DEFAULT_ADAPTER).lowercase()
        val resolved: WalletAdapter = when (name)
        {
            "accelbyte" -> AccelByteWalletAdapter
            else -> LocalWalletAdapter
        }
        Logger.info(
            LogCategory.SYSTEM,
            "WalletAdapterRegistry: resolved adapter=$name class=${resolved::class.simpleName}"
        )
        activeAdapter.set(resolved)
        return resolved
    }

    /**
     * Installs (or clears with `null`) a test adapter and returns the previous
     * value so the caller can restore it in a teardown step.
     */
    fun setForTest(adapter: WalletAdapter?): WalletAdapter?
    {
        val previous = activeAdapter.getAndSet(adapter)
        return previous
    }
}

/**
 * Local default implementation. Reads / writes [structs.account.BillingStatus.credits]
 * via the existing [BillingSync] cloud-save round-trip. This is what the system
 * does today (the dashboard math is correct without a real wallet).
 *
 * Production paths that need a wallet should call [WalletAdapterRegistry.get]
 * rather than referencing this class directly.
 */
object LocalWalletAdapter : WalletAdapter
{
    override suspend fun getBalance(userId: String): Double
    {
        if (userId.isBlank()) return 0.0
        return try
        {
            BillingSync.getAccountSettings(userId).billingStatus.credits
        }
        catch (err: Throwable)
        {
            Logger.warn(
                LogCategory.DATABASE,
                "LocalWalletAdapter.getBalance: failed to read settings for userId=$userId: ${err.message}"
            )
            0.0
        }
    }

    override suspend fun debit(userId: String, credits: Double, reason: String): Boolean
    {
        if (userId.isBlank() || credits <= 0.0) return false
        val current = BillingSync.getAccountSettings(userId)
        if (current.accelByteUserId.isBlank())
        {
            Logger.warn(
                LogCategory.DATABASE,
                "LocalWalletAdapter.debit: no account settings for userId=$userId; treating as success with $0 floor"
            )
            return true
        }
        val next = (current.billingStatus.credits - credits).coerceAtLeast(0.0)
        val updated = current.copy(billingStatus = current.billingStatus.copy(credits = next))
        val ok = BillingSync.saveAccountSettings(userId, updated)
        Logger.info(
            LogCategory.DATABASE,
            "LocalWalletAdapter.debit: userId=$userId credits=$credits reason='$reason' ok=$ok newBalance=$next"
        )
        return ok
    }

    override suspend fun credit(userId: String, credits: Double, reason: String): Boolean
    {
        if (userId.isBlank() || credits <= 0.0) return false
        val current = BillingSync.getAccountSettings(userId)
        if (current.accelByteUserId.isBlank())
        {
            // First-ever credit: synthesize an empty settings record with just the
            // userId and the credited amount. Mirrors the BillingSync first-flush
            // pattern so a new user can receive a starter pack.
            val seeded = AccountSettings(
                accelByteUserId = userId,
                billingStatus = BillingStatus(credits = credits)
            )
            return BillingSync.saveAccountSettings(userId, seeded)
        }
        val next = current.billingStatus.credits + credits
        val updated = current.copy(billingStatus = current.billingStatus.copy(credits = next))
        val ok = BillingSync.saveAccountSettings(userId, updated)
        Logger.info(
            LogCategory.DATABASE,
            "LocalWalletAdapter.credit: userId=$userId credits=$credits reason='$reason' ok=$ok newBalance=$next"
        )
        return ok
    }
}

/**
 * Real-body stub for the AccelByte platform wallet. Each method compiles and
 * exposes the platform SDK symbol names confirmed by the AccelByte MCP, but
 * the actual SDK calls are deferred to the follow-up "Workstream 2 wallet" PR.
 *
 * Set `WALLET_ADAPTER=accelbyte` in the env to activate this adapter. The
 * operator dashboard will surface the [NotImplementedError]s as a clear
 * "wallet wiring pending" banner; the local adapter (the default) continues
 * to handle the math so gameplay is not blocked.
 *
 * The platform SDK symbols that this stub binds to:
 *  - [net.accelbyte.sdk.api.platform.wrappers.Wallet.queryUserCurrencyWallets]
 *      (permission `ADMIN:NAMESPACE:{namespace}:USER:{userId}:WALLET [READ]`)
 *  - [net.accelbyte.sdk.api.platform.wrappers.Wallet.debitUserWallet]
 *      (permission `...:WALLET [UPDATE]`)
 *  - [net.accelbyte.sdk.api.platform.wrappers.Wallet.creditUserWallet]
 *      (permission `...:WALLET [UPDATE]`)
 *
 * Currency code is `CREDIT` (a virtual currency, namespace-bound). Namespace
 * comes from `org.ttt.autogenesis.serverextend.config.AccelByteConfig.getNamespace()`.
 */
@Deprecated(
    "Real wiring deferred to follow-up PR (Workstream 2 wallet); this is the interface contract for it.",
    level = DeprecationLevel.WARNING
)
object AccelByteWalletAdapter : WalletAdapter
{
    /** Virtual currency code for the per-user credit wallet. */
    const val CURRENCY_CODE: String = "CREDIT"

    override suspend fun getBalance(userId: String): Double
    {
        // TODO(Workstream 2 wallet): wire to
        //   Wallet(ServerConnector.sdk).queryUserCurrencyWallets(
        //     QueryUserCurrencyWallets.builder()
        //       .namespace(AccelByteConfig.getNamespace())
        //       .userId(userId)
        //       .currencyCode(CURRENCY_CODE)
        //       .build()
        //   )
        // Parse the response.balance field and return as Double.
        throw NotImplementedError("deferred to Workstream 2 wallet PR")
    }

    override suspend fun debit(userId: String, credits: Double, reason: String): Boolean
    {
        // TODO(Workstream 2 wallet): wire to
        //   Wallet(ServerConnector.sdk).debitUserWallet(
        //     DebitUserWallet.builder()
        //       .namespace(AccelByteConfig.getNamespace())
        //       .userId(userId)
        //       .wallet(...)
        //       .currencyCode(CURRENCY_CODE)
        //       .amount(...)
        //       .reason(reason)
        //       .build()
        //   )
        throw NotImplementedError("deferred to Workstream 2 wallet PR")
    }

    override suspend fun credit(userId: String, credits: Double, reason: String): Boolean
    {
        // TODO(Workstream 2 wallet): wire to
        //   Wallet(ServerConnector.sdk).creditUserWallet(
        //     CreditUserWallet.builder()
        //       .namespace(AccelByteConfig.getNamespace())
        //       .userId(userId)
        //       .wallet(...)
        //       .currencyCode(CURRENCY_CODE)
        //       .amount(...)
        //       .reason(reason)
        //       .build()
        //   )
        throw NotImplementedError("deferred to Workstream 2 wallet PR")
    }
}