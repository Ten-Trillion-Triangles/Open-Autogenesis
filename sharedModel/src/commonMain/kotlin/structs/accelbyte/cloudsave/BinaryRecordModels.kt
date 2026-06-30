package structs.accelbyte.cloudsave

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable

@Serializable
data class BinaryInfo(
    val contentType: String,
    val createdAt: String,
    val fileLocation: String,
    val updatedAt: String,
    val url: String? = null,
    val version: Int
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class GameBinaryRecordResponse(
    val key: String,
    val namespace: String? = null,
    val binaryInfo: BinaryInfo? = null,
    val tags: List<String>? = null,
    val setBy: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class GameBinaryRecordListResponse(
    val data: List<GameBinaryRecordResponse>,
    val paging: GameRecordPagingInfo? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class BulkGameBinaryRecordResponse(
    val records: List<GameBinaryRecordResponse>
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class UploadBinaryRecordResponse(
    val contentType: String,
    val fileLocation: String,
    val url: String,
    val version: Int
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class BinaryRecordRequest(
    @SerialName("content_type") val contentType: String,
    @SerialName("file_location") val fileLocation: String
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class UploadBinaryRecordRequest(
    @SerialName("file_type") val fileType: String
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class GameBinaryRecordCreateRequest(
    val key: String,
    @SerialName("file_type") val fileType: String,
    @SerialName("set_by") val setBy: String? = null,
    @SerialName("ttl_config") val ttlConfig: GameRecordTtlConfig? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class GameBinaryRecordAdminResponse(
    val key: String,
    val namespace: String? = null,
    val binaryInfo: BinaryInfo? = null,
    val tags: List<String>? = null,
    val setBy: String? = null,
    val ttlConfig: GameRecordTtlConfig? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class GameBinaryRecordAdminListResponse(
    val records: List<GameBinaryRecordAdminResponse>,
    val paging: GameRecordPagingInfo? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class GameBinaryRecordMetadata(
    val setBy: String? = null,
    val tags: List<String>? = null,
    val ttlConfig: GameRecordTtlConfig? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}
