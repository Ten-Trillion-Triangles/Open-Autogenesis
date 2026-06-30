package structs.accelbyte.buildinfo

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable

@Serializable
data class BuildVersionEntry(
    val buildId: String,
    val version: String,
    val createdAt: String,
    val size: Int? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class VersionHistoryResponse(val versions: List<BuildVersionEntry>) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class DiffStatusResponse(
    val status: String,
    val progress: Int? = null,
    val diffSize: Int? = null,
    val estimatedTime: Int? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class BlockDownloadUrlResponse(
    val url: String,
    val expiresAt: String,
    val blockType: String,
    val compressed: Boolean
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class CacheDiffFile(
    val path: String,
    val action: String,
    val size: Int? = null,
    val checksum: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class CacheDiffResponse(val files: List<CacheDiffFile>) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}
