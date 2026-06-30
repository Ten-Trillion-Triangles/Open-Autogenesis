package structs.accelbyte.lobby

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable

/**
 * Request to create a new party in the lobby service.
 *
 * @param namespace The namespace for the party (typically from AccelByteConfig.getNamespace()).
 * @param partyName Optional display name for the party.
 * @param maxPlayers Maximum number of players allowed in the party.
 */
@Serializable
data class CreatePartyRequest(
    val namespace: String,
    @SerialName("party_name") val partyName: String? = null,
    @SerialName("max_players") val maxPlayers: Int
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}