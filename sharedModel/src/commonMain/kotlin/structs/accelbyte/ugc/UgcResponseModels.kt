package structs.accelbyte.ugc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable
import structs.accelbyte.common.CursorPagination

@Serializable
data class CreatorFollowStateResponse(val userId: String, val state: Boolean) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class LikeStateResponse(val userId: String, val state: Boolean) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class PreviewUrlResponse(val source: String, val url: String) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class PayloadUrlResponse(val source: String, val url: String) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ScreenshotResponseModel(
    val screenshotId: String,
    val source: String,
    val url: String,
    val description: String,
    val fileExtension: String,
    val contentType: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ContentDownloadResponseModel(
    val id: String,
    val name: String,
    val userId: String,
    val namespace: String,
    val channelId: String,
    val createdTime: String,
    val downloadCount: Int,
    val likeCount: Int,
    val shareCode: String,
    val tags: List<String> = emptyList(),
    val isOfficial: Boolean,
    val isHidden: Boolean,
    val customAttributes: JsonElement? = null,
    val fileExtension: String? = null,
    val groups: List<String>? = null,
    @SerialName("payloadURL") val payloadUrls: List<PayloadUrlResponse>? = null,
    val screenshots: List<ScreenshotResponseModel>? = null,
    val payload: String? = null,
    @SerialName("previewURL") val previewUrls: List<PreviewUrlResponse>? = null,
    val subType: String? = null,
    val type: String? = null,
    val updatedTime: String? = null,
    val creatorFollowState: CreatorFollowStateResponse? = null,
    val likeState: LikeStateResponse? = null,
    val creatorName: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ContentListResponse(val data: List<ContentDownloadResponseModel>, val paging: CursorPagination) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class PaginatedContentDownloadResponse(val data: List<ContentDownloadResponseModel>, val paging: CursorPagination) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class CreatorOverviewResponseModel(
    val id: String,
    val namespace: String,
    val parentNamespace: String,
    val followCount: Int,
    val followingCount: Int,
    val totalLikedContent: Int
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}