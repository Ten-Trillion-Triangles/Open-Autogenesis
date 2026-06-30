package structs.accelbyte.lobby

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable

@Serializable
data class PartyDataResponse(
    val partyId: String,
    val namespace: String,
    val leader: String,
    val members: List<String>,
    val invitees: List<String>,
    val customAttributes: JsonElement,
    val updatedAt: Int
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class LobbyMessageResponse(
    val code: String,
    val codeName: String,
    val section: String,
    val service: String,
    val text: String,
    val attributes: List<String>
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class LobbyMessageListResponse(val messages: List<LobbyMessageResponse>) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}
