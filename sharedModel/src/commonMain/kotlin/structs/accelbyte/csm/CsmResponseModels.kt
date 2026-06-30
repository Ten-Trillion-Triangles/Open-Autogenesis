package structs.accelbyte.csm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable
import structs.accelbyte.common.PagingInfo

@Serializable
data class CsmMessage(
    val id: String,
    val content: String,
    val type: String,
    val createdAt: String,
    val status: String,
    val priority: String
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class CsmMessageListResponse(val messages: List<CsmMessage>, val paging: PagingInfo) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}
