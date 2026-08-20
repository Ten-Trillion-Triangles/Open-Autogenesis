package structs.accelbyte.event

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable
import structs.accelbyte.common.CursorPagination
import structs.accelbyte.common.PagingInfo

@Serializable
data class EventEntry(
    val eventId: Double,
    val eventType: String,
    val userId: String,
    val timestamp: String,
    val payload: JsonElement? = null,
    val namespace: String? = null,
    val sessionId: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class EventDateRange(val startDate: String, val endDate: String) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class EventListResponse(
    val events: List<EventEntry>,
    val paging: PagingInfo,
    val dateRange: EventDateRange? = null,
    val userId: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class EventMetadata(
    val source: String? = null,
    val version: String? = null,
    val correlationId: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class EventDetailResponse(
    val eventId: String,
    val eventType: String,
    val userId: String,
    val timestamp: String,
    val payload: JsonElement? = null,
    val metadata: EventMetadata? = null,
    val context: JsonElement? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}