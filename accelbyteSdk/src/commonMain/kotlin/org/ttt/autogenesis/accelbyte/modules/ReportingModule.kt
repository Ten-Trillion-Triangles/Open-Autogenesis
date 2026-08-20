@file:JsModule("@accelbyte/sdk-reporting")
@file:JsNonModule

package org.ttt.autogenesis.accelbyte.modules

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSDK
import org.ttt.autogenesis.accelbyte.SdkSetConfigParam

external object ReportingModulePackage {
    val Reporting: ReportingNamespace
}

external interface ReportingNamespace {
    val PublicReasonsApi: PublicReasonsApiFactory
    val PublicReportsApi: PublicReportsApiFactory
    val ReportsAdminApi: ReportsAdminApiFactory
    val TicketsAdminApi: TicketsAdminApiFactory
    val ReasonsAdminApi: ReasonsAdminApiFactory
    val ExtensionCategoriesAndAutoModerationActionsAdminApi: ExtensionCategoriesAndAutoModerationActionsAdminApiFactory
    val ModerationRuleAdminApi: ModerationRuleAdminApiFactory
    val ConfigurationsAdminApi: ConfigurationsAdminApiFactory
}

external interface PublicReasonsApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): PublicReasonsApi
}

external interface PublicReasonsApi {
    fun getReasons(queryParams: Json? = definedExternally): Promise<Json>
    fun getReasonGroups(queryParams: Json? = definedExternally): Promise<Json>
}

external interface PublicReportsApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): PublicReportsApi
}

external interface PublicReportsApi {
    fun createReport(data: Json): Promise<Json>
}

external interface ReportsAdminApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): ReportsAdminApi
}

external interface ReportsAdminApi {
    fun getReports(queryParams: Json? = definedExternally): Promise<Json>
    fun createReport(data: Json): Promise<Json>
}

external interface TicketsAdminApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): TicketsAdminApi
}

external interface TicketsAdminApi {
    fun getTickets(queryParams: Json? = definedExternally): Promise<Json>
    fun getTicketsStatistic(queryParams: Json): Promise<Json>
    fun deleteTicket_ByTicketId(ticketId: String): Promise<Json>
    fun getTicket_ByTicketId(ticketId: String): Promise<Json>
    fun getReports_ByTicketId(ticketId: String, queryParams: Json? = definedExternally): Promise<Json>
    fun updateResolution_ByTicketId(ticketId: String, data: Json): Promise<Json>
}

external interface ReasonsAdminApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): ReasonsAdminApi
}

external interface ReasonsAdminApi {
    fun getReasons(queryParams: Json? = definedExternally): Promise<Json>
    fun createReason(data: Json): Promise<Json>
    fun getReasonsAll(): Promise<Json>
    fun getReasonGroups(queryParams: Json? = definedExternally): Promise<Json>
    fun createReasonGroup(data: Json): Promise<Json>
    fun getReasonsUnused(queryParams: Json): Promise<Json>
    fun deleteReason_ByReasonId(reasonId: String): Promise<Json>
    fun getReason_ByReasonId(reasonId: String): Promise<Json>
    fun patchReason_ByReasonId(reasonId: String, data: Json): Promise<Json>
    fun deleteReasonGroup_ByGroupId(groupId: String): Promise<Json>
    fun getReasonGroup_ByGroupId(groupId: String): Promise<Json>
    fun patchReasonGroup_ByGroupId(groupId: String, data: Json): Promise<Json>
}

external interface ExtensionCategoriesAndAutoModerationActionsAdminApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): ExtensionCategoriesAndAutoModerationActionsAdminApi
}

external interface ExtensionCategoriesAndAutoModerationActionsAdminApi {
    fun getExtensionActions(): Promise<Json>
    fun createExtensionAction(data: Json): Promise<Json>
    fun getExtensionCategories(queryParams: Json? = definedExternally): Promise<Json>
    fun createExtensionCategory(data: Json): Promise<Json>
}

external interface ModerationRuleAdminApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): ModerationRuleAdminApi
}

external interface ModerationRuleAdminApi {
    fun createRule(data: Json): Promise<Json>
    fun getRules(queryParams: Json? = definedExternally): Promise<Json>
    fun deleteRule_ByRuleId(ruleId: String): Promise<Json>
    fun updateRule_ByRuleId(ruleId: String, data: Json): Promise<Json>
    fun getRule_ByRuleId(ruleId: String): Promise<Json>
    fun updateStatus_ByRuleId(ruleId: String, data: Json): Promise<Json>
}

external interface ConfigurationsAdminApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): ConfigurationsAdminApi
}

external interface ConfigurationsAdminApi {
    fun getConfigurations(queryParams: Json? = definedExternally): Promise<Json>
    fun createConfiguration(data: Json): Promise<Json>
}