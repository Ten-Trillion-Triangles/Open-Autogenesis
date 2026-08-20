package structs.storage

import structs.account.UsageLedger

/**
 * Reusable key for the usage ledger record stored per user.
 */
internal const val USAGE_LEDGER_KEY = "usage-ledger"

/**
 * Platform-specific access to the per-user usage ledger storage.
 *
 * Implementations can configure SDK providers on JS while the JVM target routes through
 * the shared server virtual file system. Mirrors [MasterRecordStorage].
 */
expect object UsageLedgerStorage
{
    /**
     * Configures the JS SDK factory that powers the administration APIs.
     *
     * @param provider Optional factory that must return an AccelByte SDK instance on JS.
     */
    fun configureSdk(provider : (() -> Any)? = null)

    /**
     * Loads the usage ledger for the provided user id.
     *
     * @param userId AccelByte user identifier.
     * @return Result containing the decoded [UsageLedger].
     */
    suspend fun fetchUsageLedger(userId : String) : Result<UsageLedger>

    /**
     * Persists the supplied usage ledger for the provided user id.
     *
     * @param userId AccelByte user identifier.
     * @param ledger Ledger contents to save.
     * @return Result containing the persisted [UsageLedger].
     */
    suspend fun saveUsageLedger(userId : String, ledger : UsageLedger) : Result<UsageLedger>
}