package structs.accelbyte.reporting

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable

@Serializable
data class PublicReasonQueryParams(val limit: Int? = null, val offset: Int? = null) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class PublicReasonGroupQueryParams(val limit: Int? = null, val offset: Int? = null) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ReportQueryParams(val category: String? = null, val status: String? = null, val limit: Int = 20) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class TicketQueryParams(val status: String? = null, val assignee: String? = null, val limit: Int = 20) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class TicketReportQueryParams(val limit: Int? = null, val offset: Int? = null) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class TicketResolutionRequest(val resolution: String, val notes: String? = null) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ModerationRuleQueryParams(
    val category: String? = null,
    val extensionCategory: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ConfigurationQueryParams(val category: String? = null) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ExtensionQueryParams(val type: String? = null, val limit: Int = 20) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}