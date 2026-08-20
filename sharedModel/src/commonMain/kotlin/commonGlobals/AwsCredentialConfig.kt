package commonGlobals

/**
 * Holds configuration constants for locating the AWS Bedrock credentials game record.
 */
object AwsCredentialConfig
{
    /**
     * Environment variable that overrides the default game record key.
     */
    const val RECORD_KEY_ENV = "BEDROCK_AWS_CREDENTIAL_RECORD_KEY"

    /**
     * Default key used when the override is absent.
     */
    const val DEFAULT_RECORD_KEY = "bedrock-aws-credentials"
}