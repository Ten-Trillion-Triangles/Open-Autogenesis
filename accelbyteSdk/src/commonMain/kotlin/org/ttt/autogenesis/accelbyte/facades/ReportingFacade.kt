package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Json
import kotlin.js.Promise
import kotlin.js.undefined
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.models.ActionListResponse
import org.ttt.autogenesis.accelbyte.models.ConfigurationQueryParams
import org.ttt.autogenesis.accelbyte.models.ConfigResponse
import org.ttt.autogenesis.accelbyte.models.ExtensionCategory
import org.ttt.autogenesis.accelbyte.models.ExtensionCategoryListResponse
import org.ttt.autogenesis.accelbyte.models.ExtensionQueryParams
import org.ttt.autogenesis.accelbyte.models.ModerationRuleQueryParams
import org.ttt.autogenesis.accelbyte.models.ModerationRuleResponse
import org.ttt.autogenesis.accelbyte.models.ModerationRulesListResponse
import org.ttt.autogenesis.accelbyte.models.PublicReasonGroupQueryParams
import org.ttt.autogenesis.accelbyte.models.PublicReasonListResponse
import org.ttt.autogenesis.accelbyte.models.PublicReasonQueryParams
import org.ttt.autogenesis.accelbyte.models.ReportListResponse
import org.ttt.autogenesis.accelbyte.models.ReportQueryParams
import org.ttt.autogenesis.accelbyte.models.ReportResponse
import org.ttt.autogenesis.accelbyte.models.ReasonGroupListResponse
import org.ttt.autogenesis.accelbyte.models.TicketListResponse
import org.ttt.autogenesis.accelbyte.models.TicketQueryParams
import org.ttt.autogenesis.accelbyte.models.TicketResolutionRequest
import org.ttt.autogenesis.accelbyte.models.TicketReportQueryParams
import org.ttt.autogenesis.accelbyte.models.TicketResponse
import org.ttt.autogenesis.accelbyte.modules.ReportingModulePackage
import org.ttt.autogenesis.accelbyte.util.mapJson
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors

/**
 * A thin facade over the AccelByte Reporting module. Provides the complete workflow
 * for users to report content or behavior, and for moderators to manage tickets and
 * rules. Public endpoints handle user-facing reporting; admin endpoints cover ticket
 * management and moderation configuration.
 *
 * @property sdk The underlying AccelByte SDK instance used for API calls.
 */
class ReportingFacade(private val sdk : AccelByteSdkInstance)
{
    private val module get() = ReportingModulePackage.Reporting

    private val publicReasonsApi get() = module.PublicReasonsApi(sdk.rawSdk)
    private val publicReportsApi get() = module.PublicReportsApi(sdk.rawSdk)
    private val reportsAdminApi get() = module.ReportsAdminApi(sdk.rawSdk)
    private val ticketsAdminApi get() = module.TicketsAdminApi(sdk.rawSdk)
    private val reasonsAdminApi get() = module.ReasonsAdminApi(sdk.rawSdk)
    private val extensionCategoriesApi get() = module.ExtensionCategoriesAndAutoModerationActionsAdminApi(sdk.rawSdk)
    private val moderationRuleApi get() = module.ModerationRuleAdminApi(sdk.rawSdk)
    private val configurationsApi get() = module.ConfigurationsAdminApi(sdk.rawSdk)

    /**
     * Returns the catalog of valid reasons a user may select when submitting a report.
     * Use during the report UI flow before calling [submitReport].
     *
     * @param params Pagination params.
     * @return [PublicReasonListResponse]
     */
    fun listPublicReasons(params : PublicReasonQueryParams = PublicReasonQueryParams()) : Promise<PublicReasonListResponse> =
        publicReasonsApi.getReasons(params.toJson())
            .propagateJsErrors()
            .mapJson(PublicReasonListResponse::fromJson)

    /**
     * Returns the hierarchy of report reason groups used to categorize reasons in the
     * client UI.
     *
     * @param params Pagination.
     * @return [ReasonGroupListResponse]
     */
    fun listPublicReasonGroups(params : PublicReasonGroupQueryParams = PublicReasonGroupQueryParams()) : Promise<ReasonGroupListResponse> =
        publicReasonsApi.getReasonGroups(params.toJson())
            .propagateJsErrors()
            .mapJson(ReasonGroupListResponse::fromJson)

    /**
     * Submits a new user report with the provided payload. The payload shape depends on
     * the report type — consult AccelByte's public reports API schema for the required
     * fields.
     *
     * @param payload Raw JSON report body forwarded to the SDK.
     * @return [ReportResponse]
     */
    fun submitReport(payload : Json) : Promise<ReportResponse> =
        publicReportsApi.createReport(payload)
            .propagateJsErrors()
            .mapJson(ReportResponse::fromJson)

    /**
     * Admin-only: lists all submitted reports with optional filters for category, status,
     * and page size.
     *
     * @param params Query params.
     * @return [ReportListResponse]
     */
    fun listAdminReports(params : ReportQueryParams = ReportQueryParams()) : Promise<ReportListResponse> =
        reportsAdminApi.getReports(params.toJson())
            .propagateJsErrors()
            .mapJson(ReportListResponse::fromJson)

    /**
     * Admin-only: lists support tickets, optionally filtered by status and assignee.
     *
     * @param params Query params.
     * @return [TicketListResponse]
     */
    fun listTickets(params : TicketQueryParams = TicketQueryParams()) : Promise<TicketListResponse> =
        ticketsAdminApi.getTickets(params.toJson())
            .propagateJsErrors()
            .mapJson(TicketListResponse::fromJson)

    /**
     * Admin-only: resolves (closes) a support ticket with a resolution outcome and
     * optional notes.
     *
     * @param ticketId The ticket to resolve.
     * @param request [TicketResolutionRequest]
     * @return [TicketResponse]
     */
    fun updateTicketResolution(ticketId : String, request : TicketResolutionRequest) : Promise<TicketResponse> =
        ticketsAdminApi.updateResolution_ByTicketId(ticketId, request.toJson())
            .propagateJsErrors()
            .mapJson(TicketResponse::fromJson)

    /**
     * Admin-only: retrieves the full detail record for a single ticket.
     *
     * @param ticketId Target ticket ID.
     * @return [TicketResponse]
     */
    fun getTicketDetails(ticketId : String) : Promise<TicketResponse> =
        ticketsAdminApi.getTicket_ByTicketId(ticketId)
            .propagateJsErrors()
            .mapJson(TicketResponse::fromJson)

    /**
     * Admin-only: returns all reports linked to a specific ticket.
     *
     * @param ticketId Target ticket.
     * @param params Pagination.
     * @return [ReportListResponse]
     */
    fun getTicketReports(ticketId : String, params : TicketReportQueryParams = TicketReportQueryParams()) : Promise<ReportListResponse> =
        ticketsAdminApi.getReports_ByTicketId(ticketId, params.toJson())
            .propagateJsErrors()
            .mapJson(ReportListResponse::fromJson)

    /**
     * Admin-only: lists all active content moderation rules.
     *
     * @param params Optional query filters.
     * @return [ModerationRulesListResponse]
     */
    fun listModerationRules(params : ModerationRuleQueryParams = ModerationRuleQueryParams()) : Promise<ModerationRulesListResponse> =
        moderationRuleApi.getRules(params.toJson())
            .propagateJsErrors()
            .mapJson(ModerationRulesListResponse::fromJson)

    /**
     * Admin-only: creates a new auto-moderation rule. The payload shape is defined by
     * AccelByte's moderation rule API schema.
     *
     * @param payload Raw JSON rule definition.
     * @return [ModerationRuleResponse]
     */
    fun createModerationRule(payload : Json) : Promise<ModerationRuleResponse> =
        moderationRuleApi.createRule(payload)
            .propagateJsErrors()
            .mapJson(ModerationRuleResponse::fromJson)

    /**
     * Admin-only: lists reporting service configurations.
     *
     * @param params Optional category filter.
     * @return [ConfigResponse]
     */
    fun listModeratorConfigurations(params : ConfigurationQueryParams = ConfigurationQueryParams()) : Promise<ConfigResponse> =
        configurationsApi.getConfigurations(params.toJson())
            .propagateJsErrors()
            .mapJson(ConfigResponse::fromJson)

    /**
     * Admin-only: creates or updates a reporting service configuration.
     *
     * @param payload Raw JSON configuration.
     * @return [ConfigResponse]
     */
    fun upsertConfiguration(payload : Json) : Promise<ConfigResponse> =
        configurationsApi.createConfiguration(payload)
            .propagateJsErrors()
            .mapJson(ConfigResponse::fromJson)

    /**
     * Admin-only: lists available extension categories for auto-moderation.
     *
     * @param params Optional filters.
     * @return [ExtensionCategoryListResponse]
     */
    fun listExtensionCategories(params : ExtensionQueryParams = ExtensionQueryParams()) : Promise<ExtensionCategoryListResponse> =
        extensionCategoriesApi.getExtensionCategories(params.toJson())
            .propagateJsErrors()
            .mapJson(ExtensionCategoryListResponse::fromJson)

    /**
     * Admin-only: registers a new extension category.
     *
     * @param payload Raw JSON.
     * @return [ExtensionCategory]
     */
    fun createExtensionCategory(payload : Json) : Promise<ExtensionCategory> =
        extensionCategoriesApi.createExtensionCategory(payload)
            .propagateJsErrors()
            .mapJson(ExtensionCategory::fromJson)

    /**
     * Admin-only: lists all available auto-moderation actions.
     *
     * @return [ActionListResponse]
     */
    fun listExtensionActions() : Promise<ActionListResponse> =
        extensionCategoriesApi.getExtensionActions()
            .propagateJsErrors()
            .mapJson(ActionListResponse::fromJson)
}
