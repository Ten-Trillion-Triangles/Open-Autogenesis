package structs.accelbyte.gameTelemetry

import kotlinx.serialization.Serializable
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable

@Serializable
data class VersionHistoryParams(val appId: String, val comparedBuildId: String) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class BlockUrlParams(val fileType: String) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class TelemetryQueryParams(
    val eventName: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val offset: Int? = null,
    val limit: Int? = null,
    val userId: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}
