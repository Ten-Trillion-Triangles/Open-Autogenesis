package structs.accelbyte.cloudsave

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable
import structs.accelbyte.cloudsave.GameRecordRequest
import structs.accelbyte.cloudsave.GameRecordResponse

/**
 * Represents the AWS Bedrock credentials stored as an AccelByte game record.
 */
@Serializable
data class AwsCredentialsRecord(
    val accessKeyId: String,
    val secretAccessKey: String
) : AccelByteSerializable
{
    /**
     * Returns a sanitized version of this record where any 'key=' prefixes are removed.
     */
    fun sanitized(): AwsCredentialsRecord
    {
        return AwsCredentialsRecord(
            accessKeyId = accessKeyId.removePrefix("key=").trim(),
            secretAccessKey = secretAccessKey.removePrefix("key=").trim()
        )
    }

    /**
     * Serializes the record into the AccelByte JSON string format.
     */
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)

    /**
     * Encodes the record into a [JsonElement] for CloudSave requests.
     */
    fun toJsonElement(): JsonElement = AccelByteJson.encodeToJsonElement(serializer(), this)

    /**
     * Builds the CloudSave request containing the credential payload.
     */
    fun toGameRecordRequest(): GameRecordRequest =
        GameRecordRequest(data = toJsonElement())

    /**
     * Indicates whether both the access and secret keys are populated.
     */
    fun hasKeys(): Boolean = accessKeyId.isNotBlank() && secretAccessKey.isNotBlank()

    /**
     * Validates the format of the keys to ensure they are likely valid AWS credentials.
     * Checks for common error prefixes like 'key=' or placeholders like 'change_me'.
     */
    fun isValid(): Boolean
    {
        val s = sanitized()
        if (!s.hasKeys()) return false
        
        // Common placeholders
        val placeholders = listOf("change_me", "changeme", "your_access_key", "your_secret_key")
        if (placeholders.any { s.accessKeyId.contains(it, ignoreCase = true) || s.secretAccessKey.contains(it, ignoreCase = true) })
        {
            return false
        }

        // Basic length checks (AWS access keys are 20 chars, secrets are 40)
        // We allow some flexibility but these are reasonable minimums for real keys
        if (s.accessKeyId.length < 16 || s.secretAccessKey.length < 32)
        {
            return false
        }

        return true
    }

    companion object
    {
        /**
         * Deserializes the credentials from a CloudSave payload.
         */
        fun fromJsonElement(value: JsonElement?): AwsCredentialsRecord? =
            value?.let { element ->
                if (element is JsonObject && element.isEmpty())
                {
                    return null
                }

                AccelByteJson.decodeFromJsonElement(serializer(), element)
            }

        /**
         * Extracts the credentials from an AccelByte [GameRecordResponse].
         */
        fun fromGameRecord(record: GameRecordResponse): AwsCredentialsRecord? =
            fromJsonElement(record.value)

        /**
         * Extracts the credentials from an AccelByte [GameRecordAdminResponse].
         */
        fun fromAdminGameRecord(record: GameRecordAdminResponse): AwsCredentialsRecord? =
            fromJsonElement(record.value)
    }
}