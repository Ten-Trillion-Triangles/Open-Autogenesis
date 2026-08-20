package globals

import accelbyte.cloudsave.GameRecord
import commonGlobals.AwsCredentialConfig
import commonGlobals.AwsCredentialStore
import structs.accelbyte.cloudsave.AwsCredentialsRecord

/**
 * Encapsulates the CloudSave interactions for persisting and retrieving the Bedrock AWS credentials.
 */
object AwsCredentialsRepository
{
    /**
     * Key under which the AWS credentials record is stored in AccelByte CloudSave.
     */
    val recordKey: String
        get()
        {
            return System.getenv(AwsCredentialConfig.RECORD_KEY_ENV)?.takeIf { it.isNotBlank() }
                ?: AwsCredentialConfig.DEFAULT_RECORD_KEY
        }

    /**
     * Fetches the stored credentials and refreshes the shared cache.
     */
    fun fetch(): Result<AwsCredentialsRecord>
    {
        return runCatching {
            val response = GameRecord.adminFetchRecordDirect(recordKey).getOrThrow()
            AwsCredentialsRecord.fromAdminGameRecord(response)
                ?: error("Game record '$recordKey' missing AwsCredentialsRecord payload")
        }.onSuccess { record ->
            AwsCredentialStore.update(record)
        }
    }

    /**
     * Persists the provided credentials record and updates the shared cache with the saved payload.
     */
    fun save(record: AwsCredentialsRecord): Result<AwsCredentialsRecord>
    {
        return runCatching {
            val payload = record.toJsonElement()
            GameRecord.adminReplaceRecordDirect(recordKey, payload).getOrThrow()
            record
        }.onSuccess { saved ->
            AwsCredentialStore.update(saved)
        }
    }
}