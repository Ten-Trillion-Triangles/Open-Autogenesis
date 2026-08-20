package commonGlobals

import structs.accelbyte.cloudsave.AwsCredentialsRecord
import structs.accelbyte.cloudsave.GameRecordRequest

import kotlin.concurrent.Volatile

/**
 * Keeps the in-memory copy of the AWS Bedrock credentials shared across modules.
 */
object AwsCredentialStore
{
    @Volatile
    private var cachedRecord: AwsCredentialsRecord? = null

    /**
     * Returns the current cached credential record, or `null` if not yet loaded.
     */
    fun current(): AwsCredentialsRecord? = cachedRecord

    /**
     * Updates the cache with a new credential record.
     */
    fun update(record: AwsCredentialsRecord)
    {
        if(record.hasKeys())
        {
            cachedRecord = record
        }
    }

    /**
     * Clears the stored credentials (useful for tests).
     */
    fun clear()
    {
        cachedRecord = null
    }

    /**
     * Determines whether the cache currently holds a complete credential pair.
     */
    fun hasValue(): Boolean = cachedRecord?.hasKeys() == true

    /**
     * Creates a game record request for the stored credentials, or `null` when empty.
     */
    fun toGameRecordRequest(): GameRecordRequest? = cachedRecord?.toGameRecordRequest()
}