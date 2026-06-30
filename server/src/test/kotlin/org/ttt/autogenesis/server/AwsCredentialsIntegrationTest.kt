package org.ttt.autogenesis.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import java.io.File
import org.junit.Assume.assumeTrue
import structs.accelbyte.cloudsave.AwsCredentialsRecord

/**
 * Ensures AWS credentials can round-trip through the CloudSave record serialization used for Bedrock.
 */
class AwsCredentialsIntegrationTest
{
    private val profileName: String
        get()
        {
            return System.getenv("BEDROCK_AWS_PROFILE") ?: "bedrock"
        }

    private val credentialsFile: File
        get()
        {
            val envPath = System.getenv("BEDROCK_AWS_CREDENTIALS_FILE")
            return envPath?.let(::File)
                ?: File(System.getProperty("user.home"), ".aws/credentials")
        }

    /**
     * Verifies that a profile from ~/.aws/credentials can be converted to and from an AccelByte game record.
     */
    @Test
    fun `aws credentials can be serialized to game record request`()
    {
        assumeTrue(
            "Skipping AWS key test because ${credentialsFile.absolutePath} is missing",
            credentialsFile.exists()
        )

        val parsed = parseCredentials(credentialsFile)
        val profile = parsed[profileName] ?: parsed["default"]
        assumeTrue(
            "Skipping AWS key test because profile '$profileName' is not in ${credentialsFile.absolutePath}",
            profile != null
        )

        val resolvedProfile = profile!!

        val accessKeyId = resolvedProfile["aws_access_key_id"]?.trim()
        val secretAccessKey = resolvedProfile["aws_secret_access_key"]?.trim()
        assumeTrue(
            "Skipping AWS key test because profile '$profileName' lacks access/secret keys",
            !accessKeyId.isNullOrBlank() && !secretAccessKey.isNullOrBlank()
        )

        val record = AwsCredentialsRecord(accessKeyId!!, secretAccessKey!!)
        val request = record.toGameRecordRequest()
        val roundTripped = AwsCredentialsRecord.fromJsonElement(request.data)
        assertNotNull(roundTripped)

        assertEquals(accessKeyId, roundTripped.accessKeyId)
        assertEquals(secretAccessKey, roundTripped.secretAccessKey)
        assertEquals(record.hasKeys(), roundTripped.hasKeys())

        val jsonElement = Json.parseToJsonElement(record.toJsonString())
        assertEquals(
            record.accessKeyId,
            jsonElement.jsonObject["accessKeyId"]?.jsonPrimitive?.content
        )
    }

    /**
     * Parses the INI-style AWS credentials file into a profile map.
     */
    private fun parseCredentials(file: File): Map<String, Map<String, String>>
    {
        val result = mutableMapOf<String, MutableMap<String, String>>()
        var currentProfile: String? = null

        file.forEachLine { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";"))
            {
                return@forEachLine
            }

            if (line.startsWith("[") && line.endsWith("]"))
            {
                currentProfile = line.substring(1, line.length - 1).trim()
                if (currentProfile.isNotEmpty())
                {
                    result.putIfAbsent(currentProfile, mutableMapOf())
                }

                return@forEachLine
            }

            val profile = currentProfile ?: return@forEachLine
            val keyValue = line.split("=", limit = 2).map { it.trim() }
            if (keyValue.size == 2)
            {
                result.getOrPut(profile) { mutableMapOf() }[keyValue[0]] = keyValue[1]
            }
        }

        return result
    }
}
