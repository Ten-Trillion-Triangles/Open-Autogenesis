package structs.accelbyte.event

import kotlinx.serialization.Serializable
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable

@Serializable
data class EventQueryParams(
    val startDate: String,
    val endDate: String,
    val pageSize: Int = 20
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}
