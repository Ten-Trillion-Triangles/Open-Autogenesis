package structs.accelbyte.cloudsave

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable

@Serializable
data class GameRecordKeyList(
    val keys: List<String>,
    val paging: GameRecordPagingInfo? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class GameRecordPagingInfo(
    val first: String? = null,
    val last: String? = null,
    val next: String? = null,
    val previous: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class GameRecordTtlConfig(
    val action: String? = null,
    val expiresAt: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class GameRecordAdminResponse(
    val key: String,
    val namespace: String? = null,
    val tags: List<String>? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val setBy: String? = null,
    val ttlConfig: GameRecordTtlConfig? = null,
    val value: JsonElement? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}