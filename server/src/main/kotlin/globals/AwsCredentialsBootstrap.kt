package globals

import env.bedrockEnv
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

/**
 * Loads the AWS Bedrock credentials stored in AccelByte and injects them into Bedrock.
 */
object AwsCredentialsBootstrap
{
    private var initialized = false

    /**
     * Makes sure the Bedrock environment has the latest AWS keys before the SDK runs.
     */
    fun initialize()
    {
        if(initialized)
        {
            return
        }

        initialized = true

        AwsCredentialsLoader.load().onSuccess { record ->
            if(record.isValid())
            {
                val sanitized = record.sanitized()
                Logger.info(LogCategory.SYSTEM, "Injecting AWS Bedrock keys into bedrockEnv")
                bedrockEnv.setKeys(sanitized.accessKeyId, sanitized.secretAccessKey)
            }
            else if (record.hasKeys())
            {
                Logger.warn(LogCategory.SYSTEM, "AwsCredentialsRecord contained invalid keys (failed validation), falling back to local config")
            }
            else
            {
                Logger.info(LogCategory.SYSTEM, "AwsCredentialsRecord was empty, using local config")
            }
        }
    }
}
