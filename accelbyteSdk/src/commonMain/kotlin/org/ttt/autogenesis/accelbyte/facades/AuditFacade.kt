package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Json
import kotlin.js.json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.modules.AuditModulePackage
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors

/**
 * A thin facade over the AccelByte Audit module. Provides read access to platform audit logs
 * and account history, plus management of audit log comments. Admin APIs are prefixed AdminApi;
 * the account history API is available both admin-scoped and user-scoped.
 */
class AuditFacade(private val sdk : AccelByteSdkInstance)
{
    private val auditLogsAdminApi = AuditModulePackage.Audit.AuditLogsAdminApi(sdk.rawSdk)
    private val accountHistoryAdminApi = AuditModulePackage.Audit.AccountHistoryAdminApi(sdk.rawSdk)
    private val accountHistoryApi = AuditModulePackage.Audit.AccountHistoryApi(sdk.rawSdk)
    private val accountEventsAdminApi = AuditModulePackage.Audit.AccountEventsAdminApi(sdk.rawSdk)
    private val auditLogCommentAdminApi = AuditModulePackage.Audit.AuditLogCommentAdminApi(sdk.rawSdk)

    /**
     * @param queryParams optional JSON filter; @return raw audit log entries via propagateJsErrors
     */
    fun getLogs(queryParams : Json = json()) : Promise<Json> = auditLogsAdminApi.getLogs(queryParams).propagateJsErrors()

    /**
     * @param logId target entry; @return single log record JSON
     */
    fun getLog(logId : String) : Promise<Json> = auditLogsAdminApi.getLog_ByLogId(logId).propagateJsErrors()

    /**
     * @param queryParams optional JSON filter; @return audit category list JSON
     */
    fun getConfigCategories(queryParams : Json = json()) : Promise<Json> = auditLogsAdminApi.getConfigCategories(queryParams).propagateJsErrors()

    /**
     * @return retention time range config JSON
     */
    fun getConfigTimeRange() : Promise<Json> = auditLogsAdminApi.getConfigTimeRange().propagateJsErrors()

    /**
     * @param queryParams optional JSON filter; @return current user's account history entries
     */
    fun getMyAccountHistories(queryParams : Json = json()) : Promise<Json> = accountHistoryAdminApi.getUsersMeAccountHistories(queryParams).propagateJsErrors()

    /**
     * @param userId target user; @param queryParams optional filter; @return user account history
     */
    fun getAccountHistories(userId : String, queryParams : Json = json()) : Promise<Json> = accountHistoryAdminApi.getAccountHistories_ByUserId(userId, queryParams).propagateJsErrors()

    /**
     * Deletes account history for a user (admin).
     * @param userId target user; @return deleted account history JSON
     */
    fun deleteAccountHistory(userId : String) : Promise<Json> = accountHistoryAdminApi.deleteAccountHistory_ByUserId(userId).propagateJsErrors()

    /**
     * @param queryParams optional JSON filter; @return account event list JSON
     */
    fun getEventsByUserId(userId : String, queryParams : Json = json()) : Promise<Json> = accountEventsAdminApi.getEventsCritical_ByUserId(userId, queryParams).propagateJsErrors()

    /**
     * @param queryParams optional JSON filter; @return audit comment list JSON
     */
    fun getComments(queryParams : Json) : Promise<Json> = auditLogCommentAdminApi.getComments(queryParams).propagateJsErrors()

    /**
     * @param data comment JSON; @return created comment JSON
     */
    fun createComment(data : Json) : Promise<Json> = auditLogCommentAdminApi.createComment(data).propagateJsErrors()
}
