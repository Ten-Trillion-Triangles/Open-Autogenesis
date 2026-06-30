package structs.accelbyte.differ

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable

@Serializable
data class DifferFileChange(
    val path: String,
    val action: String,
    val size: Int? = null,
    val checksum: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class DiffResultResponse(
    val diffId: String,
    val status: String,
    val progress: Int? = null,
    val files: List<DifferFileChange>,
    val createdAt: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class DiffResultV2Response(
    val diffId: String,
    val status: String,
    val progress: Int? = null,
    val files: List<DifferFileChange>,
    val metadata: JsonElement? = null,
    val createdAt: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class DifferHealthResponse(
    val status: String,
    val timestamp: String,
    val version: String,
    val uptime: Int? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}
