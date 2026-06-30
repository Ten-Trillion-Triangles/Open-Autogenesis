package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json
import kotlin.js.json

/**
 * Query parameters for fetching a paginated list of public reporting reasons.
 *
 * Use with [org.ttt.autogenesis.accelbyte.facades.ReportingFacade.listPublicReasons].
 *
 * @property limit maximum number of reasons to return per page. Null omits the param.
 * @property offset number of reasons to skip before collecting the page. Null omits the param.
 */
data class PublicReasonQueryParams(val limit : Int? = null, val offset : Int? = null) : AccelByteRequest
{
    override fun toJson() : Json = jsonOf("limit" to limit, "offset" to offset)
}

/**
 * Query parameters for fetching public reporting reason groups.
 *
 * Use with [org.ttt.autogenesis.accelbyte.facades.ReportingFacade.listPublicReasonGroups].
 *
 * @property limit maximum number of groups to return per page. Null omits the param.
 * @property offset number of groups to skip before collecting the page. Null omits the param.
 */
data class PublicReasonGroupQueryParams(val limit : Int? = null, val offset : Int? = null) : AccelByteRequest
{
    override fun toJson() : Json = jsonOf("limit" to limit, "offset" to offset)
}

/**
 * Query parameters for searching reports in the reporting service.
 *
 * @property category filter by report category identifier (e.g., "USER", "CONTENT"). Null = no filter.
 * @property status filter by report status (e.g., "OPEN", "RESOLVED", "DISMISSED"). Null = no filter.
 * @property limit maximum number of reports to return per page. Defaults to 20.
 */
data class ReportQueryParams(
    val category : String? = null,
    val status : String? = null,
    val limit : Int = 20
) : AccelByteRequest
{
    override fun toJson() : Json = jsonOf("category" to category, "status" to status, "limit" to limit)
}

/**
 * Query parameters for listing support tickets.
 *
 * @property status filter by ticket status (e.g., "NEW", "IN_PROGRESS", "RESOLVED"). Null = no filter.
 * @property assignee filter by the assignee user ID. Null = no filter (returns all).
 * @property limit maximum number of tickets to return per page. Defaults to 20.
 */
data class TicketQueryParams(
    val status : String? = null,
    val assignee : String? = null,
    val limit : Int = 20
) : AccelByteRequest
{
    override fun toJson() : Json = jsonOf("status" to status, "assignee" to assignee, "limit" to limit)
}

/**
 * Query parameters for fetching reports associated with a specific ticket.
 *
 * @property limit maximum number of reports to return per page. Null omits the param.
 * @property offset number of reports to skip before collecting the page. Null omits the param.
 */
data class TicketReportQueryParams(val limit : Int? = null, val offset : Int? = null) : AccelByteRequest
{
    override fun toJson() : Json = jsonOf("limit" to limit, "offset" to offset)
}

/**
 * Request payload for resolving (closing) a support ticket.
 *
 * @property resolution the resolution outcome, e.g., "ACTION_TAKEN", "NO_ACTION", "INVALID".
 * @property notes optional free-text notes describing the resolution (not publicly visible).
 */
data class TicketResolutionRequest(val resolution : String, val notes : String? = null) : AccelByteRequest
{
    override fun toJson() : Json = jsonOf("resolution" to resolution, "notes" to notes)
}

/**
 * Query parameters for listing moderation rules.
 *
 * @property category filter by rule category. Null = no filter.
 * @property extensionCategory filter by extension category. Null = no filter.
 */
data class ModerationRuleQueryParams(
    val category : String? = null,
    val extensionCategory : String? = null
) : AccelByteRequest
{
    override fun toJson() : Json = jsonOf("category" to category, "extensionCategory" to extensionCategory)
}

/**
 * Query parameters for listing reporting configurations.
 *
 * @property category filter by configuration category. Null = no filter (returns all).
 */
data class ConfigurationQueryParams(val category : String? = null) : AccelByteRequest
{
    override fun toJson() : Json = jsonOf("category" to category)
}

/**
 * Query parameters for listing reporting extensions.
 *
 * @property type filter by extension type identifier. Null = no filter.
 * @property limit maximum number of extensions to return per page. Defaults to 20.
 */
data class ExtensionQueryParams(val type : String? = null, val limit : Int = 20) : AccelByteRequest
{
    override fun toJson() : Json = jsonOf("type" to type, "limit" to limit)
}
