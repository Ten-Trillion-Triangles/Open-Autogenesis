package structs.accelbyte.common

import kotlinx.serialization.Serializable

@Serializable
data class OffsetPagination(
    val next: String? = null,
    val previous: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class CursorPagination(
    val first: String,
    val last: String,
    val next: String,
    val previous: String
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}