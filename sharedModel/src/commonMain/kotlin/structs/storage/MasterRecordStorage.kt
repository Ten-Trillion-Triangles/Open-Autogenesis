package structs.storage

/**
 * Reusable key for the master record stored per user.
 */
internal const val MASTER_RECORD_KEY = "master-record"

/**
 * Platform-specific access to the centralized master record storage for a given user.
 *
 * Implementations can configure SDK providers on JS while the JVM target routes through the
 * shared server virtual file system.
 */
expect object MasterRecordStorage
{
    /**
     * Configures the JS SDK factory that powers the administration APIs.
     *
     * @param provider Optional factory that must return an AccelByte SDK instance on JS.
     */
    fun configureSdk(provider : (() -> Any)? = null)

    /**
     * Loads the master record for the provided user id.
     *
     * @param userId AccelByte user identifier.
     * @return Result containing the decoded [MasterRecord].
     */
    suspend fun fetchMasterRecord(userId : String) : Result<MasterRecord>

    /**
     * Persists the supplied master record for the provided user id.
     *
     * @param userId AccelByte user identifier.
     * @param record Record contents to save.
     * @return Result containing the persisted [MasterRecord].
     */
    suspend fun saveMasterRecord(userId : String, record : MasterRecord) : Result<MasterRecord>
}
