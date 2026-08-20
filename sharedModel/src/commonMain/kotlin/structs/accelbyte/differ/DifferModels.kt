package structs.accelbyte.differ

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable

@Serializable
data class DiffRequest(
    val sourceBuildId: String,
    val destinationBuildId: String,
    val priority: Boolean = false
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}