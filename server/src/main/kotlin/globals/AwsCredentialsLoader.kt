package globals

import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import structs.accelbyte.cloudsave.AwsCredentialsRecord

/** Loads the AWS Bedrock credentials stored in AccelByte and injects them into Bedrock. */
object AwsCredentialsLoader
{
    /**
     * Loads the credential record through [AwsCredentialsRepository], reporting the result.
     *
     * @return The [AwsCredentialsRecord] returned from AccelByte, wrapped in [Result].
     */
    fun load(): Result<AwsCredentialsRecord>
    {
        Logger.info(
            LogCategory.SYSTEM,
            "Loading AWS Bedrock credentials from game record '${AwsCredentialsRepository.recordKey}'"
        )
        return AwsCredentialsRepository.fetch().onSuccess { record ->
            Logger.info(LogCategory.SYSTEM, "AWS credentials loaded (hasKeys=${record.hasKeys()}, isValid=${record.isValid()})")
        }.onFailure { err ->
            Logger.warn(LogCategory.SYSTEM, "Failed to load AWS Bedrock credentials: ${err.message}")
        }
    }
}