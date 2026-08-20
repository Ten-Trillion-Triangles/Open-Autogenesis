package structs.accelbyte.group

import kotlinx.serialization.Serializable
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable

@Serializable
data class GroupQueryParams(val groupName: String? = null, val limit: Int = 20) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class GroupCreateRequest(val groupName: String, val groupRegion: String) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}