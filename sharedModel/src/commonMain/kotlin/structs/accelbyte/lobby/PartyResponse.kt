package structs.accelbyte.lobby

import kotlinx.serialization.Serializable
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable

@Serializable
data class PartyResponse(
    val partyId: String,
    val partyName: String?,
    val members: List<String>
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}
