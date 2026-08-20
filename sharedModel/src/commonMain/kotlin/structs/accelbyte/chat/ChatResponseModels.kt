package structs.accelbyte.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable
import structs.accelbyte.common.CursorPagination
import structs.accelbyte.common.PagingInfo

@Serializable
data class ChatMessage(
    val id: String,
    val topic: String? = null,
    val userId: String? = null,
    val message: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val type: String? = null,
    val metadata: JsonElement? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ChatMessagesPage(val messages: List<ChatMessage>, val paging: PagingInfo) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class MutedTopic(
    val topic: String,
    val mutedAt: String? = null,
    val reason: String? = null,
    val mutedBy: String? = null,
    val expiresAt: String? = null,
    val status: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class MutedTopicsResponse(val topics: List<MutedTopic>) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}