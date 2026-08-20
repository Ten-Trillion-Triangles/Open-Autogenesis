package structs.accelbyte.config

import kotlinx.serialization.Serializable
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable

@Serializable
data class ConfigListParams(val limit: Int? = null, val offset: Int? = null) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class EmailConfigParams(val includeEmailTemplates: Boolean = false) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class LinkedSendersParams(val limit: Int? = null, val offset: Int? = null) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}