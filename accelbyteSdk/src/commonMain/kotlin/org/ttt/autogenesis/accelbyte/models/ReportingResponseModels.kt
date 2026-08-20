package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json
/**
 * Represents a single public report submitted by a player, including metadata and identifiers.
 *
 * @property additionalInfo Additional context about the report as JSON.
 * @property category The category of the report.
 * @property comment The comment provided by the reporter.
 * @property createdAt Timestamp when the report was created.
 * @property extensionCategory The extension category if applicable.
 * @property id The unique identifier of this report.
 * @property namespace The namespace where this report was created.
 * @property objectId The identifier of the object being reported.
 * @property objectType The type of object being reported.
 * @property reason The reason for the report.
 * @property reporterId The identifier of the reporter.
 * @property ticketId The ticket ID this report is associated with.
 * @property updatedAt Timestamp when the report was last updated.
 * @property userId The user ID of the reporter.
 */
data class ReportResponse(
    val additionalInfo : Json,
    val category : String,
    val comment : String,
    val createdAt : String,
    val extensionCategory : String?,
    val id : String,
    val namespace : String,
    val objectId : String,
    val objectType : String,
    val reason : String,
    val reporterId : String,
    val ticketId : String,
    val updatedAt : String,
    val userId : String
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : ReportResponse = ReportResponse(
            additionalInfo = json.requireJson("additionalInfo"),
            category = json.requireString("category"),
            comment = json.requireString("comment"),
            createdAt = json.requireString("createdAt"),
            extensionCategory = json.optString("extensionCategory"),
            id = json.requireString("id"),
            namespace = json.requireString("namespace"),
            objectId = json.requireString("objectId"),
            objectType = json.requireString("objectType"),
            reason = json.requireString("reason"),
            reporterId = json.requireString("reporterId"),
            ticketId = json.requireString("ticketId"),
            updatedAt = json.requireString("updatedAt"),
            userId = json.requireString("userId")
        )
    }
}

/**
 * Paginated list of reports returned by admin report queries.
 *
 * @property data The list of reports on this page.
 * @property paging Pagination metadata for navigating additional pages.
 */
data class ReportListResponse(
    val data : List<ReportResponse>,
    val paging : CursorPagination
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : ReportListResponse = ReportListResponse(
            data = json.optJsonList("data").map(ReportResponse::fromJson),
            paging = CursorPagination.fromJson(json.requireJson("paging"))
        )
    }
}

/**
 * Detailed ticket information for a moderation request, with status and linked reports.
 *
 * @property category The category of this ticket.
 * @property createdAt Timestamp when the ticket was created.
 * @property extensionCategory The extension category if applicable.
 * @property id The unique identifier of this ticket.
 * @property namespace The namespace where this ticket exists.
 * @property notes Additional notes on this ticket.
 * @property objectId The identifier of the object being moderated.
 * @property objectType The type of object being moderated.
 * @property reportsCount The number of reports linked to this ticket.
 * @property status The current status of the ticket.
 * @property updatedAt Timestamp when the ticket was last updated.
 * @property userId The user ID associated with this ticket.
 */
data class TicketResponse(
    val category : String,
    val createdAt : String,
    val extensionCategory : String?,
    val id : String,
    val namespace : String,
    val notes : String,
    val objectId : String,
    val objectType : String,
    val reportsCount : Int,
    val status : String,
    val updatedAt : String,
    val userId : String
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : TicketResponse = TicketResponse(
            category = json.requireString("category"),
            createdAt = json.requireString("createdAt"),
            extensionCategory = json.optString("extensionCategory"),
            id = json.requireString("id"),
            namespace = json.requireString("namespace"),
            notes = json.requireString("notes"),
            objectId = json.requireString("objectId"),
            objectType = json.requireString("objectType"),
            reportsCount = json.requireInt("reportsCount"),
            status = json.requireString("status"),
            updatedAt = json.requireString("updatedAt"),
            userId = json.requireString("userId")
        )
    }
}

/**
 * Paginated list of moderation tickets generated from player reports.
 *
 * @property data The list of tickets on this page.
 * @property paging Pagination metadata for navigating additional pages.
 */
data class TicketListResponse(
    val data : List<TicketResponse>,
    val paging : CursorPagination
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : TicketListResponse = TicketListResponse(
            data = json.optJsonList("data").map(TicketResponse::fromJson),
            paging = CursorPagination.fromJson(json.requireJson("paging"))
        )
    }
}

/**
 * Aggregated ticket statistics (open/moderated/total) for dashboards.
 *
 * @property moderatedCount The number of moderated tickets.
 * @property openCount The number of open tickets.
 * @property totalCount The total number of tickets.
 */
data class TicketStatisticResponse(
    val moderatedCount : Int,
    val openCount : Int,
    val totalCount : Int
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : TicketStatisticResponse = TicketStatisticResponse(
            moderatedCount = json.requireInt("moderatedCount"),
            openCount = json.requireInt("openCount"),
            totalCount = json.requireInt("totalCount")
        )
    }
}

/**
 * Publicly visible reason entry describing why a report category exists.
 *
 * @property title The title of the public reason.
 * @property description A description of the public reason.
 */
data class PublicReason(
    val title : String,
    val description : String
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : PublicReason = PublicReason(
            title = json.requireString("title"),
            description = json.requireString("description")
        )
    }
}

/**
 * Paginated list of public reasons returned to clients.
 *
 * @property data The list of public reasons on this page.
 * @property paging Pagination metadata for navigating additional pages.
 */
data class PublicReasonListResponse(
    val data : List<PublicReason>,
    val paging : CursorPagination
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : PublicReasonListResponse = PublicReasonListResponse(
            data = json.optJsonList("data").map(PublicReason::fromJson),
            paging = CursorPagination.fromJson(json.requireJson("paging"))
        )
    }
}

/**
 * Group reference that ties multiple public reasons under a shared heading.
 *
 * @property id The unique identifier of this reason group.
 * @property title The title of the reason group.
 */
data class PublicReasonGroup(
    val id : String,
    val title : String
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : PublicReasonGroup = PublicReasonGroup(
            id = json.requireString("id"),
            title = json.requireString("title")
        )
    }
}

/**
 * Paginated list of reason groups exported through public reason APIs.
 *
 * @property data The list of reason groups on this page.
 * @property paging Pagination metadata for navigating additional pages.
 */
data class ReasonGroupListResponse(
    val data : List<PublicReasonGroup>,
    val paging : CursorPagination
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : ReasonGroupListResponse = ReasonGroupListResponse(
            data = json.optJsonList("data").map(PublicReasonGroup::fromJson),
            paging = CursorPagination.fromJson(json.requireJson("paging"))
        )
    }
}

/**
 * Admin-specific reason entry that can be attached to moderation rules.
 *
 * @property createdAt Timestamp when this reason was created.
 * @property description The description of this admin reason.
 * @property groups The reason groups this admin reason belongs to.
 * @property id The unique identifier of this admin reason.
 * @property namespace The namespace where this reason exists.
 * @property title The title of this admin reason.
 * @property updatedAt Timestamp when this reason was last updated.
 */
data class AdminReason(
    val createdAt : String,
    val description : String,
    val groups : List<PublicReasonGroup>?,
    val id : String,
    val namespace : String,
    val title : String,
    val updatedAt : String
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : AdminReason = AdminReason(
            createdAt = json.requireString("createdAt"),
            description = json.requireString("description"),
            groups = json.optJsonList("groups").ifEmpty { null }?.map(PublicReasonGroup::fromJson),
            id = json.requireString("id"),
            namespace = json.requireString("namespace"),
            title = json.requireString("title"),
            updatedAt = json.requireString("updatedAt")
        )
    }
}

/**
 * Paginated list of admin reasons used internally.
 *
 * @property data The list of admin reasons on this page.
 * @property paging Pagination metadata for navigating additional pages.
 */
data class AdminReasonListResponse(
    val data : List<AdminReason>,
    val paging : CursorPagination
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : AdminReasonListResponse = AdminReasonListResponse(
            data = json.optJsonList("data").map(AdminReason::fromJson),
            paging = CursorPagination.fromJson(json.requireJson("paging"))
        )
    }
}

/**
 * Collection of all active public reasons referenced in admin tools.
 *
 * @property data The list of all public reasons.
 */
data class AdminAllReasonsResponse(
    val data : List<PublicReason>
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : AdminAllReasonsResponse = AdminAllReasonsResponse(
            data = json.optJsonList("data").map(PublicReason::fromJson)
        )
    }
}

/**
 * Collection of reasons that currently lack active assignments.
 *
 * @property reasons The list of unused reasons.
 */
data class UnusedReasonListResponse(
    val reasons : List<PublicReason>
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : UnusedReasonListResponse = UnusedReasonListResponse(
            reasons = json.optJsonList("reasons").map(PublicReason::fromJson)
        )
    }
}

/**
 * Extension category metadata for auto-moderation tooling.
 *
 * @property extensionCategory The extension category identifier.
 * @property extensionCategoryName The display name of the extension category.
 * @property serviceSource The source service for this category.
 */
data class ExtensionCategory(
    val extensionCategory : String,
    val extensionCategoryName : String,
    val serviceSource : String
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : ExtensionCategory = ExtensionCategory(
            extensionCategory = json.requireString("extensionCategory"),
            extensionCategoryName = json.requireString("extensionCategoryName"),
            serviceSource = json.requireString("serviceSource")
        )
    }
}

/**
 * Wrapped response listing extension categories returned via admin APIs.
 *
 * @property data The list of extension categories.
 */
data class ExtensionCategoryListResponse(
    val data : List<ExtensionCategory>
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : ExtensionCategoryListResponse = ExtensionCategoryListResponse(
            data = json.optJsonList("data").map(ExtensionCategory::fromJson)
        )
    }
}

/**
 * Action item describing an auto-moderation action triggered by reporting events.
 *
 * @property actionId The unique identifier of this action.
 * @property actionName The name of this action.
 * @property eventName The event name that triggers this action.
 */
data class ActionItem(
    val actionId : String,
    val actionName : String,
    val eventName : String
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : ActionItem = ActionItem(
            actionId = json.requireString("actionId"),
            actionName = json.requireString("actionName"),
            eventName = json.requireString("eventName")
        )
    }
}

/**
 * List of auto-moderation actions available to configs.
 *
 * @property data The list of action items.
 */
data class ActionListResponse(
    val data : List<ActionItem>
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : ActionListResponse = ActionListResponse(
            data = json.optJsonList("data").map(ActionItem::fromJson)
        )
    }
}

/**
 * Rate limit information applied per report category.
 *
 * @property extensionCategory The extension category for this limit.
 * @property maxReportPerTicket The maximum reports allowed per ticket.
 * @property name The name of this category limit.
 */
data class CategoryLimit(
    val extensionCategory : String?,
    val maxReportPerTicket : Int,
    val name : String
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : CategoryLimit = CategoryLimit(
            extensionCategory = json.optString("extensionCategory"),
            maxReportPerTicket = json.requireInt("maxReportPerTicket"),
            name = json.requireString("name")
        )
    }
}

/**
 * Rate limits for reporting including per-category restrictions and global intervals.
 *
 * @property categoryLimits List of per-category limits.
 * @property timeInterval The time interval for rate limiting in seconds.
 * @property userMaxReportPerTimeInterval The maximum reports a user can submit per interval.
 */
data class ReportingLimit(
    val categoryLimits : List<CategoryLimit>,
    val timeInterval : Int,
    val userMaxReportPerTimeInterval : Int
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : ReportingLimit = ReportingLimit(
            categoryLimits = json.optJsonList("categoryLimits").map(CategoryLimit::fromJson),
            timeInterval = json.requireInt("timeInterval"),
            userMaxReportPerTimeInterval = json.requireInt("userMaxReportPerTimeInterval")
        )
    }
}

/**
 * Configuration payload detailing namespace-level reporting limits.
 *
 * @property namespace The namespace for this configuration.
 * @property reportingLimit The reporting limit settings.
 * @property updatedAt Timestamp when this config was last updated.
 */
data class ConfigResponse(
    val namespace : String,
    val reportingLimit : ReportingLimit,
    val updatedAt : String
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : ConfigResponse = ConfigResponse(
            namespace = json.requireString("namespace"),
            reportingLimit = ReportingLimit.fromJson(json.requireJson("reportingLimit")),
            updatedAt = json.requireString("updatedAt")
        )
    }
}

/**
 * Payload describing ban actions that moderation rules can trigger.
 *
 * @property comment An optional comment for the ban action.
 * @property duration The duration of the ban in seconds.
 * @property reason The reason for the ban.
 * @property skipNotif Whether to skip notification for this action.
 * @property type The type of ban action.
 */
data class BanAccountAction(
    val comment : String?,
    val duration : Int,
    val reason : String,
    val skipNotif : Boolean,
    val type : String
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : BanAccountAction = BanAccountAction(
            comment = json.optString("comment"),
            duration = json.requireInt("duration"),
            reason = json.requireString("reason"),
            skipNotif = json.requireBoolean("skipNotif"),
            type = json.requireString("type")
        )
    }
}

/**
 * Structured set of actions for a moderation rule, such as ban or delete chat.
 *
 * @property banAccount The ban account action configuration.
 * @property deleteChat Whether to delete chat messages.
 * @property extensionActionIds List of extension action identifiers.
 * @property hideContent Whether to hide content.
 */
data class ModerationRuleActionsResponse(
    val banAccount : BanAccountAction?,
    val deleteChat : Boolean?,
    val extensionActionIds : List<String>?,
    val hideContent : Boolean?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : ModerationRuleActionsResponse = ModerationRuleActionsResponse(
            banAccount = json.optJson("banAccount")?.let(BanAccountAction::fromJson),
            deleteChat = json.optBoolean("deleteChat"),
            extensionActionIds = json.optStringList("extensionActionIds").takeIf { it.isNotEmpty() },
            hideContent = json.optBoolean("hideContent")
        )
    }
}

/**
 * Moderation rule metadata and thresholds stored in the system.
 *
 * @property action The action type for this rule.
 * @property actions The structured actions for this rule.
 * @property active Whether this rule is currently active.
 * @property category The category this rule applies to.
 * @property createdAt Timestamp when this rule was created.
 * @property extensionCategory The extension category for this rule.
 * @property id The unique identifier of this rule.
 * @property namespace The namespace where this rule exists.
 * @property reason The reason for this rule.
 * @property threshold The threshold that triggers this rule.
 * @property updatedAt Timestamp when this rule was last updated.
 */
data class ModerationRuleResponse(
    val action : String?,
    val actions : ModerationRuleActionsResponse,
    val active : Boolean,
    val category : String,
    val createdAt : String,
    val extensionCategory : String?,
    val id : String,
    val namespace : String,
    val reason : String,
    val threshold : Int,
    val updatedAt : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : ModerationRuleResponse = ModerationRuleResponse(
            action = json.optString("action"),
            actions = ModerationRuleActionsResponse.fromJson(json.requireJson("actions")),
            active = json.requireBoolean("active"),
            category = json.requireString("category"),
            createdAt = json.requireString("createdAt"),
            extensionCategory = json.optString("extensionCategory"),
            id = json.requireString("id"),
            namespace = json.requireString("namespace"),
            reason = json.requireString("reason"),
            threshold = json.requireInt("threshold"),
            updatedAt = json.optString("updatedAt")
        )
    }
}

/**
 * Paginated set of moderation rules returned to configuration UIs.
 *
 * @property data The list of moderation rules on this page.
 * @property paging Pagination metadata for navigating additional pages.
 */
data class ModerationRulesListResponse(
    val data : List<ModerationRuleResponse>,
    val paging : CursorPagination
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : ModerationRulesListResponse = ModerationRulesListResponse(
            data = json.optJsonList("data").map(ModerationRuleResponse::fromJson),
            paging = CursorPagination.fromJson(json.requireJson("paging"))
        )
    }
}