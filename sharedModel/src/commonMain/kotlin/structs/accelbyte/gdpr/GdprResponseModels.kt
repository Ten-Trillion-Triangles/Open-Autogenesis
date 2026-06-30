package structs.accelbyte.gdpr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable

@Serializable
data class GdprDataRetrievalResponse(
    @SerialName("Namespace") val namespace: String,
    @SerialName("RequestDate") val requestDate: String,
    @SerialName("UserID") val userId: String
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class GdprS2SDataRetrievalResponse(
    val namespace: String,
    val requestDate: String,
    val requestId: String,
    val userId: String
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class GdprFinishedDataDeletion(
    val requestDate: String,
    val finishedDate: String,
    val status: String,
    val userId: String,
    val failedMessage: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class GdprFinishedDataDeletionListResponse(val data: List<GdprFinishedDataDeletion>) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}
