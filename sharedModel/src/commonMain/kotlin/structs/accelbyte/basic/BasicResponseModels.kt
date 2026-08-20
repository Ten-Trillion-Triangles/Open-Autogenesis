package structs.accelbyte.basic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable
import structs.accelbyte.common.PagingInfo

@Serializable
data class NamespaceInfo(
    val id: String,
    val displayName: String,
    val status: String,
    val createdAt: String,
    val permissions: List<String>
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class NamespaceListResponse(val data: List<NamespaceInfo>, val paging: PagingInfo) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class NamespaceDetailsResponse(
    val namespace: String,
    val displayName: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    val config: JsonElement
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class PublisherInfoResponse(
    val publisherId: String,
    val publisherName: String,
    val namespace: String,
    val config: JsonElement
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class UploadSlotResponse(
    val uploadUrl: String,
    val fileId: String,
    val expiresAt: String,
    val maxFileSize: Long? = null,
    val folderPath: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class UserProfileResponse(
    val userId: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val publicProfile: JsonElement? = null,
    val createdAt: String,
    val updatedAt: String
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class PublicProfile(
    val userId: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val publicProfile: JsonElement? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class UserProfilesResponse(val data: List<PublicProfile>) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}