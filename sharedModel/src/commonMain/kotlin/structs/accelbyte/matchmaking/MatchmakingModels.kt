package structs.accelbyte.matchmaking

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable

@Serializable
data class MatchTicketRequest(
    val matchPool: String,
    val cooldownInSec: Int? = null,
    val params: JsonElement? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class MatchTicketFilter(
    val limit: Int? = null,
    val offset: Int? = null,
    val matchPool: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}
