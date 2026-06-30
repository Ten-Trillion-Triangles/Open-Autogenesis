package structs.accelbyte.cloudsave

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable

@Serializable
data class PlayerRecordKeyInfo(
    val key: String,
    val userId: String?
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class PlayerRecordPagingInfo(
    val first: String? = null,
    val last: String? = null,
    val next: String? = null,
    val previous: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class PlayerRecordKeyList(
    val data: List<PlayerRecordKeyInfo>,
    val paging: PlayerRecordPagingInfo? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class PlayerRecordResponse(
    val key: String,
    val namespace: String? = null,
    val userId: String? = null,
    val isPublicRecord: Boolean? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val tags: List<String>? = null,
    val setBy: String? = null,
    val value: JsonElement? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class BulkPlayerRecordResponse(
    val records: List<PlayerRecordResponse>
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}
