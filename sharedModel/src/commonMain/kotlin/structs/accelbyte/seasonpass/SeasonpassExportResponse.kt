package structs.accelbyte.seasonpass

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable

@Serializable
data class SeasonpassExportResponse(val payload: JsonElement) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}