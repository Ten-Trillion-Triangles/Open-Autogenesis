package structs.accelbyte.gameTelemetry

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable
import structs.accelbyte.common.PagingInfo

@Serializable
data class TelemetryPlaytimeResponse(
    val steamId: String,
    val playtimeSeconds: Int? = null,
    val updatedAt: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class TelemetryProtectedEventResult(
    val processed: Boolean? = null,
    val eventIds: List<String>
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class TelemetryNamespaceListResponse(val namespaces: List<String>) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class TelemetryEventRecord(
    val eventId: String,
    val eventName: String,
    val eventNamespace: String,
    val timestamp: String,
    val flightId: String? = null,
    val payload: JsonElement? = null,
    val userId: String? = null,
    val userNamespace: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class TelemetryEventListResponse(val data: List<TelemetryEventRecord>, val paging: PagingInfo) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}