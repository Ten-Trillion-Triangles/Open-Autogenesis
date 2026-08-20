package structs.accelbyte.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ImageResponse(
    val imageUrl: String,
    val smallImageUrl: String? = null,
    @SerialName("as") val asDescription: String? = null,
    val caption: String? = null,
    val width: Int? = null,
    val height: Int? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class LocalizationResponse(
    val title: String? = null,
    val description: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}