package structs.accelbyte.reporting

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable
import structs.accelbyte.common.CursorPagination
import structs.accelbyte.common.PagingInfo

@Serializable
data class ReportResponse(
    val additionalInfo: JsonElement,
    val category: String,
    val comment: String,
    val createdAt: String,
    val extensionCategory: String? = null,
    val id: String,
    val namespace: String,
    val objectId: String,
    val objectType: String,
    val reason: String,
    val reporterId: String,
    val ticketId: String,
    val updatedAt: String,
    val userId: String
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ReportListResponse(val data: List<ReportResponse>, val paging: CursorPagination) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class TicketResponse(
    val category: String,
    val createdAt: String,
    val extensionCategory: String? = null,
    val id: String,
    val namespace: String,
    val notes: String,
    val objectId: String,
    val objectType: String,
    val reportsCount: Int,
    val status: String,
    val updatedAt: String,
    val userId: String
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class TicketListResponse(val data: List<TicketResponse>, val paging: CursorPagination) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class TicketStatisticResponse(val moderatedCount: Int, val openCount: Int, val totalCount: Int) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class PublicReason(val title: String, val description: String) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class PublicReasonListResponse(val data: List<PublicReason>, val paging: CursorPagination) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class PublicReasonGroup(val id: String, val title: String) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ReasonGroupListResponse(val data: List<PublicReasonGroup>, val paging: CursorPagination) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class AdminReason(
    val createdAt: String,
    val description: String,
    val groups: List<PublicReasonGroup>? = null,
    val id: String,
    val namespace: String,
    val title: String,
    val updatedAt: String
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class AdminReasonListResponse(val data: List<AdminReason>, val paging: CursorPagination) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class AdminAllReasonsResponse(val data: List<PublicReason>) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class UnusedReasonListResponse(val reasons: List<PublicReason>) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ExtensionCategory(
    val extensionCategory: String,
    val extensionCategoryName: String,
    val serviceSource: String
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ExtensionCategoryListResponse(val data: List<ExtensionCategory>) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ActionItem(val actionId: String, val actionName: String, val eventName: String) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ActionListResponse(val data: List<ActionItem>) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class CategoryLimit(
    val extensionCategory: String? = null,
    val maxReportPerTicket: Int,
    val name: String
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ReportingLimit(
    val categoryLimits: List<CategoryLimit>,
    val timeInterval: Int,
    val userMaxReportPerTimeInterval: Int
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ConfigResponse(val namespace: String, val reportingLimit: ReportingLimit, val updatedAt: String) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class BanAccountAction(
    val comment: String? = null,
    val duration: Int,
    val reason: String,
    val skipNotif: Boolean,
    val type: String
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ModerationRuleActionsResponse(
    val banAccount: BanAccountAction? = null,
    val deleteChat: Boolean? = null,
    val extensionActionIds: List<String>? = null,
    val hideContent: Boolean? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}
