package structs.accelbyte.matchmaking

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable
import structs.accelbyte.common.PagingInfo

@Serializable
data class MatchTicketResponse(
    val ticketId: String,
    val status: String,
    val estimatedWaitTime: Int? = null,
    val queuePosition: Int? = null,
    val matchPool: String? = null,
    val partyId: String? = null,
    val createdAt: String? = null,
    val matchId: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class TicketInfo(
    val ticketId: String,
    val status: String,
    val createdAt: String? = null,
    val matchPool: String? = null,
    val estimatedWaitTime: Int? = null,
    val matchId: String? = null,
    val queuePosition: Int? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class MatchTicketListResponse(val data: List<TicketInfo>, val paging: PagingInfo) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class MatchTicketCancellationResponse(
    val ticketId: String,
    val status: String,
    val cancelledAt: String,
    val reason: String
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class MatchTicketDetailsResponse(
    val ticketId: String,
    val status: String,
    val sessionID: String? = null,
    val matchFound: Boolean = false,
    val isActive: Boolean? = null,
    val serverInfo: JsonElement? = null,
    val playerAssignments: List<JsonElement> = emptyList(),
    val estimatedWaitTime: Int? = null,
    val queuePosition: Int? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}
