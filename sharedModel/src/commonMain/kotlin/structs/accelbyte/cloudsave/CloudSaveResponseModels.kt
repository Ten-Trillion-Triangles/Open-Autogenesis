package structs.accelbyte.cloudsave

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable

@Serializable
data class GameRecordResponse(
    val key: String,
    val value: JsonElement? = null,
    val tags: List<String>? = null,
    val setBy: String? = null,
    val version: Int? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val namespace: String? = null,
    val userId: String? = null,
    @SerialName("isPublic") val isPublicRecord: Boolean? = null,
    val checksum: String? = null,
    val metadata: JsonElement? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class GameRecordListResponse(val records: List<GameRecordResponse>) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class BulkGameRecordResponse(val records: List<GameRecordResponse>, val failed: List<JsonElement>? = null) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}