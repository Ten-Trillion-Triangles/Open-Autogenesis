package structs.accelbyte.chat

import kotlinx.serialization.Serializable
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable

@Serializable
data class ChatInboxStat(
    val id: String,
    val messageRead: Int? = null,
    val messageStored: Int? = null,
    val notificationSent: Int? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class InboxStatsResponse(val data: List<ChatInboxStat>) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}