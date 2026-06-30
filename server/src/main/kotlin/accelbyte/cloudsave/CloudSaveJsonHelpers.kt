package accelbyte.cloudsave

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer
import kotlin.PublishedApi
import net.accelbyte.sdk.api.cloudsave.models.ModelsBulkGetGameRecordRequest
import net.accelbyte.sdk.api.cloudsave.models.ModelsBulkGetPlayerRecordResponse
import net.accelbyte.sdk.api.cloudsave.models.ModelsBinaryRecordRequest
import net.accelbyte.sdk.api.cloudsave.models.ModelsGameRecordRequest
import net.accelbyte.sdk.api.cloudsave.models.ModelsListPlayerRecordKeysResponse
import net.accelbyte.sdk.api.cloudsave.models.ModelsPlayerRecordKeyInfo
import net.accelbyte.sdk.api.cloudsave.models.ModelsPlayerRecordRequest
import net.accelbyte.sdk.api.cloudsave.models.ModelsPlayerRecordResponse
import net.accelbyte.sdk.api.cloudsave.models.ModelsPagination
import net.accelbyte.sdk.api.cloudsave.models.ModelsPublicGameBinaryRecordCreate
import net.accelbyte.sdk.api.cloudsave.models.ModelsGameBinaryRecordCreate
import net.accelbyte.sdk.api.cloudsave.models.ModelsGameBinaryRecordMetadataRequest
import net.accelbyte.sdk.api.cloudsave.models.ModelsTTLConfigDTO
import net.accelbyte.sdk.api.cloudsave.models.ModelsUploadBinaryRecordRequest
import net.accelbyte.sdk.api.cloudsave.models.ModelsAdminPlayerRecordResponse
import net.accelbyte.sdk.api.cloudsave.models.ModelsBulkGetAdminPlayerRecordResponse
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import structs.accelbyte.cloudsave.*
import structs.accelbyte.common.AccelByteJson

internal fun GameRecordRequest.toModelsGameRecordRequest() : ModelsGameRecordRequest
{
    return ModelsGameRecordRequest().createFromJson(toJsonString())
}

internal fun BinaryRecordRequest.toModelsBinaryRecordRequest() : ModelsBinaryRecordRequest
{
    return ModelsBinaryRecordRequest().createFromJson(toJsonString())
}

internal fun GameBinaryRecordCreateRequest.toModelsPublicGameBinaryRecordCreate() : ModelsPublicGameBinaryRecordCreate
{
    return ModelsPublicGameBinaryRecordCreate().createFromJson(toJsonString())
}

internal fun GameBinaryRecordCreateRequest.toModelsGameBinaryRecordCreate() : ModelsGameBinaryRecordCreate
{
    return ModelsGameBinaryRecordCreate().createFromJson(toJsonString())
}

internal fun UploadBinaryRecordRequest.toModelsUploadBinaryRecordRequest() : ModelsUploadBinaryRecordRequest
{
    return ModelsUploadBinaryRecordRequest().createFromJson(toJsonString())
}

internal fun GameBinaryRecordMetadata.toModelsMetadataRequest() : ModelsGameBinaryRecordMetadataRequest
{
    return ModelsGameBinaryRecordMetadataRequest().createFromJson(toJsonString())
}

internal fun ModelsTTLConfigDTO?.toGameRecordTtlConfig() : GameRecordTtlConfig?
{
    return this?.let { GameRecordTtlConfig(action = it.action, expiresAt = it.expiresAt) }
}

internal fun BulkGameRecordRequest.toModelsBulkRequest() : ModelsBulkGetGameRecordRequest
{
    return ModelsBulkGetGameRecordRequest().createFromJson(toJsonString())
}

internal fun JsonElement.toModelsAdminConcurrentRecordRequest() : net.accelbyte.sdk.api.cloudsave.models.ModelsAdminConcurrentRecordRequest
{
    // Use Jackson to avoid kotlinx.serialization issues with Any type
    val jsonString = AccelByteJson.encodeToString(this)
    Logger.debug(LogCategory.DATABASE, "Creating concurrent request with value field: $jsonString")
    
    // Use Jackson ObjectMapper to parse the JSON into a Map
    val objectMapper = com.fasterxml.jackson.databind.ObjectMapper()
    val valueMap = objectMapper.readValue(jsonString, Map::class.java) as Map<String, Any>
    
    // Create the concrete request with the data in the 'value' field
    val concreteRequest = net.accelbyte.sdk.api.cloudsave.models.ModelsAdminConcurrentRecordRequest.builder()
        .value(valueMap)
        .setBy("SERVER")
        .build()
    
    Logger.debug(LogCategory.DATABASE, "Created concurrent request: $concreteRequest")
    return concreteRequest
}

internal fun JsonElement.toModelsPlayerRecordRequest() : ModelsPlayerRecordRequest
{
    // The base ModelsPlayerRecordRequest is empty, so just return an empty instance
    // This is why records were saving as empty - the base class has no fields
    Logger.debug(LogCategory.DATABASE, "Creating empty ModelsPlayerRecordRequest (base class has no fields)")
    return ModelsPlayerRecordRequest()
}

@PublishedApi
internal fun <T> T.toPlayerRecordRequestWithSerializer(serializer : KSerializer<T>) : ModelsPlayerRecordRequest
{
    return ModelsPlayerRecordRequest().createFromJson(AccelByteJson.encodeToString(serializer, this))
}

@PublishedApi
internal inline fun <reified T> T.toPlayerRecordRequest(serializer : KSerializer<T>? = null) : ModelsPlayerRecordRequest
{
    return toPlayerRecordRequestWithSerializer(serializer ?: serializer())
}

internal fun Any?.toJsonElement() : JsonElement
{
    return when (this)
    {
        null -> JsonNull
        is JsonElement -> this
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is String -> JsonPrimitive(this)
        is Map<*, *> -> buildJsonObject {
            this@toJsonElement.forEach { (key, value) ->
                (key as? String)?.let { put(it, value.toJsonElement()) }
            }
        }
        is Iterable<*> -> buildJsonArray {
            this@toJsonElement.forEach { add(it.toJsonElement()) }
        }
        is Array<*> -> buildJsonArray {
            this@toJsonElement.forEach { add(it.toJsonElement()) }
        }
        else -> JsonPrimitive(toString())
    }
}

internal fun ModelsListPlayerRecordKeysResponse.toKeyList() : PlayerRecordKeyList
{
    return PlayerRecordKeyList(
        data = data?.map { it.toSharedKey() } ?: emptyList(),
        paging = paging?.toPaging()
    )
}

internal fun ModelsBulkGetPlayerRecordResponse.toBulkResponse() : BulkPlayerRecordResponse
{
    return BulkPlayerRecordResponse(records = data?.map { it.toSharedRecord() } ?: emptyList())
}

internal fun ModelsBulkGetAdminPlayerRecordResponse.toBulkResponse() : BulkPlayerRecordResponse
{
    return BulkPlayerRecordResponse(records = data?.map { it.toSharedRecord() } ?: emptyList())
}

internal fun net.accelbyte.sdk.api.cloudsave.models.ModelsPlayerRecordConcurrentUpdateResponse.toSharedRecord(key: String, namespace: String, userId: String, value: JsonElement?) : PlayerRecordResponse
{
    return PlayerRecordResponse(
        key = key,
        namespace = namespace,
        userId = userId,
        isPublicRecord = false,
        createdAt = "",
        updatedAt = updatedAt ?: "",
        tags = null,
        setBy = "SERVER",
        value = value
    )
}

internal fun ModelsPlayerRecordResponse.toSharedRecord() : PlayerRecordResponse
{
    return PlayerRecordResponse(
        key = key,
        namespace = namespace,
        userId = userId,
        isPublicRecord = isPublic,
        createdAt = createdAt,
        updatedAt = updatedAt,
        tags = tags,
        setBy = setBy,
        value = value?.toJsonElement()
    )
}

internal fun ModelsAdminPlayerRecordResponse.toSharedRecord() : PlayerRecordResponse
{
    return PlayerRecordResponse(
        key = key,
        namespace = namespace,
        userId = userId,
        isPublicRecord = null,
        createdAt = createdAt,
        updatedAt = updatedAt,
        tags = tags,
        setBy = null,
        value = value?.toJsonElement()
    )
}

internal fun ModelsPlayerRecordKeyInfo.toSharedKey() : PlayerRecordKeyInfo
{
    return PlayerRecordKeyInfo(key, userId)
}

internal fun ModelsPagination.toPaging() : PlayerRecordPagingInfo
{
    return PlayerRecordPagingInfo(first, last, next, previous)
}
