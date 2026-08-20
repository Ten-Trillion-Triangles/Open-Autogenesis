package structs.accelbyte.qosm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable

@Serializable
data class QosmServerResponse(
    val alias: String,
    val ip: String,
    val port: Int,
    val region: String,
    val status: String,
    @SerialName("last_update") val lastUpdate: String
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class QosmServerListResponse(val servers: List<QosmServerResponse>) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}